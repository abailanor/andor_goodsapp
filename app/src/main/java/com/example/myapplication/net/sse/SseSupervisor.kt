package com.example.myapplication.net.sse

import com.example.myapplication.core.time.MonotonicClock
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.ApiErrorClassifier
import com.example.myapplication.net.HttpClients
import com.example.myapplication.net.UnauthorizedReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.math.min

/**
 * 全进程**一条** SSE 连接的生命周期管理（方案 §3.10）。
 *
 * ## ⚠ 这个类**编译期拿不到任何登录入口**
 *
 * 构造参数里只有 [tokenProvider]（只读地取当前 token），没有任何 `login` 函数。
 * 这是**结构上**的防护，不是约定：单会话策略下"重连时顺手重新登录"会踢掉自己刚建立的会话，
 * 而对方（其实就是自己）又会重连再踢 —— 每半小时一次的自杀循环，且日志里每一步看着都合理。
 *
 * 所以 401 与 KICKED 一律**停下来把状态交出去**（[SseState.NeedsRelogin] / [SseState.Kicked]），
 * 由会话层（P6）决定要不要重登、重登几次、抖动多久。
 *
 * ## 它管的四件事
 *
 *  1. **重连退避**：1s→2s→4s…上限 30s，**带等量抖动**（同店 8 台设备同时断线时不会一起回来）；
 *  2. **判死连接**：建连探针 + 空闲超时，见下面两处 ⚠；
 *  3. **不留僵尸**：每次 `connect` 之前无条件 `cancel` 上一个 handle；
 *  4. **KICKED 闩**：一旦收到就永久停止，只有 [acknowledgeKicked] 能解开。
 *
 * ## ⚠ 建连探针（5 秒）只对**商户端**成立
 *
 * `SyncController:61` 建连即调 `reconcileNow(session)`，所以商户端"连上后 5 秒内必收到一条"
 * —— 要么是 UPDATE，要么是裸 HEARTBEAT（无 SYNC_SUB 权限时）。收不到就说明这条连接是坏的。
 *
 * 而 `AdminController:178` 的 `/api/admin/subscribe` **没有** `reconcileNow`，
 * 第一条消息要等到心跳倒计时走完（`heartbeat-seconds`）。总部版必须把
 * [expectImmediateMessage] 传 `false`，否则每条连接都会在 5 秒时被自己判死，
 * 然后无限重连 —— 表现是"SSE 一直连不上"，而服务端日志一切正常。
 *
 * 总部版还有个更阴的坑：非 ADMIN 访问它返回 **200 + 空流**（`AdminController:181`），
 * 不是 403。所以"能不能订阅"必须在登录后按 `perms` 判断，不能靠这个响应码。
 *
 * ## ⚠ 空闲超时用 [MonotonicClock]，绝不用墙钟
 *
 * 见 [MonotonicClock] 的类注释。这是唯一能检测**半开连接**的手段：服务端那边的写失败探针
 * 要等 TCP 重传超时才生效，最长十几分钟（README §9）。
 *
 * 阈值 = 3 × 心跳间隔（[HttpClients.IDLE_TIMEOUT_MILLIS] 的推理链）。
 * 为什么是 3 而不是 1：心跳是**倒计时重置**的（`SyncController:69` 的 `switchMap`），
 * 任何一条推送都会把倒计时归零，所以"1 个心跳周期没消息"是常态而不是故障。
 */
class SseSupervisor(
    private val transport: SseTransport,
    /**
     * 每次尝试**现取**：URL 里要带当前的 `priceVersion`/`stockVersion`，
     * 而这两个游标在连接期间一直在推进。固化在构造参数里的话，重连就补不到落下的变更。
     */
    private val urlProvider: () -> String,
    /** 每次尝试**现取**：401 重登后 token 变了。null = 未登录，此时不连。 */
    private val tokenProvider: () -> String?,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    /** 商户端 `true`，总部端 `false`。见类注释里的 ⚠。 */
    private val expectImmediateMessage: Boolean = true,
    /** 服务端 `app.sync.heartbeat-seconds`。客户端只用来算空闲阈值，不参与协议。 */
    private val heartbeatMillis: Long = DEFAULT_HEARTBEAT_MILLIS,
    private val connectProbeMillis: Long = HttpClients.CONNECT_PROBE_TIMEOUT_MILLIS,
    /**
     * 一条连接活过这么久就算"稳定过"，下次断开时退避计数**归零**。
     *
     * 没有这条的话，服务端每 30 分钟一次的正常关闭（`SyncController:73` 的
     * `take(Duration.ofMinutes(30))`）会让退避一路涨到 30 秒上限 ——
     * 用一整天下来，重连间隔全是 30 秒，而每一次断开其实都是正常的。
     */
    private val stableConnectionMillis: Long = DEFAULT_STABLE_CONNECTION_MILLIS,
    /** `[0,1)` 的均匀随机数。抽出来是为了让退避抖动在测试里可重现。 */
    private val random: () -> Double = { Math.random() },
    private val audit: (String) -> Unit = {},
) {

    companion object {
        /** 与服务端 `app.sync.heartbeat-seconds` 的默认值一致。 */
        const val DEFAULT_HEARTBEAT_MILLIS = 60_000L

        const val DEFAULT_STABLE_CONNECTION_MILLIS = 60_000L

        const val BASE_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L

        /** 传输层信号缓冲。满了丢**最旧**的：终止信号总在最后，不会被丢掉。 */
        private const val SIGNAL_BUFFER = 1024

        /** 2 的幂移位上限，防 `shl` 溢出成负数。 */
        private const val MAX_BACKOFF_SHIFT = 20
    }

    // ==================== 对外可见 ====================

    private val _state = MutableStateFlow<SseState>(SseState.Idle)
    val state: StateFlow<SseState> = _state.asStateFlow()

    /**
     * 服务端推来的事件。
     *
     * `replay = 0` + `DROP_OLDEST`：**没有订阅者时事件会被丢掉，这是有意的**。
     * SSE 是提示通道不是数据通道（见 [SseEvent] 的类注释），丢一条 ORDER_FILE 提示
     * 只是让那次变更晚一点被 `SyncScheduler` 拉到；而 `replay > 0` 会让重连之后
     * 新订阅者收到一批**过期**提示，反而触发一轮无意义的 sync。
     */
    private val _events = MutableSharedFlow<SseEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SseEvent> = _events.asSharedFlow()

    /** 当前是不是被 KICKED 闩住了。UI 据此显示"已在别处登录，点此重新登录"。 */
    val isKicked: Boolean get() = _state.value is SseState.Kicked

    // ==================== 控制入口 ====================

    /**
     * 非阻塞的控制命令。全部经 [commands] 送进**唯一**的 [loop] 协程，
     * 于是所有可变状态都是那个协程的局部变量 —— 不需要锁，也不会有竞态。
     */
    private sealed interface Command {
        data object Start : Command
        data object Stop : Command

        /**
         * 回到前台。Doze 会冻结定时器，所以 `onResume` 必须**主动**算一次空闲间隔，
         * 不能等超时任务自己醒。
         */
        data object Foreground : Command

        /** 用户确认了"已在别处登录"。这是解开 KICKED 闩的**唯一**途径。 */
        data object AckKicked : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** 传输层回调 → [loop]。回调来自 OkHttp 的线程，必须搬进来。 */
    private sealed interface Signal {
        data object Opened : Signal
        data class Data(val text: String) : Signal
        data object Closed : Signal
        data class Failed(val httpStatus: Int?, val message: String) : Signal
    }

    fun start() {
        commands.trySend(Command.Start)
    }

    fun stop(reason: String) {
        commands.trySend(Command.Stop)
        audit("SSE stop()：$reason")
    }

    fun onForeground() {
        commands.trySend(Command.Foreground)
    }

    fun acknowledgeKicked() {
        commands.trySend(Command.AckKicked)
    }

    /** 停掉 [loop] 本身。进程退出或换账户时用。 */
    fun close() {
        supervisorJob?.cancel()
        currentHandle?.cancel()
        currentHandle = null
    }

    // ==================== 主循环 ====================

    /**
     * 以下三个只在 [loop] 协程里读写，所以不需要 volatile / 锁 ——
     * 这正是"所有控制入口都是往 [commands] 里塞一条命令"换来的。
     */
    private var running = false
    private var kicked = false
    private var currentHandle: SseTransport.Handle? = null
    private var supervisorJob: Job? = null

    private suspend fun loop() {
        var attempt = 0
        while (true) {
            if (!running) {
                // ---- 待命：只等命令 ----
                when (commands.receive()) {
                    Command.Start -> if (kicked) {
                        audit("KICKED 未被用户确认，拒绝 start()（自动重连会和对方互踢）")
                    } else {
                        running = true
                        attempt = 0
                    }
                    Command.Foreground -> if (kicked) {
                        audit("KICKED 未被用户确认，回前台也不重连")
                    } else if (tokenProvider() == null) {
                        // 未登录时回前台不该连：连上去只会吃一个 401，把状态搞成"需要重登"
                        audit("回前台但没有 token，不连 SSE")
                    } else {
                        running = true
                        attempt = 0
                    }
                    Command.AckKicked -> {
                        kicked = false
                        _state.value = SseState.Stopped("用户已确认被踢下线")
                        audit("KICKED 闩已解除；等待会话层重新登录后 start()")
                    }
                    Command.Stop -> Unit
                }
                continue
            }

            // ---- 连接一次 ----
            val token = tokenProvider()
            if (token == null) {
                running = false
                _state.value = SseState.Stopped("未登录")
                audit("没有 token，SSE 不连")
                continue
            }

            // ⚠ 无条件先 cancel：僵尸会话会占掉单账户 6 条配额里的一条，
            // 而服务端驱逐的是**最旧**的那条 —— 可能就是刚建的这一条。
            currentHandle?.cancel()

            val startedAt = clock.elapsedRealtime()
            val attemptNo = attempt
            _state.value = SseState.Connecting(attemptNo)

            val signals = Channel<Signal>(SIGNAL_BUFFER, BufferOverflow.DROP_OLDEST)
            val handle = transport.connect(urlProvider(), token, object : SseTransport.Listener {
                override fun onOpen() {
                    signals.trySend(Signal.Opened)
                }

                override fun onEvent(data: String) {
                    signals.trySend(Signal.Data(data))
                }

                override fun onClosed() {
                    signals.trySend(Signal.Closed)
                }

                override fun onFailure(httpStatus: Int?, message: String) {
                    signals.trySend(Signal.Failed(httpStatus, message))
                }
            })
            currentHandle = handle

            val end = runConnection(signals, handle, startedAt, expectImmediateMessage)
            handle.cancel()
            currentHandle = null

            when (end) {
                is AttemptEnd.Terminal -> {
                    // running / kicked / state 已在 runConnection 里设好
                }
                is AttemptEnd.Retry -> {
                    val lived = clock.elapsedRealtime() - startedAt
                    if (lived >= stableConnectionMillis) {
                        // 稳定过的连接断开是常态（30 分钟上限、切换网络），不该累积退避
                        attempt = 0
                    }
                    val delayMs = backoffMillis(attempt)
                    attempt++
                    _state.value = SseState.Backoff(attempt, delayMs, end.cause)
                    audit("SSE 断开（${end.cause}），${delayMs}ms 后第 $attempt 次重连")
                    when (backoffSleep(delayMs)) {
                        SleepEnd.Elapsed -> Unit
                        SleepEnd.Shortcut -> audit("退避被 start()/回前台 提前打断")
                        SleepEnd.Stopped -> {
                            running = false
                            _state.value = SseState.Stopped("stop()")
                        }
                        SleepEnd.Ignore -> Unit
                    }
                }
            }
        }
    }

    /**
     * 一条连接从建立到终止。**所有时序判据都在这里**。
     *
     * @param startedAt `connect()` 之前的单调时刻，用来算"活过多久算稳定"
     */
    private suspend fun runConnection(
        signals: Channel<Signal>,
        handle: SseTransport.Handle,
        startedAt: Long,
        probe: Boolean,
    ): AttemptEnd {
        var opened = false
        var firstMessageAt = -1L
        var lastMessageAt = -1L

        while (true) {
            val now = clock.elapsedRealtime()

            // 建连探针：只在"期望建连即有消息"且还没收到任何消息时挂上
            val probeDeadline = if (probe && firstMessageAt < 0) startedAt + connectProbeMillis else null
            // 空闲超时：收到过消息就从最后一条算；没收到过则从建连算
            // （后者只对 probe=false 有意义 —— probe=true 时探针会先触发）
            val idleDeadline = when {
                lastMessageAt >= 0 -> lastMessageAt + idleMillis
                !probe -> startedAt + idleMillis
                else -> null
            }
            // 两个截止时刻取更早的那个。都为 null 时不挂超时（只等信号与命令）。
            val probeFirst = probeDeadline != null && (idleDeadline == null || probeDeadline <= idleDeadline)
            val deadline = when {
                probeDeadline == null -> idleDeadline
                idleDeadline == null -> probeDeadline
                else -> if (probeFirst) probeDeadline else idleDeadline
            }
            val waitMs = deadline?.let { it - now }

            val step = select<Step> {
                signals.onReceive { sig -> Step.Inbound(sig) }
                commands.onReceive { cmd -> Step.Control(cmd) }
                if (waitMs != null) {
                    onTimeout(waitMs.coerceAtLeast(0L)) { Step.Deadline(probeFirst) }
                }
            }

            when (step) {
                is Step.Inbound -> when (val sig = step.signal) {
                    Signal.Opened -> {
                        opened = true
                        _state.value = SseState.Open
                    }
                    is Signal.Data -> {
                        val at = clock.elapsedRealtime()
                        if (firstMessageAt < 0) firstMessageAt = at
                        lastMessageAt = at
                        val event = SseEventParser.parse(sig.text)
                        if (!opened) {
                            // 传输层违反了"onOpen 先于 onEvent"的约定。不致命，但要知道。
                            opened = true
                            _state.value = SseState.Open
                        }
                        _events.tryEmit(event)
                        if (event is SseEvent.Kicked) {
                            audit("收到 KICKED（${event.reason}）：永久停止自动重连，等用户确认")
                            kicked = true
                            running = false
                            _state.value = SseState.Kicked(event.reason)
                            return AttemptEnd.Terminal
                        }
                    }
                    Signal.Closed -> return AttemptEnd.Retry("服务端正常结束了流（每 30 分钟一次，属正常）")
                    is Signal.Failed -> {
                        if (sig.httpStatus == 401) {
                            // ⚠ 不能用同一个 token 重连：它已经死了，每一次尝试都只会再吃一个 401。
                            // SSE 的 401 响应体是空的（Spring Security 默认 entryPoint），
                            // 所以这里过一遍分类器只是为了与 HTTP 层共用同一份"能不能自动重登"的判据。
                            val reason = when (val e = ApiErrorClassifier.classify(401, null)) {
                                is ApiError.Unauthorized -> e.reason
                                else -> UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED
                            }
                            running = false
                            _state.value = SseState.NeedsRelogin(reason)
                            audit("SSE 收到 401（$reason）：停止重连，交给会话层决定是否重登")
                            return AttemptEnd.Terminal
                        }
                        return AttemptEnd.Retry(sig.message)
                    }
                }

                is Step.Control -> when (step.command) {
                    Command.Stop -> {
                        running = false
                        _state.value = SseState.Stopped("stop()")
                        return AttemptEnd.Terminal
                    }
                    Command.Foreground -> {
                        // Doze 冻结了定时器：主动算一次空闲，过期就立刻重连、**跳过退避**。
                        // ⚠ 用 clock 现取时刻，不用循环开头那个 `now` —— select 可能已经等了很久。
                        val at = clock.elapsedRealtime()
                        val idleFor = if (lastMessageAt >= 0) at - lastMessageAt else -1L
                        val stale = idleFor > idleMillis ||
                            (!opened && probe && at - startedAt > connectProbeMillis)
                        if (stale) {
                            val why = if (idleFor > idleMillis) {
                                "回前台时发现空闲已 ${idleFor}ms（> 3×心跳 ${idleMillis}ms）"
                            } else {
                                "回前台时发现这条连接从未打开过"
                            }
                            audit(why)
                            return AttemptEnd.Retry(why)
                        }
                        audit("回前台：连接仍然新鲜，不动")
                    }
                    Command.Start -> audit("SSE 已在运行，忽略重复的 start()")
                    Command.AckKicked -> Unit   // 连接期间不可能被闩住，忽略
                }

                is Step.Deadline -> {
                    val cause = if (step.probe) {
                        "建连后 ${connectProbeMillis}ms 内没有收到任何消息（服务端本应立刻补发一条）"
                    } else {
                        "空闲超过 ${idleMillis}ms（3×心跳），判定为半开连接"
                    }
                    audit(cause)
                    return AttemptEnd.Retry(cause)
                }
            }
        }
    }

    /**
     * [runConnection] 里 `select` 的三个分支压成一个类型，好让 `when` 穷尽。
     *
     * 名字刻意与 [Signal] / [Command] **不同**：同名的话 [Step.Inbound] 的构造参数类型
     * 要靠全限定名才说得清，读代码的人得反复确认指的是哪个。
     */
    private sealed interface Step {
        data class Inbound(val signal: Signal) : Step
        data class Control(val command: Command) : Step

        /**
         * @param probe `true` = 建连探针超时；`false` = 空闲超时。两者的处置一样（重连），
         *              但审计文案必须区分 —— "服务端没在建连时补发"是配置/版本问题，
         *              "空闲 180 秒"是网络问题，混在一起就没法查了。
         */
        data class Deadline(val probe: Boolean) : Step
    }

    private val idleMillis: Long get() = heartbeatMillis * 3

    // ==================== 退避 ====================

    private sealed interface SleepEnd {
        data object Elapsed : SleepEnd
        data object Shortcut : SleepEnd
        data object Stopped : SleepEnd

        /** 收到了一条与退避无关的命令（`AckKicked`）：继续等剩下的时间，不要提前放行。 */
        data object Ignore : SleepEnd
    }

    /** 可被 `stop()` / 回前台 打断的退避等待。 */
    private suspend fun backoffSleep(millis: Long): SleepEnd {
        val deadline = clock.elapsedRealtime() + millis
        while (true) {
            val left = deadline - clock.elapsedRealtime()
            if (left <= 0) return SleepEnd.Elapsed
            val r = select<SleepEnd> {
                commands.onReceive { cmd ->
                    when (cmd) {
                        Command.Stop -> SleepEnd.Stopped
                        Command.Start, Command.Foreground -> SleepEnd.Shortcut
                        Command.AckKicked -> SleepEnd.Ignore
                    }
                }
                onTimeout(left) { SleepEnd.Elapsed }
            }
            if (r != SleepEnd.Ignore) return r
        }
    }

    /**
     * 等量抖动（equal jitter）：`base/2 + random(0, base/2)`。
     *
     * 为什么不用**全**抖动（`random(0, base)`）：全抖动的下界是 0，
     * 8 台设备里总有一台立刻回来，而那台大概率又立刻失败 —— 等于没有退避。
     * 等量抖动保住"至少等一半"，同时把 8 台设备的重连时刻摊开到 base/2 的窗口里。
     */
    internal fun backoffMillis(attempt: Int): Long {
        val shift = attempt.coerceIn(0, MAX_BACKOFF_SHIFT)
        val base = min(BASE_BACKOFF_MILLIS shl shift, MAX_BACKOFF_MILLIS)
        val half = base / 2
        return half + (random() * (base - half + 1)).toLong().coerceAtMost(base - half)
    }

    /** 一条连接的收尾方式。 */
    private sealed interface AttemptEnd {
        /** 退避之后重连。[cause] 会进 [SseState.Backoff] 与审计日志。 */
        data class Retry(val cause: String) : AttemptEnd

        /**
         * 不再重连，[SseState] 已经由 [runConnection] 设成终态
         * （[SseState.Kicked] / [SseState.NeedsRelogin] / [SseState.Stopped]）。
         */
        data object Terminal : AttemptEnd
    }

    // ⚠ 放在**所有属性声明之后**：`loop()` 会读 `running`/`kicked`/`currentHandle`，
    // 而属性初始化按声明顺序执行。若 `scope` 用的是 Unconfined 一类的立即调度器，
    // `launch` 会当场跑起来，早于那些属性赋初值 —— 读到的是 JVM 默认值而不是 `false`/`null`。
    init {
        supervisorJob = scope.launch { loop() }
    }
}

/** [SseSupervisor.state]。UI 与会话层都只读这个，不读别的。 */
sealed class SseState {
    /** 还没 `start()` 过。 */
    data object Idle : SseState()

    /**
     * 正在建连。[attempt] 是**当前这轮**的重连序号，从 0 开始；
     * 一条连接活过 `stableConnectionMillis` 之后会归零。
     */
    data class Connecting(val attempt: Int) : SseState()

    data object Open : SseState()

    /** 断开后等待重连。[cause] 是给人看的原因，UI 可以直接显示。 */
    data class Backoff(val attempt: Int, val delayMillis: Long, val cause: String) : SseState()

    /**
     * 401。**已停止**，不会自动重连。
     * 会话层据 [reason] 与 [ApiErrorClassifier.mayAutoRelogin] 决定要不要重登（最多一次、带抖动）。
     */
    data class NeedsRelogin(val reason: UnauthorizedReason) : SseState()

    /**
     * 收到 KICKED。**永久停止**，`start()` 会被拒绝，只有
     * [SseSupervisor.acknowledgeKicked] 能解开 —— 之后还要等会话层重新登录。
     */
    data class Kicked(val reason: String?) : SseState()

    /** 主动停了（退后台 / 未登录 / 用户确认过被踢）。 */
    data class Stopped(val reason: String) : SseState()

    val connected: Boolean get() = this is Open
}
