package com.example.myapplication.settings

/**
 * 用户可配置项的**全量快照**（不可变值对象）。
 *
 * ## 为什么是一个 data class 而不是几十个分散的 getter
 *
 * 设置项之间有**跨项约束**，分散读会漏：
 *  - `notifyPriceChangeImmediately = true` 时，"员工改价"这个事件的开关和频率阈值都不该拦它；
 *  - `backgroundMode = FOREGROUND_ONLY` 时，前台服务相关的通知渠道压根不该建；
 *  - `pricingMode` 决定结算框里"整箱"那一栏是否参与金额计算，而 `showBoxInput=false`
 *    时用户根本填不出箱数 —— 两者不一致时以 `showBoxInput` 为准。
 *
 * 拿到一个完整快照才能在一个地方把这些关系写清楚（见 [effective]），
 * 也才能"改了任意一项就整体重算派生值"，而不是各处各自缓存。
 *
 * ## ⚠ 这个类**不含**任何 Android / DataStore 类型
 *
 * 默认值、取值范围、host 规范化都是"写错了会让 App 连不上服务器或狂发通知"的逻辑，
 * 必须能在 JVM 单测里覆盖（`AppSettingsTest`）。所以序列化到 DataStore 的胶水
 * 全部放在 [SettingsStore]，这里一个 `Preferences.Key` 都不出现。
 *
 * ## 加新设置项的规矩
 *
 * 只加字段 + 给默认值，**不要**写迁移。[SettingsStore] 对缺失的键一律取默认值，
 * 所以老用户升级后新项自动是默认状态。反过来说：**改一个已有项的默认值不会影响老用户**
 * （他们的值已经落盘了），只在需要强制刷新时才改键名。
 */
data class AppSettings(

    // ==================== 服务器 ====================

    /**
     * 服务器地址。**只存主机名/IP，不含 scheme、不含端口、不含路径**。
     * 用 [withHost] 写入，它会把用户可能粘进来的 `https://`、尾部 `/`、空格清掉。
     *
     * 默认 `10.0.2.2` 是 Android 模拟器访问宿主机的固定别名。
     *
     * ⚠ 改这个值**可能需要重签服务端证书**：TLS 走的是内置自签 CA + 证书 SAN 校验
     * （见 `HttpClients` 的说明），SAN 里没有的新 IP 会握手失败并报"证书与地址不匹配"。
     * 这是正确行为。CA 不变，所以 App 不用重装，重跑 `tools/gen-cert.sh` 即可。
     */
    val serverHost: String = DEFAULT_HOST,

    /** TLS profile 下服务端是 8443（`application-tls.properties:27`）；明文调试是 8080。 */
    val serverPort: Int = DEFAULT_TLS_PORT,

    /**
     * 是否走 HTTPS。
     *
     * 关掉它只应当在**内网调试**时发生（服务端不带 `--spring.profiles.active=tls`）。
     * ⚠ 关掉之后 JWT 与登录密码都是明文上网的，UI 上要写清楚，不能只放一个开关。
     */
    val useTls: Boolean = true,

    /**
     * 总部（丽华金龙）的 merchantCode。用于 `admin-catalog` 与"这个商品能不能直接向总部进货"的判断。
     *
     * 总部账户是直接写进 Redis 的（`seed_admin.py`），代码里 merchantCode 固定 `00001`。
     * 放成设置项是为了将来换总部账户时不用重新打包。
     */
    val adminCode: String = DEFAULT_ADMIN_CODE,

    // ==================== 开单 ====================

    /**
     * 开单页每行是否显示"整箱"输入框。
     *
     * 关掉之后**全部按零散算**（`boxCount = 0`），也**不会**再弹缺箱规表单 ——
     * 这是"我不按箱卖"的用户的正确出口，别把它做成只是隐藏 UI 却仍然要求箱规。
     */
    val showBoxInput: Boolean = true,

    /** 金额的默认算法。结算框里可以临时切换，临时切换**不写回**这里。 */
    val pricingMode: PricingMode = PricingMode.BOX_PLUS_SINGLES,

    /**
     * 载入挂起订单（pending）时是否按当前价格重扫一遍。
     *
     * 关掉 = 用挂起时存的原价。开了 = 显示新价并让用户确认。
     * 与 outbox 的"价格漂移提示"是两套机制（一个在载入草稿时，一个在上传前），别合并。
     */
    val rescanPricesOnLoadPending: Boolean = true,

    // ==================== 通知 ====================

    /**
     * 价格变更**即时**提醒：开启后 SSE 的 `UPDATE` 里只要含 `priceStreamEntries` 就立刻发通知，
     * **不受** [freqThreshold] / [freqWindow] 约束。
     *
     * 默认关：频率阈值机制已经覆盖了"有人狂改价"这个真正的诉求，
     * 而单次改价即时弹通知对绝大多数店铺是噪音。
     */
    val notifyPriceChangeImmediately: Boolean = false,

    /**
     * "修改频率高于 X 次就提醒"的 X。**全局**计数（不分商品、不分人），默认 **3**。
     *
     * 范围钳到 [MIN_FREQ_THRESHOLD]..[MAX_FREQ_THRESHOLD]：
     * 下界 1 是因为 0 会让 `count >= 0` 恒真、每次变更都触发；
     * 上界是防止手滑输入一个大数把通知彻底关掉还以为它坏了。
     */
    val freqThreshold: Int = DEFAULT_FREQ_THRESHOLD,

    /** 频率统计的时间窗。默认 1 分钟。 */
    val freqWindow: FreqWindow = FreqWindow.ONE_MINUTE,

    /**
     * 用户**显式关掉**的事件。与 [notifyEnabledOverrides] 一起构成"只对默认值的偏离"。
     *
     * ## ⚠ 为什么不直接存一个"已启用的事件集合"
     *
     * 那样每加一个新事件类型，老用户的存量集合里都没有它 → 新事件对所有人默认关闭，
     * 即使 [NotifyEvent.defaultEnabled] 是 true。这是设置持久化最经典的一个坑。
     * 存"偏离"则不会：新事件按 [NotifyEvent.defaultEnabled] 生效，直到用户手动改。
     */
    val notifyDisabledOverrides: Set<NotifyEvent> = emptySet(),

    /** 用户**显式打开**的事件（覆盖 `defaultEnabled = false` 的那些）。 */
    val notifyEnabledOverrides: Set<NotifyEvent> = emptySet(),

    // ==================== 登录 ====================

    /**
     * 冷启动时怎么处理登录。默认 [LoginMode.ASK]，也就是**只在第一次启动时问**。
     *
     * 用户的选择必须沿用（"首次选择登录或离线，下一次就沿用上一次的选择"），
     * 所以它得持久化：每次启动都弹一遍登录页，用户第三次就会烦，
     * 而"离线也能完整使用"是这个 App 的设计前提，不该被一个登录框挡住。
     */
    val loginMode: LoginMode = LoginMode.ASK,

    /**
     * 营业额里**算不算上传失败的订单**（`FAILED_PERMANENT`：重推到顶 / 413 / 400）。
     *
     * **默认算**：货已经给客人了、钱真收了，不算的话店主当天对账会发现现金比营业额多，
     * 而他不知道差在哪。想只看"成功进了系统的钱"的人可以关掉。
     *
     * ⚠ 这个开关只在**读取侧**生效（`observeContribInRange` 的 `includeFailed`），
     * 切换它不写库。靠改库实现的话，来回切几次之后任何一次搬错都会永久留在账上。
     */
    val countFailedOrdersInRevenue: Boolean = true,

    /**
     * 上一次活动的店铺（merchantCode）。空串 = 这台设备从没成功登录过。
     *
     * ## 为什么必须持久化
     *
     * 登出**不切回** `__local__`（作用域停在原店铺，见 `ServiceLocator.setSession`），
     * 所以冷启动时必须知道该把作用域恢复到哪一家店 —— 否则界面上是 `__local__` 的空数据，
     * 而用户明明昨天还在正常用。
     *
     * ⚠ 不能靠 `store_meta` 反推：那张表可能有多行（这台设备登录过好几家店），
     * `ORDER BY adoptedAt DESC` 给出的是"最早被收养的那家"，不是"最后用的那家"。
     */
    val lastStoreKey: String = "",

    // ==================== 后台 ====================

    /**
     * 默认"仅前台"：退到后台就断开 SSE，回前台带版本号重连。
     * 可选"常驻前台服务"：`dataSync` 类型 + 常驻通知。
     *
     * ⚠ Android 15 对 `dataSync` 有 6 小时/24 小时的限制，国产 ROM 还会额外杀进程，
     * 所以选了常驻也要引导用户加自启动白名单（P6 的设置页负责提示）。
     */
    val backgroundMode: BackgroundMode = BackgroundMode.FOREGROUND_ONLY,

    // ==================== 打印 ====================

    /** 小票上是否印条形码（商品条码 EAN-13 / 订单号 Code128）。 */
    val printBarcode: Boolean = true,

    /**
     * 小票抬头的商户信息。原先硬编码在 `PrintUtil.kt` 里
     * （`电话：15288180414`、`地址：官渡区新广丰B区14-16`），改成设置项。
     */
    val printShopName: String = "",
    val printShopPhone: String = "",
    val printShopAddress: String = "",
) {

    /** 服务端的 base URL。**端口总是显式写出来**，不靠 scheme 默认端口推断。 */
    val baseUrl: String
        get() = "${if (useTls) "https" else "http"}://$serverHost:$serverPort"

    /** 某个事件当前是否要通知。默认值来自 [NotifyEvent.defaultEnabled]，被用户改过则以改过的为准。 */
    fun isNotifyEnabled(event: NotifyEvent): Boolean = when (event) {
        in notifyDisabledOverrides -> false
        in notifyEnabledOverrides -> true
        else -> event.defaultEnabled
    }

    /**
     * 跨项约束算完之后的**生效值**。UI 与 notify 层一律读这个，不要自己再判一遍。
     *
     * 目前只有一条：关掉整箱输入框时，按箱计价就无从谈起，强制退回按个数计价。
     */
    val effective: AppSettings
        get() = if (!showBoxInput && pricingMode == PricingMode.BOX_PLUS_SINGLES) {
            copy(pricingMode = PricingMode.ALL_SINGLES)
        } else {
            this
        }

    /**
     * 把所有取值范围约束应用一遍，得到一个**不动点**：`normalized().normalized() == normalized()`。
     *
     * ## ⚠ 为什么必须有这个函数，而不是只在读盘时钳位
     *
     * [SettingsStore.mutate] 写完盘会**立刻**把新值放进内存 StateFlow（不等 DataStore 的
     * collector 绕一圈回来），否则 UI 拨一下开关要过一会儿才变。
     *
     * 但如果只在 `fromPrefs` 里钳位、写入时不钳，就会出现：
     * `mutate { it.copy(freqThreshold = 999999) }` → 内存里是 999999、磁盘上是 999999、
     * collector 回读之后内存变成 9999 —— **同一个设置项先显示 999999 再自己跳成 9999**。
     * 写入前也走一遍 [normalized] 之后，内存值、磁盘值、回读值三者恒等。
     */
    fun normalized(): AppSettings = copy(
        serverHost = serverHost.ifBlank { DEFAULT_HOST },
        serverPort = serverPort.coerceIn(1, 65535),
        adminCode = adminCode.ifBlank { DEFAULT_ADMIN_CODE },
        freqThreshold = freqThreshold.coerceIn(MIN_FREQ_THRESHOLD, MAX_FREQ_THRESHOLD),
    )

    /**
     * 写入服务器地址。会做规范化（去 scheme、去路径、去空白、去 userInfo、IPv6 补方括号、转小写）。
     *
     * 用户粘进来的 `host:port` 会**同时**更新 [serverPort] —— 设置页里 host 与 port 是两个输入框，
     * 但从别处复制地址时几乎总是连着端口一起复制，把它丢掉只会让人以为"改了没生效"。
     *
     * **规范化后为空则原样保留旧值** —— 用户在输入框里删到空、或者粘了一段垃圾，
     * 不该把一个好用的地址冲掉；连接失败的错误提示比"地址被静默清空"好排查得多。
     */
    fun withHost(raw: String): AppSettings {
        val hp = parseHostPort(raw)
        if (hp.host.isEmpty()) return this
        val next = copy(serverHost = hp.host)
        return if (hp.port != null) next.withPort(hp.port) else next
    }

    /** 写入端口。越界则钳到范围内，不抛。 */
    fun withPort(raw: Int): AppSettings = copy(serverPort = raw.coerceIn(1, 65535))

    /** 写入频率阈值。越界则钳到范围内，不抛。 */
    fun withFreqThreshold(raw: Int): AppSettings =
        copy(freqThreshold = raw.coerceIn(MIN_FREQ_THRESHOLD, MAX_FREQ_THRESHOLD))

    /** 切换某个事件的通知开关（写成"对默认值的偏离"，见 [notifyDisabledOverrides] 的说明）。 */
    fun withNotify(event: NotifyEvent, enabled: Boolean): AppSettings {
        val disabled = notifyDisabledOverrides.toMutableSet()
        val enabledSet = notifyEnabledOverrides.toMutableSet()
        disabled.remove(event)
        enabledSet.remove(event)
        if (enabled != event.defaultEnabled) {
            if (enabled) enabledSet.add(event) else disabled.add(event)
        }
        return copy(notifyDisabledOverrides = disabled, notifyEnabledOverrides = enabledSet)
    }

    companion object {
        /** Android 模拟器访问宿主机的固定别名。 */
        const val DEFAULT_HOST = "10.0.2.2"

        /** `application-tls.properties:27`。 */
        const val DEFAULT_TLS_PORT = 8443

        /** `application.properties:86`（不带 tls profile 的明文调试端口）。 */
        const val DEFAULT_PLAIN_PORT = 8080

        /** `seed_admin.py` 里总部账户的 merchantCode。 */
        const val DEFAULT_ADMIN_CODE = "00001"

        const val DEFAULT_FREQ_THRESHOLD = 3
        const val MIN_FREQ_THRESHOLD = 1
        const val MAX_FREQ_THRESHOLD = 9999

        /**
         * 规范化用户输入的地址。[HostPort.host] 一定不含端口，为空表示"认不出来"。
         *
         * 按顺序做：trim → 去 scheme → 截断到第一个 `/`、`?`、`#` → 去 `userInfo` →
         * 去内部空白 → 分离端口 → IPv6 补方括号 → 转小写。
         *
         * ## ⚠ "含冒号就是 IPv6" 是错的
         *
         * `10.0.0.5:8443` 也含冒号。按冒号数量判：
         *  - 0 个 → 纯主机名；
         *  - 1 个 → `host:port`；
         *  - ≥2 个 → 裸 IPv6，**必须补方括号**。
         *
         * 补方括号不是洁癖：`https://fe80::1:8443` 里的 `:8443` 会被当成 IPv6 地址的
         * 最后一段，OkHttp 解析出的端口是错的，而且**不报错** —— 表现为"连不上"，
         * 而日志里的地址看起来完全正常。
         *
         * ## ⚠ 不要在这里做 DNS 或格式强校验
         *
         * 合法的 hostname 形态太多（IDN、内网 DNS 里能用的下划线、`localhost`），
         * 校验严了会把能用的地址拒掉。这里只保证"拼出来的 URL 不会被误解析"，
         * 真正的连通性由连接失败来告诉用户。
         */
        fun parseHostPort(raw: String): HostPort {
            var s = raw.trim()
            val schemeIdx = s.indexOf("://")
            if (schemeIdx >= 0) s = s.substring(schemeIdx + 3)
            // 去掉路径/查询/片段。`userInfo`（`user:pw@host`）也一并去掉：它不属于服务器地址，
            // 留在里面会让 OkHttp 把凭据放进 URL。
            s = s.substringBefore('/').substringBefore('?').substringBefore('#')
            s = s.substringAfterLast('@')
            s = s.filter { !it.isWhitespace() }
            if (s.isEmpty()) return HostPort("", null)

            // 已经带方括号：`[::1]` 或 `[::1]:8443`
            if (s.startsWith("[")) {
                val end = s.indexOf(']')
                if (end < 0) return HostPort("", null)          // 方括号没闭合，认不出来
                val rest = s.substring(end + 1)
                val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() else null
                return HostPort(s.substring(0, end + 1).lowercase(), port)
            }

            return when (val colons = s.count { it == ':' }) {
                0 -> HostPort(s.lowercase(), null)
                1 -> {
                    val host = s.substringBefore(':').lowercase()
                    if (host.isEmpty()) HostPort("", null)      // `:8443` 这种没主机名的
                    else HostPort(host, s.substringAfter(':').toIntOrNull())
                }
                // 裸 IPv6。⚠ 必须写 `${s.lowercase()}` 而不是 `$s.lowercase()`：
                // 后者只插值 `s`，再把字面量 `.lowercase()` 拼在后面 ——
                // 得到 `[FE80::1.lowercase()]` 这种垃圾地址，而且**编译期没有任何警告**。
                else -> HostPort("[${s.lowercase()}]", null)
            }
        }

        /** 只要主机名部分。见 [parseHostPort]。 */
        fun normalizeHost(raw: String): String = parseHostPort(raw).host
    }
}

/** [AppSettings.parseHostPort] 的结果。[port] 为 null 表示输入里没带（可识别的）端口。 */
data class HostPort(val host: String, val port: Int?)

/** 金额算法。见方案 §5.1 第 6 条。 */
enum class PricingMode {
    /** `箱数 × boxPrice + 零散数 × price`（默认）。 */
    BOX_PLUS_SINGLES,

    /** `quantity × price`，即整箱也按单价算。用于"箱价没维护好"的过渡期。 */
    ALL_SINGLES,
}

/** 频率统计的时间窗。 */
enum class FreqWindow(val seconds: Int) {
    THIRTY_SECONDS(30),
    ONE_MINUTE(60),
    FIVE_MINUTES(300),
    ;

    val millis: Long get() = seconds * 1000L
}

/** 后台运行方式。 */
enum class BackgroundMode {
    /** 退后台断 SSE，回前台重连。省电，但离线期间的变更要靠重连时的版本号补齐。 */
    FOREGROUND_ONLY,

    /** `dataSync` 前台服务 + 常驻通知。 */
    PERSISTENT_SERVICE,
}

/**
 * 冷启动时的登录策略。
 *
 * 状态迁移只有三条，都是单向的（除了用户在设置里显式改）：
 * ```
 * ASK ──用户选"离线"──→ OFFLINE
 * ASK ──登录成功────→ AUTO
 * OFFLINE ──之后登录成功──→ AUTO      （"先离线用一段时间再登录，之后都自动登录"）
 * ```
 */
enum class LoginMode {
    /** 还没选过。启动时显示"登录 / 继续离线使用"两个入口。 */
    ASK,

    /**
     * 用户选过离线，之后启动**不再问**，直接进离线模式。
     *
     * 想登录随时可以从设置里进 —— 离线是可用的完整形态，不是残废状态，
     * 所以不该每次启动都拿登录框拦一道。
     */
    OFFLINE,

    /**
     * 登录成功过，之后启动**静默自动登录**（用 [com.example.myapplication.security.CredentialStore]
     * 里存的凭据）。失败就降级成离线继续用，**不弹框挡住用户**。
     *
     * ⚠ 自动重登的四条约束见 `ApiErrorClassifier.mayAutoRelogin` 的注释：
     * 全局串行、间隔 ≥12 秒、一次会话最多一次、收到过 SSE `KICKED` 就永久闩死。
     * 单会话策略下无脑自动重登会和新登录方互相踢，形成风暴。
     */
    AUTO,
}

/**
 * 可单独开关的通知事件。
 *
 * ## [defaultEnabled] 的取值依据
 *
 * 默认开的都是"**不知道就会造成损失**"的：被踢下线（会以为 App 坏了）、
 * 版本冲突（数据有分歧风险）、库存转负（对账已经在告警了）、权限被改（可能被降权而不自知）。
 *
 * 默认关的是"**高频且低信息量**"的：每笔新订单、每次取消、每次改价、每次改库存。
 * 一个 busy 店铺一天上百单，全开等于把通知栏刷满，用户最后会关掉整个 App 的通知权限 ——
 * 那比少通知几件事糟糕得多。这些事件仍然会写进变更日志（`log/ChangeLog`），要看随时能看。
 */
enum class NotifyEvent(val defaultEnabled: Boolean) {
    /** 员工改了商品价格。 */
    PRICE_CHANGED(false),

    /** 员工改了库存。 */
    STOCK_CHANGED(false),

    /** 有人下了新单。⚠ SSE 会把自己刚下的单**回声**回来，通知层必须按 `operator` 过滤掉自己。 */
    NEW_ORDER(false),

    /** 有订单被取消。 */
    ORDER_CANCELLED(false),

    /** 商品被删除。 */
    PRODUCT_DELETED(true),

    /** 我的权限被改了（`PERMISSIONS` 事件）。 */
    MY_PERMISSIONS_CHANGED(true),

    /** 被踢下线（SSE `KICKED`，或 401=别处登录）。**这个必须默认开**。 */
    KICKED(true),

    /** 订单文件 409（服务器版本落后于本地）。数据有分歧风险，必须默认开。 */
    SERVER_BEHIND(true),

    /** 库存变成负数（服务端会回 `warning = "商品数目存疑（负数）"`）。 */
    STOCK_NEGATIVE(true),
}
