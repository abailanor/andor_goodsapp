package com.example.myapplication.core.bytes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 字节层原语的测试。
 *
 * 这一层的每个断言都对应一种"静默损坏数据"的失败模式——它们不会抛异常，
 * 只会在几小时后让本地镜像与服务器对不上。所以断言写得很死。
 */
class ByteOpsTest {

    // ==================== utf8Len：绝不能用 String.length ====================

    @Test
    fun `ASCII_的字节数等于字符数`() {
        assertEquals(5, ByteOps.utf8Len("abc12"))
    }

    @Test
    fun `汉字是_3_字节，String_length_会给出错误答案`() {
        // "可乐" = 2 个字符、2 个 UTF-16 code unit、6 个 UTF-8 字节
        assertEquals(2, "可乐".length)
        assertEquals(6, ByteOps.utf8Len("可乐"))
    }

    @Test
    fun `emoji_是_4_字节且_String_length_是_2（代理对）`() {
        val beer = "🍺"
        assertEquals(2, beer.length)          // UTF-16 代理对
        assertEquals(1, beer.codePointCount(0, beer.length))
        assertEquals(4, ByteOps.utf8Len(beer))
    }

    @Test
    fun `全角字符是_3_字节`() {
        assertEquals(3, ByteOps.utf8Len("Ａ"))   // 全角 A，U+FF21
    }

    @Test
    fun `混合字符串的字节数是各段之和`() {
        // "可乐330ml" = 2 汉字(6) + 3 数字(3) + 2 字母(2) = 11
        assertEquals(11, ByteOps.utf8Len("可乐330ml"))
    }

    // ==================== 4 字节大端版本头 ====================

    @Test
    fun `版本头是大端序`() {
        // 版本 3 → 00 00 00 03（README §2.2 的示例）
        assertArrayEquals(byteArrayOf(0, 0, 0, 3), ByteOps.encodeHeader(3))
    }

    @Test
    fun `版本头大端序_高位在前`() {
        // 0x01020304 → 01 02 03 04。若是小端会得到 04 03 02 01。
        assertArrayEquals(
            byteArrayOf(0x01, 0x02, 0x03, 0x04),
            ByteOps.encodeHeader(0x01020304)
        )
    }

    @Test
    fun `版本头往返一致`() {
        for (v in intArrayOf(0, 1, 2, 255, 256, 65535, 16777216, Int.MAX_VALUE)) {
            assertEquals("版本 $v 往返失败", v, ByteOps.decodeHeader(ByteOps.encodeHeader(v)))
        }
    }

    @Test
    fun `decodeHeader_拒绝过短的输入`() {
        try {
            ByteOps.decodeHeader(byteArrayOf(0, 0, 0))
            fail("3 字节的头应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("4"))
        }
    }

    // ==================== 内容校验和（与服务端同构） ====================

    @Test
    fun `校验和是无符号字节求和`() {
        // 0xFF 必须算成 255 而不是 -1
        assertEquals(255, ByteOps.sumBytes(byteArrayOf(0xFF.toByte())))
        assertEquals(255 + 1, ByteOps.sumBytes(byteArrayOf(0xFF.toByte(), 1)))
    }

    @Test
    fun `校验和溢出时按_Int_自然回绕`() {
        // 4 万个 0xFF = 40000 * 255 = 10,200,000，不回绕
        val small = ByteArray(40_000) { 0xFF.toByte() }
        assertEquals(40_000 * 255, ByteOps.sumBytes(small))
        // 900 万个 0xFF = 2,295,000,000 > Int.MAX_VALUE(2,147,483,647)，必须回绕
        val big = ByteArray(9_000_000) { 0xFF.toByte() }
        val expected = (9_000_000L * 255L).toInt()   // Long 算完再截断 = 回绕
        assertEquals(expected, ByteOps.sumBytes(big))
    }

    @Test
    fun `校验和支持区间`() {
        val b = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(3 + 4, ByteOps.sumBytes(b, 2, 4))
        assertEquals(15, ByteOps.sumBytes(b))
    }

    // ==================== 控制字符检测 ====================

    @Test
    fun `裸换行被识别为控制字符`() {
        assertTrue(ByteOps.containsControlChar("abc\ndef".toByteArray(Charsets.UTF_8)))
        assertEquals(3, ByteOps.firstControlCharIndex("abc\ndef".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `回车也是控制字符_CRLF_行尾会被抓住`() {
        // 用 println()/newLine() 在 Windows 上会写出 \r\n —— 镜像立刻与服务器分歧
        assertTrue(ByteOps.containsControlChar("abc\r\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `空格与中文不是控制字符`() {
        assertFalse(ByteOps.containsControlChar("abc 可乐".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `DEL_0x7F_也算控制字符`() {
        assertTrue(ByteOps.containsControlChar(byteArrayOf(0x61, 0x7F)))
    }

    @Test
    fun `正常订单_JSON_不含控制字符`() {
        val json = """{"order_uuid":"x","writer":"alice","content":{},"status":1}"""
        assertFalse(ByteOps.containsControlChar(ByteOps.utf8(json)))
    }

    // ==================== A 类备注：恰好 150 字节 ====================

    @Test
    fun `padRemark_纯空格补齐到恰好_150_字节`() {
        val padded = ByteOps.padRemark("尽快发货")
        assertEquals(150, ByteOps.utf8Len(padded))
        assertTrue(padded.endsWith(" ".repeat(150 - 12)))
        assertTrue(padded.startsWith("尽快发货"))
    }

    @Test
    fun `padRemark_空字符串补成_150_个空格`() {
        val padded = ByteOps.padRemark("")
        assertEquals(150, ByteOps.utf8Len(padded))
        assertEquals(" ".repeat(150), padded)
    }

    @Test
    fun `padRemark_恰好_50_个汉字是上限`() {
        val fifty = "汉".repeat(50)          // 50 * 3 = 150 字节
        assertEquals(150, ByteOps.utf8Len(fifty))
        assertEquals(fifty, ByteOps.padRemark(fifty))   // 不需要补空格
    }

    @Test
    fun `padRemark_51_个汉字超限必须抛`() {
        try {
            ByteOps.padRemark("汉".repeat(51))
            fail("153 字节应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("150"))
        }
    }

    @Test
    fun `padRemark_含双引号必须抛_否则转义成_2_字节后_padding_全错`() {
        try {
            ByteOps.padRemark("""他说"好"""")
            fail("含双引号的备注应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("双引号"))
        }
    }

    @Test
    fun `padRemark_含反斜杠必须抛`() {
        try {
            ByteOps.padRemark("""C:\path""")
            fail("含反斜杠的备注应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("反斜杠"))
        }
    }

    @Test
    fun `padRemark_含控制字符必须抛`() {
        try {
            ByteOps.padRemark("第一行\n第二行")
            fail("含换行的备注应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("控制字符"))
        }
    }

    @Test
    fun `padRemark_结果本身不含控制字符也不含引号反斜杠`() {
        // 这条是"写错了以后永远改不动"的最后一道防线
        val padded = ByteOps.padRemark("正常备注 123")
        assertFalse(ByteOps.containsControlChar(ByteOps.utf8(padded)))
        assertFalse(padded.contains('"'))
        assertFalse(padded.contains('\\'))
        assertEquals(150, ByteOps.utf8Len(padded))
    }
}
