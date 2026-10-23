package com.example.myapplication.sync.outbox

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.money.Money
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.OrderPayloadIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拆单器的测试。
 *
 * ## 这里最该被抓住的两个 bug
 *
 *  1. **某一块超过 20KB** → 服务端 413，而 outbox 行已经 `IN_FLIGHT`。
 *     重试同样大小只会再拿一个 413，`attemptCount` 白烧三次进 FAILED_PERMANENT，
 *     用户看到的是"这单永远传不上去"，而原因写在服务端日志里。
 *  2. **各块 total 之和 ≠ 用户填的总额** → 营业额静默偏掉。
 *     拆单是把一笔钱分成 N 笔记，分错了没有任何一处会对不上账：
 *     每块自己看都是合法的订单，只有把整组加起来才发现少了。
 *
 * 所以断言的重点不是"切成了几块"（那取决于估算常数），而是
 * **每一块都塞得下** + **加起来还是那个数** + **每一块都能被解析回来**。
 */
class OrderGroupAssemblerTest {

    private val TS = 1_758_000_000_000L

    /** 确定性 uuid：必须匹配 8-4-4-4-12 十六进制，`StableOrderJson` 会校验格式。 */
    private fun uuid(i: Int) = "11111111-2222-3333-4444-%012d".format(i)

    private var uuidSeq = 0
    private val uuidFactory: () -> String = { uuid(uuidSeq++) }

    /** 普通行：13 位条码 + 中文名。方案 R6 实测 ≈87 字节。 */
    private fun line(i: Int, amount: String = "52.00") =
        OrderLine("6901234567890", "可口可乐330ml", 14, amount, "1.00")

    /** 长行：20 位条码 + 长名字 + 大金额。方案 §3.8 说的"每行能到 110+ 字节"就是这种。 */
    private fun longLine(i: Int) = OrderLine(
        "69012345678901234567",
        "可口可乐330ml*24瓶整箱装促销特价",
        1440,
        "12345.67",
        unitPrice = "8.57",
    )

    private fun bReq(
        lines: List<OrderLine>,
        totalOverrideCents: Long? = null,
    ) = OrderGroupAssembler.Request(
        fileType = OrderFileId.TYPE_B,
        year = 2028,
        month = 3,
        writer = "alice",
        lines = lines,
        ts = TS,
        totalOverrideCents = totalOverrideCents,
    )

    private fun aReq(
        lines: List<OrderLine>,
        remarkText: String? = null,
        totalOverrideCents: Long? = null,
    ) = OrderGroupAssembler.Request(
        fileType = OrderFileId.TYPE_A,
        year = 2028,
        month = null,
        writer = "alice",
        lines = lines,
        ts = TS,
        remarkText = remarkText,
        totalOverrideCents = totalOverrideCents,
    )

    private fun assembleB(lines: List<OrderLine>, totalOverrideCents: Long? = null) =
        OrderGroupAssembler.assemble(bReq(lines, totalOverrideCents), uuidFactory) { "a3f9c21b" }

    private fun assembleA(lines: List<OrderLine>, remarkText: String? = null, totalOverrideCents: Long? = null) =
        OrderGroupAssembler.assemble(aReq(lines, remarkText, totalOverrideCents), uuidFactory) { "a3f9c21b" }

    private fun json(c: OrderGroupAssembler.Chunk) = String(c.serialized.payload, Charsets.UTF_8)

    /**
     * 切出来的每一块都必须能被**独立的**解析器读回来。
     *
     * `StableOrderJson`（写）与 `OrderPayloadIndex`（读）是两套实现，
     * 让它们互相校验才叫测试；只用其中一套的话，写错了读也跟着错，断言全是绿的。
     */
    private fun parsed(c: OrderGroupAssembler.Chunk): OrderPayloadIndex.Parsed {
        val p = OrderPayloadIndex.parse(c.serialized.payload)
        assertNotNull("切出来的块自己解析不回来：${json(c)}", p)
        return p!!
    }

    // ==================== 不拆的情况 ====================

    @Test
    fun 小订单只有一块_且不写_grp_字段() {
        val r = assembleB((0 until 5).map { line(it) })

        assertEquals(1, r.chunkCount)
        assertFalse("单块订单不该被标成已拆", r.isSplit)
        assertNull("单块订单不该有 groupId", r.groupId)
        assertFalse("payload 里不该出现 grp 字段（白占 20 字节）：${json(r.chunks[0])}", json(r.chunks[0]).contains("\"grp\""))
    }

    // ==================== 方案 §7.3(c)：字节预算拆单 ====================

    @Test
    fun 长条码订单拆成多块_且每块都在预算内() {
        val lines = (0 until 200).map { longLine(it) }
        val r = assembleB(lines)

        assertTrue("200 行长条码应当被拆开，实际 ${r.chunkCount} 块", r.chunkCount >= 2)
        assertEquals("a3f9c21b", r.groupId)
        r.chunks.forEachIndexed { i, c ->
            assertTrue(
                "第 $i 块 ${c.serialized.byteSize} 字节，超过预算 ${OrderGroupAssembler.BYTE_BUDGET}" +
                    "（硬上限 ${OrderGroupAssembler.HARD_LIMIT_BYTES}，超了服务端返回 413）",
                c.serialized.byteSize <= OrderGroupAssembler.BYTE_BUDGET,
            )
            assertTrue("第 $i 块是空的", c.draft.items.isNotEmpty())
        }
    }

    @Test
    fun 行数守恒_且顺序不变() {
        val lines = (0 until 200).map { longLine(it) }
        val r = assembleB(lines)

        val flat = r.chunks.flatMap { it.draft.items }
        assertEquals("拆单丢行或多行", lines.size, flat.size)
        // 行是不可变的，所以能直接按引用比 —— 这同时证明了"没有哪一行被复制成两份"
        lines.forEachIndexed { i, l -> assertTrue("第 $i 行顺序变了", flat[i] === l) }
    }

    @Test
    fun 每块的_uuid_互不相同_而_grp_相同() {
        val r = assembleB((0 until 200).map { longLine(it) })

        val uuids = r.chunks.map { it.draft.orderUuid }
        assertEquals("多块共用 uuid 会让服务端文件里出现两行同 uuid（服务端不查重），" +
            "order_index 主键冲突、镜像比服务器长，此后所有偏移静默错位",
            uuids.size, uuids.toSet().size)
        assertTrue("这个用例需要真的拆开才有意义，实际 ${uuids.size} 块", uuids.size >= 2)
        r.chunks.forEach {
            assertEquals("a3f9c21b", it.draft.groupId)
            assertEquals("a3f9c21b", parsed(it).groupId)
            assertNotEquals("grp 绝不能复用 orderUuid", it.draft.orderUuid, it.draft.groupId)
        }
    }

    /**
     * 装箱用固定的 [OrderGroupAssembler] 内部假 uuid 探路，所以切法**只由行内容决定**。
     *
     * 这条性质值得单独钉住：如果装箱依赖真 uuid（比如边装边生成），
     * 那么"同一单重试一次切法就变了"，而 outbox 的 `payload` 是**不可变**的 ——
     * 重推时字节一变，之前记下的所有偏移全部作废。
     */
    @Test
    fun 切法与_uuid_无关() {
        val lines = (0 until 200).map { longLine(it) }
        // ⚠ 必须写参数名：`assemble` 有**两个**尾随 lambda 参数，
        // 只给一个的话它会绑到 `groupIdFactory` 上（Kotlin 的尾随 lambda 绑最后一个参数），
        // 于是 uuidFactory 悄悄退回默认的随机 uuid，这条用例就什么也没证明。
        val a = OrderGroupAssembler.assemble(bReq(lines), uuidFactory = { uuid(0) })
        val b = OrderGroupAssembler.assemble(bReq(lines), uuidFactory = { uuid(999) })

        assertEquals(a.chunks.map { it.draft.items.size }, b.chunks.map { it.draft.items.size })
        assertEquals(a.chunks.map { it.serialized.byteSize }, b.chunks.map { it.serialized.byteSize })
    }

    // ==================== 手工覆盖的总额 ====================

    @Test
    fun 手工差额整笔落在最后一块_且各块之和恒等于覆盖值() {
        val lines = (0 until 200).map { longLine(it) }
        val sumAll = lines.sumOf { Money.toCents(it.amount) }
        val override = sumAll + 5_00          // 用户抹零/加价 5 元
        val r = assembleB(lines, override)
        assertTrue("这个用例需要真的拆开才有意义", r.chunkCount >= 2)

        val totals = r.chunks.map { parsed(it).totalCents }
        assertEquals("各块 total 之和必须恒等于用户填的那个数，否则营业额静默偏掉",
            override, totals.sum())

        // 只有最后一块被打上"含手工调整"，前面的块仍然干净
        r.chunks.dropLast(1).forEachIndexed { i, c ->
            assertFalse("第 $i 块不该被标成含手工调整（差额只该落在最后一块）", parsed(c).manualTotal)
        }
        assertTrue("最后一块必须被标成含手工调整", parsed(r.chunks.last()).manualTotal)
    }

    @Test
    fun 差额为负也一样成立() {
        val lines = (0 until 200).map { longLine(it) }
        val sumAll = lines.sumOf { Money.toCents(it.amount) }
        val r = assembleB(lines, sumAll - 12_34)

        assertEquals(sumAll - 12_34, r.chunks.sumOf { parsed(it).totalCents })
    }

    @Test
    fun 单块时覆盖总额直接落在唯一那块() {
        val r = assembleB((0 until 3).map { line(it) }, totalOverrideCents = 100_00)

        assertEquals(1, r.chunkCount)
        assertEquals(100_00L, parsed(r.chunks[0]).totalCents)
        assertTrue("Σ=156.00 而 total=100.00，必须被标成含手工调整", parsed(r.chunks[0]).manualTotal)
    }

    @Test
    fun 没有覆盖时各块之和等于各行之和() {
        val lines = (0 until 200).map { longLine(it) }
        val r = assembleB(lines)

        assertEquals(lines.sumOf { Money.toCents(it.amount) }, r.chunks.sumOf { parsed(it).totalCents })
        r.chunks.forEach { assertFalse("没手工改过就不该有含手工调整标记", parsed(it).manualTotal) }
    }

    // ==================== A 类 remark ====================

    @Test
    fun A_类每一块都出生即_150_字节_remark() {
        val r = assembleA((0 until 200).map { longLine(it) }, remarkText = "向丽华金龙进货")
        assertTrue("这个用例需要真的拆开才有意义", r.chunkCount >= 2)

        r.chunks.forEachIndexed { i, c ->
            // `remarkValueIndex` 只有在值**恰好** 150 字节时才非 null
            // （见 OrderLineOffsets 的注释），所以这一条已经是宽度断言。
            assertNotNull("第 $i 块的 remarkValueIndex 是 null —— 这一块的备注以后永远改不动",
                parsed(c).offsets.remarkValueIndex)

            // 再直接量一次字节：值后面紧跟着的必须是闭引号，而不是 JSON 的其余部分。
            // 与上面那条是两套判据（解析器 vs 数字节），一起绿才算数。
            val key = ByteOps.utf8("\"remark\":\"")
            val at = indexOf(c.serialized.payload, key)
            assertTrue("第 $i 块 payload 里没有 remark 键", at >= 0)
            assertEquals("第 $i 块的 remark 不是恰好 150 字节（闭引号没落在该落的地方）",
                '"'.code.toByte(),
                c.serialized.payload[at + key.size + ByteOps.REMARK_WIDTH])
        }
    }

    @Test
    fun A_类只有第一块带用户文本_其余是空白() {
        val r = assembleA((0 until 200).map { longLine(it) }, remarkText = "向丽华金龙进货")

        assertEquals("向丽华金龙进货", parsed(r.chunks[0]).remark)
        r.chunks.drop(1).forEachIndexed { i, c ->
            // `OrderPayloadIndex` 把全空格读成 null（trimEnd + takeIf isNotEmpty）
            assertNull("第 ${i + 1} 块不该复制用户备注（改一块不会改其余块，反而制造矛盾）", parsed(c).remark)
        }
    }

    @Test
    fun A_类没填备注也强制生成_150_字节空位() {
        val r = assembleA(listOf(line(0)))

        val c = r.chunks[0]
        assertNotNull("A 类必须出生即带 remark，否则这一行以后永远插不进去", parsed(c).offsets.remarkValueIndex)
        assertNull("全空格应当被读成 null", parsed(c).remark)
        assertTrue("payload 里必须有 remark 键", json(c).contains("\"remark\":\""))
    }

    @Test
    fun 备注里的引号反斜杠控制字符与超长都被整理成合法形状() {
        // `padRemark` 会拒绝 `"` `\` 与控制字符（JSON 转义后字节数会变，等长覆写就算错了）。
        // 备注是**用户输入**，所以整理必须发生在这一层，不能把异常抛到用户脸上。
        val nasty = "第一行\n第二行\t带制表符 \"引号\" 与 \\反斜杠\\ " + "很长很长的中文备注".repeat(20)
        val r = assembleA(listOf(line(0)), remarkText = nasty)

        val c = r.chunks[0]
        val p = parsed(c)
        assertNotNull("整理后的备注必须仍然能被解析出来", p.offsets.remarkValueIndex)
        val remark = p.remark!!
        assertFalse("双引号必须被换掉", remark.contains('"'))
        assertFalse("反斜杠必须被换掉", remark.contains('\\'))
        assertFalse("换行必须被换掉 —— 裸 LF 会把一个订单劈成两行，此后所有偏移全部错乱", remark.contains('\n'))
        assertTrue("原文的开头应当保留下来：$remark", remark.startsWith("第一行"))
        // 出厂断言：整个 payload 不含任何裸控制字符
        assertFalse(ByteOps.containsControlChar(c.serialized.payload))
    }

    // ==================== 每一块都得是合法的订单 ====================

    @Test
    fun 每一块都能被解析回来_且偏移自洽() {
        val r = assembleB((0 until 200).map { longLine(it) })

        r.chunks.forEachIndexed { i, c ->
            val p = parsed(c)
            assertEquals("第 $i 块 uuid 对不上", c.draft.orderUuid, p.orderUuid)
            assertEquals("alice", p.writer)
            assertEquals(1, p.status)
            assertEquals(TS, p.orderTs)
            assertFalse("第 $i 块的 items 被整体丢弃了", p.itemsDropped)
            assertEquals("第 $i 块行数对不上", c.draft.items.size, p.itemCount)

            // 序列化器记录的字节下标必须与解析器独立扫出来的下标一致 ——
            // 两边是两套实现，不一致就说明有一方算错了，而错的一方会让补丁打歪。
            assertEquals("第 $i 块 uuidValueByteIndex 不一致",
                c.serialized.uuidValueByteIndex, p.offsets.uuidValueIndex)
            assertEquals("第 $i 块 statusValueByteIndex 不一致",
                c.serialized.statusValueByteIndex, p.offsets.statusIndex)
        }
    }

    @Test
    fun 拆出来的件数之和等于原单件数() {
        val lines = (0 until 200).map { longLine(it) }
        val r = assembleB(lines)

        assertEquals("拆单改变件数会让库存扣减对不上",
            lines.sumOf { it.quantity },
            r.chunks.sumOf { parsed(it).items.sumOf { q -> q.quantity } })
    }

    // ==================== 装不下的情况必须**响**====================

    @Test
    fun 单行就超预算时抛异常_而不是死循环或静默截断() {
        val huge = OrderLine("6901234567890", "x".repeat(30_000), 1, "1.00", "1.00")
        val e = try {
            assembleB(listOf(line(0), huge, line(1)))
            null
        } catch (ex: IllegalArgumentException) {
            ex
        }
        assertNotNull("单行超预算必须抛 IllegalArgumentException", e)
        assertTrue("异常信息要能指认是哪一行：${e!!.message}", e.message!!.contains("6901234567890"))
    }

    @Test
    fun 空订单不进_outbox() {
        try {
            assembleB(emptyList())
            org.junit.Assert.fail("空订单应当被拒绝：服务端 items 不能为空，进了 outbox 也只会永远 FAILED")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun B_类不许带备注() {
        try {
            OrderGroupAssembler.assemble(
                OrderGroupAssembler.Request(
                    fileType = OrderFileId.TYPE_B, year = 2028, month = 3, writer = "alice",
                    lines = listOf(line(0)), ts = TS, remarkText = "销售单不该有备注",
                ),
                uuidFactory,
            )
            org.junit.Assert.fail("B 类带备注应当被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun A_类的_month_必须是_null() {
        // 传 0 会撞 `OrderSyncService:60` 的 `month<1||month>12` → 400，
        // 而且是在上传时才炸，那时 outbox 行已经 IN_FLIGHT 了。
        try {
            OrderGroupAssembler.assemble(
                OrderGroupAssembler.Request(
                    fileType = OrderFileId.TYPE_A, year = 2028, month = 0, writer = "alice",
                    lines = listOf(line(0)), ts = TS,
                ),
                uuidFactory,
            )
            org.junit.Assert.fail("A 类带 month 应当被拒绝")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun B_类的_month_必须在_1_到_12() {
        for (bad in listOf(null, 0, 13)) {
            try {
                OrderGroupAssembler.assemble(
                    OrderGroupAssembler.Request(
                        fileType = OrderFileId.TYPE_B, year = 2028, month = bad, writer = "alice",
                        lines = listOf(line(0)), ts = TS,
                    ),
                    uuidFactory,
                )
                org.junit.Assert.fail("B 类 month=$bad 应当被拒绝")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun fileType_不是_A_或_B_时抛() {
        try {
            OrderGroupAssembler.assemble(
                OrderGroupAssembler.Request(
                    fileType = "C", year = 2028, month = 3, writer = "alice",
                    lines = listOf(line(0)), ts = TS,
                ),
                uuidFactory,
            )
            org.junit.Assert.fail("fileType=C 应当被拒绝（服务端只认 A/B）")
        } catch (expected: IllegalArgumentException) {
        }
    }

    // ==================== 工具 ====================

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
