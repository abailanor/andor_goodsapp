package com.example.myapplication.core.bytes

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.UTF_8

/**
 * 字节层原语。整个 App 里**只有这里**允许把字符串换算成偏移量。
 *
 * ## 为什么这个类存在
 *
 * 协议的前提是"客户端本地镜像与服务器订单文件逐字节一致"，并通过**绝对字节偏移**做等长覆写。
 * 一旦偏移算错，覆写会打到别的订单的字节上，而且**不会报错**：
 *
 *  - 服务端 `orderfile:checksum` 会按新内容重算，所以自洽；
 *  - 服务端 WAL 会忠实记录并回放这次破坏；
 *  - README §9 明确写了完整性机制**检测不出语义破坏**。
 *
 * 也就是说，偏移错误是**静默且不可恢复**的。所以偏移换算必须收敛到唯一一个入口。
 *
 * ## 绝对禁止
 *
 * **不要用 `String.length` 算偏移。** 它是 UTF-16 code unit 数：
 * 对中文既不是字符数（"可乐" 是 2 个字符、2 个 code unit，但 6 个字节）
 * 也不是字节数；对 emoji 连字符数都不是（一个 emoji 是 2 个 code unit）。
 * 一律走 [utf8Len] / [utf8]。
 *
 * 这条约束在 review 时要逐处核对：`mirror`、`sync` 两个包内**任何**用于偏移的
 * `.length` 都是 bug。
 */
object ByteOps {

    /** 文件头长度：偏移 0..3 是 4 字节大端 int 版本号，偏移 4 起才是订单内容。 */
    const val HEADER_LEN: Int = 4

    /** 行分隔符。必须是单个 LF(0x0A)，**不是** 平台分隔符、也不是 CRLF。 */
    const val LF: Byte = 0x0A

    /** A 类订单备注的固定字节宽度（README §2.3 的硬约束）。 */
    const val REMARK_WIDTH: Int = 150

    /** 字符串的 UTF-8 字节数。**所有偏移与长度计算都必须走这里。** */
    fun utf8Len(s: String): Int = s.toByteArray(UTF_8).size

    /** 字符串的 UTF-8 字节。 */
    fun utf8(s: String): ByteArray = s.toByteArray(UTF_8)

    /**
     * 把版本号编码成 4 字节头。
     *
     * `ByteBuffer` 的默认字节序**就是**大端，这里的 `.order(ByteOrder.BIG_ENDIAN)` 是冗余的。
     * **请保留它，不要"顺手优化"掉。** 它是写给后来的人看的显式声明：协议要求大端
     * （README §2.2），而这个正确性不依赖任何默认值。删掉它之后代码照样通过所有测试，
     * 直到某天有人把默认字节序改掉——那时坏掉的是**已经写进磁盘的文件**，不可恢复。
     */
    fun encodeHeader(version: Int): ByteArray =
        ByteBuffer.allocate(HEADER_LEN).order(ByteOrder.BIG_ENDIAN).putInt(version).array()

    /** 从 4 字节头解出版本号。显式大端，理由同 [encodeHeader]。 */
    fun decodeHeader(b: ByteArray): Int {
        require(b.size >= HEADER_LEN) { "版本头需要 $HEADER_LEN 字节，实际 ${b.size}" }
        return ByteBuffer.wrap(b, 0, HEADER_LEN).order(ByteOrder.BIG_ENDIAN).int
    }

    /**
     * 内容校验和：无符号字节值之和，Int 溢出自然回绕。
     *
     * 与服务端 `OrderFileService.computeContentChecksum` 完全同构——**只算偏移 4 起的内容，
     * 不含 4 字节版本头**。Kotlin 的 Int 加法本身就回绕，不需要额外掩码。
     *
     * ⚠ **这个校验和只能自比，不能与服务器对账。**
     * 服务端把权威值存在 Redis 的 `orderfile:checksum:<fileId>` 里，但**没有任何接口能读到它**。
     * 所以它的价值是发现"本地半写 / 未落盘 / 被别的代码路径改过"，
     * **不是**发现"本地与服务器分歧"。真正的端到端对账只有两个手段：
     * `/add` 响应里的 `stockChanges[].newStock`，以及强制走一次 FULL 后与本地镜像逐字节 diff。
     * 别因为"有校验和"就放松其它防护。
     */
    fun sumBytes(b: ByteArray, from: Int = 0, to: Int = b.size): Int {
        var sum = 0
        for (i in from until to) sum += (b[i].toInt() and 0xFF)
        return sum
    }

    /**
     * 是否含控制字符（`0x00`–`0x1F` 与 `0x7F`）。
     *
     * 上传订单前必须断言为 false。裸换行 `0x0A` 是其中最致命的一个：
     * 文件是一行一个订单，服务端 `locateOrderLine` 靠扫描 `\n` 定位订单边界，
     * 注入一个换行会把一个订单劈成两行，**该位置之后所有订单的定位全部错乱且不可恢复**。
     *
     * 服务端现在会拦（返回 400，指明控制字符与位置），但客户端不该依赖服务端来兜这个——
     * 最容易踩的是把 JSON 美化缩进后发过去，那是完全合法的 JSON。
     */
    fun containsControlChar(b: ByteArray): Boolean {
        for (x in b) {
            val v = x.toInt() and 0xFF
            if (v <= 0x1F || v == 0x7F) return true
        }
        return false
    }

    /** 找出第一个控制字符的位置，用于报错信息。没有则返回 -1。 */
    fun firstControlCharIndex(b: ByteArray): Int {
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            if (v <= 0x1F || v == 0x7F) return i
        }
        return -1
    }

    /**
     * 把备注空格 padding 到**恰好** [width] 字节。
     *
     * 为什么必须恰好：字节补丁是**等长覆写**（README §2.4），长度变了就没法用这个机制改。
     * 下单时没带 remark 的那一行，之后**无法插入** remark（插入会变长）。
     *
     * 为什么禁止 `"` `\` 和控制字符（README §2.3 的硬约束，**服务端不校验**）：
     * `"` 和 `\` 在 JSON 里会被转义成 2 个字节，控制字符会变成 `\uXXXX` 6 个字节，
     * 于是"文件里的字节数" ≠ "你的原文字节数"，padding 宽度和 `remarkOffset` 推算全部算错。
     * 写错的后果不是报错，而是**这条备注以后永远改不动**。
     *
     * 注意 150 指的是 JSON 字符串**值**的字节数，不含外层引号。一个汉字 3 字节，
     * 所以最多 50 个汉字。
     */
    fun padRemark(text: String, width: Int = REMARK_WIDTH): String {
        require(!text.contains('"')) { "备注不得含双引号：JSON 会把它转义成 2 字节，等长 padding 会算错" }
        require(!text.contains('\\')) { "备注不得含反斜杠：JSON 会把它转义成 2 字节，等长 padding 会算错" }
        val raw = utf8(text)
        require(!containsControlChar(raw)) {
            "备注不得含控制字符（发现 0x${
                raw[firstControlCharIndex(raw)].toInt().and(0xFF).toString(16)
            }）：JSON 会把它转义成 6 字节，等长 padding 会算错"
        }
        require(raw.size <= width) { "备注超过 $width 字节：实际 ${raw.size}" }
        return text + " ".repeat(width - raw.size)
    }

    /**
     * 按 **UTF-8 字节数**截断，绝不切在多字节序列中间。
     *
     * ⚠ 不能 `String(utf8(text), 0, maxBytes, UTF_8)` 了事：那样最后一个汉字会被切成半个序列，
     * 解码出 U+FFFD 替换字符，而**替换字符自己也占 3 字节** —— 截完的字节数既不等于 maxBytes，
     * 而且原本 3 字节的一个字变成了 3 个字节的垃圾，用户看到的是"备注末尾莫名其妙一个方块"。
     *
     * 回退规则：UTF-8 的后续字节恒为 `10xxxxxx`（0x80..0xBF）。从 maxBytes 处往前退，
     * 直到落在一个**首字节**上，那个位置就是字符边界。
     */
    fun truncateUtf8(text: String, maxBytes: Int): String {
        require(maxBytes >= 0) { "maxBytes 不能为负：$maxBytes" }
        val raw = utf8(text)
        if (raw.size <= maxBytes) return text
        var end = maxBytes
        while (end > 0 && raw[end].toInt() and 0xC0 == 0x80) end--
        return String(raw, 0, end, UTF_8)
    }

    /**
     * 把**用户填的**备注整理成恰好 [width] 字节、且能安全进 JSON 的形状，然后交给 [padRemark]。
     *
     * [padRemark] 会拒绝 `"` `\` 与控制字符（理由见它的注释），而备注是用户输入：
     * 把那个异常抛到用户脸上等于要他理解"等长覆写"。所以这里先做**等宽替换**：
     * `"`→`'`、`\`→`/`、控制字符→空格，每个都是 1 字节换 1 字节，
     * 既不改变字节数也不会引入新的非法字符；再按字节截断到 [width]。
     *
     * 于是"备注永远能被写进订单、且以后永远改得动"这件事由类型系统之外的一道工序保证，
     * 而不是靠每个调用点记得先 sanitize ——「补货清单一键转进货单」那条路径最容易漏。
     */
    fun coerceRemark(text: String, width: Int = REMARK_WIDTH): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '"' -> sb.append('\'')
                c == '\\' -> sb.append('/')
                c.code <= 0x1F || c.code == 0x7F -> sb.append(' ')
                else -> sb.append(c)
            }
        }
        return padRemark(truncateUtf8(sb.toString(), width), width)
    }
}
