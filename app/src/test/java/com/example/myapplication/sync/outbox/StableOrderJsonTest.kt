package com.example.myapplication.sync.outbox

import com.example.myapplication.core.bytes.ByteOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 订单 JSON 序列化器的测试。
 *
 * 这一层出错的话，写进服务器文件的字节就与本地推算的偏移不一致，
 * 之后每次补丁都会打到别的订单上——**而且是静默的**。
 */
class StableOrderJsonTest {

    private val uuid = "11111111-2222-3333-4444-555555555555"

    private fun draft(
        items: List<OrderLine> = listOf(
            OrderLine("6901234567890", "可口可乐330ml", 14, "52.00")
        ),
        remark: String? = null,
        groupId: String? = null,
        status: Int = 1,
    ) = OrderDraft(
        orderUuid = uuid,
        writer = "alice",
        items = items,
        total = "52.00",
        ts = 1_758_000_000_000L,
        status = status,
        groupId = groupId,
        remark = remark,
    )

    private fun json(s: SerializedOrder) = String(s.payload, Charsets.UTF_8)

    // ==================== 字段顺序：order_uuid 必须第一 ====================

    @Test
    fun `payload_以_order_uuid_开头`() {
        // 服务端 normalizeUuidOffset(OrderFileService:1122) 的兜底是
        // rawJson.indexOf("\"" + uuid + "\"") —— 取**第一次出现**。
        // 若别的字段值恰好等于 uuid 且排在前面，服务端算出的 uuidOffset 就是错的。
        val s = StableOrderJson.serialize(draft())
        assertTrue(json(s).startsWith("""{"order_uuid":"$uuid""""))
    }

    @Test
    fun `字段顺序固定为_uuid_writer_content_status_grp_remark`() {
        val j = json(StableOrderJson.serialize(draft(remark = ByteOps.padRemark("备注"), groupId = "a3f9c21b")))
        val iUuid = j.indexOf("\"order_uuid\"")
        val iWriter = j.indexOf("\"writer\"")
        val iContent = j.indexOf("\"content\"")
        val iStatus = j.indexOf("\"status\"")
        val iGrp = j.indexOf("\"grp\"")
        val iRemark = j.indexOf("\"remark\"")
        assertEquals(1, iUuid)
        assertTrue("writer 必须在 content 之前", iWriter < iContent)
        assertTrue("content 必须在 status 之前", iContent < iStatus)
        assertTrue("status 必须在 grp 之前", iStatus < iGrp)
        assertTrue("grp 必须在 remark 之前", iGrp < iRemark)
    }

    @Test
    fun `无_grp_与_remark_时不写这两个字段`() {
        val j = json(StableOrderJson.serialize(draft()))
        assertFalse(j.contains("\"grp\""))
        assertFalse(j.contains("\"remark\""))
    }

    // ==================== 单行、无控制字符 ====================

    @Test
    fun `payload_不含任何控制字符`() {
        assertFalse(ByteOps.containsControlChar(StableOrderJson.serialize(draft()).payload))
    }

    @Test
    fun `payload_是单行_不含裸换行`() {
        val s = StableOrderJson.serialize(draft())
        assertFalse(s.payload.contains(ByteOps.LF))
        assertFalse(s.payload.contains(0x0D.toByte()))
    }

    @Test
    fun `商品名里的换行被转义_不会把订单劈成两行`() {
        // 这是最致命的一种输入：文件是一行一个订单，服务端 locateOrderLine 靠扫描 \n 定位边界，
        // 注入一个换行会让该位置之后所有订单的定位全部错乱且不可恢复。
        val s = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("690", "第一行\n第二行", 1, "1.00")))
        )
        assertFalse("裸换行必须被转义掉", s.payload.contains(ByteOps.LF))
        // 转义成 \u000a 六字节形式（不用 \n 简写，统一规则更好推理）
        assertTrue(json(s).contains("\\u000a"))
    }

    @Test
    fun `商品名里的引号与反斜杠被正确转义`() {
        val s = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("690", "可乐\"大瓶\"\\促销", 1, "1.00")))
        )
        assertFalse(ByteOps.containsControlChar(s.payload))
        assertTrue(json(s).contains("\\\"大瓶\\\""))
        assertTrue(json(s).contains("\\\\"))
        // 转义后下标仍然自洽
        assertEquals(
            uuid,
            String(s.payload.copyOfRange(s.uuidValueByteIndex, s.uuidValueByteIndex + 36), Charsets.UTF_8)
        )
    }

    @Test
    fun `商品名里含_status_字面量也不会让服务端兜底搜索出错`() {
        // 服务端 normalizeStatusOffset(:1143) 的兜底是 indexOf("\"status\"")，取第一次出现。
        // 商品名叫 status 时，payload 里会先出现 "name":"status"。
        // 主路径（Jackson 字节偏移）不受影响，但这条测试把风险钉在这里，
        // 提醒任何改动字段顺序的人：不要依赖兜底路径。
        val s = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("690", "status", 1, "1.00")))
        )
        assertEquals('1', s.payload[s.statusValueByteIndex].toInt().toChar())
        assertTrue(
            "商品名里的 \"status\" 出现在真字段之前，兜底搜索会命中它 —— 主路径必须可靠",
            json(s).indexOf("\"status\"") < json(s).lastIndexOf("\"status\"")
        )
    }

    @Test
    fun `不做_HTML_转义_尖括号与和号原样保留`() {
        // Gson 默认会把 < > & = ' 转成 \u003c 之类，凭空多出字节、吃掉 20KB 预算
        val s = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("690", "A&B<C>=D'E", 1, "1.00")))
        )
        assertTrue(json(s).contains("A&B<C>=D'E"))
        assertFalse(json(s).contains("\\u003"))
    }

    @Test
    fun `中文原样输出为_UTF-8_不转成_uXXXX`() {
        val s = StableOrderJson.serialize(draft())
        assertTrue(json(s).contains("可口可乐330ml"))
        assertFalse(json(s).contains("\\u53ef"))
    }

    @Test
    fun `payload_零空白`() {
        // 商品名里本来没有空格，所以整个 payload 不该出现空格。
        // 美化缩进的 JSON 是完全合法的 JSON、服务端 parseOrderJson 不会拦，
        // 但含裸换行，追加进去会把一个订单劈成多行。
        assertFalse(json(StableOrderJson.serialize(draft())).contains(' '))
    }

    // ==================== 字节下标 ====================

    @Test
    fun `uuidValueByteIndex_处读回_36_字节正是_uuid`() {
        val s = StableOrderJson.serialize(draft())
        assertEquals(
            uuid,
            String(s.payload.copyOfRange(s.uuidValueByteIndex, s.uuidValueByteIndex + 36), Charsets.UTF_8)
        )
    }

    @Test
    fun `statusValueByteIndex_处正是状态数字`() {
        val s = StableOrderJson.serialize(draft())
        assertEquals('1', s.payload[s.statusValueByteIndex].toInt().toChar())
        val s0 = StableOrderJson.serialize(draft(status = 0))
        assertEquals('0', s0.payload[s0.statusValueByteIndex].toInt().toChar())
    }

    @Test
    fun `中文商品名不会让_statusValueByteIndex_错位`() {
        // 汉字 3 字节但 String.length 是 1，任何一处用错都会让下标偏移。
        // 两条订单**只有商品名不同**（条码/数量/金额全一样），
        // 所以 statusValueByteIndex 的差值必须恰好等于两个名字的 UTF-8 字节数之差。
        val ascii = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("6901234567890", "cola", 14, "52.00")))
        )
        val chinese = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("6901234567890", "可口可乐330ml", 14, "52.00")))
        )
        assertEquals('1', ascii.payload[ascii.statusValueByteIndex].toInt().toChar())
        assertEquals('1', chinese.payload[chinese.statusValueByteIndex].toInt().toChar())
        assertTrue(chinese.statusValueByteIndex > ascii.statusValueByteIndex)
        assertEquals(
            ByteOps.utf8Len("可口可乐330ml") - ByteOps.utf8Len("cola"),
            chinese.statusValueByteIndex - ascii.statusValueByteIndex
        )
        // 反证：如果用 String.length 算，差值会是 9-4=5 而不是 17-4=13
        assertEquals(13, chinese.statusValueByteIndex - ascii.statusValueByteIndex)
        assertTrue("String.length 会给出 5，与实测差值不同", "可口可乐330ml".length - "cola".length != 13)
    }

    @Test
    fun `emoji_商品名也不会让下标错位`() {
        // emoji 是 4 字节 / 2 个 UTF-16 code unit，比汉字更容易算错
        val s = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("690", "可乐🍺装", 1, "1.00")))
        )
        assertEquals('1', s.payload[s.statusValueByteIndex].toInt().toChar())
        assertEquals(
            uuid,
            String(s.payload.copyOfRange(s.uuidValueByteIndex, s.uuidValueByteIndex + 36), Charsets.UTF_8)
        )
    }

    @Test
    fun `remarkValueByteIndex_处读回_150_字节正是备注原文`() {
        val remark = ByteOps.padRemark("尽快发货，谢谢老板")
        val s = StableOrderJson.serialize(draft(remark = remark))
        assertNotNull(s.remarkValueByteIndex)
        val r = s.remarkValueByteIndex!!
        assertEquals(remark, String(s.payload.copyOfRange(r, r + 150), Charsets.UTF_8))
    }

    @Test
    fun `remarkOffset_推算公式成立_与_test_all_py_E10_同构`() {
        // README §2.5：remarkOffset = uuidOffset + (r - u)
        // test_all.py E10 的验证方式是"从服务器文件里切 150 字节 decode 回来与原文全等"，
        // 这里在本地构造同一份字节做同一件事。
        val remark = ByteOps.padRemark("改：明天下午三点前送到")
        val s = StableOrderJson.serialize(draft(remark = remark))
        val serverUuidOffset = 4L + s.uuidValueByteIndex
        val remarkOffset = serverUuidOffset + (s.remarkValueByteIndex!! - s.uuidValueByteIndex)

        val fileBytes = ByteOps.encodeHeader(1) + s.payload + ByteOps.LF
        val slice = fileBytes.copyOfRange(remarkOffset.toInt(), remarkOffset.toInt() + 150)
        assertEquals(remark, String(slice, Charsets.UTF_8))
    }

    @Test
    fun `无_remark_时_remarkValueByteIndex_为_null`() {
        assertNull(StableOrderJson.serialize(draft()).remarkValueByteIndex)
    }

    @Test
    fun `verifyAgainstServer_偏移一致时通过`() {
        val s = StableOrderJson.serialize(draft())
        StableOrderJson.verifyAgainstServer(
            s, 4 + s.uuidValueByteIndex.toLong(), 4 + s.statusValueByteIndex.toLong()
        )
    }

    @Test
    fun `verifyAgainstServer_偏移不一致时必须抛_这是抓住静默损坏的唯一机会`() {
        val s = StableOrderJson.serialize(draft())
        try {
            StableOrderJson.verifyAgainstServer(
                s, 4 + s.uuidValueByteIndex.toLong(), 5 + s.statusValueByteIndex.toLong()
            )
            fail("偏移不一致应当抛异常")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("偏移不一致"))
        }
    }

    // ==================== 校验：必填与格式 ====================

    @Test
    fun `拒绝非法_UUID`() {
        for (bad in listOf("not-a-uuid", "11111111-2222-3333-4444", uuid.replace("-", ""), "11111111222233334444555555555555")) {
            try {
                StableOrderJson.serialize(draft().copy(orderUuid = bad))
                fail("非法 UUID 应当被拒绝: $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("UUID"))
            }
        }
    }

    @Test
    fun `接受大写_UUID_服务端大小写均可`() {
        val s = StableOrderJson.serialize(draft().copy(orderUuid = uuid.uppercase()))
        assertEquals(
            uuid.uppercase(),
            String(s.payload.copyOfRange(s.uuidValueByteIndex, s.uuidValueByteIndex + 36), Charsets.UTF_8)
        )
    }

    @Test
    fun `拒绝空_writer`() {
        try {
            StableOrderJson.serialize(draft().copy(writer = "   "))
            fail("空 writer 应当被拒绝（服务端校验）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("writer"))
        }
    }

    @Test
    fun `拒绝空_items`() {
        try {
            StableOrderJson.serialize(draft(items = emptyList()))
            fail("B 类订单必须含非空 items（服务端校验）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("items"))
        }
    }

    @Test
    fun `拒绝空_barcode`() {
        try {
            StableOrderJson.serialize(draft(items = listOf(OrderLine("  ", "x", 1, "1.00"))))
            fail("空 barcode 应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("barcode"))
        }
    }

    @Test
    fun `拒绝非正整数_quantity`() {
        for (q in listOf(0, -1)) {
            try {
                StableOrderJson.serialize(draft(items = listOf(OrderLine("690", "x", q, "1.00"))))
                fail("quantity=$q 应当被拒绝")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("quantity"))
            }
        }
    }

    @Test
    fun `拒绝多位数_status`() {
        // 服务端要求 status 恰为一个数字字符
        try {
            StableOrderJson.serialize(draft(status = 12))
            fail("status=12 应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("status"))
        }
    }

    @Test
    fun `A_类_remark_必须恰好_150_字节`() {
        try {
            StableOrderJson.serialize(draft(remark = "没 padding 的备注"))
            fail("未 padding 到 150 字节的 remark 应当被拒绝")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("150"))
        }
        assertNotNull(StableOrderJson.serialize(draft(remark = ByteOps.padRemark("备注"))))
    }

    @Test
    fun `groupId_必须是_8_位十六进制且不得等于_uuid`() {
        for (bad in listOf("a3f9c21", "a3f9c21bb", "A3F9C21B", "zzzzzzzz", uuid)) {
            try {
                StableOrderJson.serialize(draft(groupId = bad))
                fail("非法 groupId 应当被拒绝: $bad")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("groupId"))
            }
        }
        assertNotNull(StableOrderJson.serialize(draft(groupId = "a3f9c21b")))
    }

    // ==================== 字节预算 ====================

    @Test
    fun `estimateItemBytes_等于实际增量`() {
        val line = OrderLine("6901234567890", "可口可乐330ml", 14, "52.00")
        val one = StableOrderJson.serialize(draft(items = listOf(line)))
        val two = StableOrderJson.serialize(draft(items = listOf(line, line)))
        // 多一条 item 只多它自己 + 一个逗号，estimateItemBytes 里已经把逗号算进去了
        assertEquals(two.byteSize - one.byteSize, StableOrderJson.estimateItemBytes(line))
    }

    @Test
    fun `estimateItemBytes_覆盖可选字段`() {
        val base = OrderLine("690", "可乐", 1, "1.00")
        val withUnit = base.copy(unitPrice = "3.50")
        val withProductName = base.copy(productName = "可口可乐330ml")
        assertTrue(StableOrderJson.estimateItemBytes(withUnit) > StableOrderJson.estimateItemBytes(base))
        assertTrue(StableOrderJson.estimateItemBytes(withProductName) > StableOrderJson.estimateItemBytes(base))

        val baseOnly = StableOrderJson.serialize(draft(items = listOf(base))).byteSize
        assertEquals(
            StableOrderJson.serialize(draft(items = listOf(base, withUnit))).byteSize - baseOnly,
            StableOrderJson.estimateItemBytes(withUnit)
        )
        assertEquals(
            StableOrderJson.serialize(draft(items = listOf(base, withProductName))).byteSize - baseOnly,
            StableOrderJson.estimateItemBytes(withProductName)
        )
    }

    @Test
    fun `标定拆单阈值_每行实际字节数与_20KB_上限`() {
        // 请求体上限 20KB（spring.codec.max-in-memory-size），超出返回 413。
        // 这条测试的产出是**标定值**：P5 的拆单器要按它设 byteBudget。
        // 结论：拆单阈值必须按序列化后的字节算，按行数算会在长条码/长商品名时翻车。
        val line = OrderLine("6901234567890", "可口可乐330ml", 14, "52.00")
        val n100 = StableOrderJson.serialize(draft(items = List(100) { line }))
        val n200 = StableOrderJson.serialize(draft(items = List(200) { line }))
        val n300 = StableOrderJson.serialize(draft(items = List(300) { line }))
        val perItem = (n200.byteSize - n100.byteSize) / 100

        assertEquals("估算器必须与实测增量一致", perItem, StableOrderJson.estimateItemBytes(line))
        assertTrue("每行实测 $perItem 字节，应当落在 80..95", perItem in 80..95)
        assertTrue("100 行 = ${n100.byteSize}B，在 20KB 内", n100.byteSize < 20_480)
        assertTrue("200 行 = ${n200.byteSize}B，仍在 20KB 内", n200.byteSize < 20_480)
        assertTrue("300 行 = ${n300.byteSize}B，超出 20KB → 必须拆单", n300.byteSize > 20_480)
    }

    @Test
    fun `A_类的_150_字节_remark_会吃掉外层预算`() {
        val line = OrderLine("6901234567890", "可口可乐330ml", 14, "52.00", productName = "可口可乐330ml")
        val noRemark = StableOrderJson.serialize(draft(items = List(50) { line })).byteSize
        val withRemark = StableOrderJson.serialize(
            draft(items = List(50) { line }, remark = ByteOps.padRemark("x"))
        ).byteSize
        // ,"remark":"  = 10 + 1 = 11 字节，值 150 字节，收尾引号 1 字节 → 共 162
        assertEquals(150 + ByteOps.utf8Len(",\"remark\":\"\""), withRemark - noRemark)
        assertEquals(162, withRemark - noRemark)
    }

    // ==================== 确定性 ====================

    @Test
    fun `同一_draft_序列化两次得到完全相同的字节`() {
        // outbox 的 payloadBytes 一次写入不可变；重推时若重新序列化得到不同字节，
        // 偏移就变了、remarkOffset 全错。所以序列化必须是确定性的。
        val d = draft(
            items = listOf(
                OrderLine("690", "可乐", 3, "10.50"),
                OrderLine("691", "雪碧", 1, "3.00", unitPrice = "3.00"),
            ),
            remark = ByteOps.padRemark("备注"),
            groupId = "a3f9c21b",
        )
        val a = StableOrderJson.serialize(d)
        val b = StableOrderJson.serialize(d)
        assertTrue(a.payload.contentEquals(b.payload))
        assertEquals(a.uuidValueByteIndex, b.uuidValueByteIndex)
        assertEquals(a.statusValueByteIndex, b.statusValueByteIndex)
        assertEquals(a.remarkValueByteIndex, b.remarkValueByteIndex)
        assertEquals(a, b)
    }

    @Test
    fun `lineBytes_是_payload_加单个_LF`() {
        val s = StableOrderJson.serialize(draft())
        val lb = s.lineBytes()
        assertEquals(s.payload.size + 1, lb.size)
        assertEquals(ByteOps.LF, lb.last())
        assertTrue(lb.copyOfRange(0, s.payload.size).contentEquals(s.payload))
    }

    // ==================== escape ====================

    @Test
    fun `escape_只处理必要字符`() {
        assertEquals("abc", StableOrderJson.escape("abc"))
        assertEquals("可口可乐", StableOrderJson.escape("可口可乐"))
        assertEquals("a\\\"b", StableOrderJson.escape("a\"b"))
        assertEquals("a\\\\b", StableOrderJson.escape("a\\b"))
    }

    @Test
    fun `escape_把控制字符转成_uXXXX_六字节`() {
        val withNul = "a" + 0.toChar() + "b"
        assertEquals("a\\u0000b", StableOrderJson.escape(withNul))
        assertEquals("a\\u001fb", StableOrderJson.escape("a" + 31.toChar() + "b"))
        assertEquals("a\\u007fb", StableOrderJson.escape("a" + 127.toChar() + "b"))
        // 换行与制表符也走 \u 形式（不用 \n \t 简写，统一规则更好推理）
        assertEquals("a\\u000ab", StableOrderJson.escape("a\nb"))
        assertEquals("a\\u0009b", StableOrderJson.escape("a\tb"))
        // 转义后不含任何裸控制字符
        assertFalse(ByteOps.containsControlChar(ByteOps.utf8(StableOrderJson.escape(withNul))))
    }

    // ==================== 与 Python 参照实现的字节级交叉验证 ====================

    /**
     * 期望值由 `C:\Users\hhl\Desktop\python\test_all.py` 的 `jstr()` 生成：
     * ```python
     * json.dumps(order, ensure_ascii=False, separators=(",", ":"))
     * ```
     * 那份参照实现跑通了 161 条契约断言，所以它的字节形状就是"服务器会原样落盘的形状"。
     *
     * 用 SHA-256 而不是内嵌字面量，是为了避开 Kotlin/Python/JSON 三层转义叠加导致的
     * "我以为我在比对同一串字节"的陷阱。
     *
     * ⚠ 顺带记录一个**参照测试自身的隐患**：`test_all.py` E2 用
     * `payload.rindex('"status":') + 9` 推算 statusOffset，而 Python 的 str 下标是
     * **码点**不是字节。对纯 ASCII 的 payload 两者相同，所以 E2 一直是通过的；
     * 但一旦 items 里带中文 `name`（我们要带），这个公式就会算错——
     * 实测「可口可乐330ml」让字符下标与字节下标差 8。§7.3 新增的断言必须改用
     * `len(s[:i].encode('utf-8'))`。这也正是本类把下标**在序列化过程中记录**、
     * 而不是事后再去搜的原因。
     */
    @Test
    fun `序列化结果与_Python_参照实现_jstr_逐字节一致_含中文`() {
        val s = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("6901234567890", "可口可乐330ml", 14, "52.00")))
        )
        assertEquals(224, s.byteSize)
        assertEquals(
            "ac19a30775cee9f49f15ac51e34cd70c81d14ac0563c8346306c2821a14bec48",
            sha256(s.payload)
        )
        assertEquals("u 必须是 15：{ + \"order_uuid\": + 开引号", 15, s.uuidValueByteIndex)
        assertEquals(222, s.statusValueByteIndex)
        // 服务端语义：uuidOffset = 行起点(4) + u
        assertEquals(
            uuid,
            String(s.payload.copyOfRange(s.uuidValueByteIndex, s.uuidValueByteIndex + 36), Charsets.UTF_8)
        )
        assertEquals('1', s.payload[s.statusValueByteIndex].toInt().toChar())
    }

    @Test
    fun `序列化结果与_Python_参照实现_jstr_逐字节一致_含引号与反斜杠`() {
        // 注意 total 也要跟着改成 "1.00"，否则与参照实现的字节数会差 1 —— 这正是
        // 用 SHA-256 做交叉验证的价值：它逼你把每一个字节都对上，而不是"看起来差不多"。
        val s = StableOrderJson.serialize(
            draft(items = listOf(OrderLine("690", "可乐\"大瓶\"\\促销", 1, "1.00")))
                .copy(total = "1.00")
        )
        assertEquals(218, s.byteSize)
        assertEquals(
            "29dad5123ebeb7c0e01b1f923b7a23dbc325b6a7bd7385c9c5edfa9c9c1f3f48",
            sha256(s.payload)
        )
        assertEquals(15, s.uuidValueByteIndex)
        assertEquals(216, s.statusValueByteIndex)
    }

    private fun sha256(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `escape_的输出仍是合法_JSON_字符串值_可被_Gson_读回原文`() {
        // 用一个独立的 JSON 解析器验证：转义必须**语义正确**，不只是"没有裸控制字符"。
        // 读不回来就意味着我们写进服务器文件的那一行是坏的 JSON。
        val nasty = "可乐\"大瓶\"\\促销\n第二行\t制表"
        val s = StableOrderJson.serialize(draft(items = listOf(OrderLine("690", nasty, 1, "1.00"))))
        val root = com.google.gson.JsonParser.parseString(json(s)).asJsonObject
        val item = root.getAsJsonObject("content")
            .getAsJsonArray("items")[0].asJsonObject
        assertEquals(nasty, item.get("name").asString)
        assertEquals(uuid, root.get("order_uuid").asString)
        assertEquals(1, root.get("status").asInt)
    }
}
