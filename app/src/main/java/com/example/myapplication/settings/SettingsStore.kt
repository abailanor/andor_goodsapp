package com.example.myapplication.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * [AppSettings] 的 DataStore 持久化。
 *
 * ## 契约
 *
 * - [settings] 是**热**的 StateFlow，构造后立刻可读，初值是 [AppSettings] 的默认值；
 * - [start] 在 `Application.onCreate` 调一次，之后磁盘上的值会覆盖初值；
 * - [awaitLoaded] 挂起直到**磁盘值确实被读进来**。发任何网络请求之前必须等它，理由见其注释；
 * - [mutate] 挂起直到写入落盘。**不要在主线程调**（DataStore 每次写都重写整个文件）。
 *
 * ## ⚠ 为什么必须挂 corruptionHandler
 *
 * DataStore 的 `.preferences_pb` 是 protobuf。进程在写入中途被杀（国产 ROM 上很常见）
 * 可能留下半截文件。默认行为是**此后每次读写都抛 `IOException`**，而且是永久的 ——
 * 表现为"App 一启动就崩，清数据才能好"。
 *
 * `ReplaceFileCorruptionHandler { emptyPreferences() }` 把这种情况降级成
 * "文件是空的" → 所有设置回到默认值。设置丢了用户会不爽，但 App 还能开；
 * 崩在启动页则是彻底不可用。这里选前者。
 *
 * ⚠ 凭据那边（[com.example.myapplication.security.BlobStore] 的实现）也挂了同样的 handler，
 * 语义正好对上 `CredentialStore` 的注释："文件损坏等于没有凭据"。
 *
 * @param ds 底层 DataStore。收在构造参数里（而不是自己拿 Context）是为了能在 JVM 上
 *           用 `PreferenceDataStoreFactory.create(produceFile = { 临时文件 })` 做真实的读写往返测试。
 */
class SettingsStore(private val ds: DataStore<Preferences>) {

    private val _settings = MutableStateFlow(AppSettings())

    /** 当前设置。**在 [awaitLoaded] 返回之前可能是默认值**，见类注释。 */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** 同步读取当前值。给 `Service1Api(baseUrlProvider = { ... })` 这类需要即时值的调用方用。 */
    fun current(): AppSettings = _settings.value

    private val firstLoad = CompletableDeferred<Unit>()

    @Volatile
    private var started = false

    /**
     * 开始监听磁盘。**幂等**，重复调用只有第一次生效。
     *
     * @param scope 生命周期与进程同长的 scope（`Application` 里的那个）。
     *
     * ## ⚠ 这里 launch 出去的协程**永不结束**
     *
     * DataStore 的 `data` flow 不会 complete，所以 `collect` 也不会返回，
     * 这个子协程会一直活到 [scope] 被取消。
     *
     * 传 `runBlocking { }` 的 scope 进来会**死锁**：`runBlocking` 要等所有子协程结束才返回，
     * 而这个子协程永远不结束 —— 表现为"测试跑完了但 Gradle 任务卡住不动、没有任何输出"。
     * 单测里要给一个自己管、`@After` 里取消掉的 scope（见 `SettingsStoreTest`）。
     */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            try {
                ds.data.collect { prefs ->
                    // 顺序不能反：先更新值，再宣告就绪。
                    // 反过来的话 awaitLoaded() 的等待者可能在 _settings 赋值之前就跑起来，
                    // 拿到默认值去连服务器。
                    _settings.value = fromPrefs(prefs)
                    firstLoad.complete(Unit)
                }
            } catch (e: CancellationException) {
                throw e                             // 取消要照常传播，不许吞
            } catch (e: Exception) {
                // 挂了 corruptionHandler 之后 IOException 基本到不了（损坏会被当成空文件），
                // 但目录不可写、磁盘满之类还是可能。
                // 这种情况下**绝不能让 awaitLoaded() 永久挂起** —— 表现是 App 卡死在启动页、
                // 日志里什么都没有，比"设置回默认值"糟糕得多。
                firstLoad.complete(Unit)
            }
        }
    }

    /**
     * 等磁盘上的设置被读进来。返回之后 [settings].value 就是用户的真实设置。
     *
     * **发任何网络请求之前必须调它**：否则 `baseUrl` 可能还是默认的 `10.0.2.2:8443`，
     * 请求打到不存在的地址上，用户看到的是"连接失败"，而他明明已经改过 IP 了。
     *
     * [start] 没被调过时会自己直接读一次磁盘补上，**不会**永久挂起 ——
     * 挂在这里的表现是 App 卡在启动页且日志里什么都没有，是最难查的一类故障。
     */
    suspend fun awaitLoaded() {
        if (!started) {
            runCatching { _settings.value = fromPrefs(ds.data.first()) }
            firstLoad.complete(Unit)
            return
        }
        firstLoad.await()
    }

    /**
     * 改一项或多项设置，挂起直到落盘。
     *
     * @param block 收到的是**磁盘上的当前值**（不是 [settings] 的内存快照），
     *              所以两次并发的 `mutate` 各改一个字段不会互相冲掉。
     *
     * 返回后 [settings].value **已经是新值**，不用等 DataStore 的 collector 绕一圈回来。
     *
     * ## ⚠ 两条容易写错的
     *
     * 1. `edit {}` 的 lambda 收到的是 `MutablePreferences`，必须**原地改它**。
     *    写成 `prefs.toMutableMap()` 再改是错的 —— 那是个副本，DataStore 看不见，
     *    结果是"调用返回成功、磁盘上什么都没变"，而且没有任何报错。
     * 2. 写入前必须过 [AppSettings.normalized]，否则 `mutate` 之后内存里是越界值、
     *    collector 回读之后又跳成钳位后的值，设置项会"自己变一次"。
     */
    suspend fun mutate(block: (AppSettings) -> AppSettings) {
        // ⚠ 新值要用外层变量接住，**不能靠 `edit {}` 的返回值**：
        // `DataStore<Preferences>.edit` 的 transform 签名是 `suspend (MutablePreferences) -> Unit`，
        // 而 `edit` 本身返回 `Preferences`（写入后的只读快照）。lambda 结尾写一个 `n` 会被静默丢掉，
        // 于是 `val next = ds.edit { … n }` 的类型是 `Preferences` 而不是 `AppSettings` —— 编译不过。
        lateinit var next: AppSettings
        ds.edit { prefs ->
            next = block(fromPrefs(prefs)).normalized()
            // 先清空再只写偏离项：这样将来删掉某个设置项之后，它的旧键不会永远躺在文件里。
            prefs.clear()
            writeAll(prefs, next)
        }
        _settings.value = next
    }

    // ==================== 映射 ====================

    /**
     * 磁盘 → 值对象。缺失的键一律取 [AppSettings] 的默认值，所以**加新设置项不需要写迁移**。
     *
     * 最后统一走一遍 [AppSettings.normalized]：文件可能被手改过、也可能是更老/更新的版本写的，
     * 钳位逻辑只写一份，不要在这里再抄一遍（抄两遍就一定会有一处漏改）。
     */
    private fun fromPrefs(p: Preferences): AppSettings {
        val s = AppSettings()
        return s.copy(
            serverHost = p[Keys.HOST] ?: s.serverHost,
            serverPort = p[Keys.PORT] ?: s.serverPort,
            useTls = p[Keys.USE_TLS] ?: s.useTls,
            adminCode = p[Keys.ADMIN_CODE] ?: s.adminCode,
            showBoxInput = p[Keys.SHOW_BOX_INPUT] ?: s.showBoxInput,
            pricingMode = p.enumOr(Keys.PRICING_MODE, s.pricingMode),
            rescanPricesOnLoadPending = p[Keys.RESCAN_PRICES] ?: s.rescanPricesOnLoadPending,
            notifyPriceChangeImmediately = p[Keys.NOTIFY_PRICE_NOW] ?: s.notifyPriceChangeImmediately,
            freqThreshold = p[Keys.FREQ_THRESHOLD] ?: s.freqThreshold,
            freqWindow = p.enumOr(Keys.FREQ_WINDOW, s.freqWindow),
            notifyDisabledOverrides = p.eventSet(Keys.NOTIFY_DISABLED),
            notifyEnabledOverrides = p.eventSet(Keys.NOTIFY_ENABLED),
            loginMode = p.enumOr(Keys.LOGIN_MODE, s.loginMode),
            countFailedOrdersInRevenue = p[Keys.COUNT_FAILED_IN_REVENUE] ?: s.countFailedOrdersInRevenue,
            enableBoxSelling = p[Keys.ENABLE_BOX_SELLING] ?: s.enableBoxSelling,
            lastStoreKey = p[Keys.LAST_STORE_KEY] ?: s.lastStoreKey,
            backgroundMode = p.enumOr(Keys.BACKGROUND_MODE, s.backgroundMode),
            printBarcode = p[Keys.PRINT_BARCODE] ?: s.printBarcode,
            printShopName = p[Keys.SHOP_NAME] ?: s.printShopName,
            printShopPhone = p[Keys.SHOP_PHONE] ?: s.printShopPhone,
            printShopAddress = p[Keys.SHOP_ADDRESS] ?: s.printShopAddress,
        ).normalized()
    }

    /**
     * 把 [next] 写进 [p]。
     *
     * **等于默认值的键一律 remove**，只存偏离项。这样：
     *  - 文件小（DataStore 每次写都全量重写整个文件）；
     *  - 将来改某个默认值时，没动过那一项的老用户会跟着变（这是想要的行为）；
     *  - 手动 dump `.preferences_pb` 时看到的就是"用户改过什么"，可读。
     */
    private fun writeAll(p: MutablePreferences, next: AppSettings) {
        val d = AppSettings()
        p.putOrRemove(Keys.HOST, next.serverHost, d.serverHost)
        p.putOrRemove(Keys.PORT, next.serverPort, d.serverPort)
        p.putOrRemove(Keys.USE_TLS, next.useTls, d.useTls)
        p.putOrRemove(Keys.ADMIN_CODE, next.adminCode, d.adminCode)
        p.putOrRemove(Keys.SHOW_BOX_INPUT, next.showBoxInput, d.showBoxInput)
        p.putOrRemove(Keys.PRICING_MODE, next.pricingMode.name, d.pricingMode.name)
        p.putOrRemove(Keys.RESCAN_PRICES, next.rescanPricesOnLoadPending, d.rescanPricesOnLoadPending)
        p.putOrRemove(Keys.NOTIFY_PRICE_NOW, next.notifyPriceChangeImmediately, d.notifyPriceChangeImmediately)
        p.putOrRemove(Keys.FREQ_THRESHOLD, next.freqThreshold, d.freqThreshold)
        p.putOrRemove(Keys.FREQ_WINDOW, next.freqWindow.name, d.freqWindow.name)
        p.putOrRemove(Keys.LOGIN_MODE, next.loginMode.name, d.loginMode.name)
        p.putOrRemove(Keys.COUNT_FAILED_IN_REVENUE, next.countFailedOrdersInRevenue, d.countFailedOrdersInRevenue)
        p.putOrRemove(Keys.ENABLE_BOX_SELLING, next.enableBoxSelling, d.enableBoxSelling)
        p.putOrRemove(Keys.LAST_STORE_KEY, next.lastStoreKey, d.lastStoreKey)
        p.putOrRemove(Keys.BACKGROUND_MODE, next.backgroundMode.name, d.backgroundMode.name)
        p.putOrRemove(Keys.PRINT_BARCODE, next.printBarcode, d.printBarcode)
        p.putOrRemove(Keys.SHOP_NAME, next.printShopName, d.printShopName)
        p.putOrRemove(Keys.SHOP_PHONE, next.printShopPhone, d.printShopPhone)
        p.putOrRemove(Keys.SHOP_ADDRESS, next.printShopAddress, d.printShopAddress)
        p.putOrRemove(Keys.NOTIFY_DISABLED, next.notifyDisabledOverrides.toNames(), EMPTY_NAMES)
        p.putOrRemove(Keys.NOTIFY_ENABLED, next.notifyEnabledOverrides.toNames(), EMPTY_NAMES)
    }

    private fun Set<NotifyEvent>.toNames(): Set<String> =
        if (isEmpty()) EMPTY_NAMES else mapTo(LinkedHashSet(size)) { it.name }

    /**
     * 类型安全地"值等于默认值就删键"。
     *
     * ⚠ 键与值的类型必须匹配（`Preferences.Key<T>` 对 `T`），否则 DataStore 在写盘时抛
     * `IllegalArgumentException`。之前用 `MutableMap<Key<*>, Any>` 绕过了类型检查，
     * 编译期看不出写错，只有真正落盘时才炸。
     */
    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T, default: T) {
        if (value == default) remove(key) else this[key] = value
    }

    /**
     * 读一个枚举键。
     *
     * **名字认不出来就退回默认值，不抛**。这覆盖两个方向：
     *  - 降级安装（新版本存了一个老版本没有的枚举值）；
     *  - 手改文件写错了。
     *
     * 所以枚举**只能追加、不能改名也不能复用旧名字**，改名等于让所有老用户的该项重置。
     */
    private inline fun <reified T : Enum<T>> Preferences.enumOr(
        key: Preferences.Key<String>,
        default: T,
    ): T {
        val name = this[key] ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    /**
     * 读事件集合。
     *
     * ⚠ 必须 `mapNotNull`：集合里可能有已经删掉的旧事件名。
     * ⚠ 必须重新 `toSet()`：`stringSetPreferencesKey` 读出来的 Set 不保证可修改，
     *    直接把它塞进 data class 再让调用方 `toMutableSet()` 之外原地改会炸。
     */
    private fun Preferences.eventSet(key: Preferences.Key<Set<String>>): Set<NotifyEvent> {
        val raw = this[key] ?: return emptySet()
        val all = enumValues<NotifyEvent>()
        val out = LinkedHashSet<NotifyEvent>(raw.size)
        for (name in raw) {
            val e = all.firstOrNull { it.name == name } ?: continue
            out.add(e)
        }
        return out
    }

    private object Keys {
        val HOST = stringPreferencesKey("server_host")
        val PORT = intPreferencesKey("server_port")
        val USE_TLS = booleanPreferencesKey("use_tls")
        val ADMIN_CODE = stringPreferencesKey("admin_code")

        val SHOW_BOX_INPUT = booleanPreferencesKey("show_box_input")
        val PRICING_MODE = stringPreferencesKey("pricing_mode")
        val RESCAN_PRICES = booleanPreferencesKey("rescan_prices_on_load_pending")

        val NOTIFY_PRICE_NOW = booleanPreferencesKey("notify_price_immediately")
        val FREQ_THRESHOLD = intPreferencesKey("freq_threshold")
        val FREQ_WINDOW = stringPreferencesKey("freq_window")
        val NOTIFY_DISABLED = stringSetPreferencesKey("notify_disabled")
        val NOTIFY_ENABLED = stringSetPreferencesKey("notify_enabled")

        val LOGIN_MODE = stringPreferencesKey("login_mode")
        val LAST_STORE_KEY = stringPreferencesKey("last_store_key")
        val COUNT_FAILED_IN_REVENUE = booleanPreferencesKey("count_failed_orders_in_revenue")
        val ENABLE_BOX_SELLING = booleanPreferencesKey("enable_box_selling")

        val BACKGROUND_MODE = stringPreferencesKey("background_mode")

        val PRINT_BARCODE = booleanPreferencesKey("print_barcode")
        val SHOP_NAME = stringPreferencesKey("shop_name")
        val SHOP_PHONE = stringPreferencesKey("shop_phone")
        val SHOP_ADDRESS = stringPreferencesKey("shop_address")
    }

    companion object {
        private val EMPTY_NAMES: Set<String> = emptySet()

        /**
         * 全进程**唯一**的那个 DataStore 实例。
         *
         * ⚠ 必须用委托属性（或者自己缓存成单例）：对同一个文件创建两个 `DataStore`
         * 会抛 `IllegalStateException: There are multiple DataStores active for the same file`，
         * 而且这个异常出现在**第一次访问**时，不在创建时 —— 很容易在"某个 ViewModel 里
         * 顺手 new 一个"的时候踩到。
         */
        private val Context.dataStore by preferencesDataStore(
            name = "app_settings",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        fun forApp(context: Context): SettingsStore =
            SettingsStore(context.applicationContext.dataStore)
    }
}
