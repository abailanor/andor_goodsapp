package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.lock.StripedMutex
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.OrderFileApi
import com.example.myapplication.net.Service1Api
import com.example.myapplication.net.dto.OrderSyncRequestDto
import com.example.myapplication.sync.OrderFileId
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 「校验镜像」：把服务器的文件重新 FULL 下载一份到 `mirror-tmp/`，与活动镜像**逐字节**比对。
 *
 * ## 为什么非它不可
 *
 * 服务端的 `orderfile:checksum:<fileId>` **没有任何接口能读**（README §2.2 只在启动自检里用）。
 * 三重自检（[MirrorCommitter.selfCheck]）比的是"磁盘 vs `file_state`"，那是**自比**：
 * 它只能发现本地半写、或镜像被别的路径改过，**发现不了"本地自洽但与服务器不同"**。
 *
 * 而后者恰恰是最危险的一类：等长覆写打在错误偏移上**不报错**，
 * `ensureFileBlocking`(:712) 重建出的空文件会让版本号推到 N+1 而校验和按旧值累加 ——
 * 结果是服务器文件与本地镜像**永久分歧且版本号相同**。守卫、游标、校验和全都不会响。
 *
 * 所以唯一的端到端对账手段就是：把服务器的字节再拉一遍，直接比。
 *
 * ## 三条硬规则
 *
 *  1. **只读活动镜像，绝不写它。** 服务器的副本落在 `mirror-tmp/verify-*`。
 *     校验工具把被校验的东西改坏了，比没有校验工具更糟。
 *  2. **全程流式。** 一个月的 B 类镜像可以到 26MB，`readBytes()` 会 OOM ——
 *     而这个工具最可能被调用的时机正是"怀疑数据坏了"，那时候最不该再制造一个崩溃。
 *  3. **持有 [StripedMutex] 的 `storeKey|fileId` 分段锁**，与 [MirrorCommitter] 互斥。
 *     不锁的话，一次并发 ADD 落在下载与比对之间，就会报出一个**假**的 Diverged，
 *     而"狼来了"报三次之后没人再信这个工具。
 *     ⚠ 代价是锁跨越一次网络下载（几十 MB 可能几十秒），期间该 fileId 的同步会等。
 *     这是调试动作，可以接受；不要把它挂到任何自动重试路径上。
 */
class MirrorVerifier(
    private val api: OrderFileApi,
    private val locator: MirrorLocator,
    private val stateStore: MirrorStateStore,
    /** `filesDir/mirror-tmp`。服务器副本落在这里，与活动镜像分区隔。 */
    private val tmpRoot: File,
    private val locks: StripedMutex,
    private val audit: (String) -> Unit = {},
) {

    /** 校验结论。**只有 [Diverged] 与 [LocalAhead] 需要人介入**，其余都能靠一次正常同步解决。 */
    sealed class Verdict {

        /** 逐字节一致（**含 4 字节版本头**）。这是唯一的好结果。 */
        data class Identical(val version: Int, val bytes: Long) : Verdict()

        /**
         * 服务器版本更高，且本地字节是服务器字节的**严格前缀** —— 只是落后，不是损坏。
         * 跑一次 [com.example.myapplication.sync.OrderFileSyncEngine.sync] 就补齐。
         */
        data class Behind(
            val localVersion: Int,
            val serverVersion: Int,
            val localBytes: Long,
            val serverBytes: Long,
        ) : Verdict()

        /**
         * **真分歧。**本地不是服务器的前缀，或版本号相同而字节不同。
         *
         * 处理：把活动镜像隔离到 `mirror-quarantine/`（保留证据）→ 标 DIVERGED → 强制 FULL。
         * **绝不尝试局部修补** —— 判出分歧恰恰说明"我不知道哪里坏了"。
         *
         * @param firstDiff 第一个不同字节的**绝对偏移**，从 0（版本头）算起。
         *                  长度不同而公共部分全同时，它就是较短那个文件的末尾。
         * @param cursorVersion `file_state.version`。与 [localVersion]（磁盘头部）**不等**
         *        说明"文件被改过而游标没跟上"或反之 —— 这条区分决定了是镜像坏了还是 Room 坏了。
         * @param localSample / @param serverSample 分歧点起各 [SAMPLE_BYTES] 字节的十六进制。
         *        偏移整体错位时，服务器那段会长得像"本地某处往后挪了几十字节"，肉眼就能认出来。
         * @param serverCopy 服务器副本，留在 `mirror-tmp/` 供手工比对第二处、第三处差异。
         *        路径按 (storeKey, fileId) 固定，重复校验会覆盖，**不会堆积**。
         */
        data class Diverged(
            val firstDiff: Long,
            val localBytes: Long,
            val serverBytes: Long,
            val localVersion: Int,
            val serverVersion: Int,
            val cursorVersion: Int?,
            val localSample: String,
            val serverSample: String,
            val serverCopy: File,
        ) : Verdict()

        /**
         * 本地版本比服务器**高**（服务端 SERVER_BEHIND）。服务器丢过数据或被回滚，
         * 本地镜像是唯一更全的副本。
         *
         * ⚠ **绝不能让 FULL 落地。**要走 `OrderFileSyncEngine.resolve409()` 的用户确认路径。
         *
         * 这里能从 FULL 响应里看出来是因为校验发的请求恒为 `version=0`，
         * 服务器不会拿它去比大小，于是照样回 FULL 而不是 409。
         */
        data class LocalAhead(
            val localVersion: Int,
            val serverVersion: Int,
            val localBytes: Long,
            val serverBytes: Long,
        ) : Verdict()

        /** 服务器没有这个文件（运维已归档删除）。本地那份是仅存的副本，只读展示。 */
        data class ServerMissing(val localVersion: Int, val localBytes: Long) : Verdict()

        /** 本地没有镜像文件 —— 通常是"这个月从未同步过"。同步一次即可。 */
        data class LocalMissing(val serverVersion: Int, val serverBytes: Long) : Verdict()

        /** 校验本身没跑成（网络、限速、响应形状）。[retriable] = 退避后重试有意义。 */
        data class Failed(val msg: String, val retriable: Boolean) : Verdict()
    }

    // ==================== 入口 ====================

    /**
     * 校验一个 fileId。**不抛**：协议与网络错误一律折进 [Verdict.Failed]，
     * 调用方（诊断页）只需要 `when`。
     */
    suspend fun verify(storeKey: String, fileId: String): Verdict {
        val parts = runCatching { OrderFileId.parse(fileId) }.getOrElse {
            return Verdict.Failed("非法 fileId=$fileId：${it.message}", retriable = false)
        }
        val serverCopy = Mirror(tmpFile(storeKey, fileId))

        val v = locks.withLock(StripedMutex.mirrorKey(storeKey, fileId)) {
            // 游标在锁内读：锁外读的话，一次并发提交会让"磁盘字节"与"游标版本"来自两个时刻，
            // 而 Diverged 报告里恰恰要靠这两者的关系来判断是镜像坏了还是 Room 坏了。
            val state = stateStore.read(storeKey, fileId)
            // ARCHIVED 会解析到 `mirror-archive/`。那是**想要的**：归档的前提就是服务器没这个文件了，
            // 校验应当回 ServerMissing；回了别的说明归档判断当初就是错的。
            val local = locator.resolve(storeKey, fileId, state?.syncState ?: SyncState.LIVE)
            try {
                download(parts, serverCopy)
            } catch (e: ApiError.NotFound) {
                val (ver, bytes) = local.readHeaderVersion() to local.length()
                serverCopy.file.delete()
                return@withLock Verdict.ServerMissing(ver, bytes)
            } catch (e: ApiError) {
                serverCopy.file.delete()
                return@withLock Verdict.Failed(describe(e), retriable = retriable(e))
            } catch (e: IOException) {
                serverCopy.file.delete()
                return@withLock Verdict.Failed("读写字节失败：${e.message}", retriable = true)
            }
            compare(local, serverCopy, state?.version)
        }
        audit("镜像校验 fileId=$fileId → ${summary(v)}")
        return v
    }

    /**
     * 逐个校验。**串行**：每个都是一次完整下载，并发只会把带宽和限速桶一起吃光。
     *
     * @return 与 [fileIds] 等长、同序。
     */
    suspend fun verifyAll(storeKey: String, fileIds: List<String>): List<Pair<String, Verdict>> =
        fileIds.map { it to verify(storeKey, it) }

    // ==================== 下载 ====================

    /**
     * 把服务器的文件拉进 [into]（`mirror-tmp/` 里）。
     *
     * 请求恒为 **`version = 0, lastStreamId = ""` 两个字面量** —— 与
     * `OrderFileSyncEngine.requestFull` 一样，为的是让 `order_sync.lua:61` 必走 FULL 分支。
     * 传真实游标的话服务器会回 LATEST/INCREMENTAL，那就一个字节都比不了。
     */
    private suspend fun download(parts: OrderFileId.Parts, into: Mirror) {
        // ⚠ 先删旧副本。上一次校验（或上一次崩溃）留下的文件若被当成"这次下载的结果"，
        // 就会拿一份陈旧字节去比，报出 Identical 或一个指不到真处的 Diverged。
        into.file.delete()
        val outcome = api.syncOrderFile(
            OrderSyncRequestDto(parts.type, parts.year, parts.monthOrNull, version = 0, lastStreamId = ""),
            onFull = { body -> land(body, into) },
        )
        when (outcome) {
            is Service1Api.SyncOutcome.Full -> Unit   // 已经在 onFull 里落好了

            // `version=0` 却拿到 LATEST ⇒ 服务器的 latest 也是 0 ⇒ 这个文件是空的
            // （只有 4 字节版本头，`ensureFileBlocking` 建的）。没有响应体，
            // 就照服务器的说法自己造一份"应有的样子"，走同一条比对路径 ——
            // 否则"服务端确实没有这个月的单"这条最常见的情况根本校验不了。
            is Service1Api.SyncOutcome.Latest -> {
                into.file.parentFile?.mkdirs()
                into.file.writeBytes(ByteOps.encodeHeader(outcome.version))
            }

            // ⚠ 抛 [ApiError.MalformedResponse] 而不是 IOException：形状不对**重试也没用**
            // （同样的请求会得到同样的响应），而 IOException 那一支是按"网络抖动，可重试"归类的。
            is Service1Api.SyncOutcome.Incremental ->
                throw ApiError.MalformedResponse("version=0 却拿到 INCREMENTAL（${outcome.messages.size} 条）")
        }
    }

    /** 流式落地，**不经内存整体持有**。顺带校验响应头版本与响应体自带的版本头一致。 */
    private fun land(body: Service1Api.FullFileBody, into: Mirror) {
        into.replaceAllStreaming(body.source.inputStream())
        val inBody = into.readHeaderVersion()
        if (inBody != body.version) {
            // 两者同源于服务器磁盘上的同一个文件，不等就说明传输被截断/被中间层改过。
            // 不挡下来的话，接下来的比对会把这件事报成"本地镜像坏了"。
            throw ApiError.MalformedResponse("响应头 x-file-version=v${body.version} 与响应体版本头 v$inBody 不符")
        }
    }

    // ==================== 比对 ====================

    private fun compare(local: Mirror, serverCopy: Mirror, cursorVersion: Int?): Verdict {
        if (!local.exists()) {
            // ⚠ 先读数再删：`File.length()` 与 `readHeaderVersion()` 在 delete 之后只会给 0，
            // 报告就成了"服务器也是空的"——恰好把结论说反。
            val v = Verdict.LocalMissing(serverCopy.readHeaderVersion(), serverCopy.length())
            serverCopy.file.delete()
            return v
        }

        val localBytes = local.length()
        val serverBytes = serverCopy.length()
        val localVersion = local.readHeaderVersion()
        val serverVersion = serverCopy.readHeaderVersion()

        // 连 4 字节版本头都凑不齐（半写）。这时"按内容比"没有意义：偏移 4 之后什么都不存在，
        // 而 `readHeaderVersion()` 只会给 0，比下去会把"文件被截断"报成"版本 0 与版本 N 的分歧"。
        if (localBytes < ByteOps.HEADER_LEN || serverBytes < ByteOps.HEADER_LEN) {
            return diverged(local, serverCopy, localBytes, serverBytes, localVersion, serverVersion, cursorVersion, localBytes)
        }

        // ⚠ **内容（偏移 4 起）要单独比。**
        // 版本头是版本号本身，版本不同时它**必然**不同 —— 拿整文件判"前缀"的话，
        // 分歧点恒为 0，"本地只是落后"会被一律报成真分歧，这个工具就只会喊狼来了。
        val c = diff(local.file, serverCopy.file, ByteOps.HEADER_LEN.toLong())
        val contentIdentical = c.firstDiff == null && localBytes == serverBytes

        if (contentIdentical && localVersion == serverVersion) {
            serverCopy.file.delete()
            return Verdict.Identical(localVersion, localBytes)
        }

        if (localVersion > serverVersion) {
            serverCopy.file.delete()
            return Verdict.LocalAhead(localVersion, serverVersion, localBytes, serverBytes)
        }

        // 本地内容是服务器内容的严格前缀且服务器版本更高 → 只是落后，不是损坏。
        // 判据是"第一个差异字节正好落在本地文件的末尾"（[Diff.firstDiff] 是绝对偏移）。
        if (serverVersion > localVersion && c.firstDiff == localBytes) {
            serverCopy.file.delete()
            return Verdict.Behind(localVersion, serverVersion, localBytes, serverBytes)
        }

        // 真分歧。这时才去算"整文件（含头）的第一个差异字节"：
        // 给人一个能直接 `seek` 过去的绝对偏移，也顺带覆盖"内容全同、只有版本头不同"那一支。
        val whole = diff(local.file, serverCopy.file, 0)
        return diverged(
            local, serverCopy, localBytes, serverBytes, localVersion, serverVersion, cursorVersion,
            whole.firstDiff ?: minOf(localBytes, serverBytes),
        )
    }

    /**
     * 组装 [Verdict.Diverged]，并**保留**服务器副本。
     *
     * 留着是为了能手工比对第二处、第三处差异："从某个偏移起整体错位 N 字节"这种形状
     * 只看第一处是看不出来的，而它恰恰是偏移算错时最常见的形状。
     */
    private fun diverged(
        local: Mirror,
        serverCopy: Mirror,
        localBytes: Long,
        serverBytes: Long,
        localVersion: Int,
        serverVersion: Int,
        cursorVersion: Int?,
        at: Long,
    ) = Verdict.Diverged(
        firstDiff = at,
        localBytes = localBytes,
        serverBytes = serverBytes,
        localVersion = localVersion,
        serverVersion = serverVersion,
        cursorVersion = cursorVersion,
        localSample = sample(local.file, at),
        serverSample = sample(serverCopy.file, at),
        serverCopy = serverCopy.file,
    )

    /** 第一个不同字节的**绝对**偏移。[compared] 是实际比过的字节数（= 公共前缀长度）。 */
    internal data class Diff(val firstDiff: Long?, val compared: Long)

    /**
     * 从 [from] 起逐字节比对两个文件。**分块流式**，堆上恒定 [CHUNK] × 2 字节。
     *
     * 两个 `RandomAccessFile` 都定位到 [from] 后顺序推进，指针天然对齐，中途不再 `seek`。
     * 长度不同时，公共部分全同 → 分歧点就是较短那个的末尾（[Diff.firstDiff] = `min(la, lb)`）。
     *
     * @param from 起始绝对偏移。调用方必须保证 `from <= min(两文件长度)`；
     *             不满足时可比区间为空，结论退化成"只比长度"，所以 [compare] 先挡了半写文件。
     * @throws IOException 文件不存在或读失败。**不静默返回"一致"** ——
     *         读不出来却报 Identical 是这类工具最坏的失败方式。
     */
    internal fun diff(a: File, b: File, from: Long = 0): Diff {
        if (!a.exists()) throw IOException("文件不存在：${a.path}")
        if (!b.exists()) throw IOException("文件不存在：${b.path}")
        RandomAccessFile(a, "r").use { ra ->
            RandomAccessFile(b, "r").use { rb ->
                val la = ra.length()
                val lb = rb.length()
                val end = minOf(la, lb)
                ra.seek(from)
                rb.seek(from)
                val bufA = ByteArray(CHUNK)
                val bufB = ByteArray(CHUNK)
                var off = from
                while (off < end) {
                    val n = minOf(CHUNK.toLong(), end - off).toInt()
                    ra.readFully(bufA, 0, n)
                    rb.readFully(bufB, 0, n)
                    for (i in 0 until n) {
                        if (bufA[i] != bufB[i]) return Diff(off + i, off + i - from)
                    }
                    off += n
                }
                return if (la == lb) Diff(null, end - from) else Diff(end, end - from)
            }
        }
    }

    /** 从 [at] 起最多 [SAMPLE_BYTES] 字节的十六进制。用十六进制而不是文本：这是字节工具，歧义不能有。 */
    private fun sample(file: File, at: Long): String {
        val len = file.length()
        if (at >= len) return "<末尾之后，缺 ${at - len} 字节>"
        val n = minOf(SAMPLE_BYTES.toLong(), len - at).toInt()
        val b = ByteArray(n)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(at)
            raf.readFully(b)
        }
        return b.joinToString(" ") { "%02x".format(it) }
    }

    private fun tmpFile(storeKey: String, fileId: String): File =
        File(tmpRoot, "verify-$storeKey-${fileId.replace(':', '_')}.mirror")

    private fun describe(e: ApiError): String = when (e) {
        is ApiError.Unauthorized -> "需要重新登录（${e.reason}）"
        is ApiError.ServerError -> "服务器内部错误（追踪号 ${e.traceId ?: "无"}）"
        is ApiError.RateLimited -> "触发限速"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun retriable(e: ApiError): Boolean = when (e) {
        // 429 绝不自动重试（Guava 平滑桶几乎没有突发额度）；401 要重登不是重试。
        is ApiError.RateLimited, is ApiError.Unauthorized, is ApiError.Forbidden -> false
        is ApiError.Network, is ApiError.ServerError -> true
        else -> false
    }

    /**
     * 一行结论，进变更日志与诊断页。
     *
     * **不含订单原文**（uuid、writer、金额都不出现）。唯一例外是 [Verdict.Diverged] 的
     * 十六进制样本 —— 那是"从某个偏移起整体错位 N 字节"的唯一线索，
     * 而分歧本身是灾难级事件，此时诊断价值压过隐私；且上限只有 [SAMPLE_BYTES] 字节。
     */
    private fun summary(v: Verdict): String = when (v) {
        is Verdict.Identical -> "一致（v${v.version}，${v.bytes} 字节）"
        is Verdict.Behind -> "本地落后 v${v.localVersion}→v${v.serverVersion}（${v.localBytes}/${v.serverBytes} 字节），同步即可"
        is Verdict.Diverged -> "⚠ 分歧@${v.firstDiff}：本地 v${v.localVersion}/${v.localBytes}B vs 服务器 v${v.serverVersion}/${v.serverBytes}B" +
            "（游标 ${v.cursorVersion}）本地[${v.localSample}] 服务器[${v.serverSample}]"
        is Verdict.LocalAhead -> "⚠ 本地领先 v${v.localVersion} > 服务器 v${v.serverVersion}，服务器丢过数据"
        is Verdict.ServerMissing -> "服务器已无此文件（本地 v${v.localVersion}/${v.localBytes}B）"
        is Verdict.LocalMissing -> "本地无镜像（服务器 v${v.serverVersion}/${v.serverBytes}B）"
        is Verdict.Failed -> "校验未完成：${v.msg}"
    }

    companion object {
        /** 与 [Mirror.streamContent] 同一个块大小。 */
        private const val CHUNK = 64 * 1024

        /** 分歧点两侧各取多少字节做样本。够认出"错位"的形状，又不至于把订单内容抄进日志。 */
        private const val SAMPLE_BYTES = 16
    }
}
