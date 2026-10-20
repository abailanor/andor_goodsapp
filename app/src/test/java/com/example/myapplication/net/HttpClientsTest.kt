package com.example.myapplication.net

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * TLS 证书绑定与双 client 配置的测试。
 *
 * 证书绑定这件事，**只测正面通过等于没测**——握手成功可能只是因为根本没在验。
 * 所以这里必须有一条负面测试：服务端出示一张不是我们 CA 签的证书，客户端必须拒绝。
 */
class HttpClientsTest {

    private lateinit var tls: HttpClients.PinnedTls

    /**
     * 找到 pki / res 文件。
     *
     * Gradle 跑 JVM 单测时工作目录是模块目录（`app/`），所以 `src/main/...` 与 `../tools/...`
     * 都能命中；但 Android Studio 的运行配置有时用项目根目录，所以两边都试一遍。
     */
    private fun resolve(vararg rel: String): File {
        for (p in rel) {
            val f = File(p)
            if (f.exists()) return f
        }
        throw IllegalStateException(
            "找不到 ${rel.toList()} 中的任何一个。先跑 tools/gen-cert.sh 生成 CA 与服务端证书。"
        )
    }

    private fun caPem(): ByteArray =
        resolve("src/main/res/raw/s1_ca.pem", "app/src/main/res/raw/s1_ca.pem", "../tools/pki/ca.pem")
            .readBytes()

    private fun serverP12(): File = resolve("../tools/pki/server.p12", "tools/pki/server.p12")

    private fun serverPem(): ByteArray =
        resolve("../tools/pki/server.pem", "tools/pki/server.pem").readBytes()

    @Before
    fun setUp() {
        tls = HttpClients.trustAnchorFromPem(caPem())
    }

    private val server = MockWebServer()

    /**
     * 显式构造 URL，**不要**用 `MockWebServer.url()`。
     *
     * ⚠ 环境陷阱（本机实测踩过）：`MockWebServer.url()` 内部对绑定地址做
     * `InetAddress.getHostName()`，也就是**反向 DNS 查询**。这台机器的 DNS
     * （52.80.60.30）对 `127.0.0.1` 的 PTR 查询返回了 `bbs.localhost.com`，
     * 于是 OkHttp 拿这个名字去校验证书 SAN，握手在最后一步失败：
     * `SSLPeerUnverifiedException: Hostname bbs.localhost.com not verified`。
     *
     * 注意那时**信任链已经验证通过了**（证书是我们的 CA 签的），失败纯粹在主机名。
     * 正常机器上 127.0.0.1 反向解析成 localhost，所以这个坑只在这类被 DNS 劫持的环境出现。
     *
     * 用 `localhost` 而不是 `127.0.0.1`：证书 SAN 里两者都有（`DNS:localhost` 与 `IP:127.0.0.1`），
     * 但 `localhost` 走 DNS SAN 分支，读起来更接近真实部署。
     */
    private fun url(path: String): String {
        require(server.port > 0) { "先 server.start() 再取 port" }
        return "https://localhost:" + server.port + path
    }

    @After
    fun tearDown() = server.shutdown()

    // ==================== 信任锚 ====================

    @Test
    fun `信任锚里只有我们这一张 CA_没有混入系统 CA`() {
        // 这是整个证书绑定的核心属性。若不小心把系统默认 TrustManager 也加进来，
        // 任何公共 CA 签的证书都会被接受，绑定就形同虚设——而且不会有任何报错。
        val issuers = tls.trustManager.acceptedIssuers
        assertEquals("必须恰好一个信任锚", 1, issuers.size)
        val expected = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(caPem().inputStream()) as X509Certificate
        assertEquals(expected, issuers.single())
    }

    @Test
    fun `信任锚接受由它签发的服务端证书链`() {
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val serverCert = cf.generateCertificate(serverPem().inputStream()) as X509Certificate
        val caCert = cf.generateCertificate(caPem().inputStream()) as X509Certificate
        assertEquals("gen-cert.sh 生成的是 RSA 密钥", "RSA", serverCert.publicKey.algorithm)
        // 不抛就是通过
        tls.trustManager.checkServerTrusted(arrayOf(serverCert, caCert), "RSA")
    }

    @Test
    fun `把叶子证书当 CA 装进去必须被拒绝`() {
        // server.pem 是 CA:FALSE 的叶子证书。若 basicConstraints 检查缺失，
        // 拿它当信任锚会得到一个"什么都不信任"或行为诡异的 TrustManager。
        try {
            HttpClients.trustAnchorFromPem(serverPem())
            fail("叶子证书不该被当成 CA 接受")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("CA 证书"))
        }
    }

    @Test
    fun `垃圾 PEM 会报错而不是静默生成一个空的信任库`() {
        for (bad in listOf("not a pem".toByteArray(), ByteArray(0), "-----BEGIN CERTIFICATE-----\nZZZ\n-----END CERTIFICATE-----".toByteArray())) {
            try {
                HttpClients.trustAnchorFromPem(bad)
                fail("垃圾输入应当抛异常")
            } catch (e: Exception) {
                assertNotNull(e)
            }
        }
    }

    // ==================== 双 client 配置 ====================

    @Test
    fun `SSE 的 readTimeout 必须是 0`() {
        // OkHttp 默认 10 秒，会在第一次心跳（60 秒）之前就掐断连接，
        // 表现为"连上了但一条消息都收不到，然后反复重连"（ANDROID.md §1.2）。
        val c = HttpClients.sseClient(tls)
        assertEquals(0, c.readTimeoutMillis)
    }

    @Test
    fun `普通 API 的 readTimeout 不能是 0`() {
        // 反过来，API 若 readTimeout=0，一次服务端卡死就会永久挂住调用方。
        val c = HttpClients.apiClient(tls)
        assertTrue("readTimeout 应当是有限的，实际 ${c.readTimeoutMillis}", c.readTimeoutMillis > 0)
        assertEquals(30_000, c.readTimeoutMillis)
    }

    @Test
    fun `SSE 必须关掉 OkHttp 的自动重试`() {
        // 重连由 SseSupervisor 控制（指数退避 + 抖动 + 收到 KICKED 就停止重连）。
        // 让 OkHttp 偷偷重试会绕过那套逻辑，尤其绕过"KICKED 后不重连"——
        // 那会和新登录方形成互踢风暴。
        assertFalse(HttpClients.sseClient(tls).retryOnConnectionFailure)
        // 普通 API 保留连接级重试（换一条连接重发）是净收益
        assertTrue(HttpClients.apiClient(tls).retryOnConnectionFailure)
    }

    @Test
    fun `SSE 配了 HTTP2 ping 做传输层保活`() {
        // PING 帧只有 9+9 字节，比应用层心跳便宜一个数量级；
        // 且 ping 超时时 OkHttp 会主动断开触发重连，所以应用层心跳可以放心拉到 60 秒。
        assertEquals(
            TimeUnit.SECONDS.toMillis(30),
            HttpClients.sseClient(tls).pingIntervalMillis.toLong()
        )
        assertEquals("普通 API 不需要 ping", 0, HttpClients.apiClient(tls).pingIntervalMillis)
    }

    @Test
    fun `两个 client 共享连接池`() {
        // HTTP/2 下这正是想要的：一条 TCP 同时承载 SSE 长流与零星 API 调用。
        assertSame(HttpClients.apiClient(tls).connectionPool, HttpClients.sseClient(tls).connectionPool)
    }

    @Test
    fun `协议列表包含 HTTP2 与 HTTP1_1`() {
        assertEquals(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1), HttpClients.protocols)
    }

    @Test
    fun `两个 client 真的挂上了那份协议列表`() {
        // 上一条只断言了常量本身，是句同义反复。这条才是要点：常量必须**被用掉**。
        // 少了 `.protocols(...)` 也能编译、也能跑（OkHttp 的默认值恰好一样），
        // 但"HTTP/2 是设计意图"就只存在于注释里 —— 将来默认值一变，没有任何测试会红。
        assertEquals(HttpClients.protocols, HttpClients.apiClient(tls).protocols)
        assertEquals(HttpClients.protocols, HttpClients.sseClient(tls).protocols)
    }

    @Test
    fun `离线阈值与探活窗口是文档要求的值`() {
        // 心跳 60 秒 → 客户端 3× = 180 秒判离线（ANDROID.md §5.1 的推理链）
        assertEquals(180_000L, HttpClients.IDLE_TIMEOUT_MILLIS)
        // 服务端建连时必发一条 → 5 秒没收到就说明这条连接是坏的（ANDROID.md §5.2）
        assertEquals(5_000L, HttpClients.CONNECT_PROBE_TIMEOUT_MILLIS)
    }

    // ==================== TLS 往返 ====================

    private fun useOurServerCert() {
        val pass = "changeit".toCharArray()
        val ks = KeyStore.getInstance("PKCS12").apply { load(serverP12().inputStream(), pass) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(ks, pass) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        server.useHttps(ctx.socketFactory, false)
        server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
    }

    @Test
    fun `服务端出示我们的证书时握手成功_且能协商到 HTTP2`() {
        useOurServerCert()
        server.enqueue(MockResponse().setBody("""{"success":true,"message":"ok","data":null}"""))
        server.start()

        val client = HttpClients.apiClient(tls)
        val req = Request.Builder().url(url("/api/products/list")).build()
        client.newCall(req).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("""{"success":true,"message":"ok","data":null}""", resp.body?.string())
            // 这条同时验证了"TLS + ALPN 能协商到 h2"（方案里的未验证假设 #2）。
            // MockWebServer 与 Reactor Netty 的 ALPN 实现不同，所以这只是旁证；
            // 真服务端的确认要在 P4 用 `curl --http2 -v` 看 `ALPN: server accepted h2`。
            assertEquals("ALPN 应当协商到 HTTP/2，实际 ${resp.protocol}", Protocol.HTTP_2, resp.protocol)
        }
    }

    @Test
    fun `服务端出示无关的自签证书时握手必须失败`() {
        // ⚠ 这是证书绑定唯一真正有意义的测试。
        // 没有它，"握手成功"可能只是因为客户端根本没在验证。
        val held = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(held).build()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()

        val client = HttpClients.apiClient(tls)
        val req = Request.Builder().url(url("/api/products/list")).build()
        try {
            client.newCall(req).execute().use { fail("不该握手成功：这张证书不是我们的 CA 签的") }
        } catch (e: IOException) {
            // 期望：SSLHandshakeException / CertificateException 之类
            assertTrue("应当是证书校验失败，实际 ${e.javaClass.name}: ${e.message}",
                e.toString().contains("SSL") || e.toString().contains("Certificate") ||
                    (e.cause?.toString()?.contains("Certificate") == true))
        }
    }

    @Test
    fun `走完整 OkHttp 路径的错误分类_429 与 500 追踪号`() {
        useOurServerCert()
        server.enqueue(MockResponse().setResponseCode(429)
            .setBody("""{"success":false,"message":"登录操作过于频繁，请稍后再试"}"""))
        server.enqueue(MockResponse().setResponseCode(500)
            .setBody("""{"success":false,"message":"服务器内部错误（追踪号 9f3ab12c，请提供给运维查日志）"}"""))
        server.start()

        val client = HttpClients.apiClient(tls)

        client.newCall(Request.Builder().url(url("/api/auth/login")).build())
            .execute().use { resp ->
                val e = ApiErrorClassifier.classify(resp.code, resp.body?.string())
                assertTrue("429 必须分类成 RateLimited 以便调用方拒绝自动重试，实际 $e",
                    e is ApiError.RateLimited)
            }
        client.newCall(Request.Builder().url(url("/api/products/list")).build())
            .execute().use { resp ->
                val e = ApiErrorClassifier.classify(resp.code, resp.body?.string()) as ApiError.ServerError
                assertEquals("9f3ab12c", e.traceId)
            }
    }

    @Test
    fun `401 空响应体能被正确分类为 token 过期`() {
        // SecurityConfig 没有自定义 authenticationEntryPoint → 401 带空 body。
        // 这是最常见的路径（20 小时到期 / 每月密钥轮换），必须能识别成"可以自动重登"。
        useOurServerCert()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()

        HttpClients.apiClient(tls)
            .newCall(Request.Builder().url(url("/api/products/list")).build())
            .execute().use { resp ->
                assertEquals(401, resp.code)
                val body = resp.body?.string()
                assertTrue("401 的 body 应当是空的，实际 ${body?.length}", body.isNullOrEmpty())
                val e = ApiErrorClassifier.classify(resp.code, body) as ApiError.Unauthorized
                assertEquals(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, e.reason)
                assertTrue(ApiErrorClassifier.mayAutoRelogin(e.reason))
            }
    }

    @Test
    fun `SSE client 也能完成 TLS 握手`() {
        // SSE 与 API 是两个不同的 OkHttpClient 实例，各自都要挂上 TrustManager，
        // 漏一个的话 SSE 会握手失败，表现为"REST 正常但收不到任何推送"。
        useOurServerCert()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"type\":\"HEARTBEAT\"}\n\n"))
        server.start()

        val resp = HttpClients.sseClient(tls)
            .newCall(
                Request.Builder().url(url("/api/sync/subscribe?priceVersion=0&stockVersion=0"))
                    .header("Accept", "text/event-stream").build()
            ).execute()
        resp.use {
            assertEquals(200, it.code)
            assertTrue(it.body?.string()?.contains("HEARTBEAT") == true)
        }
    }

    // 注：这里**不**测"明文请求会被拒绝"。禁明文是 network_security_config.xml
    // （Android 平台层）的约束，OkHttp 在 JVM 上并不拦 http:// —— 写一条永远通过的测试
    // 比不写更糟，它会让人以为这条防线被测过了。禁明文的验证放到 P6 的 instrumented
    // 测试里（真机上发一个 http:// 请求，断言抛 CLEARTEXT not permitted）。
}
