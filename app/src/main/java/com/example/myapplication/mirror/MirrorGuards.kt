package com.example.myapplication.mirror

/**
 * 幂等守卫：在动镜像文件之前判定"这条消息该不该应用"。
 *
 * ## 为什么这是承重墙
 *
 * 同一条变更可能到达**两次**，而且两条通道都是正常的：
 *
 *  1. HTTP 响应与 SSE 是两条独立通道。上传订单的响应里已经带了 `streamId`，
 *     但 SSE 之后还会推一条 `ORDER_FILE` 提示，触发一次 sync，
 *     而那次 sync 的 INCREMENTAL 里可能**又**包含这条 ADD；
 *  2. 游标已提交但进程在提交后、下次 sync 前被杀 → 重启后重发；
 *  3. SSE 断线期间下的单会让游标停在旧位置，下次同步重复收到已应用的消息。
 *
 * 而**订单 ADD 消息不幂等**：它带着 `uuidOffset`/`statusOffset` 让你在镜像的精确位置写入。
 * 重复应用一次，镜像里就会出现**同一个 order_uuid 的两行**，文件比服务器长，
 * 此后所有订单的定位全部错乱——而且是静默的（校验和会按新内容重算所以自洽，
 * WAL 会忠实记录并回放这次破坏，服务端完整性机制检测不出语义破坏，README §9）。
 *
 * 所以每一条消息在落盘前都要过守卫。判定只用两个**本地可验证**的事实：
 * 文件头部版本号，和文件长度。不依赖时钟、不依赖内存状态。
 */
object MirrorGuards {

    enum class Verdict {
        /** 正常应用。 */
        APPLY,

        /**
         * 已经应用过了，跳过文件写入。
         *
         * ⚠ **这个分支仍然必须走 Room 提交。** 直接 return 的话，
         * `order_index` / 统计贡献 / 吸收边界 就永久少了这一笔，而且**不会有任何报错**。
         * 典型触发场景：文件写成功了、Room 事务还没跑进程就被杀，重启后重发同一条消息。
         */
        ALREADY_APPLIED,

        /**
         * 本地镜像与消息期望的状态不符。**绝不尝试局部修补**，
         * 唯一的处理是隔离证据 + 走一次 FULL 自愈（见 [MirrorCommitter.onDiverged]）。
         */
        DIVERGED
    }

    /**
     * ADD 消息的守卫。
     *
     * @param headerVersion   当前镜像头部版本号
     * @param fileLength      当前镜像总字节数
     * @param msgVersion      消息里的 `version`（应用这条之后文件应达到的版本）
     * @param lineStart       这一行应当落在的绝对偏移 = `serverUuidOffset - uuidValueByteIndex`
     * @param lineLength      这一行的总字节数 = payload 字节数 + 1（末尾 LF）
     *
     * 判定表：
     * ```
     * msgVersion <= headerVersion && fileLength == lineStart + lineLength  → ALREADY_APPLIED
     * msgVersion <= headerVersion                                          → DIVERGED（版本到了但长度不对）
     * msgVersion != headerVersion + 1                                      → DIVERGED（增量链有空洞）
     * fileLength == lineStart                                              → APPLY（追加恰落 EOF）
     * else                                                                 → DIVERGED（有缺口或被写过）
     * ```
     *
     * `lineStart` 与 `lineLength` 两个条件合起来才能确认"已应用"：
     * 只看版本的话，一次 FULL 之后版本号也会到位，但内容可能完全不同。
     */
    fun guardAdd(
        headerVersion: Int,
        fileLength: Long,
        msgVersion: Int,
        lineStart: Long,
        lineLength: Long,
    ): Verdict = when {
        msgVersion <= headerVersion && fileLength == lineStart + lineLength -> Verdict.ALREADY_APPLIED
        msgVersion <= headerVersion -> Verdict.DIVERGED
        msgVersion != headerVersion + 1 -> Verdict.DIVERGED
        fileLength == lineStart -> Verdict.APPLY
        else -> Verdict.DIVERGED
    }

    /**
     * 补丁消息的守卫。
     *
     * @param currentBytes 该偏移处**现在**的字节（`msgVersion <= headerVersion` 时用来判是否已应用）
     *
     * 判定表：
     * ```
     * patchOffset < 4                              → DIVERGED（版本头禁改，服务端也拒绝）
     * patchOffset + patchLength > fileLength       → DIVERGED（越界）
     * msgVersion <= headerVersion:
     *     currentBytes == patchContent             → ALREADY_APPLIED
     *     否则                                      → DIVERGED
     * msgVersion != headerVersion + 1              → DIVERGED（增量链有空洞）
     * else                                         → APPLY
     * ```
     *
     * ⚠ `currentBytes` 必须由调用方**实际从文件读出来**，不能传 null 或猜测值。
     * 读不到（越界）本身就是 DIVERGED 的证据。
     */
    fun guardPatch(
        headerVersion: Int,
        fileLength: Long,
        msgVersion: Int,
        patchOffset: Long,
        patchContent: ByteArray,
        currentBytes: ByteArray?,
    ): Verdict = when {
        patchOffset < com.example.myapplication.core.bytes.ByteOps.HEADER_LEN -> Verdict.DIVERGED
        patchOffset + patchContent.size > fileLength -> Verdict.DIVERGED
        msgVersion <= headerVersion -> when {
            currentBytes == null -> Verdict.DIVERGED
            currentBytes.contentEquals(patchContent) -> Verdict.ALREADY_APPLIED
            else -> Verdict.DIVERGED
        }
        msgVersion != headerVersion + 1 -> Verdict.DIVERGED
        else -> Verdict.APPLY
    }
}
