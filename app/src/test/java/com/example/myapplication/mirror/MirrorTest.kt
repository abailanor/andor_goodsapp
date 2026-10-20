package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * 镜像文件的字节级测试。
 *
 * 参照实现是 `C:\Users\hhl\Desktop\python\s1client.py` 的 `Mirror` 类（L335-393），
 * 它跑通了 161 条契约断言。这里的断言与它语义一致，
 * 且大量使用**字面字节数组**比对——"解析后相等"抓不住编码/行尾/字节序的错误。
 */
class MirrorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mirror(name: String = "B_alice_2026-09.mirror") = Mirror(File(tmp.root, name))

    private val sampleOrder =
        """{"order_uuid":"11111111-2222-3333-4444-555555555555","writer":"alice","content":{"items":[{"barcode":"6901234567890","name":"可口可乐330ml","quantity":14,"amount":"52.00"}],"total":"52.00","ts":1758000000000},"status":1}"""

    // ==================== 创建与版本头 ====================

    @Test
    fun `新建镜像是_4_字节全零的版本头`() {
        val m = mirror()
        m.ensureCreated()
        assertEquals(4L, m.length())
        assertEquals(0, m.readHeaderVersion())
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), m.readAt(0, 4))
    }

    @Test
    fun `ensureCreated_是幂等的_不会截断已有内容`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        m.writeHeaderVersion(7)
        val before = m.length()
        m.ensureCreated()          // 再调一次
        m.ensureCreated()
        assertEquals(before, m.length())
        assertEquals(7, m.readHeaderVersion())
    }

    @Test
    fun `ensureCreated_会自动建父目录`() {
        val m = Mirror(File(tmp.root, "a/b/c/deep.mirror"))
        assertFalse(m.file.parentFile!!.exists())
        m.ensureCreated()
        assertTrue(m.file.exists())
    }

    @Test
    fun `过短的文件会被重建为_4_字节头`() {
        val f = File(tmp.root, "short.mirror")
        f.writeBytes(byteArrayOf(1, 2))       // 只有 2 字节，不合法
        val m = Mirror(f)
        m.ensureCreated()
        assertEquals(4L, m.length())
        assertEquals(0, m.readHeaderVersion())
    }

    @Test
    fun `版本头写读往返_大端`() {
        val m = mirror()
        m.ensureCreated()
        m.writeHeaderVersion(0x01020304)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), m.readAt(0, 4))
        assertEquals(0x01020304, m.readHeaderVersion())
    }

    @Test
    fun `不存在的文件_readHeaderVersion_返回_0`() {
        // 与服务端 OrderFileService.readHeaderVersionBlocking:719 的行为一致
        assertEquals(0, mirror("nope.mirror").readHeaderVersion())
        assertEquals(0L, mirror("nope.mirror").length())
    }

    // ==================== 追加：单个 LF，绝不是 CRLF ====================

    @Test
    fun `追加后文件字节是_大端头_加_原文_加_单个_LF`() {
        val m = mirror()
        m.ensureCreated()
        val offset = m.appendLine(sampleOrder)
        m.writeHeaderVersion(1)

        assertEquals("首次追加的起点必须是 4（版本头之后）", 4L, offset)

        val expected = ByteOps.encodeHeader(1) + ByteOps.utf8(sampleOrder) + byteArrayOf(0x0A)
        assertArrayEquals(expected, m.file.readBytes())
    }

    @Test
    fun `行尾绝不含_CR`() {
        // 用 println()/newLine() 会写成平台分隔符，在 Windows 上是 \r\n，镜像立刻与服务器分歧
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        m.appendLine(sampleOrder)
        val bytes = m.file.readBytes()
        assertFalse("镜像里出现了 0x0D（CR）", bytes.contains(0x0D.toByte()))
        assertEquals("应当恰好两个 LF", 2, bytes.count { it == ByteOps.LF })
    }

    @Test
    fun `连续追加_第二次落在第一次的末尾`() {
        val m = mirror()
        m.ensureCreated()
        val o1 = m.appendLine(sampleOrder)
        val o2 = m.appendLine(sampleOrder)
        assertEquals(o1 + ByteOps.utf8Len(sampleOrder) + 1, o2)
        assertEquals(4 + 2L * (ByteOps.utf8Len(sampleOrder) + 1), m.length())
    }

    @Test
    fun `appendBytes_与_appendLine_等价`() {
        val a = mirror("a.mirror").also { it.ensureCreated() }
        val b = mirror("b.mirror").also { it.ensureCreated() }
        a.appendBytes(ByteOps.utf8(sampleOrder) + ByteOps.LF)
        b.appendLine(sampleOrder)
        assertArrayEquals(a.file.readBytes(), b.file.readBytes())
    }

    // ==================== 就地补丁：等长覆写 ====================

    @Test
    fun `等长覆写不改变文件长度`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        val lenBefore = m.length()
        val statusOffset = 4L + sampleOrder.toByteArray(Charsets.UTF_8).size - 2  // 倒数第 2 字节是 '1'
        assertEquals('1'.code.toByte(), m.readAt(statusOffset, 1)[0])
        m.patchAt(statusOffset, ByteOps.utf8("0"))
        assertEquals("等长覆写后长度必须不变", lenBefore, m.length())
        assertEquals('0'.code.toByte(), m.readAt(statusOffset, 1)[0])
    }

    @Test
    fun `补丁可以写中文_按字节等长`() {
        val m = mirror()
        m.ensureCreated()
        val remark = ByteOps.padRemark("旧备注")
        m.appendLine("""{"order_uuid":"x","remark":"$remark"}""")
        val remarkOffset = 4L + """{"order_uuid":"x","remark":"""".length
        // 用另一个恰好 150 字节的备注覆盖
        val newRemark = ByteOps.padRemark("改：明天下午三点前送到")
        assertEquals(150, ByteOps.utf8Len(newRemark))
        val lenBefore = m.length()
        m.patchAt(remarkOffset, ByteOps.utf8(newRemark))
        assertEquals(lenBefore, m.length())
        assertEquals(newRemark, String(m.readAt(remarkOffset, 150), Charsets.UTF_8))
    }

    @Test
    fun `补丁越界必须抛_绝不静默截断`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        try {
            m.patchAt(m.length() - 1, ByteArray(10))
            fail("越界补丁应当抛异常")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("超出文件范围"))
        }
    }

    @Test
    fun `补丁落在版本头内必须抛`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        for (off in longArrayOf(0, 1, 2, 3)) {
            try {
                m.patchAt(off, byteArrayOf(0x30))
                fail("偏移 $off 落在版本头内，应当被拒绝")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("版本头"))
            }
        }
    }

    @Test
    fun `readAt_越界必须抛_不能静默返回短数组`() {
        // 静默截断会让"补丁前读旧字节确认 1→0"得到错误结论，
        // 进而把一次备注修改误判成取消、错误回退库存与统计。
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        try {
            m.readAt(m.length() - 2, 10)
            fail("readAt 越界应当抛异常")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("超出文件范围"))
        }
    }

    // ==================== 读行 ====================

    @Test
    fun `readLine_返回不含_LF_的一整行_中文正确`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        m.appendLine("""{"order_uuid":"y","writer":"bob","status":1}""")
        assertEquals(sampleOrder, m.readLine(4))
        assertEquals(
            """{"order_uuid":"y","writer":"bob","status":1}""",
            m.readLine(4 + ByteOps.utf8Len(sampleOrder) + 1L)
        )
    }

    @Test
    fun `readLine_拒绝版本头内与越界的起点`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        for (bad in longArrayOf(0, 3, m.length(), m.length() + 100)) {
            try {
                m.readLine(bad)
                fail("行起点 $bad 应当被拒绝")
            } catch (expected: IOException) {
                assertTrue(expected.message!!.contains("行起点越界"))
            }
        }
    }

    @Test
    fun `最后一行没有_LF_时_readLine_也能读完`() {
        val m = mirror()
        m.ensureCreated()
        m.appendBytes(ByteOps.utf8(sampleOrder))    // 故意不加 LF
        assertEquals(sampleOrder, m.readLine(4))
    }

    // ==================== 校验和 ====================

    @Test
    fun `校验和只算偏移_4_起的内容_不含版本头`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine("abc")
        // 内容 = "abc\n" = 97+98+99+10 = 304；版本头是 0 所以这里区分不出来，
        // 换个非零版本头再验一次
        m.writeHeaderVersion(0xFFFFFFFF.toInt())
        assertEquals(304, m.checksumContent())
    }

    @Test
    fun `空内容（只有版本头）的校验和是_0`() {
        val m = mirror()
        m.ensureCreated()
        assertEquals(0, m.checksumContent())
    }

    @Test
    fun `校验和可以增量维护_追加`() {
        val m = mirror()
        m.ensureCreated()
        var sum = 0
        for (s in listOf("第一行", "second", "333")) {
            val payload = ByteOps.utf8(s) + ByteOps.LF
            m.appendBytes(payload)
            sum += ByteOps.sumBytes(payload)
            assertEquals("追加 $s 后增量校验和不符", sum, m.checksumContent())
        }
    }

    @Test
    fun `校验和可以增量维护_补丁`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        var sum = m.checksumContent()
        val statusOffset = m.length() - 2
        val old = m.readAt(statusOffset, 1)
        m.patchAt(statusOffset, ByteOps.utf8("0"))
        sum = sum - ByteOps.sumBytes(old) + ByteOps.sumBytes(ByteOps.utf8("0"))
        assertEquals(sum, m.checksumContent())
    }

    @Test
    fun `streamContent_覆盖全部内容且不含版本头`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        m.appendLine(sampleOrder)
        val collected = java.io.ByteArrayOutputStream()
        m.streamContent { buf, n -> collected.write(buf, 0, n) }
        val expected = ByteOps.utf8(sampleOrder + "\n" + sampleOrder + "\n")
        assertArrayEquals(expected, collected.toByteArray())
    }

    // ==================== FULL 替换 ====================

    @Test
    fun `replaceAllStreaming_整体替换并返回校验和与总字节数`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine("旧内容应该被完全丢弃")
        m.writeHeaderVersion(99)

        val serverBytes = ByteOps.encodeHeader(5) + ByteOps.utf8(sampleOrder + "\n")
        val (checksum, total) = m.replaceAllStreaming(ByteArrayInputStream(serverBytes))

        assertArrayEquals(serverBytes, m.file.readBytes())
        assertEquals(serverBytes.size.toLong(), total)
        assertEquals(5, m.readHeaderVersion())
        assertEquals(ByteOps.sumBytes(serverBytes, 4), checksum)
        assertEquals(checksum, m.checksumContent())
    }

    @Test
    fun `replaceAllStreaming_成功后不留临时文件`() {
        val m = mirror()
        m.ensureCreated()
        m.replaceAllStreaming(ByteArrayInputStream(ByteOps.encodeHeader(1) + ByteOps.utf8("x\n")))
        val leftovers = m.file.parentFile!!.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("残留了临时文件: ${leftovers.map { it.name }}", leftovers.isEmpty())
    }

    @Test
    fun `replaceAllStreaming_失败时活动镜像不受影响_且清掉临时文件`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        m.writeHeaderVersion(3)
        val before = m.file.readBytes()

        // 一个读到一半就炸的流
        val exploding = object : java.io.InputStream() {
            private var n = 0
            override fun read(): Int = throw IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ == 0) {
                    System.arraycopy(ByteOps.encodeHeader(9), 0, b, 0, 4)
                    return 4
                }
                throw IOException("boom")
            }
        }
        try {
            m.replaceAllStreaming(exploding)
            fail("流中途失败应当抛出来")
        } catch (expected: IOException) {
            assertEquals("boom", expected.message)
        }

        assertArrayEquals("活动镜像被破坏了", before, m.file.readBytes())
        val leftovers = m.file.parentFile!!.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("失败后残留了临时文件", leftovers.isEmpty())
    }

    @Test
    fun `replaceAllStreaming_拒绝不足_4_字节的响应体`() {
        val m = mirror()
        m.ensureCreated()
        try {
            m.replaceAllStreaming(ByteArrayInputStream(byteArrayOf(1, 2)))
            fail("不足 4 字节应当被拒绝")
        } catch (expected: IOException) {
            assertTrue(expected.message!!.contains("4 字节"))
        }
    }

    @Test
    fun `replaceAllStreaming_能处理远大于缓冲区的内容`() {
        val m = mirror()
        m.ensureCreated()
        val sb = StringBuilder()
        repeat(3000) { sb.append(sampleOrder).append('\n') }   // ~1.4 MB，跨过 64KB 缓冲区
        val bytes = ByteOps.encodeHeader(42) + ByteOps.utf8(sb.toString())
        val (checksum, total) = m.replaceAllStreaming(ByteArrayInputStream(bytes))
        assertEquals(bytes.size.toLong(), total)
        assertEquals(ByteOps.sumBytes(bytes, 4), checksum)
        assertEquals(42, m.readHeaderVersion())
    }

    // ==================== 移动（隔离 / 归档） ====================

    @Test
    fun `moveTo_把镜像移走并自动建目标目录`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(sampleOrder)
        val target = File(tmp.root, "quarantine/sub/evidence.mirror")
        m.moveTo(target)
        assertFalse(m.file.exists())
        assertTrue(target.exists())
        assertEquals(Mirror(target).readLine(4), sampleOrder)
    }

    // ==================== 路径映射 ====================

    @Test
    fun `relativePath_B_类按月分文件`() {
        assertEquals(
            "00002/alice/B/2026-09.mirror",
            Mirror.relativePath("00002", "alice", "B:alice:2026-09")
        )
    }

    @Test
    fun `relativePath_A_类按年分文件`() {
        assertEquals(
            "00002/alice/A/2026.mirror",
            Mirror.relativePath("00002", "alice", "A:alice:2026")
        )
    }

    @Test
    fun `relativePath_路径里同时有_storeKey_与店主名_换店铺不会撞`() {
        val a = Mirror.relativePath("00002", "alice", "B:alice:2026-09")
        val b = Mirror.relativePath("00003", "alice", "B:alice:2026-09")
        assertFalse("两个店铺的镜像路径必须不同", a == b)
    }

    @Test
    fun `relativePath_拒绝未知类型`() {
        try {
            Mirror.relativePath("00002", "alice", "C:alice:2026-09")
            fail("C 类（完成记录）是服务器B 写的，客户端不该映射它")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("未知订单类型"))
        }
    }
}
