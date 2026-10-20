package com.example.myapplication.net.sse

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [OkHttpSseTransport] 的冒烟测试。
 *
 * ## 这里只测"真实现与 okhttp-sse 对接的那一层"
 *
 * 退避、探针、KICKED 闩全部在 [SseSupervisorTest] 里用虚拟时间覆盖。这个类只回答三个
 * **必须真连一次才知道**的问题：
 *
 *  1. okhttp-sse 到底怎么切分 `data:`（含多行 data 的拼接）；
 *  2. `cancel()` 之后 okhttp 会不会补一个 `onFailure`；
 *  3. 非 2xx 时 `response.code` 能不能拿到、`describe` 会不会把响应体原文带进日志。
 *
 * 第 2、3 条是 [SseSupervisor] 整个退避策略的地基：吞不掉 cancel 后的回调，
 * 每次**主动**断开都会被记成一次失败，重连间隔会一路涨到 30 秒上限，
 * 而现象只是"连接明明正常，重连却越来越慢"。
 */
class OkHttpSseTransportTest {

    /**
     * 挂住响应体，让连接停在"已打开、正在读"的状态。
     *
     * ⚠ 不能太长：MockWebServer.shutdown() 只等 5 秒，等不到就抛
     * `IOException("Gave up waiting for queue to shut down")` —— 那是**测试框架**的问题，
     * 与被测行为无关，却会让用例看起来像失败了。2 秒足够覆盖 cancel 后的回调窗口
     * （cancel 会让阻塞中的 read 立刻抛，若要违规回调，几毫秒内就到）。
     */
    private val BODY_DELAY_SEC = 2L

    private val SETTLE_MILLIS = 1_000L

    private lateinit var server: MockWebServer
    private lateinit var transport: OkHttpSseTransport

    /** 回调来自 OkHttp 的线程，所以容器必须是线程安全的。 */
    private val opens = AtomicInteger()
    private val events = CopyOnWriteArrayList<String>()
    private val closes = AtomicInteger()
    private val failures = CopyOnWriteArrayList<Pair<Int?, String>>()

    private var openedLatch = CountDownLatch(1)
    private var eventLatch = CountDownLatch(1)
    private var terminalLatch = CountDownLatch(1)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // ⚠ readTimeout 必须为 0：SSE 是长连接，两次推送之间的静默是常态不是超时。
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        transport = OkHttpSseTransport(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private class Rec(
        val opens: AtomicInteger,
        val events: CopyOnWriteArrayList<String>,
        val closes: AtomicInteger,
        val failures: CopyOnWriteArrayList<Pair<Int?, String>>,
        val openedLatch: CountDownLatch,
        val eventLatch: CountDownLatch,
        val terminalLatch: CountDownLatch,
    ) : SseTransport.Listener {
        override fun onOpen() {
            opens.incrementAndGet()
            openedLatch.countDown()
        }

        override fun onEvent(data: String) {
            events += data
            eventLatch.countDown()
        }

        override fun onClosed() {
            closes.incrementAndGet()
            terminalLatch.countDown()
        }

        override fun onFailure(httpStatus: Int?, message: String) {
            failures += httpStatus to message
            terminalLatch.countDown()
        }
    }

    private fun listener() = Rec(opens, events, closes, failures, openedLatch, eventLatch, terminalLatch)

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun url() = server.url("/api/sync/subscribe").toString()

    // ==================== 正常收流 ====================

    @Test
    fun 能收到事件_且流结束后走_onClosed() {
        server.enqueue(
            sse(
                """data: {"type":"HEARTBEAT","priceVersion":1,"stockVersion":2}""" + "\n\n" +
                    """data: {"type":"ORDER_FILE","fileId":"B:alice:2026-10"}""" + "\n\n"
            )
        )

        val h = transport.connect(url(), "tok", listener())
        assertTrue("onOpen 没来", openedLatch.await(10, TimeUnit.SECONDS))
        assertEquals("两条事件没都收到", 2, awaitEvents(2))
        assertTrue("流结束后没有终止回调", terminalLatch.await(10, TimeUnit.SECONDS))

        assertEquals(1, opens.get())
        assertEquals(1, closes.get())
        assertEquals("EOF 是正常结束，不该报 onFailure", emptyList<Pair<Int?, String>>(), failures.toList())
        assertEquals(
            """{"type":"HEARTBEAT","priceVersion":1,"stockVersion":2}""",
            events[0],
        )
        h.cancel()
    }

    /** [events] 是异步填充的，轮询到期望条数为止。 */
    private fun awaitEvents(n: Int, timeoutMs: Long = 10_000): Int {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (events.size < n && System.nanoTime() < deadline) Thread.sleep(10)
        return events.size
    }

    @Test
    fun 多行_data_由_SSE_层用换行拼好() {
        // 服务端把一条 JSON 折成两行 `data:` 是合法的，拼接是 SSE 层的责任，不是我们的。
        server.enqueue(sse("data: {\"type\":\"HEARTBEAT\",\ndata: \"priceVersion\":7}\n\n"))

        transport.connect(url(), "tok", listener())
        assertTrue(eventLatch.await(10, TimeUnit.SECONDS))
        assertEquals("{\"type\":\"HEARTBEAT\",\n\"priceVersion\":7}", events[0])
        // 拼好之后交给解析器必须仍然能解析
        assertEquals(SseEvent.Heartbeat(7, null), SseEventParser.parse(events[0]))
    }

    @Test
    fun 请求带上_Bearer_前缀与_event_stream_Accept() {
        server.enqueue(sse("data: {}\n\n"))
        transport.connect(url(), "abc.def.ghi", listener())
        assertTrue(eventLatch.await(10, TimeUnit.SECONDS))

        val req = server.takeRequest(10, TimeUnit.SECONDS)!!
        // 服务端 `ReactiveJwtAuthFilter:64` 判的是**区分大小写**的 `"Bearer "` 前缀
        assertEquals("Bearer abc.def.ghi", req.getHeader("Authorization"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertEquals("no-cache", req.getHeader("Cache-Control"))
    }

    @Test
    fun 没有_token_时不带_Authorization_头() {
        server.enqueue(sse("data: {}\n\n"))
        transport.connect(url(), null, listener())
        assertTrue(eventLatch.await(10, TimeUnit.SECONDS))
        assertNull(server.takeRequest(10, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    // ==================== 失败分类 ====================

    @Test
    fun 非_2xx_带出状态码_且绝不带响应体原文() {
        // 401 走 Spring Security 默认 entryPoint，响应体是空的；
        // 而反代出问题时响应体可能是一整页 HTML —— 塞进日志既没用又可能泄露内部结构。
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "text/html")
                .setBody("<html><body>nginx: upstream unauthorized</body></html>")
        )

        transport.connect(url(), "expired-token", listener())
        assertTrue(terminalLatch.await(10, TimeUnit.SECONDS))

        assertEquals(0, opens.get())
        assertEquals(1, failures.size)
        val (status, message) = failures[0]
        assertEquals(401, status)
        assertEquals("HTTP 401", message)
        assertFalse("响应体原文不许进日志", message.contains("nginx"))
    }

    @Test
    fun 服务端错误也只给状态码_不带追踪号之外的东西() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("trace=abc123"))
        transport.connect(url(), "tok", listener())
        assertTrue(terminalLatch.await(10, TimeUnit.SECONDS))
        assertEquals(500, failures.single().first)
        assertEquals("HTTP 500", failures.single().second)
    }

    // ==================== cancel 之后必须闭嘴 ====================

    @Test
    fun cancel_之后不再有任何回调() {
        // okhttp-sse 在 cancel() 之后会以 IOException("Canceled") 调 onFailure。
        // 不吞掉的话 SseSupervisor 每次**主动**断开都会被记成一次失败 → 退避一路涨到上限。
        server.enqueue(
            sse("data: {\"type\":\"HEARTBEAT\"}\n\n")
                // 头立刻发、体拖住：连接停在"已打开、正在读"的状态，正是主动断开的时机
                .setBodyDelay(BODY_DELAY_SEC, TimeUnit.SECONDS)
        )

        val h = transport.connect(url(), "tok", listener())
        assertTrue("onOpen 没来", openedLatch.await(10, TimeUnit.SECONDS))

        h.cancel()
        // 给它足够的时间去"错误地"回调
        Thread.sleep(SETTLE_MILLIS)

        assertEquals("cancel 之后不许再来 onFailure：$failures", emptyList<Pair<Int?, String>>(), failures.toList())
        assertEquals("cancel 之后不许再来 onClosed", 0, closes.get())
        assertEquals(1, opens.get())
    }

    @Test
    fun cancel_幂等_且在_onOpen_之前就取消也安全() {
        server.enqueue(sse("data: {}\n\n").setBodyDelay(BODY_DELAY_SEC, TimeUnit.SECONDS))
        val h = transport.connect(url(), "tok", listener())
        h.cancel()
        h.cancel()
        h.cancel()
        Thread.sleep(SETTLE_MILLIS)
        assertEquals(emptyList<Pair<Int?, String>>(), failures.toList())
        assertEquals(0, opens.get())
        assertEquals(0, closes.get())
    }
}
