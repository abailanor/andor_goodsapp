package com.example.myapplication.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `fileId` 的拼与拆。
 *
 * 这里最值得钉住的不是"能拼对"，而是**拼错时必须炸**：
 * `B:alice:2026-9` 与 `B:alice:2026-09` 是两个不同的文件，两个都能正常同步，
 * 只是数据分成了两半 —— 没有任何一处会报错。所以"拒绝单位数月份"这条断言
 * 比"能生成两位月份"重要得多。
 */
class OrderFileIdTest {

    // ==================== 构造 ====================

    @Test
    fun `A_类按年一个文件`() {
        assertEquals("A:alice:2026", OrderFileId.ofA("alice", 2026))
    }

    @Test
    fun `B_类的月份恒为两位_与服务端的_format_逐字一致`() {
        // 服务端是 String.format("B:%s:%d-%02d", …)
        assertEquals("B:alice:2026-01", OrderFileId.ofB("alice", 2026, 1))
        assertEquals("B:alice:2026-09", OrderFileId.ofB("alice", 2026, 9))
        assertEquals("B:alice:2026-12", OrderFileId.ofB("alice", 2026, 12))
    }

    @Test
    fun `B_类拒绝_1_到_12_之外的月份`() {
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.ofB("alice", 2026, 0) }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.ofB("alice", 2026, 13) }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.ofB("alice", 2026, -1) }
    }

    @Test
    fun `店主用户名不得为空也不得含冒号`() {
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.ofA("", 2026) }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.ofB("a:b", 2026, 3) }
    }

    @Test
    fun `年份超范围时拒绝_避免拼出永远同步不到的_fileId`() {
        // `B:alice:20261-09` 这种手滑不会报错，只会造出一个谁也不认识的文件
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.ofB("alice", 20261, 9) }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.ofA("alice", 1969) }
    }

    // ==================== 解析 ====================

    @Test
    fun `往返_A_类`() {
        val p = OrderFileId.parse(OrderFileId.ofA("alice", 2026))
        assertEquals(OrderFileId.TYPE_A, p.type)
        assertEquals("alice", p.ownerName)
        assertEquals(2026, p.year)
        assertEquals(OrderFileId.NO_MONTH, p.month)
        assertFalse(p.isB)
        assertEquals("A:alice:2026", p.fileId)
    }

    @Test
    fun `往返_B_类`() {
        val p = OrderFileId.parse(OrderFileId.ofB("alice", 2026, 9))
        assertEquals(OrderFileId.TYPE_B, p.type)
        assertEquals("alice", p.ownerName)
        assertEquals(2026, p.year)
        assertEquals(9, p.month)
        assertTrue(p.isB)
        // Parts.fileId 必须把月份补回两位，否则一次 parse→fileId 就把数据劈成两半
        assertEquals("B:alice:2026-09", p.fileId)
    }

    @Test
    fun `单位数月份的_fileId_必须被拒绝_而不是被悄悄接受`() {
        // 这是整个类存在的理由
        val e = assertThrows(IllegalArgumentException::class.java) {
            OrderFileId.parse("B:alice:2026-9")
        }
        assertTrue(e.message!!.contains("两位"))
    }

    @Test
    fun `月份缺失或与年份粘在一起时拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("B:alice:202609") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("B:alice:2026-") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("B:alice:2026-09-01") }
    }

    @Test
    fun `段数不是三段时拒绝_包括服务端的_C_类完成记录文件`() {
        // C:<yyyy>-<MM> 只有两段，是服务端内部账本，OrderSyncService:53 明确不接受它。
        // 客户端拉不到也不该拉 —— 这里必须炸，而不是构造出一个错的镜像路径。
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("C:2026-09") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("A:alice") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("A:alice:2026:x") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("") }
    }

    @Test
    fun `未知类型拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("D:alice:2026") }
    }

    @Test
    fun `非数字的年份或月份拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("A:alice:二〇二六") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("B:alice:2026-0X") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("B:alice:2026-1x") }
    }

    @Test
    fun `解析出的月份超出_1_到_12_时拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("B:alice:2026-00") }
        assertThrows(IllegalArgumentException::class.java) { OrderFileId.parse("B:alice:2026-13") }
    }

    // ==================== 营收判据 ====================

    @Test
    fun `只有_B_类计入营收_A_类是向丽华金龙进货`() {
        assertTrue(OrderFileId.isRevenueFile("B:alice:2026-09"))
        assertFalse(OrderFileId.isRevenueFile("A:alice:2026"))
        assertFalse(OrderFileId.isRevenueFile("C:2026-09"))
        // ⚠ 判据必须是"**开头**是 `B:`"。写成 `contains(":B:")` 的话，
        // 一个店主用户名恰好叫 `B` 的**进货单**（`A:B:2026`）会被算进营业额，
        // 而且数字只是偏大、看不出异常。
        assertFalse(OrderFileId.isRevenueFile("A:B:2026"))
        assertFalse(OrderFileId.isRevenueFile("B"))
    }

    @Test
    fun `Parts_isB_与_isRevenueFile_对同一个_fileId_给出一致的答案`() {
        for (f in listOf("A:alice:2026", "B:alice:2026-09")) {
            assertEquals(OrderFileId.isRevenueFile(f), OrderFileId.parse(f).isB)
        }
    }

    @Test
    fun `fileScope_的往返不会把_OUTBOX_误当成文件桶`() {
        assertEquals("FILE:B:alice:2026-09", StatsProjection.fileScope("B:alice:2026-09"))
        assertEquals("B:alice:2026-09", StatsProjection.fileIdOfScope("FILE:B:alice:2026-09"))
        assertNull(StatsProjection.fileIdOfScope(StatsProjection.OUTBOX_SCOPE))
    }
}
