package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 按 LF 切行并给出**绝对字节起点**。
 *
 * 这里唯一要紧的东西是那个偏移：FULL 重建索引时，`order_index` 里存的
 * `uuidOffset` / `statusOffset` 是 `lineStart + 行内下标`。
 * `lineStart` 算错一个字节，此后每一次取消都会打在别的订单的字节上 ——
 * 等长覆写不报错、文件长度不变、校验和自洽，**没有任何机制会发现**。
 */
class MirrorLineScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun mirror() = Mirror(File(tmp.root, "B_alice_2026-09.mirror"))

    private val o1 = """{"order_uuid":"u1","writer":"alice","content":{},"status":1}"""
    private val o2 = """{"order_uuid":"u2","writer":"bob","content":{"total":"52.00"},"status":0}"""

    private fun collect(m: Mirror): List<Pair<Long, ByteArray>> {
        val out = ArrayList<Pair<Long, ByteArray>>()
        MirrorLineScanner.scan(m) { start, payload -> out += start to payload }
        return out
    }

    @Test
    fun `只有版本头的空镜像产出_0_行`() {
        val m = mirror()
        m.ensureCreated()
        val s = MirrorLineScanner.scan(m) { _, _ -> throw AssertionError("空文件不该回调") }
        assertEquals(0, s.lines)
        assertEquals(0, s.droppedOverlong)
    }

    @Test
    fun `文件不存在时也产出_0_行_而不是抛异常`() {
        val s = MirrorLineScanner.scan(mirror()) { _, _ -> throw AssertionError("不该回调") }
        assertEquals(0, s.lines)
    }

    @Test
    fun `第一行从偏移_4_开始_而不是_0`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(o1)
        val (start, payload) = collect(m).single()
        assertEquals(ByteOps.HEADER_LEN.toLong(), start)
        assertArrayEquals(ByteOps.utf8(o1), payload)
    }

    @Test
    fun `第二行的起点等于第一行起点加它的字节长度再加一个_LF`() {
        val m = mirror()
        m.ensureCreated()
        val at1 = m.appendLine(o1)
        val at2 = m.appendLine(o2)
        val lines = collect(m)
        assertEquals(2, lines.size)
        assertEquals(at1, lines[0].first)
        assertEquals(at2, lines[1].first)
        // 独立地再算一遍，避免断言只是把 appendLine 的返回值抄回来
        assertEquals(ByteOps.HEADER_LEN.toLong(), at1)
        assertEquals(at1 + ByteOps.utf8Len(o1) + 1L, at2)
        assertArrayEquals(ByteOps.utf8(o2), lines[1].second)
    }

    @Test
    fun `中文商品名让字节偏移与字符偏移分岔_这里必须是字节`() {
        val cn = """{"order_uuid":"u1","writer":"张三","content":{"items":[{"barcode":"6901234567890","name":"可口可乐330ml","quantity":14}],"total":"52.00"},"status":1}"""
        val m = mirror()
        m.ensureCreated()
        m.appendLine(cn)
        m.appendLine(o2)
        val lines = collect(m)
        // 字符长度会少算：这一行里 6 个汉字每个多占 2 字节
        assertEquals(ByteOps.utf8Len(cn), cn.length + 12)
        assertEquals(4L + ByteOps.utf8Len(cn) + 1L, lines[1].first)
        assertArrayEquals(ByteOps.utf8(cn), lines[0].second)
    }

    @Test
    fun `payload_不含末尾的_LF`() {
        val m = mirror()
        m.ensureCreated()
        m.appendLine(o1)
        val payload = collect(m).single().second
        assertTrue(payload.isNotEmpty())
        assertTrue("最后一字节不该是 LF", payload[payload.size - 1] != ByteOps.LF)
    }

    @Test
    fun `空行照原样交出来_不悄悄跳过`() {
        // 服务端 /add 拒绝空白订单体，所以出现空行本身就是文件被改过的证据。
        // 在这里跳过会让"行数对不上"这条自检失去意义。
        val m = mirror()
        m.ensureCreated()
        m.appendLine(o1)
        m.appendBytes(byteArrayOf(ByteOps.LF))
        m.appendLine(o2)
        val lines = collect(m)
        assertEquals(3, lines.size)
        assertEquals(0, lines[1].second.size)
        assertEquals(4L + ByteOps.utf8Len(o1) + 1L, lines[1].first)
        assertArrayEquals(ByteOps.utf8(o2), lines[2].second)
    }

    @Test
    fun `末行没有_LF_时仍然交出来_并计入行数`() {
        // 服务端一律追加 LF（OrderFileService:256），走到这里说明文件尾部被截断过
        val m = mirror()
        m.ensureCreated()
        m.writeHeaderVersion(3)
        m.appendBytes(ByteOps.utf8(o1) + ByteOps.LF)
        m.appendBytes(ByteOps.utf8(o2))       // 没有 LF
        val s = MirrorLineScanner.scan(m) { _, _ -> }
        assertEquals(2, s.lines)
        assertEquals(0, s.droppedOverlong)
        val lines = collect(m)
        assertArrayEquals(ByteOps.utf8(o2), lines[1].second)
        assertEquals(4L + ByteOps.utf8Len(o1) + 1L, lines[1].first)
    }

    @Test
    fun `跨_64KB_分块的行_偏移仍然连续`() {
        // streamContent 每块 64KB；一行跨过块边界时 cursor 必须接着算，
        // 断在这里的话该行之后所有订单的偏移都会错，而错误量恰好是 65536 的倍数。
        val long = """{"order_uuid":"u1","writer":"alice","content":{"note":"${"x".repeat(100_000)}"},"status":1}"""
        val m = mirror()
        m.ensureCreated()
        m.appendLine(long)
        val at2 = m.appendLine(o2)
        val lines = collect(m)
        assertEquals(2, lines.size)
        assertEquals(ByteOps.utf8Len(long).toLong(), lines[0].second.size.toLong())
        assertEquals(at2, lines[1].first)
        assertEquals(4L + ByteOps.utf8Len(long) + 1L, at2)
        assertTrue("这一行必须真的跨过 64KB 块边界", ByteOps.utf8Len(long) > 64 * 1024)
    }

    @Test
    fun `超过单行上限的行被丢弃并计数_而不是把内存吃光`() {
        // 一个被截断到没有任何 LF 的镜像会让"当前一行"无限增长，正好绕开流式保证。
        val overlong = "y".repeat(MirrorLineScanner.MAX_LINE_BYTES + 1)
        val m = mirror()
        m.ensureCreated()
        m.appendLine(o1)
        m.appendBytes(ByteOps.utf8(overlong) + ByteOps.LF)
        m.appendLine(o2)

        var seen = 0
        val s = MirrorLineScanner.scan(m) { _, _ -> seen++ }
        assertEquals(1, s.droppedOverlong)
        assertEquals(2, s.lines)
        assertEquals(2, seen)
    }

    @Test
    fun `恰好等于上限的行不被丢弃`() {
        // 判据是 `<=`：服务端上限是 20KB（超出返回 413），256KB 已经是 12 倍余量，
        // 差一个字节就把合法行丢掉是不会发生的，但边界本身要钉住。
        val exact = "z".repeat(MirrorLineScanner.MAX_LINE_BYTES)
        val m = mirror()
        m.ensureCreated()
        m.appendBytes(ByteOps.utf8(exact) + ByteOps.LF)
        val s = MirrorLineScanner.scan(m) { _, _ -> }
        assertEquals(0, s.droppedOverlong)
        assertEquals(1, s.lines)
    }

    @Test
    fun `被丢弃的行不打乱后续行的起点`() {
        // 丢弃只是不回调，游标仍然要按字节前进 —— 否则那一行之后的偏移全错
        val overlong = "y".repeat(MirrorLineScanner.MAX_LINE_BYTES + 1)
        val m = mirror()
        m.ensureCreated()
        m.appendBytes(ByteOps.utf8(overlong) + ByteOps.LF)
        val at2 = m.appendLine(o2)
        val lines = collect(m)
        assertEquals(1, lines.size)
        assertEquals(at2, lines[0].first)
    }

    @Test
    fun `扫描出的起点与长度能原样读回同一行`() {
        // 端到端：MirrorLineScanner 给的 (start, payload) 必须与 Mirror.readLine / readAt 一致
        val m = mirror()
        m.ensureCreated()
        m.appendLine(o1)
        m.appendLine(o2)
        for ((start, payload) in collect(m)) {
            assertArrayEquals(payload, m.readAt(start, payload.size))
            assertEquals(String(payload, Charsets.UTF_8), m.readLine(start))
        }
    }
}
