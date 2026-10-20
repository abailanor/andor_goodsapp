package com.example.myapplication.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppSettings] 的测试。
 *
 * 挑出来测的都是"**写错了不会崩、但会造成真实损害**"的部分：
 *  - host 规范化写错 → 拼出的 URL 被 OkHttp 误解析（IPv6 少方括号时端口是错的，**且不报错**）；
 *  - 通知默认值写反 → 一天上百单把通知栏刷满，用户直接关掉整个 App 的通知权限；
 *  - 通知开关存成"已启用集合"而不是"对默认值的偏离" → 以后每加一个新事件类型，
 *    它对**所有老用户**都是关的，即使代码里 `defaultEnabled = true`。
 */
class AppSettingsTest {

    // ==================== 默认值 ====================

    @Test
    fun `默认值与方案约定一致`() {
        val s = AppSettings()
        // 频率阈值默认 3（用户明确要求"全局、可调、默认 3"）
        assertEquals(3, s.freqThreshold)
        assertEquals(FreqWindow.ONE_MINUTE, s.freqWindow)
        // 后台默认仅前台（省电；常驻前台服务要用户主动开）
        assertEquals(BackgroundMode.FOREGROUND_ONLY, s.backgroundMode)
        // 默认走 TLS，端口对得上服务端 application-tls.properties
        assertTrue(s.useTls)
        assertEquals("https://10.0.2.2:8443", s.baseUrl)
        // 开单默认显示整箱框、按箱价+零散算
        assertTrue(s.showBoxInput)
        assertEquals(PricingMode.BOX_PLUS_SINGLES, s.pricingMode)
        // 即时价格提醒默认关：频率阈值机制已经覆盖了"有人狂改价"
        assertFalse(s.notifyPriceChangeImmediately)
    }

    @Test
    fun `默认只开不知道就会造成损失的那几类事件`() {
        // 这四类默认开：被踢、版本冲突、库存转负、权限被改、商品被删
        assertTrue(AppSettings().isNotifyEnabled(NotifyEvent.KICKED))
        assertTrue(AppSettings().isNotifyEnabled(NotifyEvent.SERVER_BEHIND))
        assertTrue(AppSettings().isNotifyEnabled(NotifyEvent.STOCK_NEGATIVE))
        assertTrue(AppSettings().isNotifyEnabled(NotifyEvent.MY_PERMISSIONS_CHANGED))
        assertTrue(AppSettings().isNotifyEnabled(NotifyEvent.PRODUCT_DELETED))
        // 高频低信息量的四类默认关
        assertFalse(AppSettings().isNotifyEnabled(NotifyEvent.PRICE_CHANGED))
        assertFalse(AppSettings().isNotifyEnabled(NotifyEvent.STOCK_CHANGED))
        assertFalse(AppSettings().isNotifyEnabled(NotifyEvent.NEW_ORDER))
        assertFalse(AppSettings().isNotifyEnabled(NotifyEvent.ORDER_CANCELLED))
    }

    // ==================== host 规范化 ====================

    @Test
    fun `normalizeHost 去掉用户粘进来的 scheme 路径和空白`() {
        assertEquals("192.168.1.20", AppSettings.normalizeHost("  https://192.168.1.20  "))
        assertEquals("192.168.1.20", AppSettings.normalizeHost("http://192.168.1.20:8443/api/auth/login"))
        assertEquals("192.168.1.20", AppSettings.normalizeHost("192.168.1.20/"))
        assertEquals("192.168.1.20", AppSettings.normalizeHost("192.168 . 1.20"))
        // 主机名大小写不敏感，统一小写（也让证书 SAN 比对稳定）
        assertEquals("shop.example.com", AppSettings.normalizeHost("SHOP.Example.COM"))
    }

    @Test
    fun `normalizeHost 去掉 userInfo`() {
        // `user:pass@host` 不是服务器地址；留下它会让 OkHttp 把凭据发到 URL 里
        assertEquals("10.0.0.5", AppSettings.normalizeHost("http://alice:pw@10.0.0.5:8443"))
    }

    @Test
    fun `normalizeHost 给裸 IPv6 补方括号`() {
        // ⚠ 少了方括号时 `https://fe80::1:8443` 里的 `:8443` 会被当成 IPv6 的最后一段，
        // OkHttp 解析出的端口是错的，而且**不报错**。这是这里唯一真正危险的分支。
        assertEquals("[fe80::1]", AppSettings.normalizeHost("fe80::1"))
        assertEquals("[2001:db8::ff00:42:8329]", AppSettings.normalizeHost("2001:db8::ff00:42:8329"))
        assertEquals("https://[fe80::1]:8443", AppSettings().withHost("fe80::1").baseUrl)
    }

    @Test
    fun `normalizeHost 已经带方括号的 IPv6 不重复加`() {
        assertEquals("[fe80::1]", AppSettings.normalizeHost("[fe80::1]"))
        assertEquals("[fe80::1]", AppSettings.normalizeHost("https://[fe80::1]:8443/x"))
    }

    @Test
    fun `withHost 在输入是垃圾时保留旧值`() {
        val s = AppSettings(serverHost = "10.1.2.3")
        // 用户在输入框里删到空、或者只粘了个 scheme：不该把好用的地址冲掉。
        // "地址没变" 比 "地址被静默清空" 好排查得多。
        assertEquals("10.1.2.3", s.withHost("").serverHost)
        assertEquals("10.1.2.3", s.withHost("   ").serverHost)
        assertEquals("10.1.2.3", s.withHost("https://").serverHost)
        assertEquals("10.1.2.3", s.withHost("///").serverHost)
    }

    @Test
    fun `withHost 把粘进来的端口一并收进 serverPort`() {
        // 设置页里 host 与 port 是两个框，但从别处复制地址时总是连着端口一起复制。
        // 丢掉端口只会让人以为"改了没生效"。
        val s = AppSettings().withHost("10.0.0.5:9000")
        assertEquals("10.0.0.5", s.serverHost)
        assertEquals(9000, s.serverPort)
        // 端口不是数字就忽略，主机名照收
        val s2 = AppSettings(serverPort = 8443).withHost("10.0.0.5:abc")
        assertEquals("10.0.0.5", s2.serverHost)
        assertEquals(8443, s2.serverPort)
        // IPv6 带端口
        val s3 = AppSettings().withHost("[fe80::1]:9000")
        assertEquals("[fe80::1]", s3.serverHost)
        assertEquals(9000, s3.serverPort)
    }

    @Test
    fun `normalizeHost 认不出来的输入返回空串`() {
        assertEquals("", AppSettings.normalizeHost("[fe80::1"))     // 方括号没闭合
        assertEquals("", AppSettings.normalizeHost(":8443"))        // 没主机名
        assertEquals("", AppSettings.normalizeHost("@"))
    }

    @Test
    fun `baseUrl 端口总是显式写出来`() {
        // 不靠 scheme 的默认端口推断，避免"用户以为改了 443 其实连的是 8080"
        assertEquals(
            "https://h:443",
            AppSettings(serverHost = "h", serverPort = 443, useTls = true).baseUrl,
        )
        assertEquals(
            "http://h:8080",
            AppSettings(serverHost = "h", serverPort = 8080, useTls = false).baseUrl,
        )
    }

    // ==================== 钳位 ====================

    @Test
    fun `withPort 越界钳位不抛`() {
        assertEquals(1, AppSettings().withPort(0).serverPort)
        assertEquals(65535, AppSettings().withPort(70000).serverPort)
        // 负数钳到**下界**（`coerceIn` 的语义），不是上界。这里要验的是"不回绕、不抛"。
        assertEquals(1, AppSettings().withPort(-5).serverPort)
        assertEquals(9000, AppSettings().withPort(9000).serverPort)
    }

    @Test
    fun `withFreqThreshold 下界是 1 而不是 0`() {
        // 0 会让 `count >= threshold` 恒真 → 每次变更都发通知，等于把节流机制废掉
        assertEquals(1, AppSettings().withFreqThreshold(0).freqThreshold)
        assertEquals(1, AppSettings().withFreqThreshold(-10).freqThreshold)
        assertEquals(AppSettings.MAX_FREQ_THRESHOLD, AppSettings().withFreqThreshold(999_999).freqThreshold)
        assertEquals(7, AppSettings().withFreqThreshold(7).freqThreshold)
    }

    // ==================== 通知开关存的是"对默认值的偏离" ====================

    @Test
    fun `改回默认值之后偏离集合是空的`() {
        var s = AppSettings()
        // KICKED 默认开 → 关掉它是一次"偏离"
        s = s.withNotify(NotifyEvent.KICKED, false)
        assertEquals(setOf(NotifyEvent.KICKED), s.notifyDisabledOverrides)
        assertFalse(s.isNotifyEnabled(NotifyEvent.KICKED))

        // 再打开 → 回到默认，偏离应当被**清掉**而不是留在 enabled 集合里
        s = s.withNotify(NotifyEvent.KICKED, true)
        assertTrue(s.notifyDisabledOverrides.isEmpty())
        assertTrue(s.notifyEnabledOverrides.isEmpty())
        assertTrue(s.isNotifyEnabled(NotifyEvent.KICKED))
    }

    @Test
    fun `同一个事件不会同时出现在两个偏离集合里`() {
        var s = AppSettings()
            .withNotify(NotifyEvent.NEW_ORDER, true)      // 默认关 → 显式开
            .withNotify(NotifyEvent.NEW_ORDER, false)     // 又关掉 → 回到默认
        assertTrue(s.notifyEnabledOverrides.isEmpty())
        assertTrue(s.notifyDisabledOverrides.isEmpty())

        s = s.withNotify(NotifyEvent.PRICE_CHANGED, true)
            .withNotify(NotifyEvent.PRICE_CHANGED, false)
            .withNotify(NotifyEvent.PRICE_CHANGED, true)
        assertEquals(setOf(NotifyEvent.PRICE_CHANGED), s.notifyEnabledOverrides)
        assertTrue(s.notifyDisabledOverrides.isEmpty())
    }

    @Test
    fun `新加的事件类型不会因为不在存量集合里而被误关`() {
        // 这就是"存偏离"而不是"存已启用集合"的全部意义。
        // 模拟一个老用户：他显式关掉了 KICKED，其余没动过。
        val oldUser = AppSettings(notifyDisabledOverrides = setOf(NotifyEvent.KICKED))
        // 将来新增一个 defaultEnabled=true 的事件（这里拿 PRODUCT_DELETED 代替"新事件"，
        // 因为枚举没法在测试里凭空多出一个值）。它不在任何偏离集合里 → 按默认值生效。
        assertTrue(oldUser.isNotifyEnabled(NotifyEvent.PRODUCT_DELETED))
        assertFalse(oldUser.isNotifyEnabled(NotifyEvent.KICKED))
    }

    // ==================== 跨项约束 ====================

    @Test
    fun `关掉整箱输入时计价方式退回按个数`() {
        val s = AppSettings(showBoxInput = false, pricingMode = PricingMode.BOX_PLUS_SINGLES)
        assertEquals(PricingMode.ALL_SINGLES, s.effective.pricingMode)
        // 用户自己选的就是按个数，不动
        val s2 = AppSettings(showBoxInput = false, pricingMode = PricingMode.ALL_SINGLES)
        assertEquals(PricingMode.ALL_SINGLES, s2.effective.pricingMode)
        // 整箱框开着时不改
        val s3 = AppSettings(showBoxInput = true, pricingMode = PricingMode.BOX_PLUS_SINGLES)
        assertEquals(PricingMode.BOX_PLUS_SINGLES, s3.effective.pricingMode)
        // effective 是派生值，原对象不变
        assertEquals(PricingMode.BOX_PLUS_SINGLES, s.pricingMode)
    }

    @Test
    fun `normalized 把所有越界值收回来且是不动点`() {
        val bad = AppSettings(serverHost = "", serverPort = 0, adminCode = "   ", freqThreshold = -5)
        val n = bad.normalized()
        assertEquals(AppSettings.DEFAULT_HOST, n.serverHost)
        assertEquals(1, n.serverPort)
        assertEquals(AppSettings.DEFAULT_ADMIN_CODE, n.adminCode)
        assertEquals(AppSettings.MIN_FREQ_THRESHOLD, n.freqThreshold)
        // 不动点：SettingsStore 依赖这一条来保证"写进去的"与"回读出来的"恒等
        assertEquals(n, n.normalized())
        // 合法值不受影响
        val ok = AppSettings(serverHost = "shop.lan", serverPort = 9000, freqThreshold = 7)
        assertEquals(ok, ok.normalized())
    }

    @Test
    fun `FreqWindow 的秒数与毫秒对得上`() {
        assertEquals(30, FreqWindow.THIRTY_SECONDS.seconds)
        assertEquals(60_000L, FreqWindow.ONE_MINUTE.millis)
        assertEquals(300_000L, FreqWindow.FIVE_MINUTES.millis)
    }
}
