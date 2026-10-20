import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/**
 * 用**只信任内置自签 CA**的 Java TLS 客户端，验证 service1 到底协商到了 HTTP/几。
 *
 * ## 为什么需要这么个东西，而不是 `curl --http2 -v`
 *
 * 方案（§六、§八 假设 #2）原本写的是拿 curl 看 `ALPN: server accepted h2`。
 * **这台机器上的 curl 做不到**：Windows 自带的是 schannel 构建，直接报
 * `option --http2: the installed libcurl version does not support this`。
 * 而且 schannel 还会对自签 CA 做吊销检查，不给 `--ssl-no-revoke` 就报
 * `CERT_TRUST_REVOCATION_STATUS_UNKNOWN`（curl 退出码 60）——
 * 那是 curl 的问题，**不是**我们的 CA 有问题，别被它误导去重签证书。
 *
 * OkHttp 同样是 Java 的 TLS 栈，所以 JDK 的 `HttpClient` 是这里最接近它的替身：
 * 它能证明的是"**服务端**在 TLS 下通过 ALPN 提供 h2"，而客户端侧的 h2 由
 * `HttpClientsTest` 的「服务端出示我们的证书时握手成功_且能协商到 HTTP2」覆盖
 * （那条用 MockWebServer + 真实的 `tools/pki/server.p12`）。两条合起来才是端到端。
 *
 * ## 用法
 *
 * ```
 * cd C:/Users/hhl/AndroidStudioProjects/MyApplication
 * java tools/H2Probe.java tools/pki/ca.pem https://localhost:8443/api/products/list
 * ```
 *
 * JDK 11+ 的单文件源码启动，不需要编译。`401` + 空 body 是**期望结果**
 * （没带 token；`SecurityConfig` 没有自定义 entryPoint，401 不带 JSON 信封）。
 *
 * ## 2026-10-19 的实测结果
 *
 * ```
 * negotiated = HTTP_2
 * status     = 401
 * body[0..80]=
 * ```
 *
 * 顺带证实了 `ApiError.kt` 里那条注释：401 的响应体确实是空的，
 * 所以"token 过期"这条最常见的路径上没有 message 可分类，只能落到
 * `UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED`。方案里的假设 #2 到此闭合。
 */
public class H2Probe {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: java H2Probe.java <ca.pem> <https-url>");
            System.exit(2);
        }

        // 只装我们这一张 CA。**不要**把系统默认的 TrustManager 也加进来 ——
        // 加了就等于放弃证书绑定，与 HttpClients.trustAnchorFromPem 的行为不一致，
        // 验出来的结果也就不再代表 App 的行为。
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate ca;
        try (InputStream in = Files.newInputStream(Paths.get(args[0]))) {
            ca = (X509Certificate) cf.generateCertificate(in);
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("s1_ca", ca);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);

        // HTTP_2 = "优先 h2，ALPN 谈不成就退回 HTTP/1.1"，与 OkHttp 的 protocols 列表同语义。
        // 所以 negotiated 是 HTTP_1_1 时说明**服务端没开 h2**，不是客户端不会说。
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(ctx)
                .build();

        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder(URI.create(args[1])).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        System.out.println("negotiated = " + resp.version());
        System.out.println("status     = " + resp.statusCode());
        String body = resp.body();
        System.out.println("body[0..80]= " + body.substring(0, Math.min(80, body.length())).replace("\n", " "));
        System.out.println("h2         = " + (resp.version() == HttpClient.Version.HTTP_2));
    }
}
