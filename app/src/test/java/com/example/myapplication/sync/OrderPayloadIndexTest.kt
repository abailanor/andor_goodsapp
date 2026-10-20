package com.example.myapplication.sync

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.sync.outbox.OrderDraft
import com.example.myapplication.sync.outbox.OrderLine
import com.example.myapplication.sync.outbox.StableOrderJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 偏移推算与订单原文解析的测试。
 *
 * ## 为什么这一层的测试值得写这么多
 *
 * 偏移错一位的后果是**此后每一次取消都打在别的订单的字节上**，而且是静默的：
 * 等长覆写不改文件长度 → 校验和自洽 → WAL 忠实回放 → 没有任何机制会发现。
 *
 * 尤其是 [round_trip] 那一条：它把 [StableOrderJson] 序列化时**当场记录**的字节下标
 * 与 [OrderJsonScanner] 事后**独立推算**的下标做交叉比对。两者是完全不同的实现路径
 * （一个边写边记、一个扫字节），能对上才说明推算逻辑没写歪 —— 这正是方案「未验证假设 #7」
 * 的闭合方式（FULL 恢复的订单拿不到服务端偏移，只能靠自己推）。
 */
class OrderPayloadIndexTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun b(s: String) = ByteOps.utf8(s)

    /** 拼一行最小合法订单。[status] 与 [tail] 直接给 JSON 片段，方便构造字符串形态与 grp/remark。 */
    private fun line(
        content: String = """{"items":[{"barcode":"6901234567890","name":"可乐","quantity":2,"amount":"6.00"}],"total":"6.00","ts":1758000000000}""",
        writer: String = "alice",
        status: String = "1",
        tail: String = "",
    ) = """{"order_uuid":"$uuid","writer":"$writer","content":$content,"status":$status$tail}"""

    /**
     * 拼一行带指定 remark **存储形态**的订单。
     *
     * 用普通字符串而不是 raw string：要测的正是"文件里的字节数含转义"，
     * raw string 里写 `\"` 会变成字面的反斜杠加引号、把 JSON 提前截断。
     */
    private fun remarkLine(storedValue: String) =
        "{\"order_uuid\":\"$uuid\",\"writer\":\"a\",\"content\":{},\"status\":1," +
            "\"remark\":\"$storedValue\"}"

    private fun parse(line: String) = OrderPayloadIndex.parse(b(line))

    // ==================== A. 偏移推算 ====================

    @Test
    fun `往返_扫描器推算的三个下标与序列化器当场记录的完全一致`() {
        // 方案「未验证假设 #7」：FULL 恢复的订单没有服务端给的偏移，只能本地推算。
        // 这里的对照物是 StableOrderJson 在**写字节的过程中**记下来的下标 —— 独立实现，
        // 而它已经被 verifyAgainstServer() 与服务端返回的 uuidOffset/statusOffset 交叉验证过。
        val remark = ByteOps.padRemark("整箱补货")
        val s = StableOrderJson.serialize(
            OrderDraft(
                orderUuid = uuid,
                writer = "alice",
                items = listOf(
                    OrderLine("6901234567890", "可口可乐330ml", 14, "52.00"),
                    OrderLine(
                        barcode = "6900000000017",
                        name = "百事可乐🥤大瓶",
                        quantity = 3,
                        amount = "9.00",
                        unitPrice = "3.00",
                        productName = "百事可乐（总部目录名）",
                    ),
                ),
                total = "61.00",
                ts = 1_758_000_000_000L,
                status = 1,
                groupId = "a3f9c21b",
                remark = remark,
            )
        )

        val off = OrderJsonScanner.offsets(s.payload)
        assertNotNull("自己写出来的订单必须能推出来", off)
        assertEquals("uuidValueIndex", s.uuidValueByteIndex, off!!.uuidValueIndex)
        assertEquals("statusIndex", s.statusValueByteIndex, off.statusIndex)
        assertEquals("remarkValueIndex", s.remarkValueByteIndex, off.remarkValueIndex)

        // 下标本身也要能读回正确的字节，否则"两边一致"可能是一起错。
        assertEquals(uuid, String(s.payload, off.uuidValueIndex, 36, Charsets.UTF_8))
        assertEquals('1'.code.toByte(), s.payload[off.statusIndex])
        assertEquals(
            remark,
            String(s.payload, off.remarkValueIndex!!, ByteOps.REMARK_WIDTH, Charsets.UTF_8)
        )
    }

    @Test
    fun `往返_全空格_remark_也要能推出来`() {
        // 方案 §3.8：`OrderGroupAssembler` 对 A 类**强制**生成 remark，哪怕全是空格 ——
        // 补丁是等长覆写，下单时没带 remark 的那一行之后永远补不进去。
        // 所以"150 个空格"是 A 类订单最常见的 remark 形态，不是边角情况。
        //
        // 这一条抓过一个真 bug：推算 remark 值起点时用了"跳过空格与引号"的规则
        // （那是服务端 normalizeUuidOffset/normalizeStatusOffset 的规则），
        // 结果 150 个空格被跳光、正好落在闭引号上 → 判成"不可补丁"，
        // 于是全店 A 类订单的备注都改不动，而且**不报错**。
        val remark = " ".repeat(ByteOps.REMARK_WIDTH)
        val s = StableOrderJson.serialize(
            OrderDraft(
                orderUuid = uuid, writer = "alice",
                items = listOf(OrderLine("6901234567890", "可乐", 1, "3.00")),
                total = "3.00", ts = 1L, remark = remark,
            )
        )
        val off = OrderJsonScanner.offsets(s.payload)!!
        assertEquals(s.remarkValueByteIndex, off.remarkValueIndex)
        assertEquals(
            remark,
            String(s.payload, off.remarkValueIndex!!, ByteOps.REMARK_WIDTH, Charsets.UTF_8)
        )
        // 解析出来是"没有备注"，但**可补丁** —— 两件事必须分开
        val p = OrderPayloadIndex.parse(s.payload)!!
        assertNull(p.remark)
        assertNotNull(p.offsets.remarkValueIndex)
    }

    @Test
    fun `中文与_emoji_让字符下标和字节下标分岔_扫描器用的是字节`() {
        // 方案 R6：`test_all.py` 的 E2/E10 用 Python `str.index`（码点）推算偏移，
        // payload 全 ASCII 时侥幸通过，中文一来就差若干字节。这个断言钉住"差值确实存在"，
        // 免得哪天测试数据被改成纯 ASCII 而让这条保护悄悄失效。
        val s = StableOrderJson.serialize(
            OrderDraft(
                orderUuid = uuid, writer = "张三",
                items = listOf(OrderLine("6901234567890", "可口可乐330ml🥤", 14, "52.00")),
                total = "52.00", ts = 1L,
            )
        )
        val off = OrderJsonScanner.offsets(s.payload)!!
        val json = String(s.payload, Charsets.UTF_8)

        assertTrue(
            "这个夹具里必须含多字节字符，否则本测试证明不了任何事",
            json.length < s.payload.size
        )
        assertEquals('1'.code.toByte(), s.payload[off.statusIndex])
        assertEquals(uuid, String(s.payload, off.uuidValueIndex, 36, Charsets.UTF_8))
    }

    @Test
    fun `status_写成字符串_偏移指向引号内的数字`() {
        // 服务端 `parseOrderJson:1021-1023` 对 VALUE_STRING 做了 `+1`，
        // 所以 `"status":"1"` 与 `"status":1` 给出的 statusOffset 语义相同：都指向数字本身。
        val l = line(status = "\"1\"")
        val bytes = b(l)
        val off = OrderJsonScanner.offsets(bytes)!!
        assertEquals('1'.code.toByte(), bytes[off.statusIndex])
        assertEquals(1, parse(l)!!.status)
    }

    @Test
    fun `带空格的_JSON_偏移仍指向值首字节`() {
        // `s1client.py` 用 Python `json.dumps` 的默认分隔符（`", "` / `": "`），
        // 所以参照实现写出来的订单**就是带空格的**，而且能通过 `validateSingleLine`。
        // 服务端 `normalizeUuidOffset`/`normalizeStatusOffset` 的循环是
        // `while (c == '"' || c == ' ') i++;` —— 只跳空格与双引号。这里必须与它一致。
        val remark = "x".repeat(ByteOps.REMARK_WIDTH)
        val l = """{"order_uuid": "$uuid", "writer": "alice", "content": {"items": []}, "status": 1, "remark": "$remark"}"""
        val bytes = b(l)
        val off = OrderJsonScanner.offsets(bytes)!!

        assertEquals(uuid, String(bytes, off.uuidValueIndex, 36, Charsets.UTF_8))
        assertEquals('1'.code.toByte(), bytes[off.statusIndex])
        assertEquals(
            remark,
            String(bytes, off.remarkValueIndex!!, ByteOps.REMARK_WIDTH, Charsets.UTF_8)
        )
    }

    @Test
    fun `商品名恰好叫_status_扫描器只认深度1的键`() {
        // 服务端兜底路径 `OrderFileService:1147` 是 `rawJson.indexOf("\"status\"")` ——
        // **全文第一次出现**，不认嵌套层级。items 里一个名叫 `status` 的商品会被它命中。
        // 那条兜底平时不触发（Jackson 主路径给出正确提示），所以不去改服务端，
        // 但本地推算必须比它严格。
        val l = """{"order_uuid":"$uuid","writer":"alice","content":{"items":[{"barcode":"6901234567890","name":"status","quantity":1,"amount":"3.00"}],"total":"3.00","ts":1},"status":1}"""
        val bytes = b(l)
        val off = OrderJsonScanner.offsets(bytes)!!

        assertEquals('1'.code.toByte(), bytes[off.statusIndex])
        // 反证：全文第一次出现确实在 items 里，位置更靠前 —— 用 indexOf 就会错。
        assertTrue(l.indexOf("\"status\"") < off.statusIndex)
    }

    @Test
    fun `嵌套对象里的同名字段不会被当成顶层键`() {
        // content 里也有一个 `status`（深度 2）。只有深度 1 的才是协议字段。
        val l = """{"order_uuid":"$uuid","writer":"alice","content":{"status":9,"order_uuid":"别的","items":[]},"status":1}"""
        val bytes = b(l)
        val off = OrderJsonScanner.offsets(bytes)!!

        assertEquals('1'.code.toByte(), bytes[off.statusIndex])
        assertEquals(uuid, String(bytes, off.uuidValueIndex, 36, Charsets.UTF_8))
    }

    @Test
    fun `值里的转义引号不会提前结束字符串`() {
        // 若不处理 `\`，扫描器会在 `a\"` 处以为字符串结束了，
        // 于是把 writer 值里的 `status` 当成一个顶层键 —— statusIndex 会指向 `9`。
        val l = """{"order_uuid":"$uuid","writer":"a\"status\":9","content":{},"status":1}"""
        val bytes = b(l)
        val off = OrderJsonScanner.offsets(bytes)!!

        assertEquals('1'.code.toByte(), bytes[off.statusIndex])
    }

    @Test
    fun `remark_不是恰好150字节时_remarkValueIndex_为_null_即使它存在`() {
        // 补丁是**等长覆写**：拿 150 字节去覆写一个 20 字节的 remark 会吃掉后面 130 字节的 JSON，
        // 而文件长度不变 → 校验和自洽 → WAL 忠实回放 → **没有任何机制会发现**。
        // 记 null 的后果只是"这条备注改不动"，用户会在 UI 上看到明确提示。
        for (r in listOf("短备注", "x".repeat(149), "x".repeat(151), "")) {
            val l = line(tail = ""","remark":"$r"""")
            val off = OrderJsonScanner.offsets(b(l))
            assertNotNull("remark=${r.length} 时整行仍应可解析", off)
            assertNull("remark 长度 ${ByteOps.utf8Len(r)} 不该被判成可补丁", off!!.remarkValueIndex)
        }
        // 缺失也一样
        assertNull(OrderJsonScanner.offsets(b(line()))!!.remarkValueIndex)
        // 恰好 150 字节（含中文，按**字节**算不是按字符）就可以
        val ok = ByteOps.padRemark("整箱补货")
        assertEquals(150, ByteOps.utf8Len(ok))
        assertNotNull(OrderJsonScanner.offsets(b(line(tail = ""","remark":"$ok"""")))!!.remarkValueIndex)
    }

    @Test
    fun `remark_长度按文件里的字节算_含转义`() {
        // 判据必须是"**文件里**的字节数"（含转义），因为补丁覆写的正是那一段字节。
        // 按"字符数 == 150"判的话：148 个 x + 一个转义引号在文件里是 150 字节、可补丁，
        // 而 149 个字符 + 一个转义引号是 151 字节、不可补丁 —— 两种都会判错。
        val esc = "\\\""   // JSON 源码里的 \" —— 文件中占 2 字节
        assertNotNull(
            "148 个 x + 一个转义引号 = 文件中恰好 150 字节，应当可补丁",
            OrderJsonScanner.offsets(b(remarkLine("x".repeat(148) + esc)))!!.remarkValueIndex
        )
        assertNull(
            "149 个 x + 一个转义引号 = 文件中 151 字节，不可补丁",
            OrderJsonScanner.offsets(b(remarkLine("x".repeat(149) + esc)))!!.remarkValueIndex
        )
    }

    @Test
    fun `推不出来时返回_null_而不是抛异常`() {
        // 调用方是 FULL 重建（一次几万行）。抛异常会让整次 FULL 失败，
        // 而那时镜像**已经写好了**、游标还没提交 → 下次同步又是 FULL → 死循环。
        for (bad in listOf(
            "", "   ", "[1,2]", "{", "{}", "null", "not json at all",
            """{"order_uuid":"$uuid"}""",                 // 缺 status
            """{"writer":"a","content":{},"status":1}""",  // 缺 order_uuid
        )) {
            assertNull(bad, OrderJsonScanner.offsets(b(bad)))
        }
        // 缺 writer 的行 scanner 照样给出偏移（它只管位置），但 parse 必须拒收
        val noWriter = """{"order_uuid":"$uuid","content":{},"status":1}"""
        assertNotNull(OrderJsonScanner.offsets(b(noWriter)))
        assertNull(parse(noWriter))
    }

    // ==================== B. 字段解析 ====================

    @Test
    fun `parse_把序列化器写出的订单完整还原`() {
        val s = StableOrderJson.serialize(
            OrderDraft(
                orderUuid = uuid, writer = "张三",
                items = listOf(
                    OrderLine("6901234567890", "可口可乐330ml", 14, "52.00"),
                    OrderLine("6900000000017", "百事可乐", 3, "9.00"),
                ),
                total = "61.00", ts = 1_758_000_000_000L, status = 1,
                groupId = "a3f9c21b", remark = ByteOps.padRemark("整箱补货  "),
            )
        )
        val p = OrderPayloadIndex.parse(s.payload)
        assertNotNull(p)
        with(p!!) {
            assertEquals(uuid, orderUuid)
            assertEquals("张三", writer)
            assertEquals(1, status)
            assertEquals(1_758_000_000_000L, orderTs)
            assertEquals(6100L, totalCents)
            assertFalse("明细和 == total，不该打手工调整标记", manualTotal)
            assertEquals("a3f9c21b", groupId)
            assertEquals("整箱补货", remark)   // trimEnd 掉了 padding
            assertFalse(itemsDropped)
            assertEquals(2, itemCount)
            assertEquals("6901234567890", items[0].barcode)
            assertEquals("可口可乐330ml", items[0].name)
            assertEquals(14, items[0].quantity)
            assertEquals(5200L, items[0].amountCents)
            assertEquals(900L, items[1].amountCents)
        }
    }

    @Test
    fun `必备字段不合格时整单返回_null`() {
        for (bad in listOf(
            line(writer = ""),                                   // 服务端 `:1004-1006` writer 不能为空
            line(writer = "   "),
            line(status = "12"),                                 // 必须恰为一个数字字符
            line(status = "-1"),
            line(status = "\"abc\""),
            line(content = "null"),                              // `:1009-1011` content 不能为 null
            """{"order_uuid":"$uuid","writer":"a","status":1}""", // 缺 content
        )) {
            assertNull(bad, parse(bad))
        }
    }

    @Test
    fun `content_不是对象也能通过_与服务端一致`() {
        // 服务端只要求 content **键存在且非 null**（`:1008-1015` 的 hasContent），
        // 不要求它是对象；`extractItems` 的 `path("content").path("items")` 拿不到就返回空列表。
        val p = parse(line(content = "\"随便一个字符串\""))
        assertNotNull(p)
        assertEquals(0, p!!.itemCount)
        assertFalse(p.itemsDropped)
    }

    @Test
    fun `没有_total_字段时用明细求和_且不打手工调整标记`() {
        // ⚠ `amount` 是**该行总价**，不是单价（开单页把箱/个换算完再写进来）。
        // 所以 quantity=2 + amount="6.00" 的明细和是 600 分，不是 1200。
        val l = """{"order_uuid":"$uuid","writer":"a","content":{"items":[{"barcode":"x","quantity":2,"amount":"6.00"}],"ts":1},"status":1}"""
        val p = parse(l)!!
        assertEquals(600L, p.totalCents)
        assertFalse("没有总额字段谈不上手工调整", p.manualTotal)
        // 参照实现不写 amount 也不写 name
        assertEquals("", p.items[0].name)
    }

    @Test
    fun `total_与明细和不一致时打手工调整标记_统计用_total`() {
        val l = """{"order_uuid":"$uuid","writer":"a","content":{"items":[{"barcode":"x","quantity":2,"amount":"6.00"}],"total":"5.00","ts":1},"status":1}"""
        val p = parse(l)!!
        assertTrue(p.manualTotal)
        // **不静默取其一**：覆盖发生在总额层，统计一律用 total
        assertEquals(500L, p.totalCents)
    }

    @Test
    fun `total_不是合法数字时退回明细求和_并且仍然打标记`() {
        // 不打标记的话"total 字段坏了"会被彻底隐藏 —— 统计照跑、数字悄悄用了明细和。
        for (bad in listOf("abc", "12.3.4", "")) {
            val l = """{"order_uuid":"$uuid","writer":"a","content":{"items":[{"barcode":"x","quantity":2,"amount":"6.00"}],"total":"$bad","ts":1},"status":1}"""
            val p = parse(l)!!
            assertTrue("total=$bad 必须打标记", p.manualTotal)
            assertEquals("退回明细求和（amount 是行总价）", 600L, p.totalCents)
        }
    }

    @Test
    fun `items_有一条不合格时整单_items_清空并打_itemsDropped`() {
        // 服务端 A 类（strictItems=false）路径 `extractItems:1087-1088`：一条不合格就 List.of()。
        // 合格判据逐字是：`barcode.isTextual() && !isBlank() && quantity.isIntegralNumber() && >0`。
        val valid = """{"barcode":"6901234567890","quantity":2,"amount":"6.00"}"""
        for (badItem in listOf(
            """{"barcode":6901234567890,"quantity":2}""",   // barcode 不是字符串
            """{"barcode":"","quantity":2}""",              // barcode 空白
            """{"quantity":2}""",                           // 缺 barcode
            """{"barcode":"x","quantity":3.0}""",           // 不是整数字面量（Jackson DoubleNode）
            """{"barcode":"x","quantity":"3"}""",           // 字符串不是数字
            """{"barcode":"x","quantity":0}""",             // 必须 > 0
            """{"barcode":"x","quantity":-2}""",
            """{"barcode":"x"}""",                          // 缺 quantity
            """{"barcode":"x","quantity":99999999999}""",   // 超出 Int 范围
            "3",                                            // 元素不是对象
        )) {
            val l = """{"order_uuid":"$uuid","writer":"a","content":{"items":[$valid,$badItem],"total":"12.00","ts":1},"status":1}"""
            val p = parse(l)
            assertNotNull("items 不合格不该让整单消失：$badItem", p)
            assertEquals(badItem, 0, p!!.itemCount)
            assertTrue(badItem, p.itemsDropped)
        }
    }

    @Test
    fun `items_缺失或是空数组时_itemsDropped_为_false`() {
        // 与"有但不合格"必须区分：B 类文件里的订单是服务端严格校验后才落盘的，
        // 所以 itemsDropped=true 出现在 B 类上只可能意味着本解析器与 Jackson 判断不一致。
        for (c in listOf("""{"ts":1}""", """{"items":[],"ts":1}""", """{"items":null,"ts":1}""")) {
            val p = parse(line(content = c))!!
            assertEquals(c, 0, p.itemCount)
            assertFalse(c, p.itemsDropped)
        }
        // items 不是数组：服务端 `:1074-1078` 非严格路径也是返回空列表
        val p = parse(line(content = """{"items":{},"ts":1}"""))!!
        assertEquals(0, p.itemCount)
        assertFalse(p.itemsDropped)
    }

    @Test
    fun `amount_缺失或坏掉记_0_并让_manualTotal_成立`() {
        // `amount` 是我们自己的扩展字段（服务端 extractItems 根本不读它），
        // 参照实现也不写。缺失不该让条目消失 —— 那会让 order_item_index 少行、库存算错。
        val p = parse("""{"order_uuid":"$uuid","writer":"a","content":{"items":[{"barcode":"x","quantity":2}],"total":"6.00","ts":1},"status":1}""")!!
        assertEquals(1, p.itemCount)
        assertFalse(p.itemsDropped)
        assertEquals(0L, p.items[0].amountCents)
        assertEquals(600L, p.totalCents)
        assertTrue("Σitems(0) != total(600) → 必须打标记", p.manualTotal)
    }

    @Test
    fun `remark_去掉尾部空格_但偏移不受影响`() {
        val padded = ByteOps.padRemark("整箱补货")
        val l = line(tail = ""","remark":"$padded"""")
        val p = parse(l)!!
        assertEquals("整箱补货", p.remark)
        // 偏移指向值首字节、长度固定 150，与存不存 padding 无关
        assertEquals(p.offsets.remarkValueIndex, OrderJsonScanner.offsets(b(l))!!.remarkValueIndex)
    }

    @Test
    fun `全空白的_remark_记成_null_但仍然可补丁`() {
        // "没有备注"与"备注改不动"是两回事：150 个空格恰好是等长覆写的合法目标，
        // 所以 remarkValueIndex 必须非 null，只是解析出来的文本 trimEnd 后为空 → 记 null。
        val p = parse(remarkLine(" ".repeat(ByteOps.REMARK_WIDTH)))!!
        assertNull(p.remark)
        assertNotNull(p.offsets.remarkValueIndex)
    }

    @Test
    fun `orderTs_缺失或坏掉时记_0`() {
        // 协议本身没有订单时间字段，FULL 恢复的订单只能靠 content.ts。
        // 缺失记 0 → 按日/月统计会归到 1970，这是"别的客户端写的订单"的固有代价。
        assertEquals(0L, parse(line(content = """{"items":[]}"""))!!.orderTs)
        assertEquals(0L, parse(line(content = """{"items":[],"ts":"昨天"}"""))!!.orderTs)
        assertEquals(1758000000000L, parse(line(content = """{"items":[],"ts":1758000000000}"""))!!.orderTs)
    }

    @Test
    fun `grp_读成_groupId_没有时为_null`() {
        assertEquals("a3f9c21b", parse(line(tail = ""","grp":"a3f9c21b""""))!!.groupId)
        assertNull(parse(line())!!.groupId)
    }

    @Test
    fun `顶层不是对象时返回_null_不抛`() {
        assertNull(parse("[1,2]"))
        assertNull(parse("null"))
        assertNull(parse("not json"))
        assertNull(parse(""))
    }

    @Test
    fun `status_允许_0_到_9_的全部取值`() {
        // 取消(0)/待处理(1)/已完成(2)/已删除(3) 都是文件里真实存在的字节，
        // FULL 重建时必须原样读出来，不能因为"没见过"就把整单丢掉。
        for (d in 0..9) {
            assertEquals(d, parse(line(status = d.toString()))!!.status)
        }
    }
}
