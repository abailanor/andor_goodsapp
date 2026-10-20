package com.example.myapplication.net.sse

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSE 的传输层。**只是"建一条连接、把回调搬进来、能被取消"**，不含任何策略。
 *
 * ## 为什么要抽出来
 *
 * [SseSupervisor] 要测的是策略：退避多久、什么时候判死连接、KICKED 之后还连不连。
 * 那些断言必须能在**虚拟时间**里跑 —— 用 MockWebServer 的话，"5 秒探针"和
 * "180 秒空闲"都得真等，一个测试类跑几分钟，而且必然偶发。
 *
 * 抽成接口之后，测试传一个假传输（手动触发 `onOpen`/`onEvent`/`onFailure`），
 * 用 `runTest` 的虚拟时间推进，几十毫秒跑完全部时序分支。
 * 真实现 [OkHttpSseTransport] 只由一个 MockWebServer 冒烟测试覆盖"能不能收到事件"。
 *
 * 这也是项目里 `ProductApi` / `OrderFileApi` / `MirrorStateStore` 用的同一套办法。
 */
interface SseTransport {

    /**
     * 发起连接。**立即返回**，不阻塞；结果全部走 [listener]。
     *
     * @param url   完整的 subscribe URL，含 `priceVersion`/`stockVersion` 查询参数
     * @param token `Authorization: Bearer <token>`；null 表示未登录（调用方不该在这种状态下连）
     */
    fun connect(url: String, token: String?, listener: Listener): Handle

    /**
     * 回调。实现**必须**保证：
     *  1. [onOpen] 至多一次，且在任何 [onEvent] 之前；
     *  2. [onClosed] 与 [onFailure] 至多一个、至多一次（终止回调）；
     *  3. `cancel()` 之后不再有任何回调（含 onFailure）—— 见 [OkHttpSseTransport]。
     */
    interface Listener {
        /** HTTP 2xx 且响应头已到手。**此时还没有任何消息**。 */
        fun onOpen()

        /** 一条 `data:`。多行 data 已由 SSE 层用 `\n` 拼好。 */
        fun onEvent(data: String)

        /** 服务端正常结束了流。商户端每 30 分钟一次（`SyncController:73` 的 `take(Duration)`），**属正常**。 */
        fun onClosed()

        /**
         * 连接失败或中途断开。
         *
         * @param httpStatus 非 2xx 时的状态码（401 是最要紧的一种：token 死了，
         *                   重连同一个 token 只是白跑）。传输层拿不到时为 null。
         * @param message    给人看的原因，**不要**把响应体原文塞进来。
         */
        fun onFailure(httpStatus: Int?, message: String)
    }

    /** 一条活着的连接。 */
    interface Handle {
        /** 主动断开。幂等。调用后不会再有回调。 */
        fun cancel()
    }
}

/**
 * [SseTransport] 的 okhttp-sse 实现。
 *
 * ## ⚠ `cancel()` 之后必须吞掉回调
 *
 * okhttp-sse 在 `EventSource.cancel()` 之后会以 `IOException("Canceled")` 调 `onFailure`。
 * 不吞掉的话，[SseSupervisor] 每次**主动**断开（重连前清僵尸连接、判死连接）都会被
 * 看成一次失败 → 退避计数 +1 → 而重连又主动断开上一条 → 退避一路涨到 30 秒上限。
 * 表现是"连接明明正常，重连间隔却越来越长"，而且看不出原因。
 *
 * ## ⚠ 重连前一定要 `cancel()` 旧连接
 *
 * 单账户在服务端只有 6 条会话配额（`MerchantSyncRegistry.maxSessionsPerMerchant`），
 * 超了会驱逐**最旧**的。僵尸连接占着配额，新建的那条可能反过来把自己挤掉。
 * 这条规则由 [SseSupervisor] 负责执行（它在每次 `connect` 之前无条件 cancel 上一个 handle）。
 */
class OkHttpSseTransport(
    private val client: OkHttpClient,
) : SseTransport {

    private val factory = EventSources.createFactory(client)

    override fun connect(url: String, token: String?, listener: SseTransport.Listener): SseTransport.Handle {
        val request = Request.Builder()
            .url(url)
            // 服务端 `produces = TEXT_EVENT_STREAM_VALUE`，不带 Accept 也能命中，
            // 但显式写出来能让"这是一个 SSE 请求"在抓包里可见。
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .apply {
                // 服务端 `ReactiveJwtAuthFilter:64` 判的是**区分大小写**的 `"Bearer "` 前缀
                if (token != null) header("Authorization", "Bearer $token")
            }
            .build()

        val cancelled = AtomicBoolean(false)
        val source = factory.newEventSource(request, object : EventSourceListener() {

            override fun onOpen(eventSource: EventSource, response: Response) {
                if (cancelled.get()) return
                listener.onOpen()
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (cancelled.get()) return
                // `type` 是 SSE 的 `event:` 字段。服务端返回 `Flux<Map<String,Object>>`，
                // **不写 event 名**，事件种类在 JSON 的 `type` 字段里，所以这里忽略它。
                listener.onEvent(data)
            }

            override fun onClosed(eventSource: EventSource) {
                if (cancelled.get()) return
                listener.onClosed()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (cancelled.get()) return
                listener.onFailure(response?.code, describe(t, response))
            }
        })

        return object : SseTransport.Handle {
            override fun cancel() {
                // 先置位再 cancel：cancel() 可能同步触发 onFailure，顺序反了就会漏吞
                if (cancelled.compareAndSet(false, true)) source.cancel()
            }
        }
    }

    /**
     * 把异常/响应压成一句能进日志的话。
     *
     * ⚠ **绝不带响应体原文**：401 走 Spring Security 默认 entryPoint，响应体是空的；
     * 而反代出问题时响应体可能是一整页 HTML。
     */
    private fun describe(t: Throwable?, response: Response?): String = when {
        response != null -> "HTTP ${response.code}"
        t is IOException -> t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
        t != null -> t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
        else -> "未知原因"
    }
}
