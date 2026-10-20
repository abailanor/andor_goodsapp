package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.lock.StripedMutex
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 镜像状态的持久化后端。P1 之后由 Room 实现（`file_state` + `order_index` + `stats_contrib`）。
 *
 * **实现方必须保证**：[commit] / [commitDiverged] / [commitFull] 各自是**一个原子事务**。
 * 进程在事务中间被杀的话，下次启动的三重自检（[MirrorCommitter.selfCheck]）会发现不一致并走 FULL 自愈。
 */
interface MirrorStateStore {

    /** 读游标与自检值。没有记录时返回 null（调用方按 [MirrorState.fresh] 处理）。 */
    suspend fun read(storeKey: String, fileId: String): MirrorState?

    /**
     * 提交一次 ADD 或 PATCH 的结果。
     *
     * ⚠ **只允许在镜像文件已经 fsync 之后调用。** 反过来的话进程在两步之间被杀就会
     * "游标已前进而文件没改"，那段变更**永久丢失且无法自愈**（下次同步会命中 LATEST）。
     * 这个顺序由 [MirrorCommitter] 强制，不要绕过它直接调本方法。
     *
     * ⚠ `record.alreadyApplied == true` 时**同样必须提交**。跳过就会让 `order_index`
     * 与统计永久少一笔，而且不报错。
     */
    suspend fun commit(record: MirrorCommitRecord)

    /**
     * FULL 落地后的提交：重建订单索引 + 统计贡献先减后加 + 写游标，**必须在同一个事务里**。
     *
     * 实现要点：
     *  1. 扫描 [mirror] 建索引行的那一步是**文件 I/O，必须在事务之外**做完
     *     （事务持有数据库写锁，在里面流式读几十 MB 会把整个 App 的数据库卡住）；
     *  2. 统计贡献必须**先减掉该 fileId 的旧贡献（`WHERE frozen=0`）再加新贡献**，
     *     否则重复 FULL 会累加；
     *  3. `order_index` 不存镜像路径——路径由 [MirrorLocator] 按 `syncState` 解析，
     *     存了的话归档时要改写几千行。
     */
    suspend fun commitFull(storeKey: String, fileId: String, state: MirrorState, mirror: Mirror)

    /**
     * 镜像已隔离，把状态标成 DIVERGED 并把游标归零。
     *
     * 归零是**故意的**：这样即使调度器逻辑有 bug，`version=0 + lastStreamId=null`
     * 也保证下次一定走 FULL。取证材料是隔离到 `mirror-quarantine/` 的那个文件，
     * 不依赖数据库里留旧游标。
     */
    suspend fun commitDiverged(storeKey: String, fileId: String, reason: String)

    /**
     * 服务端已归档删除这个文件（409 → `version=0` 探测得到 LATEST）。
     *
     * 与 [commitDiverged] 的关键区别：**游标原样冻结，不归零**。
     *
     * 归零会造成两件事：
     *  1. `stats_contrib` 按 `(fileId, version)` 记账，与游标行对不上；
     *  2. 运维哪天把文件恢复了，`version=0` → FULL → 把归档镜像覆盖掉，
     *     而 `order_index` 还指着归档路径 —— 之后每次取消都打在错误的偏移上。
     *
     * 归档只是"不再同步"，**不是"不再统计"**：`stats_contrib` 的 scope 保留
     * `FILE:<fileId>` 不变，另置 `frozen=1`；FULL 重投影路径必须 `WHERE frozen=0`，
     * 否则一次误解档会把历史统计清零。
     *
     * 归档**不删任何数据**：`order_index` / `order_item_index` / `stats_contrib` 全部保留。
     */
    suspend fun commitArchived(storeKey: String, fileId: String, reason: String)
}

/**
 * 按 `syncState` 解析镜像文件的实际位置。
 *
 * 三个区互不重叠：
 *  - `mirror/`          活动镜像，参与同步
 *  - `mirror-archive/`  服务端已归档删除的月份，只读
 *  - `mirror-quarantine/` 判定 DIVERGED 时隔离的证据，只读
 *
 * `order_index` **不存路径**，就是因为路径会随 `syncState` 变；存了的话归档时要改写几千行。
 */
class MirrorLocator(
    private val mirrorRoot: File,
    private val archiveRoot: File,
) {
    /**
     * @param storeKey **merchantCode**（店铺编号，如 `00002`），不是店主用户名
     * @param fileId   形如 `B:alice:2026-09`，中间的 `alice` 是**店主用户名**
     *                 （文件归属店主，员工操作的是店铺文件，README §4.2）
     *
     * 两个维度都必须进路径：一台设备先后登录两个店铺时，
     * 缺 storeKey 会让 A 店和 B 店的 `2026-09.mirror` 写到同一个文件上。
     */
    fun resolve(storeKey: String, fileId: String, syncState: SyncState): Mirror {
        val ownerName = fileId.split(':').getOrElse(1) { "" }
        require(ownerName.isNotEmpty()) { "fileId 里没有店主用户名: $fileId" }
        val rel = Mirror.relativePath(storeKey, ownerName, fileId)
        val root = if (syncState == SyncState.ARCHIVED) archiveRoot else mirrorRoot
        return Mirror(File(root, rel))
    }

    fun quarantineTarget(storeKey: String, fileId: String, quarantineRoot: File, nowMillis: Long): File {
        val safe = fileId.replace(':', '_')
        return File(quarantineRoot, "$storeKey-$safe-$nowMillis.mirror")
    }
}

/**
 * **唯一允许修改镜像文件的入口。**
 *
 * ## 它强制的三件事
 *
 * 1. **顺序**：`守卫判定 → 写内容 + fsync → 写版本头 + fsync → 提交游标`。
 *    前两步的顺序与服务器一致（README §8：Lua 原子提交 Redis 效果 → 磁盘内容 → 磁盘头部）。
 *    反过来会在崩溃时留下"头部说已是新版本、内容还是旧的"。
 *    最后一步（游标）必须在文件之后：Android 随时可能被杀，反了就是永久丢数据。
 *    这个顺序写死在 [runCommit] 里，没有参数可以调换。
 *
 * 2. **互斥**：同一 `storeKey|fileId` 的追加与补丁严格串行。
 *
 * 3. **幂等**：每条消息落盘前过 [MirrorGuards]，重复到达不会写两次。
 *
 * ## DIVERGED 的唯一处理
 *
 * 隔离证据 → 标 DIVERGED → 归零游标 → 由调度器排一次 FULL。**绝不尝试局部修补。**
 * 局部修补的前提是"我知道哪里坏了"，而守卫判 DIVERGED 恰恰说明我不知道。
 */
class MirrorCommitter(
    private val locator: MirrorLocator,
    private val quarantineRoot: File,
    private val locks: StripedMutex,
    private val stateStore: MirrorStateStore,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val audit: (String) -> Unit = {},
) {

    /** 提交结果。调用方据此决定是否要排 FULL。 */
    sealed class Result {
        /** 成功。`applied=false` 表示守卫判定已应用过、跳过了文件写入但补齐了 Room。 */
        data class Committed(val applied: Boolean, val version: Int) : Result()

        /** 镜像已隔离，需要 FULL 自愈。 */
        data class Diverged(val reason: String, val quarantinedTo: File?) : Result()
    }

    // ==================== ADD ====================

    /**
     * 应用一条 ADD。
     *
     * @param payloadBytes        订单原文的 UTF-8 字节，**不含**末尾 LF
     * @param uuidValueByteIndex  `u`：uuid **值**在 payload 里的字节下标
     * @param remarkValueByteIndex `r`：remark **值**在 payload 里的字节下标；A 类才有
     * @param serverUuidOffset    服务端响应/流消息给的 `uuidOffset`（绝对字节偏移）
     * @param serverStatusOffset  服务端给的 `statusOffset`
     *
     * `u` 与 `r` 必须来自**你上传的那段 payload**，不是从文件里搜出来的。
     * README §2.5：`remarkOffset = uuidOffset + (r - u)`。
     */
    suspend fun commitAdd(
        storeKey: String,
        fileId: String,
        orderUuid: String,
        msgVersion: Int,
        payloadBytes: ByteArray,
        uuidValueByteIndex: Int,
        remarkValueByteIndex: Int?,
        serverUuidOffset: Long,
        serverStatusOffset: Long,
        newStreamId: String?,
    ): Result = runCommit(storeKey, fileId) { mirror, state ->
        val lineStart = serverUuidOffset - uuidValueByteIndex
        val lineLength = payloadBytes.size.toLong() + 1   // + 末尾 LF
        val headerVersion = mirror.readHeaderVersion()
        val fileLength = mirror.length()

        when (val v = MirrorGuards.guardAdd(headerVersion, fileLength, msgVersion, lineStart, lineLength)) {
            MirrorGuards.Verdict.APPLY -> {
                val withLf = payloadBytes + ByteOps.LF
                // 1) 内容
                mirror.appendBytes(withLf)
                // 2) 版本头
                mirror.writeHeaderVersion(msgVersion)
                val newState = state.copy(
                    version = msgVersion,
                    lastStreamId = newStreamId ?: state.lastStreamId,
                    checksum = state.checksum + ByteOps.sumBytes(withLf),
                    mirrorBytes = fileLength + lineLength,
                    firstSyncedAt = state.firstSyncedAt ?: wallClock(),
                )
                CommitOutcome(
                    record = MirrorCommitRecord(
                        storeKey = storeKey,
                        fileId = fileId,
                        state = newState,
                        alreadyApplied = false,
                        order = OrderDelta.Added(
                            orderUuid = orderUuid,
                            lineStart = lineStart,
                            uuidOffset = serverUuidOffset,
                            statusOffset = serverStatusOffset,
                            remarkOffset = remarkValueByteIndex?.let { r ->
                                serverUuidOffset + (r - uuidValueByteIndex)
                            },
                            payload = payloadBytes,
                            appliedFileVersion = msgVersion,
                        ),
                    ),
                    result = Result.Committed(applied = true, version = msgVersion),
                )
            }

            MirrorGuards.Verdict.ALREADY_APPLIED -> {
                // 文件不动，但 Room 必须补齐（否则统计永久少一笔且无报错）
                val reconciled = reconcileStateFromDisk(mirror, state, headerVersion, fileLength, fileId)
                CommitOutcome(
                    record = MirrorCommitRecord(
                        storeKey = storeKey,
                        fileId = fileId,
                        state = reconciled.copy(
                            lastStreamId = newStreamId ?: reconciled.lastStreamId,
                            firstSyncedAt = reconciled.firstSyncedAt ?: wallClock(),
                        ),
                        alreadyApplied = true,
                        order = OrderDelta.Added(
                            orderUuid = orderUuid,
                            lineStart = lineStart,
                            uuidOffset = serverUuidOffset,
                            statusOffset = serverStatusOffset,
                            remarkOffset = remarkValueByteIndex?.let { r ->
                                serverUuidOffset + (r - uuidValueByteIndex)
                            },
                            payload = payloadBytes,
                            appliedFileVersion = msgVersion,
                        ),
                    ),
                    result = Result.Committed(applied = false, version = headerVersion),
                )
            }

            MirrorGuards.Verdict.DIVERGED -> CommitOutcome.diverged(
                "ADD 守卫判定分歧 uuid=$orderUuid msgVersion=$msgVersion headerVersion=$headerVersion " +
                    "fileLength=$fileLength 期望行起点=$lineStart 行长=$lineLength"
            )
        }
    }

    // ==================== PATCH ====================

    /**
     * 应用一个等长补丁。
     *
     * @param knownStatusOffset 该订单在 `order_index` 里记录的 statusOffset；查不到传 null
     * @param knownRemarkOffset 同上，remarkOffset；B 类或无 remark 传 null
     */
    suspend fun commitPatch(
        storeKey: String,
        fileId: String,
        msgVersion: Int,
        patchOffset: Long,
        patchContentBytes: ByteArray,
        orderUuid: String?,
        knownStatusOffset: Long?,
        knownRemarkOffset: Long?,
        newStreamId: String?,
    ): Result = runCommit(storeKey, fileId) { mirror, state ->
        val headerVersion = mirror.readHeaderVersion()
        val fileLength = mirror.length()

        // 读旧字节：既供守卫判"已应用"，也供服务端对齐的 1→0 校验。
        // 越界时传 null，让守卫判 DIVERGED —— 越界本身就是分歧的证据。
        val oldBytes: ByteArray? = try {
            if (patchOffset < ByteOps.HEADER_LEN ||
                patchOffset + patchContentBytes.size > fileLength
            ) null
            else mirror.readAt(patchOffset, patchContentBytes.size)
        } catch (e: IOException) {
            null
        }

        val verdict = MirrorGuards.guardPatch(
            headerVersion, fileLength, msgVersion, patchOffset, patchContentBytes, oldBytes
        )

        when (verdict) {
            MirrorGuards.Verdict.APPLY, MirrorGuards.Verdict.ALREADY_APPLIED -> {
                val kind = PatchClassifier.classify(
                    patchOffset, patchContentBytes.size, knownStatusOffset, knownRemarkOffset
                )
                // 与服务端 OrderFileService:383-391 的条件逐字对齐：
                // 只有 1→0 才回退库存。条件更宽会在服务端没回退时回退，长期偏离。
                val isOneToZero = kind == PatchKind.STATUS &&
                    oldBytes != null && oldBytes.size == 1 && oldBytes[0] == '1'.code.toByte() &&
                    patchContentBytes.size == 1 && patchContentBytes[0] == '0'.code.toByte()

                // 只有 1 字节的状态补丁才有"新状态数字"。`'0'..'9'` 之外的字节（服务端不会发，
                // 但流消息是服务端给的、客户端不该假设）一律记 null，让 order_index.status 保持原值，
                // 而不是写进去一个 -5 之类的垃圾 —— 那种值在 UI 上看不出异常，只会让筛选悄悄漏单。
                val newStatusDigit = if (kind == PatchKind.STATUS && patchContentBytes.size == 1) {
                    val b = patchContentBytes[0].toInt()
                    if (b in '0'.code..'9'.code) b - '0'.code else null
                } else null

                // 备注文本必须在**这里**解码好带进 Room：事务里不许做文件 I/O，
                // 所以 store 没法自己去镜像里读那 150 字节（见 OrderDelta.Patched.newRemark）。
                // trimEnd 与 OrderPayloadIndex 存 remark 的规则一致 —— 文件里那 150 字节的
                // 尾部空格对用户没有意义，两边规则不同的话改一次备注就会多出/少掉一堆空格。
                val newRemark = if (kind == PatchKind.REMARK) {
                    String(patchContentBytes, Charsets.UTF_8).trimEnd().takeIf { it.isNotEmpty() }
                } else null

                if (kind == PatchKind.UNKNOWN) {
                    audit(
                        "UNKNOWN 补丁 fileId=$fileId offset=$patchOffset len=${patchContentBytes.size} " +
                            "knownStatus=$knownStatusOffset knownRemark=$knownRemarkOffset —— 不猜，触发 FULL 自愈"
                    )
                }

                if (verdict == MirrorGuards.Verdict.APPLY) {
                    // 1) 内容
                    mirror.patchAt(patchOffset, patchContentBytes)
                    // 2) 版本头
                    mirror.writeHeaderVersion(msgVersion)
                }

                val oldSum = oldBytes?.let { ByteOps.sumBytes(it) } ?: 0
                val applied = verdict == MirrorGuards.Verdict.APPLY
                // ALREADY_APPLIED 时以磁盘为准对账（见 reconcileStateFromDisk）
                val baseState = if (applied) state
                else reconcileStateFromDisk(mirror, state, headerVersion, fileLength, fileId)
                val newState = baseState.copy(
                    version = if (applied) msgVersion else baseState.version,
                    lastStreamId = newStreamId ?: baseState.lastStreamId,
                    checksum = if (applied) {
                        baseState.checksum - oldSum + ByteOps.sumBytes(patchContentBytes)
                    } else baseState.checksum,
                    mirrorBytes = if (applied) fileLength else baseState.mirrorBytes,
                    firstSyncedAt = baseState.firstSyncedAt ?: wallClock(),
                )
                CommitOutcome(
                    record = MirrorCommitRecord(
                        storeKey = storeKey,
                        fileId = fileId,
                        state = newState,
                        alreadyApplied = !applied,
                        order = OrderDelta.Patched(
                            orderUuid = orderUuid,
                            patchOffset = patchOffset,
                            patchKind = kind,
                            newStatusDigit = newStatusDigit,
                            newRemark = newRemark,
                            cancelAppliedVersion = if (isOneToZero) msgVersion else null,
                        ),
                    ),
                    result = Result.Committed(
                        applied = verdict == MirrorGuards.Verdict.APPLY,
                        version = newState.version,
                    ),
                )
            }

            MirrorGuards.Verdict.DIVERGED -> CommitOutcome.diverged(
                "PATCH 守卫判定分歧 uuid=$orderUuid msgVersion=$msgVersion headerVersion=$headerVersion " +
                    "offset=$patchOffset len=${patchContentBytes.size} fileLength=$fileLength"
            )
        }
    }

    // ==================== FULL ====================

    /**
     * 用服务器字节流整体替换本地镜像。
     *
     * 流程：流式写临时文件 → fsync → 原子 rename → **一个事务**里重建索引 + 统计先减后加 + 提交游标。
     *
     * @param serverVersion   `x-file-version` 响应头的值
     * @param newStreamId     `x-stream-id` 响应头的值。**FULL 的游标只能走响应头**——
     *                        响应体是文件字节流，没有别的地方能带出来。不读它的话
     *                        做完一次全量就永远没有游标，之后每次都只能再走全量。
     *
     * ⚠ 响应头名实测是**小写**（`x-file-version` / `x-stream-id`），取的时候要不区分大小写。
     * ⚠ **不要依据 Content-Type 判断这是不是 FULL**：服务端显式设了 `application/octet-stream`，
     *    但 Spring 按 `Resource` 的文件名（`.txt`）重新推断，实测发出的是 `text/plain`。
     */
    suspend fun commitFull(
        storeKey: String,
        fileId: String,
        source: InputStream,
        serverVersion: Int,
        newStreamId: String?,
    ): Result {
        val key = StripedMutex.mirrorKey(storeKey, fileId)
        return locks.withLock(key) {
            val prev = stateStore.read(storeKey, fileId)
            // FULL 之前镜像可能是 LIVE 也可能是 DIVERGED/ARCHIVED 解档；一律落到活动区
            val mirror = locator.resolve(storeKey, fileId, SyncState.LIVE)
            mirror.file.parentFile?.mkdirs()
            val (checksum, total) = mirror.replaceAllStreaming(source)
            val state = MirrorState(
                version = serverVersion,
                lastStreamId = newStreamId,
                checksum = checksum,
                mirrorBytes = total,
                firstSyncedAt = prev?.firstSyncedAt ?: wallClock(),
                syncState = SyncState.LIVE,
            )
            stateStore.commitFull(storeKey, fileId, state, mirror)
            Result.Committed(applied = true, version = serverVersion)
        }
    }

    // ==================== 归档 ====================

    /**
     * 服务端已归档删除这个文件（`resolve409()` 的探测返回 LATEST）。
     *
     * 镜像搬进 `mirror-archive/`，`file_state` 标 ARCHIVED，**游标与索引与统计贡献全部原样保留**
     * （贡献置 `frozen=1`，不再参与 FULL 重投影）。
     *
     * @return `true` = 归档完成（含"本地本来就没有镜像可搬"）。
     *         `false` = **镜像搬不动**（跨文件系统 / 权限 / 被别的进程占用），
     *         此时**不标记 ARCHIVED**。
     *
     * ⚠ 搬不动就宁可留在 LIVE 继续同步，也不要写 ARCHIVED：
     * 半归档状态（数据库说已归档、文件还躺在活动区）会让下一次 FULL
     * 把服务器较短的字节流写进那个"本该冻结"的文件，而调用方以为它是只读证据。
     * 这属于**静默破坏**，没有任何机制会发现。
     *
     * 归档区的路径是**稳定的**（不像 quarantine 带时间戳）：同一个 fileId 二次归档会覆盖
     * 上一份。这里显式记一条审计，免得事后看归档文件时分不清它是哪一次归档留下的。
     */
    suspend fun archive(storeKey: String, fileId: String, reason: String): Boolean =
        locks.withLock(StripedMutex.mirrorKey(storeKey, fileId)) {
            val live = locator.resolve(storeKey, fileId, SyncState.LIVE)
            if (live.exists()) {
                val target = locator.resolve(storeKey, fileId, SyncState.ARCHIVED)
                if (target.exists()) audit("归档区已有同名镜像，将被覆盖 fileId=$fileId")
                val ok = runCatching { live.moveTo(target.file) }
                    .onFailure { e -> audit("归档镜像失败 fileId=$fileId: ${e.message} —— 保持 LIVE") }
                    .isSuccess
                if (!ok) return@withLock false
            }
            stateStore.commitArchived(storeKey, fileId, reason)
            audit("ARCHIVED fileId=$fileId reason=$reason")
            true
        }

    // ==================== 自检 ====================

    /**
     * 三重自检。任一不符即判 DIVERGED。
     *
     * ```
     * readHeaderVersion() == file_state.version
     * file.length()       == file_state.mirrorBytes
     * diskChecksum()      == file_state.checksum
     * ```
     *
     * 调用时机：App 启动（只查当月+上月，避免卡顿）、每次 FULL 之后、后台每天一次全量抽检。
     *
     * ⚠ 第三项对几十 MB 的文件是流式求和（约 30~80ms），**不要在主线程或每次操作前调**。
     */
    suspend fun selfCheck(storeKey: String, fileId: String, verifyChecksum: Boolean): Boolean {
        val state = stateStore.read(storeKey, fileId) ?: return true
        if (state.syncState == SyncState.DIVERGED) return false
        val mirror = locator.resolve(storeKey, fileId, state.syncState)
        if (!mirror.exists()) {
            // ⚠ ARCHIVED 时**不许**判 DIVERGED。归档月份本来就可能没有本地镜像
            // （归档发生在客户端下载它之前），而 diverge 会**把游标归零** ——
            // 一旦运维恢复了这个文件，归零的游标会让下一次同步走 FULL，
            // 用服务器的字节流覆盖掉那份本该冻结的归档镜像。
            // 也就是说，"自检发现文件不见了"这个动作自己会解除归档，而且不报错。
            if (state.syncState == SyncState.ARCHIVED) {
                audit("自检：归档镜像不在 mirror-archive/ fileId=$fileId —— 只报不判分歧")
                return false
            }
            // 文件没了但状态说它应该在 —— 也是分歧
            diverge(storeKey, fileId, "自检：镜像文件不存在但 file_state 有记录")
            return false
        }
        val headerOk = mirror.readHeaderVersion() == state.version
        val lenOk = mirror.length() == state.mirrorBytes
        val sumOk = !verifyChecksum || mirror.checksumContent() == state.checksum
        if (headerOk && lenOk && sumOk) return true
        diverge(
            storeKey, fileId,
            "自检不符 header=${mirror.readHeaderVersion()}/${state.version} " +
                "len=${mirror.length()}/${state.mirrorBytes} " +
                "checksum=${if (verifyChecksum) mirror.checksumContent().toString() else "skip"}/${state.checksum}"
        )
        return false
    }

    // ==================== 内部 ====================

    private class CommitOutcome(
        val record: MirrorCommitRecord?,
        val result: Result,
        val divergeReason: String? = null,
    ) {
        companion object {
            fun diverged(reason: String) = CommitOutcome(null, Result.Diverged(reason, null), reason)
        }
    }

    /**
     * ALREADY_APPLIED 时的状态对账：**以磁盘为准**。
     *
     * 正常情况下 Room 里的 state 与磁盘是自洽的（两者在上一次提交里一起写的）。
     * 但有一种情况不是：**文件已 fsync、Room 事务还没跑，进程就被杀了**。
     * 重启后重发同一条消息会命中 ALREADY_APPLIED，而此时 Room 里的 state
     * 要么根本不存在（调用方回退成 [MirrorState.fresh]，version=0）、要么停留在旧值。
     *
     * 直接拿它 `copy` 就会把 version 写成 0 —— 下次同步永远停在旧版本，
     * 而且**不会有任何报错**。这正是"AlreadyApplied 分支直接 return"那个陷阱的变体。
     *
     * 校验和只能流式实算（几十 MB 约 30~80ms），但这条路径只在崩溃恢复时走，代价可接受。
     * 正常双投递（HTTP 响应 + SSE 回声）时 state 与磁盘一致，直接返回、不做任何 I/O。
     */
    private fun reconcileStateFromDisk(
        mirror: Mirror,
        state: MirrorState,
        headerVersion: Int,
        fileLength: Long,
        fileId: String,
    ): MirrorState {
        if (state.version == headerVersion && state.mirrorBytes == fileLength) return state
        audit(
            "状态对账 fileId=$fileId：Room 记录(v${state.version}/${state.mirrorBytes}B) 与 " +
                "磁盘(v$headerVersion/${fileLength}B) 不符，以磁盘为准重建（多半是上次提交在文件落盘后、Room 事务前被杀）"
        )
        return state.copy(
            version = headerVersion,
            mirrorBytes = fileLength,
            checksum = mirror.checksumContent(),
        )
    }

    /**
     * 所有提交路径的唯一骨架。**顺序写死在这里，没有参数可以调换。**
     */
    private suspend fun runCommit(
        storeKey: String,
        fileId: String,
        body: (Mirror, MirrorState) -> CommitOutcome,
    ): Result {
        val key = StripedMutex.mirrorKey(storeKey, fileId)
        return locks.withLock(key) {
            val state = stateStore.read(storeKey, fileId) ?: MirrorState.fresh()
            if (state.syncState == SyncState.ARCHIVED) {
                // 归档月份是终态：不接受任何写入。服务端如果又给它建了新订单，
                // 本地与服务器会各自独立发展 —— 这是用户选择归档时接受的后果。
                return@withLock Result.Diverged("fileId=$fileId 已归档，拒绝写入", null)
            }
            val mirror = locator.resolve(storeKey, fileId, state.syncState)
            mirror.ensureCreated()

            val outcome = body(mirror, state)

            if (outcome.divergeReason != null) {
                val target = onDiverged(storeKey, fileId, mirror, outcome.divergeReason)
                return@withLock Result.Diverged(outcome.divergeReason, target)
            }

            // 3) 游标与索引 —— 必须在文件 fsync 之后
            outcome.record?.let { stateStore.commit(it) }
            outcome.result
        }
    }

    /** 隔离证据 + 标 DIVERGED + 归零游标。证据**不删**，方便事后排查。 */
    private suspend fun onDiverged(
        storeKey: String,
        fileId: String,
        mirror: Mirror,
        reason: String,
    ): File? {
        audit("DIVERGED fileId=$fileId reason=$reason")
        val target = if (mirror.exists()) {
            locator.quarantineTarget(storeKey, fileId, quarantineRoot, wallClock()).also {
                runCatching { mirror.moveTo(it) }
                    .onFailure { e -> audit("隔离镜像失败 $fileId: ${e.message}") }
                    .getOrNull()
            }
        } else null
        diverge(storeKey, fileId, reason)
        return target
    }

    private suspend fun diverge(storeKey: String, fileId: String, reason: String) {
        stateStore.commitDiverged(storeKey, fileId, reason)
    }
}
