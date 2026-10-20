package com.example.myapplication.net

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 两个 OkHttpClient：**普通 API** 与 **SSE**，配置不同，绝不能共用。
 *
 * ## 为什么必须分开
 *
 * SSE 是长连接，服务端无事件时最多 60 秒才发一次心跳（`app.sync.heartbeat-seconds`）。
 * OkHttp 默认 `readTimeout` 是 **10 秒**，会在第一次心跳之前就掐断连接，
 * 表现为"连上了但一条消息都收不到，然后反复重连"（ANDROID.md §1.2）。
 * 所以 SSE 的 client 必须 `readTimeout = 0`（不限时）。
 *
 * 反过来，普通 API **绝不能** `readTimeout = 0`——那样一次服务端卡死就会永久挂住调用方。
 *
 * 两个 client **共享 ConnectionPool 与 Dispatcher**：在 HTTP/2 下这正是我们想要的，
 * 一条 TCP 连接同时承载 SSE 长流与零星 API 调用（README §9：HTTP/1.1 下每个客户端
 * 稳态要 2~3 条常驻连接，开了 HTTP/2 降到 1 条）。
 *
 * ## TLS：只信任内置的自签 CA
 *
 * 用户不购买任何服务，所以走"自签 CA + App 内置信任锚"（`tools/gen-cert.sh` 生成，
 * CA 证书在 `res/raw/s1_ca.pem`）。这等于**强制证书绑定**，比公共 CA 更严：
 * 公共 CA 体系里任何一家被攻破都能给你的 IP 签一张浏览器会信任的证书，
 * 而自签 CA 的私钥只在你手上。
 *
 * 信任锚挂在 **OkHttpClient 层**而不是只靠 `network_security_config.xml`，
 * 因为 NSC 是**安装期静态解析**的，跟不了用户在设置里改的服务器 IP；
 * 而 OkHttpClient 的 TrustManager 与 IP 无关——只要对方出示的证书能链到我们的 CA 就放行，
 * 主机名校验仍然由 OkHttp 默认的 HostnameVerifier 按证书 SAN 做。
 *
 * ⚠ 所以：用户在设置里把 IP 改成一个证书 SAN 里没有的地址时，TLS 会**明确失败**
 * 并报"证书与地址不匹配"。这是正确行为，不是 bug。换 IP 要重跑 `tools/gen-cert.sh`
 * 重签服务端证书（**CA 不变，App 不用重装**）。
 *
 * ## HTTP/2
 *
 * OkHttp 在 TLS 下通过 ALPN 自动协商 HTTP/2，`protocols` 用默认值即可（`[H2, HTTP_1_1]`）。
 * 服务端要配套 `server.http2.enabled=true` + `server.ssl.*`——Spring Boot 3.5.2 的
 * `NettyReactiveWebServerFactory.listProtocols()` 在**有 SSL 时选 `H2`、无 SSL 时选 `H2C`**，
 * 且总是同时保留 `HTTP11`（已反编译核实）。
 *
 * **不走明文 h2c**：那需要 `Protocol.H2_PRIOR_KNOWLEDGE`，而设了之后就**只**说 h2c、
 * 不再回退（服务端不支持时直接失败而不是降级）；Spring 那边是 HTTP11+H2C 的 Upgrade 语义，
 * 两者是否互通没实测过。既然本来就要 HTTPS，没必要冒这个险。
 */
object HttpClients {

    /** SSE 的 ping 间隔。HTTP/2 PING 帧只有 9+9 字节，比应用层心跳便宜一个数量级。 */
    private const val SSE_PING_INTERVAL_SECONDS = 30L

    /** 应用层心跳将改为 60 秒，客户端离线阈值 = 3× = 180 秒（ANDROID.md §5.1 的推理链）。 */
    const val IDLE_TIMEOUT_MILLIS = 180_000L

    /** (重)连后多久没收到任何消息就判定这条连接是坏的（ANDROID.md §5.2）。 */
    const val CONNECT_PROBE_TIMEOUT_MILLIS = 5_000L

    private val pool = ConnectionPool(8, 5, TimeUnit.MINUTES)

    /**
     * 从 PEM 字节构造"只信任这一张 CA"的 SSL 配置。
     *
     * 纯 JVM，不依赖 Android —— 所以能在单元测试里验证（见 `HttpClientsTest`）。
     */
    fun trustAnchorFromPem(pem: ByteArray): PinnedTls {
        val cf = CertificateFactory.getInstance("X.509")
        val ca = cf.generateCertificate(ByteArrayInputStream(pem)) as X509Certificate
        require(ca.basicConstraints >= 0) {
            "res/raw/s1_ca.pem 不是一张 CA 证书（basicConstraints 缺失或 CA:FALSE）。" +
                "重跑 tools/gen-cert.sh。"
        }

        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("s1_ca", ca)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(ks)
        }
        // 只取我们这一个 TrustManager。**不要**把系统默认的也加进来 ——
        // 加了就等于放弃证书绑定，任何公共 CA 签的证书都会被接受。
        val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().singleOrNull()
            ?: throw IllegalStateException("TrustManagerFactory 没有产出 X509TrustManager")
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return PinnedTls(ctx.socketFactory, tm)
    }

    /** [trustAnchorFromPem] 的结果。 */
    class PinnedTls(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    /**
     * 普通 API 用的 client。
     *
     * `retryOnConnectionFailure(true)`：普通请求是幂等的读、或调用方自己会处理重复的写，
     * OkHttp 的连接级重试（换一条连接重发）在这里是净收益。
     * ⚠ 但**订单上传**不能靠它重试——见 `OutboxStore` 的 IN_FLIGHT write-ahead 说明。
     */
    fun apiClient(tls: PinnedTls): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(pool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .sslSocketFactory(tls.socketFactory, tls.trustManager)
        .protocols(protocols)
        .build()

    /**
     * SSE 用的 client。
     *
     * - `readTimeout = 0`：长连接必需，默认 10 秒会在第一次心跳前掐断
     * - `retryOnConnectionFailure(false)`：**重连由 SseSupervisor 自己控制**
     *   （指数退避 + 抖动 + KICKED 时不重连）。让 OkHttp 偷偷重试会绕过那套逻辑，
     *   尤其是绕过"收到 KICKED 就停止自动重连"——那会和新登录方形成互踢风暴。
     * - `pingInterval`：HTTP/2 PING 做传输层保活。它比应用层心跳便宜得多（9+9 字节），
     *   而且 ping 超时时 OkHttp 会**主动**断开触发我们的重连，
     *   所以应用层心跳可以放心拉到 60 秒。
     * - `writeTimeout` 保留：SSE 的请求体是空的，但握手阶段仍要写。
     */
    fun sseClient(tls: PinnedTls): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(pool)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)          // 0 = 不限时，SSE 必需
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(SSE_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .sslSocketFactory(tls.socketFactory, tls.trustManager)
        .protocols(protocols)
        .build()

    /**
     * 支持的协议，按**优先级降序**（OkHttp 要求列表里必须含 `HTTP_1_1`，否则
     * `Builder.protocols()` 直接抛 `IllegalArgumentException`）。
     *
     * 显式写出来并**真的挂到两个 client 上**，是为了让"我们指望 ALPN 协商到 h2"这件事可见：
     * 它恰好等于 OkHttp 的默认值，所以这行代码是零行为改动 —— 但少了它，
     * "HTTP/2 是设计意图"就只存在于注释里，将来谁动了默认值也不会有任何测试变红。
     *
     * 已实测：service1（Spring Boot 3.5.2 + `server.ssl.*` + `server.http2.enabled=true`）
     * 在 8443 上与 Java TLS 客户端 ALPN 协商到 **HTTP_2**（`H2Probe`，2026-10-19）。
     * 方案里"未验证假设 #2"到此闭合。
     */
    val protocols: List<Protocol> = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
}
