package com.example.myapplication.sync

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.mirror.MirrorCommitRecord
import com.example.myapplication.mirror.MirrorCommitter
import com.example.myapplication.mirror.MirrorStateStore
import com.example.myapplication.mirror.PatchClassifier
import com.example.myapplication.mirror.PatchKind
import com.example.myapplication.mirror.SyncState
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.ApiErrorClassifier
import com.example.myapplication.net.OrderFileApi
import com.example.myapplication.net.Service1Api
import com.example.myapplication.net.UnauthorizedReason
import com.example.myapplication.net.dto.OrderStreamFields
import com.example.myapplication.net.dto.OrderStreamMessageDto
import com.example.myapplication.net.dto.OrderSyncRequestDto
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * 订单文件同步的状态机（方案 §3.4）。
 *
 * ```
 * sync(fileId, version, lastStreamId)
 * ├─ 200 LATEST       → 只覆盖保存 streamId
 * ├─ 200 INCREMENTAL  → 按 messages 顺序逐条走 MirrorCommitter；全部完成后 version 应等于响应 version
 * ├─ 200 FULL         → 流式落地 + 同一事务内重建 order_index / 统计贡献 / 游标
 * ├─ 409              → resolve409()
 * └─ 其它             → 分类（429 绝不自动重试；500 记追踪号 + 时间）
 * ```
 *
 * ## 三条贯穿全类的规则
 *
 * **一、引擎自己不写 Room，也不碰镜像文件。**
 * 唯一例外是"LATEST 时只推进 streamId"这一条游标写入（[handleLatest]），
 * 它不改文件所以没有"先文件后游标"的顺序问题。
 * 其余写入一律经 [MirrorCommitter]（顺序、互斥、幂等三道保证都在它里面）。
 * [dao] 只用来**读** `order_index` 找补丁目标。
 *
 * **二、任何"我不认识/我无法验证"的消息一律 `Reject` → 走 FULL。**
 * 猜一个偏移或跳一条消息，后果是镜像与服务器不再逐字节一致，而等长覆写不报错、
 * 文件长度不变、校验和自洽 —— **没有任何机制会发现**。
 * FULL 是唯一能保证回到一致状态的动作，代价只是几十 KB～几 MB 的流量。
 *
 * **三、游标一律存服务端返回的队尾 id（[cursorOf]），不逐条存 `m.id`。**
 * 理由见 [OrderStreamMessageDto.id] 的注释。
 *
 * ## ⚠ 一个无法消除的二义性（409 → 探测得 LATEST）
 *
 * "运维把文件归档删除了"与"Redis 被清空、但磁盘文件还在"在协议上**完全同形**：
 * 后者的 `orderfile:version:<fileId>` 键没了，`latest` 读成 0，而 FULL 分支的
 * `x-file-version` 是从**磁盘头部**读的（可能是 57）。于是客户端 v57 > latest 0 → 409，
 * 探测 `version=0` 时 `0 == latest` → **LATEST** → 判归档。
 *
 * 判归档是**无损**的：镜像搬进 `mirror-archive/`（不删）、游标冻结、统计贡献 `frozen=1`。
 * 但本地会停止同步那个文件，所以要**大声记审计**（带本地版本号），
 * 让运维一看就知道该去 `archive_orderfile.py` 那边核对。
 */
class OrderFileSyncEngine(
    private val api: OrderFileApi,
    private val committer: MirrorCommitter,
    private val stateStore: MirrorStateStore,
    /** **只读**：查 `order_index` 找补丁目标。写入一律经 [committer]。 */
    private val dao: OrderFileDao,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val audit: (String) -> Unit = {},
) {

    /**
     * 一次同步的结果。**每个分支都要求调用方做点什么**，所以不用布尔值：
     * 调度器要区分"追平了"与"该月已归档、别再排了"，UI 要区分"429 别重试"与"断网可以退避"。
     */
    sealed class Outcome {

        /** 本地已是最新（可能刚落了个空镜像，也可能只刷新了 streamId）。 */
        data class UpToDate(val version: Int, val streamId: String?) : Outcome()

        /**
         * 增量链全部应用完。
         *
         * @param applied 真正写了镜像的消息数
         * @param echoes  被幂等守卫判成 ALREADY_APPLIED 的消息数。
         *        **不是错误**：SSE 回声与 HTTP 响应会把同一条消息投递两次，
         *        队尾游标也可能比已应用的最后一条旧。这个数字只用于诊断，
         *        它突然变大说明游标策略或 SSE 去重出了问题。
         */
        data class Applied(val applied: Int, val echoes: Int, val version: Int, val streamId: String?) : Outcome()

        /** 走了 FULL，`order_index` 与统计贡献已整体重建。 */
        data class Rebuilt(val version: Int, val streamId: String?) : Outcome()

        /**
         * 服务端已归档删除这个文件。调度器必须**把它从待同步集合里去掉**，
         * UI 该月置灰、只显示、不允许再往里开单。
         *
         * @param localVersion 冻结在归档那一刻的本地版本号（诊断用）。
         */
        data class Archived(val fileId: String, val localVersion: Int) : Outcome()

        /**
         * 本地版本比服务器**高**（`OrderSyncService` 的 SERVER_BEHIND，HTTP 409）。
         *
         * 服务器丢过数据而客户端镜像完好。此时**绝不能**让 FULL 落地：
         * 那是用较短的文件覆盖唯一的完整副本。必须问用户：
         *  - 接受服务器版本 → [acceptServerVersion]（丢弃本地尾部）
         *  - 回推本地尾部 → P5 的 `batch-upsert` 差集路径
         */
        data class ServerBehind(val localVersion: Int, val serverVersion: Int) : Outcome()

        /** 401。**不要拿旧 token 重试。** [mayAutoRelogin] 见 [ApiErrorClassifier]。 */
        data class NeedsRelogin(val reason: UnauthorizedReason, val mayAutoRelogin: Boolean) : Outcome()

        /** 429。**绝不自动重试**（Guava 平滑桶几乎没有突发额度，重试只会把窗口顶满）。 */
        data class RateLimited(val msg: String) : Outcome()

        /** 其它失败。[retriable] = 退避后重试有意义（断网、500）。 */
        data class Failed(val msg: String, val retriable: Boolean) : Outcome()
    }

    // ==================== 入口 ====================

    /**
     * 同步一个 fileId 到最新。
     *
     * **不抛异常**（协议与网络错误一律折进 [Outcome]），调用方只需要 `when`。
     */
    suspend fun sync(storeKey: String, fileId: String): Outcome {
        val parts = parseParts(fileId) ?: return Outcome.Failed("非法 fileId=$fileId", retriable = false)
        val state = stateStore.read(storeKey, fileId)
        if (state?.syncState == SyncState.ARCHIVED) {
            // 归档是终态。调度器本来就该跳过它，这里再挡一道：
            // 万一它没跳过，一次同步就会用服务器（可能已不存在的）字节覆盖归档镜像。
            return Outcome.Archived(fileId, state.version)
        }
        return guarded(storeKey, fileId, parts) {
            dispatch(
                storeKey, fileId, parts,
                api.syncOrderFile(
                    // ⚠ month 必须是 `monthOrNull`：A 类的 NO_MONTH 是 0，
                    // 直接传会撞上服务端 `month < 1 || month > 12` 的校验 → 400，
                    // 于是"同步某一年的进货单"永远失败，而错误信息完全指不到这里。
                    request(parts, state?.version ?: 0, state?.lastStreamId),
                    onFull = { body -> landFull(storeKey, fileId, body) },
                ),
            )
        }
    }

    /**
     * [Outcome.ServerBehind] 对话框的"接受服务器版本"出口：强制 FULL 落地。
     *
     * ⚠ 这会**丢弃本地多出来的尾部订单**。只有用户明确点了才允许调，引擎自己绝不走这条路。
     * 另一个出口"重推本地尾部"在现有 API 上做不到（没有上传文件尾部的接口），
     * 可行路径是 FULL 到临时区 → 按 `order_uuid` 做差集 → 差集逐条 `/add`（限速）→
     * **再强制 FULL 重建索引与统计**（重推后服务器上的偏移与本地记录的完全不同，
     * 不重建的话之后每次取消都打在错误偏移上，而且是静默的）。那是 P5 的事。
     */
    suspend fun acceptServerVersion(storeKey: String, fileId: String): Outcome {
        val parts = parseParts(fileId) ?: return Outcome.Failed("非法 fileId=$fileId", retriable = false)
        val local = stateStore.read(storeKey, fileId)?.version ?: 0
        audit("用户接受服务器版本 fileId=$fileId：丢弃本地 v$local 多出的尾部订单")
        return guarded(storeKey, fileId, parts) { requestFull(storeKey, fileId, parts) }
    }

    // ==================== 三个分支 ====================

    private suspend fun dispatch(
        storeKey: String,
        fileId: String,
        parts: OrderFileId.Parts,
        outcome: Service1Api.SyncOutcome,
    ): Outcome = when (outcome) {
        is Service1Api.SyncOutcome.Latest -> handleLatest(storeKey, fileId, parts, outcome)
        is Service1Api.SyncOutcome.Incremental -> handleIncremental(storeKey, fileId, parts, outcome)
        // FULL 已经在 onFull 回调里落地并提交完了（见 Service1Api.SyncOutcome.Full 的注释），
        // 这里只是把回执翻译出去。
        is Service1Api.SyncOutcome.Full -> Outcome.Rebuilt(outcome.version, cursorOf(outcome.streamId))
    }

    /**
     * `clientVersion == latest`。**只覆盖保存 streamId。**
     */
    private suspend fun handleLatest(
        storeKey: String,
        fileId: String,
        parts: OrderFileId.Parts,
        o: Service1Api.SyncOutcome.Latest,
    ): Outcome {
        val cursor = cursorOf(o.streamId)
        val state = stateStore.read(storeKey, fileId)

        if (state == null || state.syncState != SyncState.LIVE) {
            // 从未同步过，或上次判了 DIVERGED（游标已归零）。
            // 我们能走到 LATEST 说明发过去的 version 就等于 latest，而这两种情况发的都是 0
            // ⇒ **服务端这个文件是空的**。必须落一个 4 字节空镜像，不能只写游标行：
            // 只写游标的话三重自检会发现"file_state 有记录但镜像文件不存在"→ 判 DIVERGED，
            // 于是"服务端确实没有这个月的单"被记成"本地镜像坏了"，然后无休止地排 FULL。
            landEmpty(storeKey, fileId, o.version, cursor)
            return Outcome.UpToDate(o.version, cursor)
        }

        if (o.version != state.version) {
            // LATEST 的判据就是两者相等，不等说明响应自相矛盾（或本地状态在请求期间被改过）。
            // 不猜，走 FULL —— 它是唯一能把本地拉回一致状态的动作。
            audit("LATEST 却版本不符 fileId=$fileId 响应 v${o.version} / 本地 v${state.version}")
            return requestFull(storeKey, fileId, parts)
        }

        // ⚠ `cursor ?: state.lastStreamId`：服务端在流为空时返回 `tailId = ""`，
        // 照抄会把游标清空 → 下次 lastStreamId 为空 → 无条件 FULL。
        // 一个 26MB 的文件被反复全量下载，现象是"同步很慢"，没有任何报错。
        val merged = cursor ?: state.lastStreamId
        stateStore.commit(
            MirrorCommitRecord(
                storeKey = storeKey,
                fileId = fileId,
                // 没有文件写入，所以 checksum/mirrorBytes/firstSyncedAt 原样带过去。
                state = state.copy(lastStreamId = merged),
                order = null,
                // 这个标志只在带 order 时有意义（"文件写入被跳过但 Room 要补齐"）。
                alreadyApplied = false,
            )
        )
        return Outcome.UpToDate(state.version, merged)
    }

    /**
     * 按顺序逐条应用。**顺序错了镜像就错了。**
     */
    private suspend fun handleIncremental(
        storeKey: String,
        fileId: String,
        parts: OrderFileId.Parts,
        o: Service1Api.SyncOutcome.Incremental,
    ): Outcome {
        val tail = cursorOf(o.streamId)
        var applied = 0
        var echoes = 0
        var sawUnknownPatch = false

        for ((i, m) in o.messages.withIndex()) {
            when (val r = applyMessage(storeKey, fileId, m, tail)) {
                is MsgResult.Ok -> {
                    if (r.applied) applied++ else echoes++
                    if (r.unknownPatch) sawUnknownPatch = true
                }

                is MsgResult.Reject -> {
                    // ⚠ **必须 break 而不是 continue。** 跳过一条 ADD 会让下一条 ADD 的
                    // `lineStart != 文件当前长度` → guardAdd → DIVERGED → 镜像被隔离。
                    // 也就是说"跳过一条"的后果不是少一笔订单，而是整个月被判分歧。
                    audit("增量链在第 ${i + 1}/${o.messages.size} 条中断 fileId=$fileId：" +
                        "${r.reason} —— 改走 FULL")
                    return requestFull(storeKey, fileId, parts)
                }
            }
        }

        // 连续性守卫的客户端半边。Lua 只在"链能补到 latest"时才返回 INCREMENTAL
        // （`order_sync.lua:100`），但**已落地的版本**仍可能落后：某条被判分歧、
        // 或者本地状态本来就不自洽。不校验就返回成功的话游标已推到队尾，
        // 下次只会拿到 LATEST —— 差的那几条**永远不会再被投递**，统计静默少一笔。
        val after = stateStore.read(storeKey, fileId)
        if (after == null || after.version < o.version) {
            audit("增量链应用完但版本没追上 fileId=$fileId 本地 v${after?.version} < 响应 v${o.version} —— 走 FULL")
            return requestFull(storeKey, fileId, parts)
        }

        if (sawUnknownPatch) {
            // 镜像字节是对的（UNKNOWN 补丁照样写进去了，否则就不再逐字节一致），
            // 但 order_index 无从更新 —— 那一单的 status/remark 会一直停在旧值。
            audit("本次增量含 UNKNOWN 补丁 fileId=$fileId —— 走 FULL 重建索引")
            return requestFull(storeKey, fileId, parts)
        }

        return Outcome.Applied(applied, echoes, after.version, after.lastStreamId)
    }

    /** 强制 FULL：`version=0, lastStreamId=""` 两个字面量，一定让 Lua 走 `:61` 那一支。 */
    private suspend fun requestFull(storeKey: String, fileId: String, parts: OrderFileId.Parts): Outcome {
        val outcome = api.syncOrderFile(
            OrderSyncRequestDto(parts.type, parts.year, parts.monthOrNull, version = 0, lastStreamId = ""),
            onFull = { body -> landFull(storeKey, fileId, body) },
        )
        return when (outcome) {
            is Service1Api.SyncOutcome.Full -> Outcome.Rebuilt(outcome.version, cursorOf(outcome.streamId))

            // version=0 得到 LATEST ⇒ latest 也是 0 ⇒ 服务端这个文件是空的。
            // 仍然要落空镜像并清索引，理由同 handleLatest。
            is Service1Api.SyncOutcome.Latest -> {
                val cursor = cursorOf(outcome.streamId)
                landEmpty(storeKey, fileId, outcome.version, cursor)
                Outcome.Rebuilt(outcome.version, cursor)
            }

            is Service1Api.SyncOutcome.Incremental -> {
                // 不可能：lastStreamId 为空时 Lua 在 `:61` 就返回 FULL 了。
                audit("强制 FULL 却拿到 INCREMENTAL fileId=$fileId —— 服务端行为与 order_sync.lua 不符")
                Outcome.Failed("强制 FULL 拿到了 INCREMENTAL", retriable = false)
            }
        }
    }

    // ==================== 409 ====================

    /**
     * SERVER_BEHIND 的二值探测。
     *
     * 409 的响应体**不含服务器版本号**，所以只能再发一次 `version=0, lastStreamId=""`：
     *  - `Latest` → 服务器根本没这个文件（运维已归档删除）→ 归档本地
     *  - `Full`   → 文件还在、只是版本号更小 → 交给用户决定
     */
    private suspend fun resolve409(storeKey: String, fileId: String, parts: OrderFileId.Parts): Outcome {
        val localVersion = stateStore.read(storeKey, fileId)?.version ?: 0
        val probe = try {
            api.probeAfter409(parts.type, parts.year, parts.monthOrNull) {
                // ⚠ **body 一个字节都不读、更不落地。**
                // 那是服务器**较短**的文件，而本地镜像是唯一更全的副本；
                // 覆盖它就是不可逆丢数据。这里只需要响应头里的 x-file-version，
                // 它已经被 Service1Api 摘出来放进 SyncOutcome.Full 了。
            }
        } catch (e: ApiError) {
            return classifyFailure(e)
        } catch (e: IOException) {
            return Outcome.Failed("409 探测中断: ${e.message}", retriable = true)
        }

        return when (probe) {
            is Service1Api.SyncOutcome.Latest -> {
                // 二义性见类 KDoc：这也可能是"Redis 被清空而磁盘文件还在"。
                // 判归档是无损的（镜像搬进 mirror-archive/、贡献 frozen=1），所以按协议办，
                // 但把本地版本号大声记下来 —— 它远大于 0 就是那条二义路径的指纹。
                audit("409 → 探测得 LATEST：判定服务端已归档删除 fileId=$fileId 本地游标 v$localVersion" +
                    if (localVersion > 0) "（⚠ 本地版本非 0，也可能是 Redis 被清空而磁盘文件还在，请核对服务端 data/orders/）" else "")
                if (!committer.archive(storeKey, fileId, "409 探测得 LATEST")) {
                    // 搬不动就不标 ARCHIVED：半归档状态会让下一次 FULL 把服务器较短的字节流
                    // 写进那个"本该冻结"的文件，而调用方以为它是只读证据。
                    return Outcome.Failed("归档镜像搬移失败，fileId=$fileId 仍是 LIVE", retriable = true)
                }
                Outcome.Archived(fileId, localVersion)
            }

            is Service1Api.SyncOutcome.Full -> {
                audit("SERVER_BEHIND fileId=$fileId 本地 v$localVersion > 服务器 v${probe.version}" +
                    " —— 不落地，等用户决定（接受服务器版本 / 回推本地尾部）")
                Outcome.ServerBehind(localVersion, probe.version)
            }

            is Service1Api.SyncOutcome.Incremental -> {
                // version=0 时 Lua 不可能走到增量分支。
                audit("409 探测返回 INCREMENTAL fileId=$fileId —— 服务端行为与 order_sync.lua 不符")
                Outcome.Failed("409 探测返回了不可能的 INCREMENTAL", retriable = false)
            }
        }
    }

    // ==================== 单条消息 ====================

    private sealed class MsgResult {
        /** @param applied false = 幂等守卫判成已应用（重复投递），Room 已补齐 */
        data class Ok(val applied: Boolean, val version: Int, val unknownPatch: Boolean = false) : MsgResult()

        /** 这条消息无法安全应用。调用方必须停止整条链并走 FULL。 */
        data class Reject(val reason: String) : MsgResult()
    }

    private suspend fun applyMessage(
        storeKey: String,
        fileId: String,
        m: OrderStreamMessageDto,
        tail: String?,
    ): MsgResult {
        val f = m.fields ?: return MsgResult.Reject("消息没有 fields（id=${m.id}）")

        // Lua 已经按 file_id 过滤过（`order_sync.lua:85`），同一条 stream 里混着多个月份。
        // 这里再核一遍：万一过滤失效，把别的月份的订单追加进这个镜像就是**静默**的字节级破坏。
        val msgFileId = f[OrderStreamFields.FILE_ID]
        if (msgFileId != fileId) {
            return MsgResult.Reject("消息属于另一个文件 file_id=$msgFileId ≠ $fileId（id=${m.id}）")
        }

        val msgVersion = f[OrderStreamFields.VERSION]?.toIntOrNull()
            ?: return MsgResult.Reject("消息缺 version 或不是整数（id=${m.id}）")

        // ⚠ **不看 type 推断补丁语义。** CANCEL / COMPLETE / DELETE_COMPLETED 三者同形
        // （1 字节打在 statusOffset 上），判据一律是偏移与长度，见 PatchClassifier。
        return when (f[OrderStreamFields.TYPE]) {
            OrderStreamFields.TYPE_ADD -> applyAdd(storeKey, fileId, f, msgVersion, tail)

            OrderStreamFields.TYPE_CANCEL,
            OrderStreamFields.TYPE_COMPLETE,
            OrderStreamFields.TYPE_DELETE_COMPLETED,
            -> applyPatch(storeKey, fileId, f, msgVersion, tail)

            else -> MsgResult.Reject("未知的消息 type=${f[OrderStreamFields.TYPE]}（id=${m.id}）")
        }
    }

    private suspend fun applyAdd(
        storeKey: String,
        fileId: String,
        f: Map<String, String>,
        msgVersion: Int,
        tail: String?,
    ): MsgResult {
        val uuid = f[OrderStreamFields.ORDER_UUID] ?: return MsgResult.Reject("ADD 缺 order_uuid")
        val orderJson = f[OrderStreamFields.ORDER_JSON]
            ?: return MsgResult.Reject("ADD 缺 order_json uuid=$uuid")
        val uuidOffset = f[OrderStreamFields.UUID_OFFSET]?.toLongOrNull()
            ?: return MsgResult.Reject("ADD 缺 uuid_offset uuid=$uuid")
        val statusOffset = f[OrderStreamFields.STATUS_OFFSET]?.toLongOrNull()
            ?: return MsgResult.Reject("ADD 缺 status_offset uuid=$uuid")

        // `order_json` 就是上传时的原文（`OrderFileService:270` 传的是 rawJson，不含末尾 LF），
        // 与镜像里那一行逐字节相同 —— 所以可以直接拿它扫行内下标，不必去读文件。
        val payload = ByteOps.utf8(orderJson)
        // ⚠ 行首与行长**必须**由本地扫出来的 `uuidValueIndex` 换算（`lineStart = uuidOffset - u`），
        // 不要用"uuidOffset - 15"那个常数：它依赖"order_uuid 是第一个字段且前缀恰好 15 字节"，
        // 而扫描不依赖任何字段顺序假设。差一个字节，此后每一次取消都打在别的订单上，
        // 且等长覆写不报错、文件长度不变、校验和自洽。
        val off = OrderJsonScanner.offsets(payload)
            ?: return MsgResult.Reject("ADD 的 order_json 扫不出偏移 uuid=$uuid —— 不猜")

        return when (val r = committer.commitAdd(
            storeKey = storeKey,
            fileId = fileId,
            orderUuid = uuid,
            msgVersion = msgVersion,
            payloadBytes = payload,
            uuidValueByteIndex = off.uuidValueIndex,
            remarkValueByteIndex = off.remarkValueIndex,
            serverUuidOffset = uuidOffset,
            serverStatusOffset = statusOffset,
            newStreamId = tail,
        )) {
            is MirrorCommitter.Result.Committed -> MsgResult.Ok(r.applied, r.version)
            is MirrorCommitter.Result.Diverged -> MsgResult.Reject("ADD 被判分歧：${r.reason}")
        }
    }

    private suspend fun applyPatch(
        storeKey: String,
        fileId: String,
        f: Map<String, String>,
        msgVersion: Int,
        tail: String?,
    ): MsgResult {
        val uuid = f[OrderStreamFields.ORDER_UUID]
        val patchOffset = f[OrderStreamFields.PATCH_OFFSET]?.toLongOrNull()
            ?: return MsgResult.Reject("补丁缺 patch_offset uuid=$uuid")
        val patchContent = f[OrderStreamFields.PATCH_CONTENT]
            ?: return MsgResult.Reject("补丁缺 patch_content uuid=$uuid")

        val contentBytes = ByteOps.utf8(patchContent)
        val declaredLen = f[OrderStreamFields.PATCH_LENGTH]?.toIntOrNull()
        if (declaredLen != null && declaredLen != contentBytes.size) {
            // `patch_length` 必须等于 `patch_content` 的 **UTF-8 字节长度**（服务端就是这么算的，
            // 客户端发补丁时也受同一条校验约束）。不一致说明我们对这条消息的理解是错的，
            // 而 PatchClassifier 是按 contentBytes.size 分类的 ——
            // 长度对不上会把"改备注"判成"未知补丁"，或者反过来把取消判成改备注。
            return MsgResult.Reject(
                "补丁长度自相矛盾 uuid=$uuid patch_length=$declaredLen 实际字节=${contentBytes.size}"
            )
        }

        // 先按 uuid 查索引行拿 statusOffset/remarkOffset。
        // ⚠ 查不到时**必须**按偏移反查：FULL 重建会跳过解析失败的行
        // （OrderPayloadIndex.parse 返回 null 时不建索引），那一单的 uuid 不在 order_index 里，
        // 但它确实躺在镜像里、确实会被补丁打到。
        val target = uuid?.let { dao.orderIndex(storeKey, fileId, it) }
            ?: findByOffset(storeKey, fileId, patchOffset, contentBytes.size)

        val kind = PatchClassifier.classify(
            patchOffset, contentBytes.size, target?.statusOffset, target?.remarkOffset
        )
        if (kind == PatchKind.UNKNOWN) {
            // **不在这里提前 return**：镜像必须照写（否则与服务器不再逐字节一致），
            // 只是 order_index 无从更新，事后由调用方排一次 FULL 重建。
            audit("UNKNOWN 补丁 fileId=$fileId offset=$patchOffset len=${contentBytes.size} " +
                "uuid=$uuid 反查到的索引行=${target?.orderUuid} —— 不猜，稍后走 FULL")
        }

        return when (val r = committer.commitPatch(
            storeKey = storeKey,
            fileId = fileId,
            msgVersion = msgVersion,
            patchOffset = patchOffset,
            patchContentBytes = contentBytes,
            // 按偏移反查到时，索引行里的 uuid 才是权威的（消息里那个可能压根没有）。
            orderUuid = target?.orderUuid ?: uuid,
            knownStatusOffset = target?.statusOffset,
            knownRemarkOffset = target?.remarkOffset,
            newStreamId = tail,
        )) {
            is MirrorCommitter.Result.Committed ->
                MsgResult.Ok(r.applied, r.version, unknownPatch = kind == PatchKind.UNKNOWN)

            is MirrorCommitter.Result.Diverged -> MsgResult.Reject("补丁被判分歧：${r.reason}")
        }
    }

    /**
     * uuid 查不到索引行时的兜底：按补丁长度反查。
     *
     * 只有两种长度有意义 —— 1（状态字节）与 150（A 类备注）。
     * 其它长度返回 null，让 [PatchClassifier] 判 UNKNOWN，**不猜**。
     */
    private suspend fun findByOffset(storeKey: String, fileId: String, patchOffset: Long, len: Int) =
        when (len) {
            1 -> dao.orderIndexByStatusOffset(storeKey, fileId, patchOffset)
            ByteOps.REMARK_WIDTH -> dao.orderIndexByRemarkOffset(storeKey, fileId, patchOffset)
            else -> null
        }

    // ==================== 落地 ====================

    /**
     * FULL 的字节流直接进 [MirrorCommitter.commitFull]：
     * 它内部是"写临时文件 → fsync → 原子 rename → 一个 Room 事务重建索引与统计"。
     *
     * 一次拷贝，不额外经 `mirror-tmp/` 中转。事务在连接还没关时执行是可接受的：
     * 此刻全部字节已经 fsync，事务失败或进程被杀都不会丢镜像内容。
     */
    private suspend fun landFull(storeKey: String, fileId: String, body: Service1Api.FullFileBody) {
        committer.commitFull(
            storeKey = storeKey,
            fileId = fileId,
            source = body.source.inputStream(),
            serverVersion = body.version,
            newStreamId = cursorOf(body.streamId),
        )
    }

    /**
     * 落一个"只有 4 字节版本头"的空镜像。
     *
     * 复用 [MirrorCommitter.commitFull] 而不是自己写文件：committer 是镜像的唯一写入口，
     * 而且这一趟顺带把 `order_index` / `order_item_index` / 统计贡献清空 ——
     * 服务端说这个文件是空的，本地留着旧索引就是"历史页有单、镜像里没有"，统计也一直多算。
     */
    private suspend fun landEmpty(storeKey: String, fileId: String, version: Int, streamId: String?) {
        committer.commitFull(
            storeKey = storeKey,
            fileId = fileId,
            source = ByteArrayInputStream(ByteOps.encodeHeader(version)),
            serverVersion = version,
            newStreamId = streamId,
        )
    }

    // ==================== 杂项 ====================

    private fun request(parts: OrderFileId.Parts, version: Int, lastStreamId: String?) =
        OrderSyncRequestDto(parts.type, parts.year, parts.monthOrNull, version, lastStreamId)

    /**
     * 服务端在流为空时返回 `tailId = ""`（`order_sync.lua:47`）。
     *
     * 空串**不是**游标：存进去等于清空游标，下次 `lastStreamId` 为空 → 无条件 FULL。
     * 一律归一成 null，由调用方决定是"保留旧游标"还是"确实还没有游标"。
     */
    private fun cursorOf(raw: String?): String? = raw?.takeIf { it.isNotEmpty() }

    private fun parseParts(fileId: String): OrderFileId.Parts? = try {
        OrderFileId.parse(fileId)
    } catch (e: IllegalArgumentException) {
        audit("非法 fileId=$fileId: ${e.message}")
        null
    }

    /** 把协议与网络错误折进 [Outcome]，让引擎对调用方**不抛异常**。 */
    private suspend fun guarded(
        storeKey: String,
        fileId: String,
        parts: OrderFileId.Parts,
        body: suspend () -> Outcome,
    ): Outcome = try {
        body()
    } catch (e: ApiError.Conflict) {
        // 两种语义靠这个标志区分：SERVER_BEHIND 要走探测，别的 409 只是失败。
        if (e.isOrderFileServerBehind) resolve409(storeKey, fileId, parts) else classifyFailure(e)
    } catch (e: ApiError) {
        classifyFailure(e)
    } catch (e: IOException) {
        // FULL 落地时流被掐断也算这里。replaceAllStreaming 会删掉临时文件，
        // 活动镜像仍是上一个一致状态，所以退避重试是安全的。
        audit("同步中断（I/O）fileId=$fileId: ${e.message}")
        Outcome.Failed("网络中断: ${e.message}", retriable = true)
    }

    private fun classifyFailure(e: ApiError): Outcome = when (e) {
        is ApiError.Unauthorized ->
            Outcome.NeedsRelogin(e.reason, ApiErrorClassifier.mayAutoRelogin(e.reason))

        // ⚠ 429 绝不自动重试：桶是**按 IP** 的（整店共享），且 Guava 平滑桶
        // `maxBurstSeconds=1.0` 只能存 0.083 个令牌 —— 重试只会把窗口顶满，
        // 让同一家店的其它设备一起被锁死。
        is ApiError.RateLimited -> Outcome.RateLimited(e.msg)

        is ApiError.ServerError -> {
            // 500 的响应体**只有一个追踪号**，没有异常消息。连时间一起记，
            // 否则事后对服务端日志时连是哪一次请求都找不到。
            audit("同步 500 fileId 未知 traceId=${e.traceId} at=${now()} msg=${e.msg}")
            Outcome.Failed("服务端异常（追踪号 ${e.traceId}）", retriable = true)
        }

        is ApiError.Network -> Outcome.Failed("网络失败: ${e.message}", retriable = true)

        is ApiError.Conflict -> {
            audit("同步 409（不是 SERVER_BEHIND）：${e.msg}")
            Outcome.Failed("冲突: ${e.msg}", retriable = false)
        }

        // 400 / 403 / 404 / 413 / 其它状态码 / 形状错误：重试都无意义。
        // 400 尤其要盯：多半是 A 类把 month=0 发出去了（见 OrderFileId.Parts.monthOrNull）。
        // ⚠ 基类 ApiError 没有 `msg` 属性（只有各子类有），所以这里只能用 Throwable.message。
        else -> {
            audit("同步失败 ${e.javaClass.simpleName}: ${e.message}")
            Outcome.Failed(e.message ?: e.javaClass.simpleName, retriable = false)
        }
    }
}
