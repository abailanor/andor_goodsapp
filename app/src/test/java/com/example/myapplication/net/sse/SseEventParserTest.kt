package com.example.myapplication.net.sse

import com.example.myapplication.net.dto.SyncKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SseEventParser] 的解析（方案 §3.10）。
 *
 * ## 这里盯的是一类特定的"崩在回调里"
 *
 * 解析发生在 SSE 的回调线程上。一个没被 catch 的异常从那里逃出去，okhttp-sse 会当成
 * 连接失败 → [SseSupervisor] 退避重连 → 服务端建连即补发同一条消息 → 再崩。
 * **表现为"连上就断、断了就重连"的无限循环，而服务端日志一切正常。**
 *
 * 所以这些用例的断言重点不是"解析对了"，而是"**每一种畸形输入都落到
 * [SseEvent.Malformed] / [SseEvent.Unknown]，一个都不抛**"。
 *
 * ## 三种"裸心跳"
 *
 * [SseEvent.Heartbeat] 的两个版本号都可能是 null，来源见它自己的注释：
 * 无 `SYNC_SUB` 权限、管理员端 `/api/admin/subscribe`、以及服务端将来改形状。
 * 这里三种都覆盖，因为其中两种在生产里是**常态**而不是异常。
 */
class SseEventParserTest {

    // JUnit 的 assertTrue 没有 Kotlin contract，`is` 之后不会智能转换，所以显式 cast 一次。
    private fun malformed(data: String): SseEvent.Malformed {
        val e = SseEventParser.parse(data)
        assertTrue("应落到 Malformed，实际 $e", e is SseEvent.Malformed)
        return e as SseEvent.Malformed
    }

    private fun unknown(data: String): SseEvent.Unknown {
        val e = SseEventParser.parse(data)
        assertTrue("应落到 Unknown，实际 $e", e is SseEvent.Unknown)
        return e as SseEvent.Unknown
    }

    // ==================== HEARTBEAT ====================

    @Test
    fun 心跳_两个版本号都在() {
        val e = SseEventParser.parse("""{"type":"HEARTBEAT","priceVersion":12,"stockVersion":7}""")
        assertEquals(SseEvent.Heartbeat(12, 7), e)
    }

    @Test
    fun 心跳_裸的只有_type() {
        // 管理员端 `/api/admin/subscribe`（AdminController:198）**永远**只有 type。
        assertEquals(SseEvent.Heartbeat(null, null), SseEventParser.parse("""{"type":"HEARTBEAT"}"""))
    }

    @Test
    fun 心跳_版本号为_null_字面量时不抛() {
        // `asInt` 在 JsonNull 上抛 UnsupportedOperationException —— 那正是"连上就断"的成因。
        val e = SseEventParser.parse("""{"type":"HEARTBEAT","priceVersion":null,"stockVersion":null}""")
        assertEquals(SseEvent.Heartbeat(null, null), e)
    }

    @Test
    fun 心跳_版本号被写成字符串时当缺失而不是崩() {
        val e = SseEventParser.parse("""{"type":"HEARTBEAT","priceVersion":"12","stockVersion":7}""")
        assertEquals(SseEvent.Heartbeat(null, 7), e)
    }

    // ==================== KICKED / PERMISSIONS ====================

    @Test
    fun 被踢下线_带原因() {
        val e = SseEventParser.parse("""{"type":"KICKED","reason":"账户在别处登录"}""")
        assertEquals(SseEvent.Kicked("账户在别处登录"), e)
    }

    @Test
    fun 被踢下线_没有原因也要能解析() {
        // reason 缺失时仍然必须是 Kicked —— 少一个字段就把"别再连了"降级成 Unknown，
        // 客户端会继续自动重连，和新登录方互踢成风暴。
        assertEquals(SseEvent.Kicked(null), SseEventParser.parse("""{"type":"KICKED"}"""))
    }

    @Test
    fun 权限变更_正常列表() {
        val e = SseEventParser.parse("""{"type":"PERMISSIONS","username":"alice","perms":["SYNC_SUB","PRICE_CHANGE"]}""")
        assertEquals(SseEvent.Permissions("alice", listOf("SYNC_SUB", "PRICE_CHANGE")), e)
    }

    @Test
    fun 权限变更_空权限列表是合法的() {
        // 权限被全部收回时服务端发的就是空数组。它必须与"字段缺失"一样落成 emptyList，
        // 而不能落成 Malformed —— 后者会让 UI 继续显示旧权限。
        val e = SseEventParser.parse("""{"type":"PERMISSIONS","username":"alice","perms":[]}""")
        assertEquals(SseEvent.Permissions("alice", emptyList()), e)
    }

    @Test
    fun 权限变更_列表里混进非字符串只丢那几个() {
        // 服务端发的是 `new ArrayList<>(perms)`，元素恒为字符串；
        // 但"整体失败"的代价是权限缓存停在旧值上，比丢掉一个坏元素严重得多。
        val e = SseEventParser.parse("""{"type":"PERMISSIONS","perms":["SYNC_SUB",3,null,"STOCK_CHANGE"]}""")
        assertEquals(SseEvent.Permissions(null, listOf("SYNC_SUB", "STOCK_CHANGE")), e)
    }

    // ==================== ORDER_FILE ====================

    @Test
    fun 订单文件提示_全字段() {
        val e = SseEventParser.parse(
            """{"type":"ORDER_FILE","fileId":"B:alice:2026-10","fileType":"B","version":87}"""
        )
        assertEquals(SseEvent.OrderFileHint("B:alice:2026-10", "B", 87), e)
    }

    @Test
    fun 订单文件提示_缺_fileId_是_Malformed() {
        // 没有 fileId 就不知道该同步哪个文件，"收到了但什么都不做"和"根本没收到"无法区分。
        val e = malformed("""{"type":"ORDER_FILE","fileType":"B","version":87}""")
        assertTrue(e.reason.contains("fileId"))
    }

    @Test
    fun 订单文件提示_只有_fileId_也能用() {
        // fileType/version 只用于日志与"落后多少"的显示，缺了不影响发起 sync。
        assertEquals(
            SseEvent.OrderFileHint("A:alice:2026", null, null),
            SseEventParser.parse("""{"type":"ORDER_FILE","fileId":"A:alice:2026"}"""),
        )
    }

    // ==================== UPDATE ====================

    @Test
    fun 变更载荷_复用_SyncDataResponseDto() {
        val e = SseEventParser.parse(
            """{"type":"UPDATE","data":{"type":"INCREMENTAL","priceVersion":41,"stockVersion":9,""" +
                """"priceStreamEntries":[{"barcode":"6901234567890","new_price":"3.50"}]}}"""
        )
        val u = e as SseEvent.Update
        assertEquals(SyncKind.INCREMENTAL, u.data.kind)
        assertEquals(41, u.data.priceVersion)
        assertEquals(9, u.data.stockVersion)
        assertEquals("3.50", u.data.priceStreamEntries?.single()?.get("new_price"))
        assertNull(u.data.products)
    }

    @Test
    fun 变更载荷_SERVER_BEHIND_也要原样交出去() {
        // 解析层不判断能不能应用（那是 ProductSyncEngine 的事）。
        // 这里挡一次，是为了保证"服务端落后"这个信号不会在解析阶段就被丢掉。
        val e = SseEventParser.parse("""{"type":"UPDATE","data":{"type":"SERVER_BEHIND","priceVersion":3,"stockVersion":1}}""")
        assertEquals(SyncKind.SERVER_BEHIND, (e as SseEvent.Update).data.kind)
    }

    @Test
    fun 变更载荷_缺_data_是_Malformed() {
        malformed("""{"type":"UPDATE","priceVersion":41}""")
    }

    @Test
    fun 变更载荷_data_是_JSON_null_是_Malformed() {
        malformed("""{"type":"UPDATE","data":null}""")
    }

    @Test
    fun 变更载荷_data_不是对象是_Malformed() {
        // `gson.fromJson("[1,2]", SyncDataResponseDto)` 抛 JsonSyntaxException。
        malformed("""{"type":"UPDATE","data":[1,2]}""")
    }

    // ==================== Unknown / Malformed ====================

    @Test
    fun 不认识的_type_是_Unknown_而不是崩() {
        // 服务端比客户端新是**版本偏移**，不是错误。落到 Malformed 的话，
        // 一次服务端升级会被诊断成"链路坏了"。
        assertEquals("NEW_ORDER", unknown("""{"type":"NEW_ORDER","orderId":"x"}""").type)
    }

    @Test
    fun 缺_type_是_Unknown() {
        assertNull(unknown("""{"priceVersion":1}""").type)
    }

    @Test
    fun 不是_JSON_是_Malformed() {
        // 反代出问题时插进来的就是一整页 HTML。
        val e = malformed("<html><body>502 Bad Gateway</body></html>")
        assertTrue(e.raw.startsWith("<html>"))
    }

    @Test
    fun JSON_null_是_Malformed() {
        // `gson.fromJson("null", JsonObject)` 返回 null，不抛 —— 必须显式挡。
        malformed("null")
    }

    @Test
    fun JSON_数组是_Malformed() {
        malformed("[1,2,3]")
    }

    @Test
    fun 空字符串是_Malformed() {
        malformed("")
    }

    @Test
    fun 原始片段被截断以免把整张_FULL_商品表写进日志() {
        val huge = "x".repeat(5000)
        val e = unknown("""{"type":"WHATEVER","blob":"$huge"}""")
        assertTrue(
            "raw 长度 ${e.raw.length} 应被截到 ${SseEventParser.RAW_SNIPPET_LIMIT} 附近",
            e.raw.length < SseEventParser.RAW_SNIPPET_LIMIT + 40,
        )
        assertTrue(e.raw.endsWith("字节)"))
    }

    @Test
    fun 短于上限的原文不被截断() {
        assertEquals("""{"type":"WHATEVER","a":1}""", unknown("""{"type":"WHATEVER","a":1}""").raw)
    }
}
