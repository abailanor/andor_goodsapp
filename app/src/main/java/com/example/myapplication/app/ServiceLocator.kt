package com.example.myapplication.app

import android.content.Context
import com.example.myapplication.R
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.net.HttpClients
import com.example.myapplication.net.Service1Api
import com.example.myapplication.security.AndroidKeystoreSecretBox
import com.example.myapplication.security.CredentialStore
import com.example.myapplication.security.Credentials
import com.example.myapplication.security.DataStoreBlobStore
import com.example.myapplication.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 手写 ServiceLocator（**不引 DI 框架**，与项目现状一致）。
 *
 * 存在的唯一理由是：`SettingsStore`、`CredentialStore`、两个 `OkHttpClient`、`Service1Api`
 * 都必须是**全进程单例**，而且它们之间有构造顺序上的硬约束。散在各 ViewModel 里各自 new
 * 会撞上两类静默故障（都在下面的字段注释里写了）：
 *  - 对同一个文件开第二个 DataStore → `IllegalStateException`，且**在首次访问时才抛**；
 *  - 没等 `awaitLoaded()` 就发请求 → `baseUrl` 还是默认的 `10.0.2.2:8443`，
 *    用户看到的是"连接失败"，而他明明已经改过 IP。
 *
 * ## ⚠ 拿到的 [Context] 一律存 `applicationContext`
 *
 * 这个对象与进程同长。存 Activity 引用就是内存泄漏，而且是"转屏几次之后 OOM"那种迟发的。
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    /**
     * 与进程同长的 scope，给 [SettingsStore.start] 与后续的同步引擎用。
     *
     * `SupervisorJob`：一个子协程抛异常不能把整棵树带走 —— 那会让 SSE 的一次解析错误
     * 顺手取消掉设置监听，之后所有设置改动都不再落盘，且没有任何日志。
     *
     * ⚠ **永不取消**。`SettingsStore.start` 里 launch 的 collector 不会结束
     * （DataStore 的 `data` flow 不 complete），所以这个 scope 必须活到进程死。
     * 单测里不能把它换成 `runBlocking` 的 scope，会死锁（见 `SettingsStoreTest`）。
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 设置。**构造时就 `start`**，所以冷启动的磁盘读与 UI 构建是并行的，
     * 等到真正要用时（`api()`）通常已经就绪。
     *
     * 单例：见类注释里 DataStore 那条。
     */
    val settings: SettingsStore = SettingsStore.forApp(appContext).also { it.start(appScope) }

    val database: AppDatabase by lazy { AppDatabase.getDatabase(appContext) }

    val products: ProductRepository by lazy { ProductRepository(database.productDao()) }

    /**
     * 凭据读写。懒构造：它本身很便宜，但 `boxProvider` 会碰 AndroidKeyStore，
     * 而 Keystore 在少数 ROM 上首次访问要几十毫秒 —— 不该算进冷启动。
     *
     * 凭据文件与设置文件**分开**（`cred_blobs` vs `app_settings`），理由见
     * [DataStoreBlobStore] 的类注释。
     */
    val credentials: CredentialStore by lazy {
        CredentialStore(
            store = DataStoreBlobStore.forApp(appContext),
            boxProvider = { storeKey -> AndroidKeystoreSecretBox.getOrCreate(storeKey) },
        )
    }

    // ==================== 会话 ====================

    private val _session = MutableStateFlow<Credentials?>(null)

    /**
     * 当前登录会话，null = 未登录（**离线模式也算未登录**，见 [currentStoreKey]）。
     *
     * UI 观察它来决定显示哪些入口（权限驱动的界面）；[api] 的 `tokenProvider` 读它。
     */
    val session: StateFlow<Credentials?> = _session.asStateFlow()

    /** 登录成功后调。token 变了之后**下一次请求**就会带上新的（`tokenProvider` 每次现取）。 */
    fun setSession(creds: Credentials?) {
        _session.value = creds
    }

    /**
     * 当前作用域键 = Room 里所有表主键的第一列。
     *
     * 未登录时用保留值 [LOCAL_STORE_KEY]（方案 R2：用户可能先离线用几个月才买服务，
     * 所以 App 必须**无账号也能完整使用**）。首次登录成功后在"上线向导"里做一次性**收养**：
     * 一个事务把所有表的 storeKey 改写成真实 merchantCode，必须用户明确确认，不可逆。
     */
    fun currentStoreKey(): String = _session.value?.merchantCode ?: LOCAL_STORE_KEY

    /**
     * 文件归属者（店主用户名），镜像路径 `mirror/<merchantCode>/<ownerName>/...` 要用。
     *
     * ⚠ 它**不等于** merchantCode —— 这个混淆在 P0 期间真实造成过一次路径错误。
     * 未登录（离线模式）时退回 [LOCAL_STORE_KEY]，这样离线下单的镜像路径也是稳定的。
     */
    fun currentOwnerName(): String = _session.value?.ownerName ?: LOCAL_STORE_KEY

    // ==================== 网络 ====================

    /**
     * 只信任内置自签 CA 的 TLS 配置。**懒构造**，理由见 [api]。
     *
     * ⚠ 失败时**故意抛**，不退回系统 CA。退回等于悄悄放弃证书绑定，
     * 那会让"只有我们的私钥能给这个 IP 签证书"这条防线消失，而且用户毫不知情。
     */
    private val tls: HttpClients.PinnedTls by lazy {
        val pem = appContext.resources.openRawResource(R.raw.s1_ca).use { it.readBytes() }
        HttpClients.trustAnchorFromPem(pem)
    }

    /** 普通 API 的 client。与 [sseClient] 的超时/重试策略完全不同，绝不能共用。 */
    val apiClient by lazy { HttpClients.apiClient(tls) }

    /**
     * SSE 的 client（`readTimeout = 0` + `retryOnConnectionFailure(false)` + 30s ping）。
     *
     * 交给 P4 的 `SseSupervisor`。⚠ 那里**编译期不能拿到 `Service1Api` 的引用** ——
     * 否则"重连时顺手重新登录"就变得可能，而单会话策略下那会自己踢自己，
     * 形成每半小时一次的自杀循环。所以这里只暴露 client，不暴露 api。
     */
    val sseClient by lazy { HttpClients.sseClient(tls) }

    private val apiInstance by lazy {
        Service1Api(
            apiClient = apiClient,
            // 每次请求现取：用户在设置里改 IP 之后，已经发出去的引用也要立刻生效。
            baseUrlProvider = { settings.current().baseUrl },
            tokenProvider = { _session.value?.token },
        )
    }

    /**
     * 取 [Service1Api]。
     *
     * ## ⚠ 它是 `suspend` 的，这不是随手加的
     *
     * 里面 `settings.awaitLoaded()` 是**发任何网络请求之前的硬前置条件**：
     * 不等它，`baseUrl` 可能还是默认的 `10.0.2.2:8443`，请求打到不存在的地址上，
     * 用户看到的是"连接失败"，而他明明已经改过 IP —— 这类故障从错误信息上完全看不出原因。
     *
     * 把等待放在**获取 API 对象**这一步（而不是指望每个调用点记得先 await），
     * 就使得"拿到 api 却没等设置加载完"在类型上不可能：非挂起上下文里根本调不到它。
     * `awaitLoaded()` 幂等，首次之后只是 complete 过的 deferred 上的一次 `await`，近乎免费。
     *
     * ## ⚠ 为什么 TLS 也在这里才初始化
     *
     * [tls] 要读 `res/raw/s1_ca.pem` 并做 X.509 解析。放在 `Application.onCreate` 里
     * 等于给冷启动加一次主线程磁盘读；更要紧的是**万一那个文件坏了或缺了，App 会直接起不来**，
     * 而正确的表现是"能开、但一连服务器就报清楚的错"。所以推迟到第一次真的要联网时。
     *
     * @throws IllegalStateException CA 读不出来或不是 CA 证书。这是**打包/构建问题**，
     *         不是运行时状态，消息里直接写了要重跑 `tools/gen-cert.sh`。
     */
    suspend fun api(): Service1Api {
        settings.awaitLoaded()
        return withContext(Dispatchers.IO) { apiInstance }
    }

    companion object {
        /**
         * 离线模式（未登录）的 storeKey 保留值。
         *
         * ⚠ 真实的 merchantCode 是 5 位数字（`seed_admin.py` 里总部是 `00001`），
         * 所以带下划线的这个名字**不可能与任何真实店铺撞上**。
         */
        const val LOCAL_STORE_KEY = "__local__"
    }
}
