package com.example.myapplication.app

import android.content.Context
import com.example.myapplication.R
import com.example.myapplication.core.lock.StripedMutex
import com.example.myapplication.core.money.Money
import com.example.myapplication.core.time.AndroidMonotonicClock
import com.example.myapplication.data.AdoptionTx
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.HotspotMerger
import com.example.myapplication.data.HotspotSyncService
import com.example.myapplication.data.OrderRepository
import com.example.myapplication.data.PriceDrift
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.SessionRepository
import com.example.myapplication.data.SessionStore
import com.example.myapplication.data.StatsRepository
import com.example.myapplication.db.CurrentStore
import com.example.myapplication.db.StoreMeta
import com.example.myapplication.log.ChangeLog
import com.example.myapplication.mirror.MirrorCommitter
import com.example.myapplication.mirror.MirrorLocator
import com.example.myapplication.mirror.MirrorVerifier
import com.example.myapplication.mirror.RoomMirrorStateStore
import com.example.myapplication.net.AuthApi
import com.example.myapplication.net.HttpClients
import com.example.myapplication.net.OrderFileApi
import com.example.myapplication.net.ProductApi
import com.example.myapplication.net.Service1Api
import com.example.myapplication.net.dto.OrderSyncRequestDto
import com.example.myapplication.net.dto.ProductAddRequestDto
import com.example.myapplication.net.sse.OkHttpSseTransport
import com.example.myapplication.net.sse.SseSupervisor
import com.example.myapplication.security.AndroidKeystoreSecretBox
import com.example.myapplication.security.CredentialStore
import com.example.myapplication.security.Credentials
import com.example.myapplication.security.DataStoreBlobStore
import com.example.myapplication.settings.SettingsStore
import com.example.myapplication.sync.OrderFileSyncEngine
import com.example.myapplication.sync.ProductSyncEngine
import com.example.myapplication.sync.StockLedger
import com.example.myapplication.sync.SyncScheduler
import com.example.myapplication.sync.outbox.OutboxStore
import com.example.myapplication.sync.outbox.OutboxUploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.TimeZone
import java.util.UUID

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
     * 当前 storeKey 的 `store_meta` 内存副本。由 [startWatchers] 启动的 collector 维护。
     *
     * 两个消费者，都因为"**必须同步读**"才需要这份副本：
     *  - [currentOwnerName]：拼 fileId 与镜像路径，调用点在同步流程深处，不能挂起；
     *  - [sseSupervisor] 的 `urlProvider`：每次重连现取 `priceVersion`/`stockVersion`。
     *
     * 副本落后一点是**安全**的：版本号偏小只会让服务端多发一次全量快照，不会算错任何东西。
     * 反过来若为了"精确"而在这些地方 `runBlocking` 查库，就会在重连风暴里把线程堵死。
     */
    private val currentMeta = MutableStateFlow<StoreMeta?>(null)

    /**
     * 启动跟随作用域的观察者。**在 `Application.onCreate` 里调一次。**
     *
     * 不放在构造器或某个 `by lazy` 里，是因为它一启动就会打开数据库，
     * 而 `onCreate` 跑在主线程上、耗时直接算进冷启动（见 [App] 的类注释）。
     * `launch` 本身不阻塞，Room 的打开发生在 `appScope` 的后台线程上。
     *
     * 跟随 [CurrentStore.key] 自动重查：换店登录后游标与 `ownerName` 都换成另一家店的。
     */
    fun startWatchers() {
        appScope.launch {
            CurrentStore.key
                .flatMapLatest { storeMetaDao.observe(it) }
                .collect { currentMeta.value = it }
        }
    }

    /**
     * 当前登录会话，null = 未登录（**离线模式也算未登录**，见 [currentStoreKey]）。
     *
     * UI 观察它来决定显示哪些入口（权限驱动的界面）；[api] 的 `tokenProvider` 读它。
     */
    val session: StateFlow<Credentials?> = _session.asStateFlow()

    /**
     * 登录成功后调（登出调 `setSession(null)`）。
     * token 变了之后**下一次请求**就会带上新的（`tokenProvider` 每次现取）。
     *
     * ## ⚠ 会话与作用域是**两件事**，登出只清前者
     *
     * 此前 [CurrentStore] 的 `set()` 与 `resetToLocal()` 全仓**零调用方**，而
     * `ProductRepository` 是靠观察 `CurrentStore.key` 做 `flatMapLatest` 重查的 ——
     * 换账号登录后商品列表根本不会重查。所以登录时必须在这里一起改作用域，
     * 不给调用方留下"只改了一个"的机会。
     *
     * 但**登出绝不 reset 作用域**：登出之后新产生的离线数据仍然归**原来那家店**。
     * 理由是这些数据最终要上传到那家店的订单文件里，而上传需要那家店的 token；
     * 若登出就切回 [LOCAL_STORE_KEY]，这些单会变成"无主数据"——
     * 下一家登录的店会把它们收养走（串店），或者永远躺在 `__local__` 下没人上传。
     *
     * `__local__` 因此只有一个含义：**这台设备从没成功登录过**。
     */
    fun setSession(creds: Credentials?) {
        _session.value = creds
        if (creds != null) CurrentStore.set(creds.merchantCode)
    }

    /**
     * 显式切换数据作用域。**只有两个调用点**：冷启动恢复上次的店铺、以及用户在设置里换店登录。
     *
     * 与 [setSession] 分开是因为"切作用域"与"拿到 token"是两件事：
     * 冷启动时可能根本没有 token（离线模式），但作用域必须落到上次那家店，
     * 否则界面上显示的就是 `__local__` 的空数据。
     */
    fun switchStore(storeKey: String) {
        CurrentStore.set(storeKey)
    }

    /**
     * 当前作用域键 = Room 里所有 22 张表主键的第一列。
     *
     * **读的是 [CurrentStore]**，不是 `_session`：作用域只能有一个来源，
     * 否则就会出现上面 [setSession] 注释里描述的那种分叉。
     *
     * 从没登录过时是保留值 [LOCAL_STORE_KEY]（方案 R2：用户可能先离线用几个月才买服务，
     * 所以 App 必须**无账号也能完整使用**）。首次登录成功后做一次性**收养**
     * （[com.example.myapplication.data.AdoptionTx]）：一个事务把所有表的 storeKey
     * 改写成真实 merchantCode，不可逆。
     */
    fun currentStoreKey(): String = CurrentStore.now

    /**
     * 文件归属者（店主用户名），镜像路径 `mirror/<merchantCode>/<ownerName>/...` 与
     * fileId（`B:<ownerName>:<年>-<月>`）都要用它。
     *
     * ⚠ 它**不等于** merchantCode，也**不等于** [Credentials.username]（员工登录时是员工名）。
     * 这个混淆在 P0 期间真实造成过一次路径错误，而现在服务端在登录响应里回传了权威的
     * `ownerName`（见 `LoginResponseDto.fileOwnerOrNull`）。
     *
     * ## 三级回退，顺序不能换
     *
     *  1. 当前会话的 `ownerName` —— 登录成功时服务端给的权威值；
     *  2. `store_meta` 里存的那份 —— **登出之后**走这里。会话已经没了，
     *     但作用域还停在这家店，离线下单仍然要落进正确的镜像文件；
     *  3. [LOCAL_STORE_KEY] —— 从没登录过。
     *
     * ⚠ 少了第 2 级的后果很具体：登出后开的单会落进 `B:__local__:<年>-<月>`，
     * 而它**照样能同步**（服务端按需建文件），于是那些订单只有这台设备看得到，
     * 重新登录后也找不回来 —— 两边都不报错。
     */
    fun currentOwnerName(): String =
        _session.value?.ownerName
            ?: currentMeta.value?.ownerName?.takeIf { it.isNotBlank() }
            ?: LOCAL_STORE_KEY

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

    /**
     * 本机设备 id。**安装后第一次访问时生成，之后永久不变。**
     *
     * ## 用来干什么
     *
     * 标 outbox 行的**出处**（`order_outbox.originDevice`）。离线时两台设备通过热点
     * 互相交换未上传的订单，交换之后双方手上都有同一批 uuid；
     * 上线时若两边都自动上传，服务端不查重（`order_write.lua` 里没有任何去重）→
     * 同一个 uuid 在文件里出现两行、**库存扣两次**，而镜像比服务器长、
     * 此后所有偏移静默错位。
     *
     * 有了出处就能定一条简单规则：**只自动上传本机创建的单**，
     * 别的设备传来的单在「订单」页单独列出来、只有用户明确勾选时才上传（接管）。
     * 正常情况下创建者自己会上传它，接管只用于"那台设备再也不会开机了"。
     *
     * ## ⚠ 为什么用 SharedPreferences 而不是 DataStore
     *
     * `OutboxStore.enqueue` 在写行的那一刻就需要这个值，而 DataStore 的读是**挂起**的
     * （`SettingsStore` 要先 `awaitLoaded()`）。让开单路径去等一个异步设置加载，
     * 就等于给"按下上传"这件事加了一个可能挂住的前置条件。
     * SharedPreferences 的首次读虽然也碰磁盘，但只有几十字节、且是 `by lazy`，
     * 第一次真正开单时才发生。
     *
     * ## ⚠ 重装会变
     *
     * 卸载重装会生成新 id，于是旧 id 名下那些没传出去的单会变成"来自其他设备"、
     * 不再自动上传。这是**可接受**的：用户能在「订单」页看见它们并手动接管，
     * 而如果为了"稳定"去用 ANDROID_ID 之类的硬件标识，换机/恢复出厂时会静默串号 ——
     * 那比多一次手动接管糟得多。
     */
    val deviceId: String by lazy {
        val prefs = appContext.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    // ==================== 网络接口的转发层 ====================

    /**
     * 为什么要转发一层，而不是把 [apiInstance] 直接交给同步引擎。
     *
     * `apiInstance` 的构造会连带触发 [tls] → 读 `res/raw/s1_ca.pem` → 做 X.509 解析，
     * 而那个文件坏了或缺了会**抛**（[api] 的注释里写了这是刻意的，不退回系统 CA）。
     * 若引擎直接持有 `apiInstance`，那么"打开设置页看一眼同步状态"这种**纯本地**动作
     * 就会在 CA 坏掉时把 App 打崩 —— 恰好是 [api] 专门要避免的情形
     * （"能开、但一连服务器就报清楚的错"）。
     *
     * 转发之后，CA 的解析被推迟到**真的发请求那一刻**，异常也落在那次调用的错误处理里。
     */
    private val orderFileApi: OrderFileApi = object : OrderFileApi {
        override suspend fun addOrder(type: String, year: Int, month: Int?, payloadJson: String) =
            apiInstance.addOrder(type, year, month, payloadJson)

        override suspend fun syncOrderFile(
            request: OrderSyncRequestDto,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ) = apiInstance.syncOrderFile(request, onFull)

        override suspend fun probeAfter409(
            type: String,
            year: Int,
            month: Int?,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ) = apiInstance.probeAfter409(type, year, month, onFull)
    }

    private val authApi: AuthApi = object : AuthApi {
        override suspend fun login(username: String, password: String, priceVersion: Int, stockVersion: Int) =
            apiInstance.login(username, password, priceVersion, stockVersion)

        override suspend fun loginEmployee(
            merchantCode: String,
            employeeUsername: String,
            password: String,
            priceVersion: Int,
            stockVersion: Int,
        ) = apiInstance.loginEmployee(merchantCode, employeeUsername, password, priceVersion, stockVersion)
    }

    private val productApi: ProductApi = object : ProductApi {
        override suspend fun getProduct(barcode: String) = apiInstance.getProduct(barcode)
        override suspend fun batchUpsertProducts(products: List<ProductAddRequestDto>) =
            apiInstance.batchUpsertProducts(products)

        override suspend fun deleteProduct(barcode: String) = apiInstance.deleteProduct(barcode)
    }

    // ==================== 目录 ====================
    //
    // 四个根目录**必须分开**，混用会造成不可逆的数据损坏：
    //  - `mirror-tmp` 是 [MirrorVerifier] 下载服务器副本的地方。它与活动镜像同目录的话，
    //    一次校验中途被杀就会留下半个文件，而下一次校验可能拿它当"服务器副本"去比对；
    //  - `mirror-quarantine` 存的是 DIVERGED 事故的**唯一物证**。删了就查不出原因，
    //    所以它不与任何东西共用，也不该被任何"清理缓存"顺手扫掉；
    //  - `mirror-archive` 是归档月份的镜像。归档只是"不再同步"，**不是"不再统计"**，
    //    所以它绝不能与 tmp 混在一起被清掉。

    private val files: File get() = appContext.filesDir

    val mirrorRoot: File by lazy { File(files, "mirror") }
    val mirrorArchiveRoot: File by lazy { File(files, "mirror-archive") }
    val mirrorQuarantineRoot: File by lazy { File(files, "mirror-quarantine") }
    val mirrorTmpRoot: File by lazy { File(files, "mirror-tmp") }

    // ==================== 共享的时钟 / 时区 / 审计 ====================

    /**
     * 墙钟。**全进程只此一份**，传给每一个需要 `now` 的组件。
     *
     * 各自 `{ System.currentTimeMillis() }` 在功能上等价，但一旦将来要做"按同一时刻对账"
     * （例如 outbox 的 `pricedAt` 与统计的 dimKey 必须同源），两份 lambda 就没法保证一致了。
     */
    val now: () -> Long = { System.currentTimeMillis() }

    /**
     * 时区。**全进程只此一份**，理由比 [now] 更硬：
     *
     * `stats_contrib` 的 `dimKey` 里含日期，而日期按当时设备的时区算。
     * `OUTBOX` 桶由 [outboxStore] 加、由 [mirrorStateStore] 减 —— 两侧若拿到不同的
     * `timeZone` provider，同一笔钱就会加在一个 dimKey 上、减在另一个上：
     * 旧键留下一笔搬不走的钱、新键多出一笔负数，而**两边各自看都是对的**。
     *
     * ⚠ 用户在运行期间改系统时区仍然会造成这个问题（旧时区没有被存下来）。
     * `StatsProjection` 的类注释已把"改时区必须重投影"列为全局约束，设置页必须写明。
     */
    val timeZone: () -> TimeZone = { TimeZone.getDefault() }

    /**
     * 变更日志，同时是所有组件 `audit` 回调的归宿。
     *
     * ⚠ 接线之前，每个类的 `audit: (String) -> Unit` 都还是默认的空实现 `{}` ——
     * 也就是说 P0-P5 里所有"大声记审计"的地方**全部在往地上丢**。
     * 那些审计恰恰是唯一能发现静默损坏的线索（DIVERGED、FULL 行数对不上、
     * 库存对账偏低、偏移交叉校验失败……），丢掉它们等于把预警系统关掉。
     */
    val changeLog: ChangeLog by lazy {
        ChangeLog(
            dirProvider = { File(files, "logs") },
            scope = appScope,
            now = now,
            timeZone = timeZone,
        )
    }

    /** 按子系统分 tag，否则变更日志里 400 行全叫 "SYNC" 根本没法读。 */
    private fun audit(tag: String): (String) -> Unit = { changeLog.log(tag, it) }

    // ==================== DAO ====================

    val productDao by lazy { database.productDao() }
    val storeMetaDao by lazy { database.storeMetaDao() }
    val stockDao by lazy { database.stockDao() }
    val orderFileDao by lazy { database.orderFileDao() }

    // ==================== 镜像层 ====================

    /**
     * 分段锁。**必须全进程一份**：`MirrorCommitter` 与 `MirrorVerifier` 要靠它互斥
     * （校验的三条硬规则之一就是"持同一把分段锁"）。
     * 各建一个的话，校验与提交就能同时写同一个镜像文件。
     */
    val locks: StripedMutex by lazy { StripedMutex() }

    val mirrorLocator: MirrorLocator by lazy { MirrorLocator(mirrorRoot, mirrorArchiveRoot) }

    val mirrorStateStore: RoomMirrorStateStore by lazy {
        RoomMirrorStateStore.of(database, orderFileDao, timeZone, now, audit("MIRROR"))
    }

    val mirrorCommitter: MirrorCommitter by lazy {
        MirrorCommitter(mirrorLocator, mirrorQuarantineRoot, locks, mirrorStateStore, now, audit("MIRROR"))
    }

    val mirrorVerifier: MirrorVerifier by lazy {
        MirrorVerifier(orderFileApi, mirrorLocator, mirrorStateStore, mirrorTmpRoot, locks, audit("VERIFY"))
    }

    // ==================== 同步与 outbox ====================

    val stockLedger: StockLedger by lazy { StockLedger(stockDao, now) }

    val outboxStore: OutboxStore by lazy {
        OutboxStore.of(database, orderFileDao, timeZone, now, deviceId = { deviceId }, audit("OUTBOX"))
    }

    val orderFileSyncEngine: OrderFileSyncEngine by lazy {
        OrderFileSyncEngine(orderFileApi, mirrorCommitter, mirrorStateStore, orderFileDao, now, audit("SYNC"))
    }

    val outboxUploader: OutboxUploader by lazy {
        OutboxUploader(
            api = orderFileApi,
            store = outboxStore,
            committer = mirrorCommitter,
            syncFile = orderFileSyncEngine::sync,
            dao = orderFileDao,
            stockLedger = stockLedger,
            deviceId = { deviceId },
            audit = audit("OUTBOX"),
        )
    }

    val productSyncEngine: ProductSyncEngine by lazy {
        ProductSyncEngine(productDao, storeMetaDao, stockLedger, productApi, now)
    }

    /**
     * 登录会话。**整条同步链的起点**：没有它就没有 token、没有真实 storeKey、
     * 没有 `ownerName`，P0-P5 那八百多个测试覆盖的代码在真机上一步都跑不到。
     */
    val sessions: SessionRepository by lazy {
        SessionRepository(
            auth = authApi,
            storeMetaDao = storeMetaDao,
            sessionStore = SessionStore.of(credentials, settings),
            adoption = AdoptionTx.of(database, now, audit("SESSION")),
            // 必须是 appScope：登录是后台行为，用 ViewModel 的 scope 会随着离开登录页被取消，
            // 于是"点了登录→跳回主界面→登录被悄悄取消"，而界面永远停在灰色。
            scope = appScope,
            setSession = ::setSession,
            switchStore = ::switchStore,
            currentStoreKey = ::currentStoreKey,
            refreshEnumFloor = { sk, createdAt -> syncScheduler.refreshEnumFloor(sk, createdAt) },
            // ⚠ 顺序在 refreshEnumFloor 之后（见 SessionRepository 那个参数的注释）
            onLoggedIn = ::onLoggedIn,
            onLoggedOut = ::onLoggedOut,
            now = now,
            audit = audit("SESSION"),
        )
    }

    val syncScheduler: SyncScheduler by lazy {
        SyncScheduler(
            syncOne = orderFileSyncEngine::sync,
            checkOne = mirrorCommitter::selfCheck,
            dao = orderFileDao,
            storeMetaDao = storeMetaDao,
            timeZone = timeZone,
            now = now,
            audit = audit("SYNC"),
        )
    }

    // ==================== 给 UI 的两个读取门面 ====================
    //
    // ViewModel 只准通过它们读订单与统计，不许自己去捞 DAO：
    // 订单列表要把 `order_index`（已同步）与 `order_outbox`（未上传）**合成一个视图**，
    // 统计要在数字旁边算出那个 `*`（本地数据是否完整）。
    // 这两件事各有一个"做错了但看不出来"的方式（见各自的类注释），
    // 散在各 ViewModel 里各写一遍迟早会写出不一致的两份。

    val orders: OrderRepository by lazy {
        OrderRepository(
            dao = orderFileDao,
            outbox = outboxStore,
            uploader = outboxUploader,
            storeKey = ::currentStoreKey,
            ownerName = ::currentOwnerName,
            deviceId = { deviceId },
            audit = audit("ORDER"),
        )
    }

    val stats: StatsRepository by lazy {
        StatsRepository(
            dao = orderFileDao,
            storeKey = ::currentStoreKey,
            ownerName = ::currentOwnerName,
        )
    }

    /**
     * 价格漂移检测。**只检测，不自动改价** —— 理由见 [PriceDrift] 的类注释
     * （顾客已经按旧价付过钱了，悄悄改成新价等于事后涨价）。
     */
    val priceDrift: PriceDrift by lazy {
        PriceDrift(
            dao = orderFileDao,
            storeKey = ::currentStoreKey,
            currentUnitPrices = { barcodes ->
                // 批量查一次：一张单几十行，逐条查就是几十次数据库往返
                products.getProductsByBarcodes(barcodes).associate { it.barcode to Money.toCents(it.price.toPlainString()) }
            },
        )
    }

    // ==================== 热点互同步 ====================

    /**
     * 离线时两台设备直接互相同步未上传的订单与待推送的改价。
     *
     * 与 `DataShareService`（整库拷贝，只用于商品目录首次铺货）**不是一回事**：
     * 整库覆盖对 outbox 是丢数据，这里走行级双向合并。
     */
    val hotspotMerger: HotspotMerger by lazy {
        HotspotMerger(
            outbox = outboxStore,
            productDao = productDao,
            ownerName = ::currentOwnerName,
            audit = audit("HOTSPOT"),
        )
    }

    val hotspotSync: HotspotSyncService by lazy {
        HotspotSyncService(
            context = appContext,
            merger = hotspotMerger,
            deviceId = { deviceId },
            storeKey = ::currentStoreKey,
            audit = audit("HOTSPOT"),
        )
    }

    // ==================== SSE ====================

    val sseSupervisor: SseSupervisor by lazy {
        SseSupervisor(
            transport = OkHttpSseTransport(sseClient),
            urlProvider = {
                val m = currentMeta.value
                settings.current().baseUrl.trimEnd('/') +
                    "/api/sync/subscribe?priceVersion=${m?.priceVersion ?: 0}&stockVersion=${m?.stockVersion ?: 0}"
            },
            // null = 未登录。SseSupervisor 在这种状态下不连。
            tokenProvider = { _session.value?.token },
            scope = appScope,
            clock = AndroidMonotonicClock(),
            // 商户端 `/api/sync/subscribe` 建连时会补发一次性快照（reconcileNow）
            expectImmediateMessage = true,
            audit = audit("SSE"),
        )
    }

    private val sessionStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 进程内**只跑一次**：恢复数据作用域 + 按 [com.example.myapplication.settings.LoginMode]
     * 决定要不要静默自动登录。
     *
     * 放在这里而不是 `App.onCreate`，是因为 `sessions` 会连带强制打开 Room
     * （`AdoptionTx.of` 要 `RoomDatabase` 实例），而 `onCreate` 跑在主线程上、
     * 耗时直接算进冷启动（见 [App] 的类注释）。放在 `MainActivity` 里，
     * 数据库本来就要为第一个页面打开，不多花一次。
     *
     * ⚠ 用 `AtomicBoolean` 而不是让调用方自己记：Activity 会因转屏/深色模式切换重建，
     * 重跑一次 `startUp` 就会再发一次自动登录 —— 而登录限速是 5/min 的平滑桶，
     * 转屏几次就能把自己锁进 429。
     */
    fun startSessionOnce() {
        if (sessionStarted.compareAndSet(false, true)) sessions.startUp()
    }

    // ==================== 登录后的启动序列 ====================

    /**
     * 登录成功之后要做的事。**顺序是硬的**，每一步都依赖前一步的结果。
     *
     * ```
     * 1. awaitReady()        设置没加载完就发请求 → baseUrl 还是默认的 10.0.2.2 → "连接失败"
     * 2. SSE start           建连要带 priceVersion/stockVersion，晚开就漏掉这段时间的变更
     * 3. recoverInFlight     ⚠ 必须在 uploadReady **之前**
     * 4. urgentSync          把当月/上月追平，镜像才是最新的
     * 5. uploadReady         把离线攒下的单发出去
     * 6. backgroundCatchUp   补齐历史月份（预算 12 个文件，新月优先）
     * ```
     *
     * ## ⚠ 第 3 步为什么不能省、也不能挪到后面
     *
     * `IN_FLIGHT` 意味着"上一次发出去了但不知道服务端收没收下"。
     * 不先把它核对掉就去发新的单，等于在"可能已经重复了一次"的基础上继续叠加 ——
     * 而服务端 `order_write.lua` 里没有任何 uuid 去重，重复上传返回 **200**、
     * 追加为新行、**库存再扣一次**，此后镜像比服务器长、所有偏移静默错位。
     *
     * `recoverInFlight` 自己会先同步那个文件、在 `order_index` 里找自己的 uuid，
     * 找到了当成功、没找到才重推 —— 这个判定必须在任何新上传之前完成。
     *
     * ## 全程不抛
     *
     * 跑在 [appScope] 上，任何一步失败都只记日志、不中断后面的步骤：
     * 断网时第 2-6 步全会失败，但用户已经登录成功了，界面该显示"已登录 + 离线"，
     * 而不是因为 SSE 连不上就把整个登录状态回滚掉。
     */
    fun onLoggedIn(storeKey: String) {
        appScope.launch {
            val log = audit("STARTUP")
            try {
                awaitReady()
            } catch (e: Exception) {
                log("设置加载失败，跳过启动序列：${e.message}")
                return@launch
            }
            val owner = currentOwnerName()

            runCatching { sseSupervisor.start() }
                .onFailure { log("SSE 启动失败：${it.message}") }

            runCatching {
                val recovered = outboxUploader.recoverInFlight(storeKey, owner)
                if (recovered.isNotEmpty()) log("IN_FLIGHT 恢复：$recovered")
            }.onFailure { log("IN_FLIGHT 恢复失败：${it.message}") }

            runCatching {
                val r = syncScheduler.urgentSync(storeKey)
                log("急通道同步：$r")
            }.onFailure { log("急通道同步失败：${it.message}") }

            runCatching {
                val r = outboxUploader.uploadReady(storeKey, owner)
                if (r.attempted > 0) log("上传未同步订单：$r")
            }.onFailure { log("上传未同步订单失败：${it.message}") }

            runCatching {
                val r = syncScheduler.backgroundCatchUp(storeKey)
                log("后台补齐历史月份：$r")
            }.onFailure { log("后台补齐失败：${it.message}") }
        }
    }

    /**
     * 登出：断开 SSE。
     *
     * ⚠ **不停调度器、不清 outbox**。数据留在原店铺的作用域下（见 [setSession]），
     * 重新登录同一家店时接着上传。
     */
    fun onLoggedOut() {
        runCatching { sseSupervisor.stop("用户登出") }
    }

    /**
     * 发任何网络请求之前的硬前置条件，与 [api] 里的 `awaitLoaded()` 是同一件事。
     *
     * 同步引擎持有的是 [orderFileApi] 转发层（不是 `apiInstance`），所以类型上
     * **拦不住**"设置还没加载完就发请求"。启动调度器或 SSE 之前必须先调它，
     * 否则 `baseUrl` 还是默认的 `10.0.2.2:8443`，用户看到"连接失败"而他明明改过 IP。
     */
    suspend fun awaitReady() {
        settings.awaitLoaded()
    }

    companion object {
        /**
         * 离线模式（未登录）的 storeKey 保留值。
         *
         * ⚠ 真实的 merchantCode 是 5 位数字（`seed_admin.py` 里总部是 `00001`），
         * 所以带下划线的这个名字**不可能与任何真实店铺撞上**。
         */
        /**
         * 转发 [com.example.myapplication.db.LOCAL_STORE_KEY]，**不要**在这里再写一遍字面量。
         *
         * 两处各写一份的话，改了其中一处就会出现"离线数据写在 A 键下、查的时候用 B 键"，
         * 而两边各自看都是对的 —— 收养会静默搬不到任何东西。
         */
        const val LOCAL_STORE_KEY = com.example.myapplication.db.LOCAL_STORE_KEY
    }
}
