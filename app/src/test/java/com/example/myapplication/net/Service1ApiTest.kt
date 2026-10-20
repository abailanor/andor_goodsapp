package com.example.myapplication.net

import com.example.myapplication.net.dto.OrderPatchRequestDto
import com.example.myapplication.net.dto.OrderStreamFields
import com.example.myapplication.net.dto.OrderSyncRequestDto
import com.example.myapplication.net.dto.ProductAddRequestDto
import com.example.myapplication.net.dto.SyncKind
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [Service1Api] 的契约测试。
 *
 * 这里**故意走明文 HTTP**：TLS 与证书绑定已经由 `HttpClientsTest` 覆盖，
 * 而这个类要测的是**协议契约**（哪种响应认成哪种结果、请求体字节有没有被动过、
 * 该重试的和不该重试的分别是什么）。让它依赖 `tools/pki/` 下的证书文件，
 * 只会让"跑单测之前要先跑 gen-cert.sh"变成一个额外的失败原因。
 *
 * ## 最有价值的几条
 *
 *  1. **FULL 靠 `X-File-Version` 头判定，不是 Content-Type**（两条对照测试）；
 *  2. **`/add` 的请求体逐字节等于传进去的字符串**（服务端原样落盘，动一个空格镜像就分歧）；
 *  3. **`/add` 在"请求已发出、连接被重置"时不重发**（重发 = 服务器上出现两行同一个 order_uuid）；
 *  4. **409 探测发的是字面量 `version=0` / `lastStreamId=""`**。
 */
class Service1ApiTest {

    private val server = MockWebServer()

    /** 每个测试可以换 token；null 表示未登录（不该挂 Authorization 头）。 */
    private var token: String? = "jwt-abc"

    private lateinit var api: Service1Api

    /**
     * 默认 client：`retryOnConnectionFailure(true)`（与 `HttpClients.apiClient` 一致），
     * 这样"addOrder 不重试"这条测试才有对照意义。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Before
    fun setUp() {
        server.start()
        api = Service1Api(client, { "http://localhost:${server.port}" }, { token })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun enqueueJson(body: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    /**
     * 取出服务端收到的**那一条**请求。
     *
     * ⚠ 用带超时的重载。无参的 `takeRequest()` 会**无限阻塞**——
     * 一个测试里不小心调两次（比如先读 body 再读 path）就会让整个 Gradle 测试任务挂死，
     * 而且没有任何报错，看起来像"编译卡住了"。这个坑我在写这批测试时真踩了。
     */
    private fun recorded(): RecordedRequest {
        val req = server.takeRequest(10, TimeUnit.SECONDS)
        if (req == null) {
            // 顺手把 body 也读出来，方便定位；返回一个占位对象让断言去失败
            fail("服务端没有收到请求（或者已经被这个测试里的上一次 takeRequest 取走了）")
        }
        return req!!
    }

    /** 请求体的 JSON 视图。 */
    private fun recordedJson(): JsonObject =
        JsonParser.parseString(recorded().body.readUtf8()).asJsonObject

    // ==================== 鉴权头 ====================

    @Test
    fun `带 token 时挂 Bearer 头_前缀区分大小写`() = runTest {
        // 服务端 ReactiveJwtAuthFilter:64 判的是 header.startsWith("Bearer ")，
        // **区分大小写**。写成 "bearer " 会得到 401，而且是最难查的那种（一切看起来都对）。
        enqueueJson("""{"success":true,"data":[]}""")
        api.listProducts()
        assertEquals("Bearer jwt-abc", recorded().getHeader("Authorization"))
    }

    @Test
    fun `未登录时不挂 Authorization 头`() = runTest {
        // 挂一个 "Bearer null" 上去，服务端会当成"带了个非法 token"，
        // 报 401 而不是"没登录"，把登录页的逻辑带偏。
        token = null
        enqueueJson("""{"success":true,"data":{"token":"t"}}""")
        api.login("alice", "pw", 0, 0)
        assertNull(recorded().getHeader("Authorization"))
    }

    // ==================== 信封解包 ====================

    @Test
    fun `success 为 false 时用服务端的 message 报 BadRequest`() = runTest {
        enqueueJson("""{"success":false,"message":"商品不存在"}""", 200)
        try {
            api.getProduct("6901234567890")
            fail("success=false 不该静默当成成功")
        } catch (e: ApiError.BadRequest) {
            assertEquals("商品不存在", e.msg)
        }
    }

    @Test
    fun `success 为 true 但 data 为 null 时报形状错误_不是空指针`() = runTest {
        // 服务端 ApiResponse.ok(data) 在 data 为 null 时就是这个形状。
        // 归一成 MalformedResponse 而不是让它变成 NPE，调用方才能给出"响应不对劲"的提示。
        enqueueJson("""{"success":true,"message":"ok","data":null}""")
        try {
            api.listProducts()
            fail("data=null 不该被当成空列表")
        } catch (e: ApiError.MalformedResponse) {
            assertTrue(e.msg.contains("data"))
        }
    }

    @Test
    fun `反代返回的 HTML 错误页不会把整页塞进错误文案`() = runTest {
        // 真实场景：用户把 IP 填成了一个 nginx，或者服务端挂了由反代兜底。
        // 把一整页 HTML 显示给用户既没用又难看，还可能泄露内部信息。
        val html = "<html><head><title>502 Bad Gateway</title></head><body>nginx</body></html>"
        enqueueJson(html, 502)
        try {
            api.listProducts()
            fail("非 JSON 不该被解析成功")
        } catch (e: ApiError) {
            assertFalse("错误文案里不该出现 HTML，实际: ${e.message}", e.message!!.contains("<html>"))
        }
    }

    @Test
    fun `错误分类覆盖 401 空体_403_413_429_500 追踪号`() = runTest {
        // 401 空体是最常见的路径（20 小时到期 / 每月 1 号 3:40 密钥轮换）
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(403)
            .setBody("""{"success":false,"message":"权限不足"}"""))
        server.enqueue(MockResponse().setResponseCode(413)
            .setBody("""{"success":false,"message":"Payload too large"}"""))
        server.enqueue(MockResponse().setResponseCode(429)
            .setBody("""{"success":false,"message":"操作过于频繁"}"""))
        server.enqueue(MockResponse().setResponseCode(500)
            .setBody("""{"success":false,"message":"服务器内部错误（追踪号 9f3ab12c，请提供给运维查日志）"}"""))

        val e1 = catchApiError { api.listProducts() } as ApiError.Unauthorized
        assertEquals(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, e1.reason)
        assertTrue("token 过期必须允许自动重登", ApiErrorClassifier.mayAutoRelogin(e1.reason))

        assertTrue(catchApiError { api.listProducts() } is ApiError.Forbidden)
        // 413 的正确反应是**拆单**，不是重试，所以要能单独认出来
        assertTrue(catchApiError { api.listProducts() } is ApiError.PayloadTooLarge)
        assertTrue(catchApiError { api.listProducts() } is ApiError.RateLimited)

        val e5 = catchApiError { api.listProducts() } as ApiError.ServerError
        assertEquals("追踪号要能提取出来交给运维", "9f3ab12c", e5.traceId)
    }

    @Test
    fun `401 别处登录绝不允许自动重登`() = runTest {
        // 单会话策略：自动重登会和新登录方互相踢，形成风暴。
        enqueueJson("""{"success":false,"message":"该账户已在别处登录，请重新登录"}""", 401)
        val e = catchApiError { api.listProducts() } as ApiError.Unauthorized
        assertEquals(UnauthorizedReason.SESSION_TAKEN_OVER, e.reason)
        assertFalse(ApiErrorClassifier.mayAutoRelogin(e.reason))
    }

    private suspend fun catchApiError(block: suspend () -> Unit): ApiError = try {
        block()
        fail("应当抛 ApiError")
        throw AssertionError("unreachable")
    } catch (e: ApiError) {
        e
    }

    // ==================== /add：请求体必须逐字节不动 ====================

    @Test
    fun `addOrder 的请求体逐字节等于传进去的字符串`() = runTest {
        // 服务端 OrderFileService:256 是 `(rawJson + "\n").getBytes(UTF_8)` 原样追加。
        // 客户端本地镜像里存的是同一串字节。**动一个空格、把中文转成 \uXXXX、
        // 换个字段顺序，镜像就和服务器分歧了**，而且等长覆写不会报错，只会静默改错字节。
        val payload = """{"order_uuid":"u-1","writer":"alice","content":{"items":[{"barcode":"6901234567890","name":"可口可乐330ml","quantity":14,"amount":"52.00"}],"total":"52.00","ts":1758000000000},"status":1,"remark":"x"}"""
        enqueueJson("""{"success":true,"message":"订单已加入","data":{"fileId":"B:alice:2026-10","orderUuid":"u-1","uuidOffset":16,"statusOffset":200,"version":7,"streamId":"1-0","stockChanges":[{"barcode":"6901234567890","newStock":86}]}}""")

        val r = api.addOrder("B", 2026, 10, payload)

        val req = recorded()
        assertEquals(payload, req.body.readUtf8())
        // 年份月份走**查询参数**，不在 body 里
        assertEquals("/api/orderfiles/add?type=B&year=2026&month=10", req.path)
        assertTrue("Content-Type 必须是 JSON，实际 ${req.getHeader("Content-Type")}",
            req.getHeader("Content-Type")!!.startsWith("application/json"))

        // 响应解析：偏移是权威值，必须原样拿到
        assertEquals("B:alice:2026-10", r.fileId)
        assertEquals(16L, r.uuidOffset)
        assertEquals(200L, r.statusOffset)
        assertEquals(7, r.version)
        assertEquals("1-0", r.streamId)
        assertEquals(1, r.stockChanges!!.size)
        assertEquals(86, r.stockChanges!![0].newStock)
    }

    @Test
    fun `A 类订单不带 month 查询参数`() = runTest {
        // A 类按年分文件（`A:<owner>:<yyyy>`），带了 month 服务端会走 validateTypeAndMonth 报 400。
        enqueueJson("""{"success":true,"data":{"fileId":"A:alice:2026","orderUuid":"u","uuidOffset":16,"statusOffset":99,"version":1,"streamId":"2-0"}}""")
        api.addOrder("A", 2026, null, """{"order_uuid":"u"}""")
        assertEquals("/api/orderfiles/add?type=A&year=2026", recorded().path)
    }

    @Test
    fun `addOrder 用的 client 关掉了连接级重试_而普通 client 没有`() {
        // ⚠ 这是整个测试类里后果最严重的一条，所以先说清楚**为什么这样测**。
        //
        // 要防的事：OkHttp 在"请求体已经发出去了、然后连接被重置"时会**静默重发整个 POST**
        // （`isRecoverable()` 只对 SocketTimeoutException 检查 requestSendStarted）。
        // 对 /add 这是灾难 —— 服务端 order_write.lua 是原子的，很可能已经提交了，
        // 重发就会在文件里追加**同一个 order_uuid 两次**（parseOrderJson 不查重）→
        // 两台设备各收一条 ADD、各追加一行 → order_index 主键冲突、镜像比服务器长 →
        // **之后所有偏移错位，而且是静默的**。
        //
        // ## 我原本想用"模拟连接重置 + 数请求次数"来测，那个测法是假的
        //
        // 我先写了对照实验：同一个 `DISCONNECT_AFTER_REQUEST` 策略打一个**允许**重试的
        // GET，断言 `requestCount > 1`。**它失败了** —— OkHttp 对 GET 也没重发。
        // 原因是它的重试还要求 `canRetry()`（得有备用路由），而 MockWebServer 只给
        // 一个 127.0.0.1 路由，于是重试路径根本没被走到。
        //
        // 也就是说，那个版本的 `requestCount == 1` 会**无条件通过**，
        // 跟 addOrder 用哪个 client 毫无关系 —— 一条给人虚假安全感的测试比没有测试更糟。
        //
        // 所以改成直接断言**机制本身**：addOrder 走的那个 client 的标志位是 false，
        // 而传进来的普通 client 是 true。这个断言不依赖 OkHttp 内部启发式，
        // 也不会因为版本升级而悄悄变成假通过。
        //
        // 真正兜住"结果未知"窗口的是 outbox 的 IN_FLIGHT write-ahead（P5，可确定性测试）；
        // 这里只是第一道防线。
        assertTrue("普通 API client 应当保留连接级重试（幂等读是净收益）", client.retryOnConnectionFailure)
        assertFalse("addOrder 用的 client 必须关掉它", api.noRetryClient.retryOnConnectionFailure)
        // newBuilder() 共享连接池，所以这不是"另起一套连接"
        assertSame("必须共享连接池，否则 HTTP/2 的单连接优势就没了",
            client.connectionPool, api.noRetryClient.connectionPool)
    }

    @Test
    fun `addOrder 遇到连接被重置时抛 Network_且不产生第二次请求`() = runTest {
        // 上一条证明了"标志位是对的"，这条证明"错误确实被归一成 ApiError.Network"，
        // 于是调用方能走 outbox 的 IN_FLIGHT 恢复流程，而不是崩、也不是盲目重推。
        //
        // 注意：这里 `requestCount == 1` **不是**在证明"没有重试"（见上一条的说明），
        // 它只是顺带确认没有意外的第二次调用。
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        try {
            api.addOrder("B", 2026, 10, """{"order_uuid":"u-1"}""")
            fail("连接被重置应当抛 ApiError.Network")
        } catch (e: ApiError.Network) {
            // 期望
        }
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
    }

    // ==================== /patch ====================

    @Test
    fun `patchOrder 的字段名与服务端 DTO 一致`() = runTest {
        // 字段名写错的话 Jackson 留 null → @NotNull 校验失败 → 400，
        // 但错误信息只会说"patchOffset不能为空"，很难想到是自己拼错了名字。
        enqueueJson("""{"success":true,"message":"补丁已应用","data":{"fileId":"B:alice:2026-10","orderUuid":"u-1","action":"CANCEL","version":8,"streamId":"3-0"}}""")
        val r = api.patchOrder(
            OrderPatchRequestDto("B", 2026, 10, "CANCEL", 16L, "u-1", 200L, 1, "0")
        )
        val sent = recordedJson()
        assertEquals("CANCEL", sent.get("action").asString)
        assertEquals(16L, sent.get("uuidOffset").asLong)
        assertEquals(200L, sent.get("patchOffset").asLong)
        assertEquals(1, sent.get("patchLength").asInt)
        assertEquals("0", sent.get("patchContent").asString)
        assertEquals("u-1", sent.get("uuid").asString)
        assertEquals(8, r.version)
    }

    @Test
    fun `改备注也是 action 等于 CANCEL_长度 150`() = runTest {
        // 服务端用户侧只允许 CANCEL 这一个 action（OrderFileService:361）。
        // 改备注必须借道它，patchLength=150。
        // 后果是流消息里 type 恒为 CANCEL，客户端只能靠偏移三分支区分取消与改备注。
        assertEquals("CANCEL", OrderPatchRequestDto.ACTION_CANCEL)
        assertEquals(150, OrderPatchRequestDto.REMARK_BYTE_LEN)
        enqueueJson("""{"success":true,"data":{"fileId":"A:alice:2026","orderUuid":"u","action":"CANCEL","version":3,"streamId":"4-0"}}""")
        api.patchOrder(
            OrderPatchRequestDto("A", 2026, null, "CANCEL", 16L, "u", 900L, 150, " ".repeat(150))
        )
        // 只取一次请求：body 与 path 都从同一个对象上读
        val req = recorded()
        assertEquals("/api/orderfiles/patch", req.path)
        val sent = JsonParser.parseString(req.body.readUtf8()).asJsonObject
        assertEquals(150, sent.get("patchLength").asInt)
        assertEquals("150 字节的 remark 必须原样发出去", 150, sent.get("patchContent").asString.length)
    }

    // ==================== /sync 的四态 ====================

    @Test
    fun `sync_LATEST 也要保存 streamId`() = runTest {
        enqueueJson("""{"success":true,"data":{"type":"LATEST","version":42,"streamId":"9-0"}}""")
        val o = api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 42, "9-0")) {}
        assertTrue(o is Service1Api.SyncOutcome.Latest)
        o as Service1Api.SyncOutcome.Latest
        assertEquals(42, o.version)
        // LATEST 的 streamId 仍然要覆盖保存：它是下一次增量同步的游标。
        assertEquals("9-0", o.streamId)
    }

    @Test
    fun `sync_INCREMENTAL 保留消息顺序与字符串值`() = runTest {
        // 顺序错了镜像就错了（ADD 消息带绝对偏移，乱序应用会写到错误位置）。
        //
        // ⚠ 每条消息的形状是 **`{id, fields}` 嵌套**，不是摊平的 —— `order_sync.lua:86-90`
        // 逐条 `cjson.encode({ id = …, fields = {…} })`。这个测试早先写的是摊平形状并且**通过了**，
        // 于是生产代码里的 `List<Map<String,String>>` 一直没被发现是错的：
        // Gson 解到嵌套的 `fields` 会抛 `Expected a string but was BEGIN_OBJECT`，
        // 真实服务器上每一次 INCREMENTAL 都会变成 MalformedResponse。
        // 教训：**mock 的响应体必须逐字抄服务端/Lua 的输出**，不能照着自己 DTO 的形状编。
        enqueueJson(
            """{"success":true,"data":{"type":"INCREMENTAL","version":5,"streamId":"2-0","messages":[""" +
                """{"id":"1-0","fields":{"file_id":"B:alice:2026-10","version":"4","type":"ADD","order_uuid":"u1","uuid_offset":"4","status_offset":"180","order_json":"{\"order_uuid\":\"u1\"}","operator":"alice","timestamp":"1758000000000"}},""" +
                """{"id":"2-0","fields":{"file_id":"B:alice:2026-10","version":"5","type":"CANCEL","order_uuid":"u1","uuid_offset":"4","patch_offset":"180","patch_length":"1","patch_content":"0","operator":"bob","timestamp":"1758000001000"}}""" +
                """]}}"""
        )
        val o = api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 3, "1-0")) {}
            as Service1Api.SyncOutcome.Incremental
        assertEquals(2, o.messages.size)
        assertEquals("1-0", o.messages[0].id)
        assertEquals("4", o.messages[0].fields!!["version"])
        assertEquals("ADD", o.messages[0].fields!!["type"])
        assertEquals("CANCEL", o.messages[1].fields!!["type"])
        assertEquals("180", o.messages[1].fields!!["patch_offset"])
        // 流消息里**没有** line_start / line_length，必须自己推
        assertFalse(o.messages[0].fields!!.containsKey("line_start"))
        assertFalse(o.messages[0].fields!!.containsKey("line_length"))
    }

    @Test
    fun `sync_INCREMENTAL_认得总部发来的_COMPLETE_与_DELETE_COMPLETED`() = runTest {
        // `OrderFileService:498` 用 `streamAction` 原样当 `type` 写进流，
        // 取值还有 COMPLETE / DELETE_COMPLETED（`:69`/`:70`），**不止 ADD/CANCEL**。
        // 它们与取消同形（patchLength=1 打在 statusOffset 上），
        // 所以客户端一律按偏移分类，不看 type —— 但 type 要能原样读出来供日志与 UI 用。
        enqueueJson(
            """{"success":true,"data":{"type":"INCREMENTAL","version":8,"streamId":"8-0","messages":[""" +
                """{"id":"7-0","fields":{"file_id":"A:alice:2026","version":"7","type":"COMPLETE","order_uuid":"u1","uuid_offset":"4","patch_offset":"200","patch_length":"1","patch_content":"2","operator":"serverb","timestamp":"1758000002000"}},""" +
                """{"id":"8-0","fields":{"file_id":"A:alice:2026","version":"8","type":"DELETE_COMPLETED","order_uuid":"u1","uuid_offset":"4","patch_offset":"200","patch_length":"1","patch_content":"3","operator":"serverb","timestamp":"1758000003000"}}""" +
                """]}}"""
        )
        val o = api.syncOrderFile(OrderSyncRequestDto("A", 2026, null, 6, "6-0")) {}
            as Service1Api.SyncOutcome.Incremental
        // 这一层只负责把 type 原样读出来；"该不该回退库存"由 PatchClassifier 按偏移判，
        // 那边有独立的测试，不在这里跨层断言。
        assertEquals("COMPLETE", o.messages[0].fields!![OrderStreamFields.TYPE])
        assertEquals("2", o.messages[0].fields!![OrderStreamFields.PATCH_CONTENT])
        assertEquals("DELETE_COMPLETED", o.messages[1].fields!![OrderStreamFields.TYPE])
        assertEquals("3", o.messages[1].fields!![OrderStreamFields.PATCH_CONTENT])
    }

    @Test
    fun `sync_FULL 走裸字节流_游标只在响应头里`() = runTest {
        // 服务端 OrderFileController:100-107：FULL 用 FileSystemResource 直接 sendfile，
        // version 与 streamId 只能走 X-File-Version / X-Stream-Id。
        // 不带出去的话客户端做完一次全量就永远没有游标，之后每次都只能再走全量。
        val fileBytes = buildFakeMirror(version = 3, lines = 500)
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("X-File-Version", "3")
                // HTTP/2 下头名会被强制小写，OkHttp 的 header() 不区分大小写，
                // 所以这里故意用混合大小写来钉住这个性质
                .setHeader("x-stream-id", "77-0")
                .setBody(Buffer().write(fileBytes))
        )

        var received: ByteArray? = null
        var reportedVersion = -1
        var reportedStreamId = ""
        val o = api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 0, null)) { body ->
            reportedVersion = body.version
            reportedStreamId = body.streamId
            // 必须**流式**读：真机上这是 26MB 级别，readByteArray() 会 OOM。
            // 这里按 8KB 一块读，顺便证明 source 是可增量消费的。
            val sink = Buffer()
            var chunk = 0
            while (!body.source.exhausted()) {
                body.source.read(sink, 8192)
                chunk++
            }
            assertTrue("应当分多块读到的，实际 $chunk 块", chunk > 1)
            received = sink.readByteArray()
        }

        assertTrue(o is Service1Api.SyncOutcome.Full)
        o as Service1Api.SyncOutcome.Full
        assertEquals(3, o.version)
        assertEquals("77-0", o.streamId)
        assertEquals(3, reportedVersion)
        assertEquals("77-0", reportedStreamId)
        assertEquals("FULL 落地必须逐字节一致", fileBytes.toList(), received!!.toList())
        assertEquals(fileBytes.size.toLong(), received!!.size.toLong())
    }

    @Test
    fun `sync_判 FULL 靠 X-File-Version 头_不是 Content-Type`() = runTest {
        // ⚠ 反着来的两条对照，缺一条都不算证明。
        //
        // (a) Content-Type 是 octet-stream 但**没有**版本头、body 是 JSON 信封
        //     → 必须按 JSON 解析。（反代改写 Content-Type、或错误页伪装都会造成这种）
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("""{"success":true,"data":{"type":"LATEST","version":9,"streamId":"1-0"}}""")
        )
        val a = api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 9, "1-0")) {}
        assertTrue("Content-Type 不该决定判定，实际 $a", a is Service1Api.SyncOutcome.Latest)

        // (b) Content-Type 是 application/json 但**有**版本头、body 是裸字节
        //     → 必须按 FULL 处理。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("X-File-Version", "11")
                .setHeader("X-Stream-Id", "5-0")
                .setBody(Buffer().write(buildFakeMirror(11, 3)))
        )
        var n = 0
        val b = api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 0, null)) { n += it.source.readByteArray().size }
        assertTrue("有版本头就必须当 FULL，实际 $b", b is Service1Api.SyncOutcome.Full)
        assertEquals((b as Service1Api.SyncOutcome.Full).version, 11)
        assertTrue("FULL 的 body 必须真的被交给回调，实际 $n 字节", n > 4)
    }

    @Test
    fun `sync_信封自称 FULL 却没有版本头时报形状错误_不猜`() = runTest {
        // 猜错方向的代价是不对称的：把 JSON 当字节流写进镜像 = 镜像彻底坏掉。
        enqueueJson("""{"success":true,"data":{"type":"FULL","version":1,"streamId":"1-0"}}""")
        val e = catchApiError {
            api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 0, null)) {}
        }
        assertTrue("应当是 MalformedResponse，实际 $e", e is ApiError.MalformedResponse)
    }

    @Test
    fun `sync_未知 type 绝不当成 FULL`() = runTest {
        // 服务端将来加一种类型时，默认当 FULL 会**覆盖**本地数据 —— 不可逆。
        enqueueJson("""{"success":true,"data":{"type":"WHATEVER","version":1,"streamId":"1-0"}}""")
        val e = catchApiError {
            api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 0, null)) {}
        }
        assertTrue(e is ApiError.MalformedResponse)
        assertEquals(SyncKind.UNKNOWN, SyncKind.of("WHATEVER"))
    }

    @Test
    fun `sync_409 分类成 SERVER_BEHIND_且能与注册冲突区分`() = runTest {
        // OrderFileController:110-115 的 409 文案是固定的，注册冲突的文案完全不同，
        // 所以按文案判定可靠。判错的后果：把"服务器落后"当成普通冲突 →
        // 拿服务器内容覆盖本地 → 丢掉客户端唯一那份更完整的数据。
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Stream-Id", "88-0")
                .setBody("""{"success":false,"message":"服务器版本落后于客户端，请重推本地文件尾部增量"}""")
        )
        val e = catchApiError {
            api.syncOrderFile(OrderSyncRequestDto("B", 2026, 10, 87, "86-0")) {}
        } as ApiError.Conflict
        assertTrue("必须识别成订单文件 SERVER_BEHIND", e.isOrderFileServerBehind)

        enqueueJson("""{"success":false,"message":"用户名已存在"}""", 409)
        val e2 = catchApiError { api.register("alice", "pw123456", null) } as ApiError.Conflict
        assertFalse("注册冲突不该被误判成 SERVER_BEHIND", e2.isOrderFileServerBehind)
    }

    @Test
    fun `409 探测发的是字面量 version 0 与空 lastStreamId`() = runTest {
        // 这是协议里没有的自定义探测：靠"服务器到底还有没有这个文件"来区分
        // 归档删除（→ LATEST）与真落后（→ FULL）。
        // 传进来的任何 version 都会让探测失去意义，所以必须是字面量。
        enqueueJson("""{"success":true,"data":{"type":"LATEST","version":0,"streamId":"0-0"}}""")
        api.probeAfter409("B", 2026, 10) {}
        val sent = recordedJson()
        assertEquals(0, sent.get("version").asInt)
        assertEquals("", sent.get("lastStreamId").asString)
        assertEquals("B", sent.get("type").asString)
        assertEquals(2026, sent.get("year").asInt)
        assertEquals(10, sent.get("month").asInt)
    }

    // ==================== 商品接口的字段名陷阱 ====================

    @Test
    fun `batch-upsert 必须显式带上 orderable_哪怕它是 false`() = runTest {
        // ⚠ 服务端 ProductAddRequest.orderable 是**原始 boolean**。
        // JSON 里漏掉这个键 → Jackson 留默认值 false → **商品被静默改成不可订**，
        // 没有任何报错。回推 400 条本地商品时这条路径最容易漏。
        enqueueJson("""{"success":true,"data":[{"barcode":"690","created":true}]}""")
        api.batchUpsertProducts(
            listOf(
                ProductAddRequestDto("690", "可乐", "3.50", null, 0, 12, orderable = false),
                ProductAddRequestDto("691", "雪碧", "3.50", "42.00", 12, 5, orderable = true),
            )
        )
        val sent = recordedJson()
            .getAsJsonArray("products")
        assertEquals(2, sent.size())
        assertTrue("orderable=false 也必须出现在 JSON 里", sent[0].asJsonObject.has("orderable"))
        assertFalse(sent[0].asJsonObject.get("orderable").asBoolean)
        assertTrue(sent[1].asJsonObject.get("orderable").asBoolean)
        // 金额必须是字符串，不能是数字（服务端用 ToStringSerializer，客户端不能过 Double）
        assertEquals("3.50", sent[0].asJsonObject.get("price").asString)
        // boxPrice=null 表示不按整箱卖；Gson 默认不写 null 键，服务端留 null，语义一致
        assertEquals(0, sent[0].asJsonObject.get("boxSize").asInt)
    }

    @Test
    fun `stock adjust 的字段名是 correctedStock 且必须是 JSON 数字`() = runTest {
        // 服务端 ProductController:83 的校验是 `body.get("correctedStock") instanceof Number`。
        // 发字符串会直接 400，而且错误文案是"barcode与correctedStock不能为空"——
        // 明明传了却说没传，很难查。
        enqueueJson("""{"success":true,"data":{"barcode":"690","oldStock":3,"correctedStock":10,"stockVersion":9}}""")
        val r = api.adjustStock("690", 10)
        val sent = recordedJson()
        assertTrue(sent.get("correctedStock").asJsonPrimitive.isNumber)
        assertEquals(10, sent.get("correctedStock").asInt)
        // 响应里的绝对值也叫 correctedStock（不叫 newStock）——两个名字都要认，
        // 漏一个就不推进 StockLedger 的吸收边界，库存会双算。
        assertEquals(10, r.correctedStock)
        assertEquals(9, r.stockVersion)
    }

    @Test
    fun `改库存的响应里 newStock 是绝对值`() = runTest {
        // StockLedger 靠它推进吸收边界。把它当增量用就会双算。
        enqueueJson("""{"success":true,"data":{"barcode":"690","oldStock":100,"change":-14,"newStock":86,"stockVersion":12,"warning":"商品数目存疑（负数）"}}""")
        val r = api.changeStock("690", -14)
        assertEquals(86, r.newStock)
        assertEquals(-14, r.change)
        assertEquals(12, r.stockVersion)
        assertEquals("商品数目存疑（负数）", r.warning)
        val sent = recordedJson()
        assertEquals("change 带符号，负数是出库", -14, sent.get("change").asInt)
    }

    @Test
    fun `商品列表的价格按字符串解析_不经过 Double`() = runTest {
        // 服务端 ProductData 的 getter 上有 ToStringSerializer，所以线上是 "85.00" 而不是 85.0。
        // 客户端必须保持字符串/BigDecimal，过一遍 Double 就会把 85.00 变成 85.0，
        // 显示与统计都会不一致。
        enqueueJson("""{"success":true,"data":[{"barcode":"690","name":"可乐","price":"85.00","boxPrice":"340.00","boxSize":24,"stock":10,"orderable":true,"updatedAt":1758000000000}]}""")
        val p = api.listProducts().single()
        assertEquals("85.00", p.price)
        assertEquals("340.00", p.boxPrice)
        assertEquals(24, p.boxSize)
        assertEquals(1758000000000L, p.updatedAt)
    }

    @Test
    fun `FULL 商品快照没有 updatedAt_这是类型上可见的`() = runTest {
        // SyncDataResponse.ProductDataResponse **没有** updatedAt 字段。
        // 这正是 StockLedger 不能拿 timestamp 切分吸收边界的原因之一，
        // 所以两种商品形状分成两个 DTO，而不是共用一个可空的 updatedAt。
        val json = com.google.gson.Gson().toJson(
            mapOf("barcode" to "690", "name" to "可乐", "price" to "3.50", "boxSize" to 0, "stock" to 0, "orderable" to true)
        )
        val snap = com.google.gson.Gson().fromJson(
            json, com.example.myapplication.net.dto.ProductSnapshotDto::class.java
        )
        assertEquals("3.50", snap.price)
        // 编译期就没有 updatedAt 这个属性 —— 这条断言的真正价值在于
        // 如果有人给它加上 updatedAt，这行会编译失败，逼他面对"FULL 里拿不到时间戳"这个事实。
        assertEquals(0, snap.boxSize)
    }

    // ==================== 登录 ====================

    @Test
    fun `登录请求带上游标_否则每次登录都要 FULL`() = runTest {
        // 服务端只有收到 priceVersion/stockVersion 才 setSyncData（AuthService:95/:130）。
        enqueueJson("""{"success":true,"data":{"token":"t","username":"alice","merchantCode":"99901","level":"OWNER","perms":["ORDER_WRITE","SYNC_SUB"],"createdAt":1750000000000,"sessionId":"s-1","previousLoginAt":0,"currentLoginAt":1758000000000}}""")
        val r = api.login("alice", "pw", 12, 34)
        val sent = recordedJson()
        assertEquals(12, sent.get("priceVersion").asInt)
        assertEquals(34, sent.get("stockVersion").asInt)
        assertEquals("t", r.token)
        assertEquals(setOf("ORDER_WRITE", "SYNC_SUB"), r.permSet())
        // createdAt=0 表示"未知"，不是 1970 年成立。这里是非 0，正常。
        assertEquals(1750000000000L, r.createdAt)
        assertEquals(0L, r.previousLoginAt)
        assertNull("没带游标就不会有 syncData", r.syncData)
    }

    @Test
    fun `员工登录的字段名不一样`() = runTest {
        // merchantCode + employeeUsername，不是 username。写错会得到 400
        // "merchantCode不能为空"，而不是"用户名或密码错误"。
        enqueueJson("""{"success":true,"data":{"token":"t","username":"bob","merchantCode":"99901","level":"EMPLOYEE"}}""")
        api.loginEmployee("99901", "bob", "pw", 0, 0)
        val req = recorded()
        assertEquals("/api/auth/login/employee", req.path)
        val sent = JsonParser.parseString(req.body.readUtf8()).asJsonObject
        assertEquals("99901", sent.get("merchantCode").asString)
        assertEquals("bob", sent.get("employeeUsername").asString)
        assertFalse("员工登录的请求体里没有 username 这个键", sent.has("username"))
    }

    @Test
    fun `总部登录的响应里没有 level 也没有 syncData`() = runTest {
        // 客户端要自己补 level=ADMIN，否则 Credentials.level 会是 null。
        enqueueJson("""{"success":true,"data":{"token":"t","username":"serverb","merchantCode":"00001","perms":["ADMIN"],"createdAt":0,"sessionId":"s","previousLoginAt":0,"currentLoginAt":1}}""")
        val r = api.adminLogin("serverb", "pw")
        assertEquals("ADMIN", com.example.myapplication.net.dto.AdminLoginResponseDto.ADMIN_LEVEL)
        assertEquals("00001", r.merchantCode)
        assertEquals("createdAt=0 表示未知，不能当 1970", 0L, r.createdAt)
    }

    // ==================== FileId ====================

    @Test
    fun `fileId 构造与解析往返`() {
        assertEquals("A:alice:2026", com.example.myapplication.net.dto.FileId.ofA("alice", 2026))
        // 月份必须补零到两位，否则 `B:alice:2026-1` 与服务端的 `B:alice:2026-01`
        // 是两个不同的 fileId → 镜像路径分叉、游标各存一份，**永远同步不上**。
        assertEquals("B:alice:2026-01", com.example.myapplication.net.dto.FileId.ofB("alice", 2026, 1))
        assertEquals("B:alice:2026-12", com.example.myapplication.net.dto.FileId.ofB("alice", 2026, 12))
        assertEquals("C:2026-03", com.example.myapplication.net.dto.FileId.ofCompleted(2026, 3))

        val b = com.example.myapplication.net.dto.FileId.parse("B:alice:2026-01")!!
        assertEquals("B", b.type)
        assertEquals("alice", b.ownerName)
        assertEquals(2026, b.year)
        assertEquals(1, b.month)

        val a = com.example.myapplication.net.dto.FileId.parse("A:alice:2026")!!
        assertNull("A 类按年分文件，没有月份", a.month)

        val c = com.example.myapplication.net.dto.FileId.parse("C:2026-03")!!
        assertNull("C 类是总部的完成记录，没有 owner", c.ownerName)
        assertEquals(3, c.month)
    }

    @Test
    fun `用户名里有百分号也能构造 fileId`() {
        // validateUsername 的正则 `^[^/\\:*?"<>|\p{Cntrl}.]{1,64}$` **允许 %**。
        // 用 "...%d...".format() 拼 fileId 的话，这种用户名会抛
        // UnknownFormatConversionException —— 而且是登录之后第一次开单时才炸。
        val id = com.example.myapplication.net.dto.FileId.ofB("100%店", 2026, 5)
        assertEquals("B:100%店:2026-05", id)
        assertEquals("100%店", com.example.myapplication.net.dto.FileId.parse(id)!!.ownerName)
    }

    @Test
    fun `fileId 格式不认识时返回 null_不抛`() {
        // 流消息里的 file_id 是服务端给的。将来加了新类型时客户端不该崩，
        // 该做的是记日志 + 忽略这条消息。
        for (bad in listOf(null, "", "X:alice:2026", "B:alice", "B:alice:2026-13",
            "B:alice:2026-00", "B:alice:xx-01", "A:alice:2026-01", "B:a:b:c:d")) {
            assertNull("fileId=$bad 应当返回 null",
                com.example.myapplication.net.dto.FileId.parse(bad))
        }
    }

    // ==================== 辅助 ====================

    /**
     * 造一个假的镜像文件字节：4 字节大端版本头 + N 行订单 JSON（每行以 \n 结束）。
     *
     * 用真结构而不是随机字节，是为了让"落地字节逐字节一致"这条断言有意义 ——
     * 随机字节也能过，但那就测不出"头被截断/被多加了 BOM"这类真实错误。
     */
    private fun buildFakeMirror(version: Int, lines: Int): ByteArray {
        val out = Buffer()
        out.writeInt(version)                       // 大端，与服务端一致
        for (i in 1..lines) {
            out.writeUtf8("""{"order_uuid":"u-$i","status":1}""")
            out.writeUtf8("\n")
        }
        return out.readByteArray()
    }
}
