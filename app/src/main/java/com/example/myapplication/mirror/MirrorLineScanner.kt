package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import java.io.ByteArrayOutputStream

/**
 * 把镜像按 LF 切成一行行订单原文，同时给出每行的**绝对字节起点**。
 *
 * FULL 重建索引时用：那时流消息里的 `line_start` / `uuid_offset` / `status_offset`
 * 一个都没有，只能靠"这一行从文件哪个偏移开始" + [com.example.myapplication.sync.OrderJsonScanner]
 * 在行内推算的下标，两者相加才是能写进 `order_index` 的绝对偏移。
 *
 * ## 为什么必须流式
 *
 * 一个月的 B 类镜像在"一天上百单、一单上百行"的量级下可以到 5~26 MB。
 * [Mirror] 刻意不提供 `readAll()`，这里也不能把整个文件攒进内存 ——
 * 所以按 [Mirror.streamContent] 的 64KB 分块走，只在内存里保留**当前一行**（≤20KB）。
 *
 * ## 为什么有 [MAX_LINE_BYTES] 这道闸
 *
 * 一个被截断/损坏到没有任何 LF 的镜像会让"当前一行"无限增长，正好绕开上面那条流式保证。
 * 服务端 `spring.codec.max-in-memory-size` 是 20KB（超出返回 413），所以合法行不可能超过它；
 * 256KB 是 12 倍余量。超过就丢弃该行并计数 —— 调用方据此记审计日志，
 * 少一笔是看得见的（行数对不上自检），OOM 是看不见的。
 */
object MirrorLineScanner {

    /** 单行字节上限。见类注释。 */
    const val MAX_LINE_BYTES = 256 * 1024

    /**
     * @param lines 交给 [onLine] 的行数
     * @param droppedOverlong 因超过 [MAX_LINE_BYTES] 被丢弃的行数。**非 0 就说明镜像坏了**，
     *        调用方必须记审计日志（多半要跟着一次 DIVERGED 判定）。
     */
    data class ScanSummary(val lines: Int, val droppedOverlong: Int)

    /**
     * @param onLine 收到 (该行首字节的绝对偏移, 订单原文字节**不含** LF)。
     *               空行也会照原样交出来（`payload.size == 0`）—— 服务端 `/add` 拒绝空白订单体，
     *               所以出现空行本身就是文件被改过的证据，交给调用方解析失败后记日志，
     *               比在这里悄悄跳过更容易发现。
     */
    fun scan(mirror: Mirror, onLine: (lineStart: Long, payload: ByteArray) -> Unit): ScanSummary {
        val buf = ByteArrayOutputStream()
        // streamContent 从偏移 4（版本头之后）开始，cursor 始终指向"下一个待处理字节"的绝对偏移
        var cursor = ByteOps.HEADER_LEN.toLong()
        var lineStart = cursor
        var lines = 0
        var dropped = 0

        fun emit() {
            if (buf.size() <= MAX_LINE_BYTES) {
                onLine(lineStart, buf.toByteArray())
                lines++
            } else {
                dropped++
            }
            buf.reset()
        }

        mirror.streamContent { chunk, n ->
            for (i in 0 until n) {
                if (chunk[i] == ByteOps.LF) {
                    emit()
                    cursor++
                    lineStart = cursor
                } else {
                    buf.write(chunk[i].toInt())
                    cursor++
                }
            }
        }
        // 末行没有 LF。服务端一律追加 LF（`OrderFileService:256`），正常走不到这里；
        // 走到了说明文件尾部被截断过，仍然交出去让调用方解析、失败后记日志。
        if (buf.size() > 0) emit()

        return ScanSummary(lines, dropped)
    }
}
