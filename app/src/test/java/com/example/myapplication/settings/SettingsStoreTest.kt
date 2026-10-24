package com.example.myapplication.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * [SettingsStore] 的测试，跑在**真实的 DataStore** 上（临时文件），不是 mock。
 *
 * 用真家伙是必要的：这里要验的性质全都是"存储层的行为"，mock 掉就是自说自话 ——
 * 尤其是**文件损坏**那条：`ReplaceFileCorruptionHandler` 到底会不会被触发、
 * 触发之后 `data` flow 是发一个空 `Preferences` 还是抛 `IOException`，
 * 只有真写坏一个 `.preferences_pb` 才知道。而这条性质直接决定"进程被杀在写入中途"
 * 之后 App 是**设置回默认值**还是**永远崩在启动页**。
 *
 * ⚠ 用 [runBlocking] 而不是 `runTest`：DataStore 在自己的 IO scope 上做真实文件读写，
 * 混进 `runTest` 的虚拟时间调度器只会带来假失败。这里没有需要跳过的延时。
 *
 * ## ⚠ 同一个文件路径在一个 JVM 里只能开一个 DataStore
 *
 * `SingleProcessDataStore` 在构造时把 canonical path 登记进一个**进程级静态集合**，
 * 第二次对同一路径构造就抛 `IllegalStateException: There are multiple DataStores active
 * for the same file`，而且**没有注销的入口**（取消 scope 也没用）。
 * 所以"写完重新打开读一遍"的测试必须先把文件字节拷到另一个路径，见 [copyTo]。
 * 这个限制也正是生产代码里 DataStore 必须走单例委托的原因（`SettingsStore.forApp`）。
 */
class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 每个测试方法的硬超时。
     *
     * 这个类里的失败模式**就是死锁**（把永不结束的 collector 挂成 `runBlocking` 的子协程、
     * 或者 `awaitLoaded()` 等一个永远不会 complete 的 deferred），而死锁的表现是
     * Gradle 测试任务**无声无息地卡住**，没有任何输出能判断卡在哪 —— 上一次为此白等了十几分钟。
     * 有了这个 rule，卡住会变成一条带栈的失败，并且 `lookingForStuckThread` 会指出是哪个线程。
     *
     * ⚠ `Timeout` 是 `TestRule`，所以它**已经是每个测试方法一次**，不需要（也没有）"perTest" 工厂。
     * 它把测试放到另一个线程里跑，超时后中断该线程并抛 `TestTimedOutException` ——
     * 死锁的线程本身可能仍然停在那里，但 Gradle 任务会**结束并报错**，这正是我们要的。
     */
    @get:Rule
    val stuckGuard: Timeout = Timeout.builder()
        .withLookingForStuckThread(true)
        .withTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scopes = mutableListOf<CoroutineScope>()

    /**
     * 传给 `SettingsStore.start` 的 scope，**不是** `runBlocking` 的那个。
     *
     * ⚠ 这一条踩过一次，代价是整个 Gradle 测试任务无声无息地卡住 13 分钟：
     * `start()` 里 launch 的 collector 永不结束（DataStore 的 `data` flow 不 complete），
     * 而 `runBlocking` 要等**所有子协程**结束才返回 → 死锁，且没有任何输出可以判断卡在哪。
     * 生产代码传的是 Application 的 scope（本来就与进程同长），所以那边没问题。
     */
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() {
        storeScope.cancel()
        scopes.forEach { it.cancel() }
    }

    private fun newDataStore(file: File): DataStore<Preferences> {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = { file },
        )
    }

    /** 新建 store、接上 [storeScope]、等到磁盘值就绪。 */
    private suspend fun SettingsStore.opened(): SettingsStore = also {
        it.start(storeScope)
        it.awaitLoaded()
    }

    // ==================== 读写往返 ====================

    @Test
    fun `awaitLoaded 之后读到的是磁盘上的值而不是默认值`() = runBlocking {
        val ds = newDataStore(tmp.root.resolve("await.preferences_pb"))
        // 直接往底层写，模拟"上一次运行时用户改过设置"
        ds.edit {
            it[stringPreferencesKey("server_host")] = "192.168.1.77"
            it[intPreferencesKey("server_port")] = 9000
        }

        val store = SettingsStore(ds)
        // start 之前 current() 是默认值 —— 这正是"没等 awaitLoaded 就发请求会连错地址"的成因
        assertEquals("10.0.2.2", store.current().serverHost)

        store.opened()

        assertEquals("192.168.1.77", store.current().serverHost)
        assertEquals(9000, store.current().serverPort)
        assertEquals("https://192.168.1.77:9000", store.current().baseUrl)
    }

    @Test
    fun `每种类型的字段都能编码到磁盘再解回来`() = runBlocking {
        val target = AppSettings(
            serverHost = "shop.lan",
            serverPort = 8080,
            useTls = false,
            adminCode = "00042",
            showBoxInput = false,
            pricingMode = PricingMode.ALL_SINGLES,
            rescanPricesOnLoadPending = false,
            notifyPriceChangeImmediately = true,
            freqThreshold = 7,
            freqWindow = FreqWindow.FIVE_MINUTES,
            notifyDisabledOverrides = setOf(NotifyEvent.KICKED, NotifyEvent.PRODUCT_DELETED),
            notifyEnabledOverrides = setOf(NotifyEvent.NEW_ORDER),
            // ⚠ 必须设成**非默认值**：这条断言是整对象比较，
            // 而 loginMode 的默认值恰好是 ASK。留成默认的话，
            // 就算 writeAll 漏了这一项，读回来也是 ASK，测试照样绿 ——
            // 而"首次选择登录/离线要沿用"这条需求全靠它真的落盘。
            loginMode = LoginMode.AUTO,
            lastStoreKey = "00002",
            // ⚠ 同样必须设成**非默认值**（默认是 true）。留成默认的话，
            // 就算 writeAll 漏了这一项，读回来也是 true，测试照样绿 ——
            // 而"营业额算不算上传失败的单"这个开关会静默失效，用户切了没反应。
            countFailedOrdersInRevenue = false,
            backgroundMode = BackgroundMode.PERSISTENT_SERVICE,
            printBarcode = false,
            printShopName = "丽华金龙",
            printShopPhone = "15288180414",
            printShopAddress = "官渡区新广丰B区14-16",
        )

        val written = tmp.root.resolve("roundtrip_a.preferences_pb")
        SettingsStore(newDataStore(written)).opened().mutate { target }

        // 换一个路径打开同样的字节，确保读回来的不是内存里的 StateFlow
        val rereadFile = copyTo(written, "roundtrip_b.preferences_pb")
        val reread = SettingsStore(newDataStore(rereadFile)).opened()

        assertEquals(target, reread.current())
    }

    private fun copyTo(src: File, newName: String): File =
        tmp.root.resolve(newName).also { src.copyTo(it, overwrite = true) }

    @Test
    fun `等于默认值的键不落盘`() = runBlocking {
        val ds = newDataStore(tmp.root.resolve("sparse.preferences_pb"))
        val store = SettingsStore(ds).opened()

        // 改一遍再改回默认值
        store.mutate { it.copy(freqThreshold = 9, printShopName = "临时") }
        store.mutate { it.copy(freqThreshold = 3, printShopName = "") }

        val prefs = ds.data.first()
        assertTrue("改回默认值之后应当一个键都不剩，实际 ${prefs.asMap().keys}", prefs.asMap().isEmpty())
    }

    @Test
    fun `mutate 基于磁盘上的当前值而不是内存快照`() = runBlocking {
        val store = SettingsStore(newDataStore(tmp.root.resolve("mutate.preferences_pb"))).opened()

        // 两次 mutate 各改一个不同的字段。如果 mutate 是拿内存里的旧快照做 copy 再整体覆盖，
        // 第二次就会把第一次的改动冲掉 —— 这是"设置改一项丢一项"的典型成因。
        store.mutate { it.copy(serverHost = "a.example") }
        store.mutate { it.copy(freqThreshold = 11) }

        assertEquals("a.example", store.current().serverHost)
        assertEquals(11, store.current().freqThreshold)
    }

    @Test
    fun `start 幂等`() = runBlocking {
        val store = SettingsStore(newDataStore(tmp.root.resolve("idempotent.preferences_pb")))
        store.start(storeScope)
        store.start(storeScope)
        store.start(storeScope)
        store.awaitLoaded()
        assertEquals(AppSettings(), store.current())
    }

    @Test
    fun `没调 start 时 awaitLoaded 也能读到磁盘值而不是永久挂起`() = runBlocking {
        val ds = newDataStore(tmp.root.resolve("nostart.preferences_pb"))
        ds.edit { it[stringPreferencesKey("server_host")] = "10.9.9.9" }

        val store = SettingsStore(ds)
        store.awaitLoaded()          // 没有 start()。挂在这里 = App 卡在启动页且日志里什么都没有
        assertEquals("10.9.9.9", store.current().serverHost)
    }

    @Test
    fun `mutate 返回后内存里立刻是新值_越界值也不会先显示再自己跳变`() = runBlocking {
        val store = SettingsStore(newDataStore(tmp.root.resolve("immediate.preferences_pb"))).opened()

        store.mutate { it.copy(printShopName = "丽华金龙") }
        // 不等 collector 绕一圈。UI 拨开关必须立刻反映出来。
        assertEquals("丽华金龙", store.current().printShopName)

        store.mutate { it.copy(freqThreshold = 999_999) }
        // 写入前就钳位，所以内存值 == 磁盘值 == 回读值，不会出现"先显示 999999 再自己变成 9999"
        assertEquals(AppSettings.MAX_FREQ_THRESHOLD, store.current().freqThreshold)
        assertEquals(AppSettings.MAX_FREQ_THRESHOLD, store.current().normalized().freqThreshold)

        // 给 DataStore 的 collector 足够时间回读一遍，确认内存里的值没有跳变
        delay(400)
        assertEquals(AppSettings.MAX_FREQ_THRESHOLD, store.current().freqThreshold)
    }

    // ==================== 向前/向后兼容 ====================

    @Test
    fun `认不出来的枚举名退回默认值而不是崩`() = runBlocking {
        val ds = newDataStore(tmp.root.resolve("enum.preferences_pb"))
        ds.edit {
            // 键名是**契约**，这里故意重复字面量：改了键名这个测试就会红，
            // 而改键名等于让所有老用户的该项设置被重置，本来就该停下来想一想。
            it[stringPreferencesKey("pricing_mode")] = "SOMETHING_FROM_A_NEWER_VERSION"
            it[stringPreferencesKey("background_mode")] = ""
        }
        val store = SettingsStore(ds).opened()

        assertEquals(PricingMode.BOX_PLUS_SINGLES, store.current().pricingMode)
        assertEquals(BackgroundMode.FOREGROUND_ONLY, store.current().backgroundMode)
    }

    @Test
    fun `认不出来的事件名被忽略_认得出的照常生效`() = runBlocking {
        val ds = newDataStore(tmp.root.resolve("events.preferences_pb"))
        ds.edit {
            it[stringSetPreferencesKey("notify_disabled")] =
                setOf("AN_EVENT_WE_REMOVED", NotifyEvent.KICKED.name)
        }
        val store = SettingsStore(ds).opened()

        assertFalse(store.current().isNotifyEnabled(NotifyEvent.KICKED))
        // 没被显式关掉的事件不受影响
        assertTrue(store.current().isNotifyEnabled(NotifyEvent.SERVER_BEHIND))
        assertEquals(setOf(NotifyEvent.KICKED), store.current().notifyDisabledOverrides)
    }

    @Test
    fun `越界的数值被钳回来`() = runBlocking {
        val ds = newDataStore(tmp.root.resolve("clamp.preferences_pb"))
        ds.edit {
            it[intPreferencesKey("server_port")] = 0
            it[intPreferencesKey("freq_threshold")] = 999_999
        }
        val store = SettingsStore(ds).opened()

        assertEquals(1, store.current().serverPort)
        assertEquals(AppSettings.MAX_FREQ_THRESHOLD, store.current().freqThreshold)
    }

    // ==================== 损坏恢复 ====================

    @Test
    fun `文件损坏被当成全是默认值而不是抛异常`() = runBlocking {
        val file = tmp.root.resolve("corrupt.preferences_pb")
        // 半截 protobuf：进程在写入中途被杀时留下的就是这种东西。
        // `0x0A 0x7F` = 字段 1、wire type 2、长度 127，但后面只有 5 个字节 → 必然解析失败。
        file.writeBytes(byteArrayOf(0x0A, 0x7F, 0xFF.toByte(), 0x00, 0x13, 0x28, 0xFF.toByte()))

        val store = SettingsStore(newDataStore(file))
        store.start(storeScope)
        store.awaitLoaded()          // 不许抛，也不许永久挂起

        assertEquals(AppSettings(), store.current())

        // 损坏之后**还能继续写**。DataStore 的默认行为是此后每次读写都抛 IOException，
        // 那样 App 会永久崩在启动页，只有清数据才能救 —— 比"设置回默认值"糟糕得多。
        store.mutate { it.copy(freqThreshold = 5) }
        assertEquals(5, store.current().freqThreshold)
    }
}
