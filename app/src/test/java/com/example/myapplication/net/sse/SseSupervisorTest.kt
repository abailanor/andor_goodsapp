package com.example.myapplication.net.sse

import com.example.myapplication.core.time.FakeMonotonicClock
import com.example.myapplication.core.time.MonotonicClock
import com.example.myapplication.net.UnauthorizedReason
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SseSupervisor] 的时序策略（方案 §3.10）。
 *
 * ## 为什么全部跑在虚拟时间里
 *
 * 被测的四个量是 5 秒建连探针、3×心跳的空闲阈值、1→30 秒的退避、以及"活过 60 秒算稳定"。
 * 用 MockWebServer 真等的话一个测试类要跑几分钟，而且必然偶发 —— 偶发的重连测试
 * 比没有测试更糟，因为它会在真正出问题的那天正好是绿的。
 *
 * 所以传输层被 [FakeTransport] 替掉，回调由测试**手动**触发，时间由 [TestCoroutineScheduler] 推进。
 * 真实现 [OkHttpSseTransport] 只由 MockWebServer 冒烟测试覆盖"能不能收到事件"。
 *
 * ## 两套时钟，各自测一件事
 *
 *  - [SchedulerClock]（跟着虚拟时间走）：测**超时本身**该在第几毫秒触发；
 *  - [FakeMonotonicClock]（手动推进，与调度器无关）：测 **Doze**。Doze 的语义正是
 *    "定时器不响，但时间照样过去"，只有让两者脱钩才造得出来。
 *
 * ## ⚠ 不要用 advanceUntilIdle()
 *
 * 断开 → 退避 → 重连 → 探针超时 → 断开…… 只要假传输不主动送消息，这个环就永远有下一步，
 * `advanceUntilIdle()` 不会返回。所有用例一律 `advanceTimeBy(n); runCurrent()`。
 */
class SseSupervisorTest {

    private val auditLines = mutableListOf<String>()

    // ==================== 假件 ====================

    /** 单调时钟跟着虚拟时间走：调度器推进多少，"过了多久"就是多少。 */
    private class SchedulerClock(private val scheduler: TestCoroutineScheduler) : MonotonicClock {
        override fun elapsedRealtime(): Long = scheduler.currentTime
    }

    private class FakeHandle(val listener: SseTransport.Listener) : SseTransport.Handle {
        var cancelled = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun open() = listener.onOpen()
        fun event(data: String) = listener.onEvent(data)
        fun closed() = listener.onClosed()
        fun fail(httpStatus: Int?, message: String) = listener.onFailure(httpStatus, message)
    }

    private class FakeTransport : SseTransport {
        val handles = mutableListOf<FakeHandle>()
        val urls = mutableListOf<String>()
        val tokens = mutableListOf<String?>()

        override fun connect(url: String, token: String?, listener: SseTransport.Listener): SseTransport.Handle {
            urls += url
            tokens += token
            return FakeHandle(listener).also { handles += it }
        }

        val last: FakeHandle get() = handles.last()
    }

    private fun TestScope.newSupervisor(
        clock: MonotonicClock = SchedulerClock(testScheduler),
        token: () -> String? = { "tok" },
        expectImmediateMessage: Boolean = true,
        heartbeatMillis: Long = 60_000L,
        connectProbeMillis: Long = 5_000L,
        stableConnectionMillis: Long = 60_000L,
        random: () -> Double = { 0.0 },
        urlProvider: () -> String = { "https://s1/api/sync/subscribe" },
    ): Pair<SseSupervisor, FakeTransport> {
        val t = FakeTransport()
        val s = SseSupervisor(
            transport = t,
            urlProvider = urlProvider,
            tokenProvider = token,
            scope = backgroundScope,
            clock = clock,
            expectImmediateMessage = expectImmediateMessage,
            heartbeatMillis = heartbeatMillis,
            connectProbeMillis = connectProbeMillis,
            stableConnectionMillis = stableConnectionMillis,
            random = random,
            audit = { auditLines += it },
        )
        return s to t
    }

    /** `advanceTimeBy(n)` 不执行**恰好**落在目标时刻上的任务，必须再 `runCurrent()`。 */
    private fun TestScope.tick(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }

    private fun backoff(state: SseState): SseState.Backoff {
        assertTrue("应处于退避，实际 $state", state is SseState.Backoff)
        return state as SseState.Backoff
    }

    private fun heartbeat(price: Int? = 1, stock: Int? = 1) =
        """{"type":"HEARTBEAT","priceVersion":$price,"stockVersion":$stock}"""

    // ==================== 建连 ====================

    @Test
    fun 未登录时_start_不连_直接停在_未登录() = runTest {
        val (s, t) = newSupervisor(token = { null })
        s.start()
        runCurrent()

        assertEquals(SseState.Stopped("未登录"), s.state.value)
        assertEquals(0, t.handles.size)

        // 停下来之后不能自己爬起来：一直连一直吃 401 也是重连风暴的一种。
        tick(120_000)
        assertEquals(0, t.handles.size)
        assertEquals(SseState.Stopped("未登录"), s.state.value)
    }

    @Test
    fun 回前台_未登录_也不连() = runTest {
        val (s, t) = newSupervisor(token = { null })
        s.onForeground()
        tick(60_000)
        assertEquals(0, t.handles.size)
    }

    @Test
    fun 回前台_已登录_即使没调用过_start_也会连() = runTest {
        val (s, t) = newSupervisor()
        s.onForeground()
        runCurrent()
        assertEquals(1, t.handles.size)
        assertEquals(SseState.Connecting(0), s.state.value)
    }

    @Test
    fun 每次尝试都现取_URL_与_token() = runTest {
        var seq = 0
        val (s, t) = newSupervisor(
            token = { "tok-${++seq}" },
            urlProvider = { "https://s1/api/sync/subscribe?priceVersion=$seq" },
        )
        s.start()
        runCurrent()
        t.last.fail(null, "断线")
        runCurrent()
        tick(500)

        assertEquals(2, t.handles.size)
        // 固化在构造参数里的话，重连带的还是旧游标，断线期间的变更就永久补不回来了。
        assertEquals(listOf("tok-1", "tok-2"), t.tokens)
        assertTrue(t.urls[1].contains("priceVersion=2"))
    }

    // ==================== 建连探针 ====================

    @Test
    fun 商户端_建连五秒内没有任何消息_判死并重连() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        assertEquals(SseState.Connecting(0), s.state.value)

        tick(4_999)
        assertEquals("探针不该提前触发", 1, t.handles.size)

        tick(1)
        val st = backoff(s.state.value)
        assertEquals(1, st.attempt)
        assertEquals(500L, st.delayMillis)
        assertTrue("文案要能看出是建连探针而不是空闲超时：${st.cause}", st.cause.contains("建连后"))

        tick(500)
        assertEquals(2, t.handles.size)
        assertEquals(SseState.Connecting(1), s.state.value)
    }

    @Test
    fun 商户端_建连即收到裸心跳_探针被满足() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.open()
        // 无 SYNC_SUB 权限时服务端建连发的就是**裸**心跳；它同样算"这条连接是活的"。
        t.last.event(heartbeat(price = null, stock = null))
        runCurrent()

        assertEquals(SseState.Open, s.state.value)
        tick(60_000)
        assertEquals(1, t.handles.size)
    }

    @Test
    fun 总部端_关掉建连探针_五秒静默不判死() = runTest {
        // `/api/admin/subscribe` 没有 reconcileNow，第一条消息要等满一个心跳周期。
        // 探针开着的话每条连接都会在 5 秒被自己判死 → 无限重连，而服务端日志一切正常。
        val (s, t) = newSupervisor(expectImmediateMessage = false, heartbeatMillis = 60_000L)
        s.start()
        runCurrent()

        tick(30_000)
        assertEquals("总部端不该有 5 秒探针", 1, t.handles.size)
        assertEquals(SseState.Connecting(0), s.state.value)

        // 但空闲阈值仍然生效：3 × 60s。
        tick(150_000)
        assertEquals(1, t.handles.size)
        assertTrue(backoff(s.state.value).cause.contains("空闲"))

        tick(500)
        assertEquals(2, t.handles.size)
    }

    @Test
    fun 重连前_旧连接被取消_不留僵尸() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        val first = t.last
        tick(5_000)
        // 僵尸会话会占掉单账户 6 条配额里的一条，而服务端驱逐的是**最旧**的那条 —— 可能就是刚建的这一条。
        assertTrue("判死重连时必须 cancel 掉上一条", first.cancelled)

        tick(500) // 走完退避
        assertEquals(2, t.handles.size)
        assertTrue("第二条建起来之后第一条也必须是已取消的", first.cancelled)
    }

    // ==================== 空闲超时 ====================

    @Test
    fun 收到消息后_超过三倍心跳没有消息_判为半开连接() = runTest {
        val (s, t) = newSupervisor(heartbeatMillis = 1_000L) // idle = 3s
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()
        assertEquals(SseState.Open, s.state.value)

        tick(2_999)
        assertEquals(1, t.handles.size)
        tick(1)
        assertTrue(backoff(s.state.value).cause.contains("半开"))
    }

    @Test
    fun 任何一条消息都把空闲倒计时归零() = runTest {
        // 心跳是**倒计时重置**的（SyncController:69 的 switchMap），
        // 所以阈值必须是 3×心跳而不是 1×，否则正常的推送间隔就会被误判成故障。
        val (s, t) = newSupervisor(heartbeatMillis = 1_000L) // idle = 3s
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()

        tick(2_000)
        t.last.event(heartbeat()) // 距上一条 2s < 3s，倒计时归零
        runCurrent()

        tick(2_000) // t=4000：距**第一条**已经 4s > 3s，但距第二条只有 2s
        assertEquals("倒计时应已归零，不该按第一条算", 1, t.handles.size)
        assertEquals(SseState.Open, s.state.value)

        tick(1_000) // t=5000：距第二条满 3s
        assertTrue(backoff(s.state.value).cause.contains("半开"))
        tick(500)
        assertEquals(2, t.handles.size)
    }

    // ==================== 退避 ====================

    @Test
    fun 退避_等量抖动_增长与上限() = runTest {
        // `random()=0` → 等量抖动的下界 base/2；`→1` → 上界 base。
        val (lo, _) = newSupervisor(random = { 0.0 })
        val (hi, _) = newSupervisor(random = { 0.9999999 })

        assertEquals(500L, lo.backoffMillis(0))
        assertEquals(1_000L, lo.backoffMillis(1))
        assertEquals(2_000L, lo.backoffMillis(2))
        assertEquals(4_000L, lo.backoffMillis(3))
        assertEquals(15_000L, lo.backoffMillis(5)) // base = min(32s, 30s) = 30s
        assertEquals(15_000L, lo.backoffMillis(6))

        for (attempt in 0..6) {
            val base = minOf(1_000L shl minOf(attempt, 20), 30_000L)
            assertEquals("attempt=$attempt 下界", base / 2, lo.backoffMillis(attempt))
            assertEquals("attempt=$attempt 上界", base, hi.backoffMillis(attempt))
        }

        // 为什么不用**全**抖动：全抖动的下界是 0，8 台设备里总有一台立刻回来，
        // 而那台大概率又立刻失败 —— 等于没有退避。
        assertTrue("等量抖动必须保住至少一半", lo.backoffMillis(4) >= 8_000L)
    }

    @Test
    fun 退避次数极大时不溢出() = runTest {
        val (s, _) = newSupervisor(random = { 0.0 })
        // `1000 shl 40` 会回绕成负数，负数的 delay 等于没有退避 → 紧密重连循环。
        assertTrue(s.backoffMillis(Int.MAX_VALUE) in 15_000L..30_000L)
        assertTrue(s.backoffMillis(-5) in 500L..1_000L)
    }

    @Test
    fun 活过稳定期的连接断开时_退避计数归零() = runTest {
        // 服务端每 30 分钟主动关一次流属**正常**。不归零的话用一整天下来
        // 重连间隔全是 30 秒上限，而每一次断开其实都不是故障。
        val (s, t) = newSupervisor(stableConnectionMillis = 1_000L)

        s.start()
        runCurrent()
        t.last.fail(null, "断线") // 只活了 0ms
        runCurrent()
        assertEquals(1, backoff(s.state.value).attempt)
        assertEquals(500L, backoff(s.state.value).delayMillis)
        tick(500)

        t.last.fail(null, "断线") // 又只活了 0ms
        runCurrent()
        assertEquals("秒断的连接必须累积退避", 2, backoff(s.state.value).attempt)
        assertEquals(1_000L, backoff(s.state.value).delayMillis)
        tick(1_000)

        // 第三条活过 stableConnectionMillis 再被服务端正常关闭
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()
        tick(1_000)
        t.last.closed()
        runCurrent()

        val st = backoff(s.state.value)
        assertEquals("稳定过的连接断开不该累积退避", 1, st.attempt)
        assertEquals(500L, st.delayMillis)
    }

    @Test
    fun 服务端每三十分钟正常关流_走的是重连而不是终态() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()
        t.last.closed()
        runCurrent()

        assertTrue(backoff(s.state.value).cause.contains("正常"))
        tick(500)
        assertEquals(2, t.handles.size)
    }

    @Test
    fun 退避期间_stop_立刻停下_不再重连() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.fail(null, "断线")
        runCurrent()
        assertTrue(s.state.value is SseState.Backoff)

        s.stop("退后台")
        runCurrent()
        assertEquals(SseState.Stopped("stop()"), s.state.value)

        tick(120_000)
        assertEquals(1, t.handles.size)
    }

    @Test
    fun 退避期间_回前台_跳过剩余等待立刻重连() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.fail(null, "断线")
        runCurrent()
        assertEquals(1, t.handles.size)

        s.onForeground()
        runCurrent()
        assertEquals("回前台不该干等退避", 2, t.handles.size)
    }

    // ==================== Doze ====================

    @Test
    fun 回前台_发现空闲已过期_立刻重连() = runTest {
        // 时钟与调度器**脱钩**：Doze 冻结的正是定时器，而 elapsedRealtime 照样往前走。
        val clock = FakeMonotonicClock(0)
        val (s, t) = newSupervisor(clock = clock, heartbeatMillis = 1_000L) // idle = 3s
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()
        assertEquals(SseState.Open, s.state.value)

        clock.advance(10_000) // 设备睡了 10 秒，定时器没响
        s.onForeground()
        runCurrent()

        assertTrue("应主动发现空闲过期，实际 ${s.state.value}", backoff(s.state.value).cause.contains("回前台时发现空闲"))
        tick(500)
        assertEquals(2, t.handles.size)
    }

    @Test
    fun 回前台_连接仍然新鲜_不动() = runTest {
        val clock = FakeMonotonicClock(0)
        val (s, t) = newSupervisor(clock = clock, heartbeatMillis = 60_000L)
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()

        clock.advance(1_000)
        s.onForeground()
        runCurrent()

        assertEquals(SseState.Open, s.state.value)
        assertEquals(1, t.handles.size)
        assertTrue(auditLines.any { it.contains("仍然新鲜") })
    }

    @Test
    fun 回前台_发现这条连接从未打开过_立刻重连() = runTest {
        val clock = FakeMonotonicClock(0)
        val (s, t) = newSupervisor(clock = clock)
        s.start()
        runCurrent()
        assertEquals(SseState.Connecting(0), s.state.value)

        clock.advance(30_000) // TCP 半开：connect 发出去了，onOpen 永远不来
        s.onForeground()
        runCurrent()

        assertTrue(backoff(s.state.value).cause.contains("从未打开过"))
        tick(500)
        assertEquals(2, t.handles.size)
    }

    // ==================== 401 ====================

    @Test
    fun 收到_401_停止重连_并交出_需要重新登录() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.fail(401, "HTTP 401")
        runCurrent()

        assertEquals(SseState.NeedsRelogin(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED), s.state.value)
        assertFalse(s.state.value.connected)

        // ⚠ 用同一个 token 重连只是白跑：它已经死了，每次尝试都只会再吃一个 401。
        tick(300_000)
        assertEquals(1, t.handles.size)
        assertEquals(SseState.NeedsRelogin(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED), s.state.value)
    }

    @Test
    fun 收到_401_不是闩_会话层换了_token_之后可以重连() = runTest {
        // 刻意**不**把 401 做成闩：会话层重登成功后拿到新 token，SSE 必须能重新起来。
        // 闩住的是 KICKED（那是"别再连了"），不是 401（那是"换个凭据再连"）。
        var token = "old"
        val (s, t) = newSupervisor(token = { token })
        s.start()
        runCurrent()
        t.last.fail(401, "HTTP 401")
        runCurrent()
        assertEquals(1, t.handles.size)

        token = "new"
        s.start()
        runCurrent()
        assertEquals(2, t.handles.size)
        assertEquals("new", t.tokens.last())
    }

    @Test
    fun 非_401_的失败走重连() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.fail(500, "HTTP 500")
        runCurrent()
        assertTrue(s.state.value is SseState.Backoff)
        tick(500)
        assertEquals(2, t.handles.size)
    }

    @Test
    fun 传输层拿不到状态码时也走重连() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.fail(null, "SocketTimeoutException")
        runCurrent()
        assertEquals("SocketTimeoutException", backoff(s.state.value).cause)
    }

    // ==================== KICKED 闩 ====================

    @Test
    fun 收到_KICKED_永久停止_且_start_被拒绝() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.open()
        t.last.event("""{"type":"KICKED","reason":"账户在别处登录"}""")
        runCurrent()

        assertEquals(SseState.Kicked("账户在别处登录"), s.state.value)
        assertTrue(s.isKicked)

        // ⚠ 自动重连会和新登录方互相踢，形成风暴。这是唯一的"必须改变连接策略"的事件。
        tick(300_000)
        assertEquals(1, t.handles.size)

        s.start()
        tick(60_000)
        assertEquals("KICKED 未被用户确认时 start() 必须被拒绝", 1, t.handles.size)
        assertTrue(auditLines.any { it.contains("拒绝 start()") })

        s.onForeground()
        tick(60_000)
        assertEquals("回前台同样不能解开 KICKED 闩", 1, t.handles.size)
    }

    @Test
    fun 用户确认之后_闩解除_可以重新连() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.event("""{"type":"KICKED"}""")
        runCurrent()
        assertTrue(s.isKicked)

        s.acknowledgeKicked()
        runCurrent()
        assertFalse(s.isKicked)
        assertTrue(s.state.value is SseState.Stopped)

        s.start()
        runCurrent()
        assertEquals(2, t.handles.size)
    }

    // ==================== 事件流 ====================

    @Test
    fun 事件被解析后交给订阅者() = runTest {
        val (s, t) = newSupervisor()
        val got = mutableListOf<SseEvent>()
        backgroundScope.launch { s.events.toList(got) }
        runCurrent()

        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat(3, 4))
        t.last.event("""{"type":"ORDER_FILE","fileId":"B:alice:2026-10","fileType":"B","version":9}""")
        t.last.event("""{"type":"PERMISSIONS","perms":["SYNC_SUB"]}""")
        runCurrent()

        assertEquals(
            listOf(
                SseEvent.Heartbeat(3, 4),
                SseEvent.OrderFileHint("B:alice:2026-10", "B", 9),
                SseEvent.Permissions(null, listOf("SYNC_SUB")),
            ),
            got,
        )
        assertEquals(SseState.Open, s.state.value)
    }

    @Test
    fun 畸形消息不断连接() = runTest {
        // 解析异常从回调里逃出去 = 断连接 = 重连 = 服务端补发同一条 = 再断，无限循环。
        val (s, t) = newSupervisor()
        val got = mutableListOf<SseEvent>()
        backgroundScope.launch { s.events.toList(got) }
        runCurrent()

        s.start()
        runCurrent()
        t.last.open()
        t.last.event("<html>502</html>")
        t.last.event("not json at all")
        t.last.event(heartbeat())
        runCurrent()

        assertEquals(SseState.Open, s.state.value)
        assertEquals(1, t.handles.size)
        assertEquals(3, got.size)
        assertTrue(got[0] is SseEvent.Malformed)
        assertTrue(got[1] is SseEvent.Malformed)
        assertTrue(got[2] is SseEvent.Heartbeat)
    }

    @Test
    fun 没有订阅者时事件被丢掉_这是有意的() = runTest {
        // SSE 是提示通道：丢一条 ORDER_FILE 只是让那次变更晚一点被 SyncScheduler 拉到；
        // 而 replay > 0 会让重连后的新订阅者收到一批**过期**提示，触发一轮无意义的 sync。
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.event(heartbeat())
        runCurrent()

        val got = mutableListOf<SseEvent>()
        backgroundScope.launch { s.events.toList(got) }
        runCurrent()
        tick(1_000)
        assertEquals("迟到订阅者不该收到历史事件", emptyList<SseEvent>(), got)
    }

    // ==================== stop / close ====================

    @Test
    fun 连接期间_stop_立刻断开() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()
        assertEquals(SseState.Open, s.state.value)

        s.stop("退后台")
        runCurrent()
        assertEquals(SseState.Stopped("stop()"), s.state.value)
        assertTrue(t.handles[0].cancelled)

        tick(300_000)
        assertEquals(1, t.handles.size)
    }

    @Test
    fun close_取消当前连接且循环不再起来() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat())
        runCurrent()

        s.close()
        assertTrue(t.handles[0].cancelled)

        tick(300_000)
        assertEquals(1, t.handles.size)
        s.start()
        tick(300_000)
        assertEquals("close() 之后 start() 不该再起一条连接", 1, t.handles.size)
    }

    @Test
    fun 运行中重复_start_被忽略() = runTest {
        val (s, t) = newSupervisor()
        s.start()
        runCurrent()
        t.last.open()
        t.last.event(heartbeat()) // 满足建连探针，免得 tick 时被探针打断
        runCurrent()
        assertEquals(SseState.Open, s.state.value)

        s.start()
        s.start()
        tick(10_000)
        assertEquals("重复 start() 不该新建连接", 1, t.handles.size)
        assertTrue(auditLines.any { it.contains("忽略重复的 start()") })
    }
}
