package com.example.myapplication.app

import android.app.Application
import android.content.Context

/**
 * 进程入口。唯一的职责是**建好 [ServiceLocator] 并把设置监听跑起来**。
 *
 * ## ⚠ `onCreate` 里不许做任何可能阻塞或抛异常的事
 *
 * 这个函数跑在主线程上，而它的耗时会**直接加到冷启动时间**里。更要紧的是它抛异常
 * 就等于 App 起不来，而下面这些东西恰恰都是"可能失败但失败了也该让 App 开着"的：
 *  - DataStore 文件损坏（挂了 `ReplaceFileCorruptionHandler`，降级成默认设置，见 [SettingsStore]）；
 *  - AndroidKeyStore 不可用（少数 ROM / OTA 之后，降级成"不能自动登录"）；
 *  - `res/raw/s1_ca.pem` 缺失或不是 CA 证书（推迟到第一次联网时才碰，见 [ServiceLocator.api]）。
 *
 * 所以这里只做两件事：new 一个 locator、调 [ServiceLocator.settings] 的 `start`
 * （已经在 locator 的字段初始化里做了）。Room 的打开、凭据的解密、TLS 的构造全部懒。
 */
class App : Application() {

    /** 在 [onCreate] 里赋值。之后与进程同长，不重建。 */
    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator(this)
        // 只是 launch 一个 collector，不阻塞：Room 的打开发生在 appScope 的后台线程上。
        // 必须在这里启动而不是等某个页面第一次用到 —— [ServiceLocator.currentOwnerName]
        // 在登出状态下要靠这份 store_meta 副本才能拼出正确的 fileId，
        // 晚启动就会有一段时间退回 `__local__`，那期间开的单会落进一个只有本机知道的文件。
        locator.startWatchers()
    }

    companion object {
        /**
         * 从任意 [Context] 拿到 locator。
         *
         * 用 `applicationContext` 强转而不是让调用方自己持有 Application 引用，
         * 是因为 Activity / Service 的 context 传进来也必须能用。
         *
         * @throws ClassCastException 只在**单元测试或别的 Application 实现**下发生。
         *         正式构建里 `AndroidManifest` 的 `<application android:name=".app.App">` 保证了类型。
         */
        fun locatorOf(context: Context): ServiceLocator =
            (context.applicationContext as App).locator
    }
}
