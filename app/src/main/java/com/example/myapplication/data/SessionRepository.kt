package com.example.myapplication.data

import com.example.myapplication.db.LOCAL_STORE_KEY
import com.example.myapplication.db.StoreMeta
import com.example.myapplication.db.StoreMetaDao
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.AuthApi
import com.example.myapplication.net.dto.LoginResponseDto
import com.example.myapplication.security.Credentials
import com.example.myapplication.settings.LoginMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 登录会话的编排层：登录 / 自动登录 / 登出 / 换店，以及离线数据的**收养**。
 *
 * ## 登录绝不阻塞用户
 *
 * 用户点完登录就该立刻回到主界面继续开单，登录在后台跑完，
 * 状态靠 [state] 反映（导航栏：登录成功=白，其余=灰）。
 * 所以对外暴露的是 [loginAsync] 这类**立刻返回**的方法，[login] 那个 suspend 版本
 * 是给测试与"确实想等结果"的调用方用的。
 *
 * ## 四条不能搞错的顺序
 *
 *  1. **收养在切作用域之前**。`AdoptionTx` 把 `__local__` 的行改写成真实 merchantCode，
 *     改完再 `switchStore` 才看得见。反过来的话会有一段"作用域已切、数据还没搬"的空窗，
 *     用户看到的是空店铺。
 *  2. **读 `store_meta` 在收养之后**。收养会把 `__local__` 那行搬成 merchantCode，
 *     而那行里的 `enumFloor` 可能已经被离线期间写入的订单月份推得更早。
 *     在收养前读就读不到它。
 *  3. **`ownerName` 校验在写库之前**。拿不到归属者就绝不能落任何状态 ——
 *     否则 `store_meta.ownerName` 是错的，此后每一笔订单都落进错误的文件。
 *  4. **`upsert` 是 `REPLACE`**，所以必须读-合并-写。直接写一个新 `StoreMeta`
 *     会把 `enumFloor` / `priceVersion` / `stockVersion` / `adoptedAt` 全部冲掉。
 *     其中 `enumFloor` 最贵：它是"只往前推不往后收"的单调量，
 *     被冲掉就意味着**补传的历史订单月份永远不会被同步**，而且不会报错。
 */
class SessionRepository(
    private val auth: AuthApi,
    private val storeMetaDao: StoreMetaDao,
    private val sessionStore: SessionStore,
    private val adoption: AdoptionTx,
    /** 后台登录跑在这上面。**不能**用 ViewModel 的 scope —— 那个会随着离开登录页被取消。 */
    private val scope: CoroutineScope,
    /** = `ServiceLocator::setSession`。它同时驱动 [com.example.myapplication.db.CurrentStore]。 */
    private val setSession: (Credentials?) -> Unit,
    /** = `ServiceLocator::switchStore`。 */
    private val switchStore: (String) -> Unit,
    private val currentStoreKey: () -> String,
    /** = `SyncScheduler::refreshEnumFloor`（丢掉返回值）。 */
    private val refreshEnumFloor: suspend (storeKey: String, createdAt: Long) -> Unit,
    /**
     * = `ServiceLocator::onLoggedIn`。启动 SSE 与三通道同步。
     *
     * ⚠ 必须在 [refreshEnumFloor] **之后**调：调度器要靠 enumFloor 决定枚举哪些月份，
     * 早一步的话补传的历史订单所在的月份不在枚举范围里，永远不会被同步。
     */
    private val onLoggedIn: (String) -> Unit = {},
    /** = `ServiceLocator::onLoggedOut`。只断 SSE，不停调度器、不清 outbox。 */
    private val onLoggedOut: () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val audit: (String) -> Unit = {},
) {

    // ==================== 状态 ====================

    /**
     * 会话状态。UI（导航栏颜色、设置页的店铺名）只观察这一个流。
     *
     * 与 [LoginOutcome] 分开是有意的：Outcome 是"这一次登录的结果"，
     * 一次性、带细节；State 是"现在处于什么状态"，长期、给界面用。
     */
    sealed interface SessionState {

        /** 从没登录过、也没选过。启动页要显示"登录 / 继续离线使用"两个入口。 */
        data object AskChoice : SessionState

        /** 离线可用。作用域可能是 [LOCAL_STORE_KEY]，也可能是上次那家店（登出后不切回）。 */
        data class Offline(val storeKey: String) : SessionState

        /** 后台登录中。**界面不该因此被挡住** —— 用户照常开单。 */
        data object SigningIn : SessionState

        /**
         * 已登录。[shopName] 是店主用户名（本系统里店铺就是用它标识的），设置页要显示它。
         *
         * [warning] 非 null 时表示"登录成功了但有件事要说"——目前是
         * "离线期间有别的设备登录过这个账号"。它不是错误，所以不放进 [Failed]。
         */
        data class Online(
            val storeKey: String,
            val shopName: String,
            val username: String,
            val level: String,
            val warning: String?,
        ) : SessionState

        /**
         * 登录失败。**用户照常能继续离线开单** —— 这个状态只是"上次登录没成"的提示，
         * 不是"App 不可用"。
         *
         * 不带"失败前的状态"：作用域在失败时根本没动过（见 [login] 的校验顺序），
         * UI 要知道当前是哪家店直接读 [currentStoreKey]，不必让状态机多存一份可能过期的副本。
         */
        data class Failed(val outcome: LoginOutcome) : SessionState
    }

    /** 一次登录的结果。每个分支都对应一句不同的用户可见文案，所以不用布尔值。 */
    sealed interface LoginOutcome {

        data class Success(
            val storeKey: String,
            val shopName: String,
            val username: String,
            val level: String,
            val warning: String?,
            /** 收养搬了多少行。0 = 没有离线数据需要收养（不是错误）。 */
            val adoptedRows: Int,
        ) : LoginOutcome

        /** 用户名或密码不对。改了可以再试。 */
        data class BadCredentials(val msg: String) : LoginOutcome

        /**
         * 连不上 / 限速 / 服务端异常。
         *
         * @param retryable false 表示**绝不自动重试**（429：桶按 IP 共享，
         *        Guava 平滑桶几乎没有突发额度，重试只会把整店一起锁死）。
         */
        data class Unreachable(val msg: String, val retryable: Boolean) : LoginOutcome

        /**
         * 服务端没回传 `ownerName`，而这是**员工登录** → 无法确定订单文件归属。
         *
         * ⚠ 这时**绝不能**拿 `username` 顶。员工的订单文件归属店主，
         * 用员工名拼出来的 fileId 指向一个服务器上并不存在的文件——而它照样能同步
         * （服务端按需建），于是订单只有这台设备看得到，同店其它设备永远同步不到。
         * 出路只有两条：升级服务端，或改用店主账号登录。
         */
        data class OwnerUnknown(val msg: String) : LoginOutcome

        /**
         * 目标店铺**已经有本地数据**，收养被拒绝（一条都没改，数据都还在）。
         *
         * 需要用户决定怎么合并，不能由代码猜。
         */
        data class AdoptionConflicted(val storeKey: String, val occupied: List<String>) : LoginOutcome

        /** 响应缺字段。说明服务端版本对不上，重试一万次也是同一个结果。 */
        data class Malformed(val msg: String) : LoginOutcome

        /** 这个店铺收到过 SSE `KICKED`，已永久闩死。必须用户明确操作才能再登。 */
        data class Kicked(val msg: String) : LoginOutcome
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.AskChoice)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    // ==================== 冷启动 ====================

    /**
     * 冷启动：恢复作用域，再按 [LoginMode] 决定要不要自动登录。**立刻返回。**
     *
     * 在 `MainActivity` 里调一次。三条路径：
     *  - `ASK`（从没选过）→ 停在 [SessionState.AskChoice]，启动页给两个入口；
     *  - `OFFLINE`（用户选过离线）→ 直接进离线，**不再问**；
     *  - `AUTO`（登录成功过）→ 静默自动登录，失败就降级成离线继续用，**不弹框挡人**。
     */
    fun startUp() {
        scope.launch {
            sessionStore.awaitReady()
            val s = sessionStore.settings()
            // 先把作用域恢复到最后那家店。登出**不切回** __local__，
            // 所以冷启动时必须显式恢复，否则界面上是空数据。
            if (s.lastStoreKey.isNotBlank()) switchStore(s.lastStoreKey)
            when (s.loginMode) {
                LoginMode.ASK -> _state.value = SessionState.AskChoice
                LoginMode.OFFLINE -> _state.value = SessionState.Offline(currentStoreKey())
                LoginMode.AUTO -> {
                    _state.value = SessionState.SigningIn
                    // autoLogin 返回 null = 没有可用凭据（不是失败）。
                    // 非 null 时 login() 自己已经把状态投影好了。
                    // 失败也**不弹框挡人**：降级成离线继续用，Failed 状态里带着原因。
                    if (autoLogin() == null) _state.value = SessionState.Offline(currentStoreKey())
                }
            }
        }
    }

    /** 用户在启动页选了"继续离线使用"。之后启动不再问。 */
    fun chooseOffline() {
        _state.value = SessionState.Offline(currentStoreKey())
        scope.launch { sessionStore.rememberOfflineChoice() }
    }

    // ==================== 登录 ====================

    /**
     * 后台登录。**立刻返回**，结果通过 [state] 反映。
     *
     * @param merchantCode 员工登录时必填（店主的编号）；店主登录传 null。
     */
    fun loginAsync(username: String, password: String, merchantCode: String?) {
        // 已经有一次在飞就别再发一次：登录限速是 5/min 的平滑桶，
        // 用户连点两下就能把自己锁在 429 里 12 秒。
        if (_state.value is SessionState.SigningIn) return
        _state.value = SessionState.SigningIn
        scope.launch { login(username, password, merchantCode) }
    }

    /**
     * 用存好的凭据自动登录。
     *
     * @return null 表示"没有可自动登录的凭据"（不是失败，调用方按离线处理）。
     */
    suspend fun autoLogin(): LoginOutcome? {
        val key = sessionStore.settings().lastStoreKey.takeIf { it.isNotBlank() } ?: return null
        if (sessionStore.isKicked(key)) {
            // ⚠ 单会话策略下自动重登会和新登录方互相踢，形成风暴。闩死了就只能等用户明确操作。
            audit("自动登录被拒：$key 收到过 KICKED，已永久闩死")
            return LoginOutcome.Kicked("这个账号已在别处登录并被标记，需要手动重新登录")
        }
        val saved = sessionStore.loadCredentials(key) ?: run {
            audit("自动登录跳过：$key 没有可用凭据（Keystore 不可用或被清过）")
            return null
        }
        // 员工登录必须带 merchantCode，且走的是另一个端点与另一个限速桶
        val asEmployee = saved.level == LoginResponseDto.LEVEL_A || saved.level == LoginResponseDto.LEVEL_B
        return login(
            saved.username,
            saved.password,
            if (asEmployee) saved.merchantCode else null,
        )
    }

    /**
     * 登录。**不抛**：网络与协议错误一律折进 [LoginOutcome]。
     *
     * 结果同时落到 [state] —— 这是**唯一**的入口，所以直接调它（而不是 [loginAsync]）
     * 的调用方也能看到状态变化。分成两个方法的话，总有一条路径会忘了更新状态，
     * 而界面停在 `SigningIn` 转圈不动是很难查的。
     *
     * @param merchantCode 员工登录时必填；店主登录传 null 或空串。
     */
    suspend fun login(username: String, password: String, merchantCode: String?): LoginOutcome =
        finish(doLogin(username, password, merchantCode))

    private suspend fun doLogin(username: String, password: String, merchantCode: String?): LoginOutcome {
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) {
            return LoginOutcome.BadCredentials("用户名与密码都不能为空")
        }
        val code = merchantCode?.trim().orEmpty()

        // 本地游标要带上：漏传等于每次登录都拿 FULL（服务端只有收到游标才 setSyncData）
        val localKey = currentStoreKey()
        val localMeta = storeMetaDao.get(localKey)
        val pv = localMeta?.priceVersion ?: 0
        val sv = localMeta?.stockVersion ?: 0

        val resp = try {
            if (code.isEmpty()) auth.login(name, password, pv, sv)
            else auth.loginEmployee(code, name, password, pv, sv)
        } catch (e: ApiError) {
            return classify(e)
        }

        // ---- 响应校验 ----
        val token = resp.token?.takeIf { it.isNotBlank() }
            ?: return LoginOutcome.Malformed("登录响应缺 token")
        val storeKey = resp.merchantCode?.takeIf { it.isNotBlank() }
            ?: return LoginOutcome.Malformed("登录响应缺 merchantCode")
        val level = resp.level.orEmpty()

        // ⚠ 必须在写任何状态之前校验：拿不到归属者就绝不能落库，
        // 否则 store_meta.ownerName 是错的，此后每一笔订单都落进错误的文件。
        val owner = resp.fileOwnerOrNull()
            ?: return LoginOutcome.OwnerUnknown(
                "服务端没有回传订单文件归属者（ownerName），员工登录无法确定订单该写进哪个文件。" +
                    "请升级服务端，或改用店主账号登录。"
            )

        // ---- 收养：只在作用域还是 __local__ 时跑，且必须在切作用域之前 ----
        val adoptedRows = if (localKey == LOCAL_STORE_KEY) {
            when (val r = adoption.adopt(LOCAL_STORE_KEY, storeKey)) {
                is AdoptionTx.Result.Adopted -> r.totalRows
                AdoptionTx.Result.NoOp -> 0
                is AdoptionTx.Result.Refused -> {
                    audit("登录中止：$storeKey 下已有本地数据（${r.occupied}），__local__ 的数据原样保留")
                    return LoginOutcome.AdoptionConflicted(storeKey, r.occupied)
                }
            }
        } else 0

        // ---- 写 store_meta（读-合并-写，因为 upsert 是 REPLACE）----
        val warning = writeStoreMeta(storeKey, owner, name, level, resp)

        // ---- 切会话与作用域 ----
        val at = now()
        val creds = Credentials(
            username = resp.username?.takeIf { it.isNotBlank() } ?: name,
            password = password,
            token = token,
            merchantCode = storeKey,
            ownerName = owner,
            level = level,
            perms = resp.permSet(),
            loginAt = resp.currentLoginAt ?: at,
            savedAt = at,
        )
        // setSession 内部会把 CurrentStore 切到 storeKey（作用域的唯一写入点）
        setSession(creds)

        if (!sessionStore.saveCredentials(creds)) {
            // 不是登录失败：本次会话照常可用，只是下次要重新输密码。
            audit("⚠ 凭据保存失败（AndroidKeyStore 不可用？）storeKey=$storeKey —— 下次无法自动登录")
        }
        sessionStore.rememberLogin(storeKey, LoginMode.AUTO)

        // enumFloor = max(min(createdAt 所在月, 本地最早订单月), 2020-01)，必须持久化
        refreshEnumFloor(storeKey, resp.createdAt ?: 0L)

        if (adoptedRows > 0) audit("离线数据已收养：__local__ → $storeKey，共 $adoptedRows 行")

        // ⚠ 放在最后：启动序列里的后台补齐要靠刚写好的 enumFloor 决定枚举范围，
        // 早一步调就会漏掉补传历史订单所在的那些月份。
        onLoggedIn(storeKey)
        return LoginOutcome.Success(
            storeKey = storeKey,
            shopName = owner,
            username = creds.username,
            level = level,
            warning = warning,
            adoptedRows = adoptedRows,
        )
    }

    /**
     * 登出。**作用域停在原店铺**，不切回 [LOCAL_STORE_KEY]。
     *
     * 理由是登出之后新产生的离线数据仍然要上传到那家店，而上传需要那家店的 token；
     * 若切回 `__local__`，这些单会变成无主数据 —— 下一家登录的店会把它们收养走（串店），
     * 或者永远躺在 `__local__` 下没人上传。
     *
     * 数据一律**不删**：切回去还能看到。
     */
    fun logout() {
        setSession(null)
        // 断 SSE：token 已经没了，留着连接只会在下一次心跳拿一个 401，
        // 而 401 会触发自动重登逻辑 —— 用户刚登出就被登回去。
        onLoggedOut()
        _state.value = SessionState.Offline(currentStoreKey())
        audit("登出（作用域保留在 ${currentStoreKey()}，数据未删）")
    }

    // ==================== 内部 ====================

    /**
     * 读-合并-写 `store_meta`，返回需要警告用户的文案（或 null）。
     *
     * ⚠ [StoreMetaDao.upsert] 是 `REPLACE`：直接写一个新对象会把 `enumFloor` /
     * `priceVersion` / `stockVersion` / `adoptedAt` 全部冲掉。
     */
    private suspend fun writeStoreMeta(
        storeKey: String,
        owner: String,
        fallbackUsername: String,
        level: String,
        resp: LoginResponseDto,
    ): String? {
        // 必须在收养**之后**读：收养会把 __local__ 那行搬成 storeKey，
        // 而那行里的 enumFloor 可能已被离线期间写入的订单月份推得更早。
        val existing = storeMetaDao.get(storeKey)

        // "离线期间有别的设备登录过"：服务器只存权威值、不接受客户端上传，
        // 所以不用担心时钟或伪造。这是 KICKED 推送覆盖不到的情况（被踢时你可能根本没连着）。
        val serverPrev = resp.previousLoginAt ?: 0L
        val warning = if (existing != null && existing.lastLoginAt != 0L &&
            serverPrev != 0L && serverPrev != existing.lastLoginAt
        ) {
            "离线期间有别的设备登录过这个账号（服务端记录的上次登录时间 ${serverPrev} " +
                "与本地记录的 ${existing.lastLoginAt} 不符）"
        } else null

        storeMetaDao.upsert(
            StoreMeta(
                storeKey = storeKey,
                ownerName = owner,
                username = resp.username?.takeIf { it.isNotBlank() }
                    ?: existing?.username?.takeIf { it.isNotEmpty() } ?: fallbackUsername,
                level = level,
                // createdAt = 0 表示"未知"，不是"1970 年成立"。已知的值优先，
                // 服务端这次给了 0 也不能把之前存过的真值冲掉。
                createdAt = (resp.createdAt ?: 0L).takeIf { it != 0L } ?: (existing?.createdAt ?: 0L),
                // ⚠ enumFloor 只保留、不重置。它是单调量，冲掉就等于永久漏同步早于它的月份。
                enumFloorYear = existing?.enumFloorYear ?: ENUM_FLOOR_HARD_MIN_YEAR,
                enumFloorMonth = existing?.enumFloorMonth ?: ENUM_FLOOR_HARD_MIN_MONTH,
                // 游标同理：归零会让下一次商品同步退化成 FULL（不致命，但白跑一次几十 KB）
                priceVersion = existing?.priceVersion ?: 0,
                stockVersion = existing?.stockVersion ?: 0,
                // 这两个由下面的 recordLogin 写权威值，这里只是别让 REPLACE 把它们清零
                lastLoginAt = existing?.lastLoginAt ?: 0L,
                previousLoginAt = existing?.previousLoginAt ?: 0L,
                adoptedAt = existing?.adoptedAt ?: 0L,
                // 权限按**名字**存，不按序号 —— 服务端可能插入新权限
                permsCsv = resp.permSet().sorted().joinToString(","),
                adminCode = existing?.adminCode?.takeIf { it.isNotEmpty() }
                    ?: sessionStore.settings().adminCode,
            )
        )
        // 登录时间走定向 UPDATE：它必须是服务端给的权威值，不能与上面的合并逻辑混在一起
        storeMetaDao.recordLogin(storeKey, serverPrev, resp.currentLoginAt ?: 0L)
        return warning
    }

    private fun classify(e: ApiError): LoginOutcome {
        val msg = e.message ?: e.javaClass.simpleName
        return when (e) {
            // 401 在登录路径上只有一种含义：用户名或密码不对（或账号被删/改名）。
            // 都归到"改了再试"，细分理由对用户在登录框里没有帮助。
            is ApiError.Unauthorized -> LoginOutcome.BadCredentials(msg)
            is ApiError.BadRequest -> LoginOutcome.BadCredentials(msg)
            is ApiError.RateLimited -> LoginOutcome.Unreachable(msg, retryable = false)
            is ApiError.Forbidden -> LoginOutcome.Unreachable(msg, retryable = false)
            is ApiError.ServerError -> {
                // 500 的响应体只有一个追踪号。连时间一起记，否则事后对不上服务端日志。
                audit("登录 500 traceId=${e.traceId} at=${now()} msg=${e.msg}")
                LoginOutcome.Unreachable("服务端异常（追踪号 ${e.traceId}）", retryable = true)
            }
            is ApiError.Network -> LoginOutcome.Unreachable("连不上服务器: ${e.message}", retryable = true)
            // 200 但形状不对 = 服务端版本对不上。重试一万次也是同一个结果。
            is ApiError.MalformedResponse -> LoginOutcome.Malformed(msg)
            else -> {
                audit("登录失败 ${e.javaClass.simpleName}: $msg")
                LoginOutcome.Unreachable(msg, retryable = false)
            }
        }
    }

    /** 把结果投影到 [state]。返回入参，方便 `login` 写成一行。 */
    private fun finish(outcome: LoginOutcome): LoginOutcome {
        _state.value = when (outcome) {
            is LoginOutcome.Success -> SessionState.Online(
                storeKey = outcome.storeKey,
                shopName = outcome.shopName,
                username = outcome.username,
                level = outcome.level,
                warning = outcome.warning,
            )
            is LoginOutcome.BadCredentials,
            is LoginOutcome.Unreachable,
            is LoginOutcome.OwnerUnknown,
            is LoginOutcome.AdoptionConflicted,
            is LoginOutcome.Malformed,
            is LoginOutcome.Kicked -> SessionState.Failed(outcome)
        }
        return outcome
    }
}
