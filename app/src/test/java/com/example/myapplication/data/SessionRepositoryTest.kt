package com.example.myapplication.data

import com.example.myapplication.db.EnumFloor
import com.example.myapplication.db.LOCAL_STORE_KEY
import com.example.myapplication.db.StoreMeta
import com.example.myapplication.db.StoreMetaDao
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.AuthApi
import com.example.myapplication.net.UnauthorizedReason
import com.example.myapplication.net.dto.LoginResponseDto
import com.example.myapplication.security.Credentials
import com.example.myapplication.settings.AppSettings
import com.example.myapplication.settings.LoginMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionRepository] 的登录编排。
 *
 * 这里钉住的四类错误全都是**静默**的：
 *  - `ownerName` 退错 → 订单落进一个只有本机知道的私有文件，同店其它设备永远同步不到；
 *  - `store_meta` 被 REPLACE 直写 → `enumFloor` 归零 → 补传的历史月份**永远不会被同步**；
 *  - 收养时机错了 → 离线数据搬不到，或者搬进错误的店铺；
 *  - 被踢过还自动重登 → 单会话策略下两台设备无限互踢。
 *
 * 四条都没有异常、没有日志、界面上看起来一切正常。
 */
class SessionRepositoryTest {

    private val auth = FakeAuth()
    private val meta = FakeStoreMetaDao()
    private val session = FakeSessionStore()
    private val audits = mutableListOf<String>()
    private val enumFloors = mutableListOf<Pair<String, Long>>()
    /** 调用顺序日志。用来钉住"先刷 enumFloor 再启动同步"这条硬顺序。 */
    private val calls = mutableListOf<String>()
    private var clock = 1_700_000_000_000L

    /** 当前作用域。生产环境是 `CurrentStore.now`。 */
    private var storeKey = LOCAL_STORE_KEY

    private var currentCreds: Credentials? = null

    /** 收养时"目标店铺已有数据"的表名集合。非空 → AdoptionTx 拒绝。 */
    private var occupiedTables = emptySet<String>()
    private val adoptedTables = mutableListOf<String>()

    private val adoption = AdoptionTx(
        query = { sql, _ -> if (tableOf(sql) in occupiedTables) 1L else 0L },
        exec = { sql, _ -> adoptedTables += tableOf(sql); 2 },
        inTransaction = { body -> body() },
        now = { clock },
        audit = { audits += it },
    )

    // Unconfined：这些替身都不真的挂起，launch 会同步跑完，断言可以直接读 state
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private val repo = SessionRepository(
        auth = auth,
        storeMetaDao = meta,
        sessionStore = session,
        adoption = adoption,
        scope = scope,
        setSession = { currentCreds = it; if (it != null) storeKey = it.merchantCode },
        switchStore = { storeKey = it },
        currentStoreKey = { storeKey },
        refreshEnumFloor = { sk, ca ->
            enumFloors += sk to ca
            calls += "enumFloor:$sk"
        },
        onLoggedIn = { sk -> calls += "onLoggedIn:$sk" },
        onLoggedOut = { calls += "onLoggedOut" },
        now = { clock },
        audit = { audits += it },
    )

    private fun tableOf(sql: String): String =
        if (sql.startsWith("UPDATE ")) sql.removePrefix("UPDATE ").substringBefore(" ")
        else sql.substringAfter("FROM ").substringBefore(" ")

    // ==================== 构造测试数据 ====================

    private fun resp(
        token: String? = "tok-1",
        username: String? = "alice",
        merchantCode: String? = "00002",
        level: String? = "OWNER",
        ownerName: String? = "alice",
        createdAt: Long? = 1_600_000_000_000L,
        previousLoginAt: Long? = 0L,
        currentLoginAt: Long? = clock,
        perms: List<String?>? = listOf("ORDER_B", "SYNC_SUB"),
    ) = LoginResponseDto(
        token, username, merchantCode, level, null, perms,
        createdAt, "sid-1", previousLoginAt, currentLoginAt, ownerName,
    )

    private fun meta(
        storeKey: String,
        ownerName: String = "",
        username: String = "",
        level: String = "",
        createdAt: Long = 0L,
        floorYear: Int = 2020,
        floorMonth: Int = 1,
        priceVersion: Int = 0,
        stockVersion: Int = 0,
        lastLoginAt: Long = 0L,
        adoptedAt: Long = 0L,
        adminCode: String = "",
    ) = StoreMeta(
        storeKey, ownerName, username, level, createdAt, floorYear, floorMonth,
        priceVersion, stockVersion, lastLoginAt, 0L, adoptedAt, "", adminCode,
    )

    private fun login(u: String = "alice", p: String = "pw", code: String? = null) =
        runBlocking { repo.login(u, p, code) }

    // ==================== ownerName：最关键的一组 ====================

    @Test
    fun `员工登录时_ownerName_是店主名_而不是员工名`() {
        auth.serve(resp(username = "clerk1", level = "B", ownerName = "alice"))

        val r = login("clerk1", "pw", code = "00002") as SessionRepository.LoginOutcome.Success

        // fileId 是 B:<ownerName>:<年>-<月>。用员工名拼出来的文件在服务器上并不存在，
        // 而它照样能同步（服务端按需建），于是订单只有这台设备看得到。
        assertEquals("alice", r.shopName)
        assertEquals("clerk1", r.username)
        assertEquals("alice", meta.rows["00002"]!!.ownerName)
        assertEquals("clerk1", meta.rows["00002"]!!.username)
        assertEquals("alice", currentCreds!!.ownerName)
    }

    @Test
    fun `服务端不回传_ownerName_且是员工登录时_拒绝登录_且不写任何状态`() {
        auth.serve(resp(username = "clerk1", level = "A", ownerName = null))

        val r = login("clerk1", "pw", code = "00002")

        assertTrue("期望 OwnerUnknown，实际 $r", r is SessionRepository.LoginOutcome.OwnerUnknown)
        // ⚠ 绝不能拿 username 顶。落到这里说明退路写错了。
        assertNull("拒绝登录时不该写 store_meta", meta.rows["00002"])
        assertNull("拒绝登录时不该建会话", currentCreds)
        assertEquals("拒绝登录时作用域不该动", LOCAL_STORE_KEY, storeKey)
        assertTrue(enumFloors.isEmpty())
        assertTrue("拒绝登录就不该记住自动登录", session.remembers.isEmpty())
    }

    @Test
    fun `服务端不回传_ownerName_但是店主登录时_退回_username`() {
        // resolveFileOwner 对非 A/B 的 level 直接 return username，所以这个退路是照抄服务端那一个分支
        auth.serve(resp(username = "alice", level = "OWNER", ownerName = null))

        val r = login("alice", "pw") as SessionRepository.LoginOutcome.Success

        assertEquals("alice", r.shopName)
        assertEquals("alice", meta.rows["00002"]!!.ownerName)
    }

    @Test
    fun `ownerName_是空白串时也算拿不到`() {
        auth.serve(resp(level = "B", ownerName = "   "))

        assertTrue(login("clerk1", "pw", code = "00002") is SessionRepository.LoginOutcome.OwnerUnknown)
    }

    // ==================== 收养 ====================

    @Test
    fun `作用域还是___local___时先收养_再切作用域`() {
        auth.serve(resp())

        val r = login() as SessionRepository.LoginOutcome.Success

        assertEquals(AdoptionTx.TABLES.size, adoptedTables.size)
        assertEquals(AdoptionTx.TABLES.size * 2, r.adoptedRows)
        assertEquals("00002", storeKey)
        assertTrue(audits.any { "离线数据已收养" in it })
    }

    @Test
    fun `作用域已经是真实店铺时不收养`() {
        storeKey = "00007"
        auth.serve(resp())

        val r = login() as SessionRepository.LoginOutcome.Success

        assertEquals(0, r.adoptedRows)
        assertTrue("不该发任何收养 UPDATE", adoptedTables.isEmpty())
    }

    @Test
    fun `收养被拒时_不落任何状态_且作用域不变`() {
        occupiedTables = setOf("product_local")
        auth.serve(resp())

        val r = login()

        assertTrue(r is SessionRepository.LoginOutcome.AdoptionConflicted)
        assertEquals(listOf("product_local"), (r as SessionRepository.LoginOutcome.AdoptionConflicted).occupied)
        assertNull("收养被拒就不该写 store_meta", meta.rows["00002"])
        assertNull(currentCreds)
        assertEquals(LOCAL_STORE_KEY, storeKey)
        assertTrue(adoptedTables.isEmpty())
    }

    // ==================== store_meta 合并写入 ====================

    @Test
    fun `store_meta_是读改写_enumFloor_与游标不被_REPLACE_冲掉`() {
        // 离线期间补传过 2019-03 的订单 → enumFloor 已经被推到 2019-03。
        // 收养之后这一行挂在 00002 下。upsert 是 REPLACE，直写一个新对象就把它冲回 2020-01，
        // 于是 2019 年那些月份**永远不会被同步**，而且不会报错。
        meta.rows["00002"] = meta(
            "00002", floorYear = 2019, floorMonth = 3,
            priceVersion = 41, stockVersion = 17, adoptedAt = 1_690_000_000_000L,
        )
        auth.serve(resp(createdAt = 1_600_000_000_000L))

        login()

        val m = meta.rows["00002"]!!
        assertEquals(2019, m.enumFloorYear)
        assertEquals(3, m.enumFloorMonth)
        assertEquals(41, m.priceVersion)
        assertEquals(17, m.stockVersion)
        assertEquals(1_690_000_000_000L, m.adoptedAt)
        // 身份字段用服务端这次的权威值
        assertEquals("alice", m.ownerName)
        assertEquals("ORDER_B,SYNC_SUB", m.permsCsv)
    }

    @Test
    fun `本地没有这一行时_enumFloor_用硬下界_而不是_0`() {
        auth.serve(resp())

        login()

        val m = meta.rows["00002"]!!
        assertEquals(ENUM_FLOOR_HARD_MIN_YEAR, m.enumFloorYear)
        assertEquals(ENUM_FLOOR_HARD_MIN_MONTH, m.enumFloorMonth)
    }

    @Test
    fun `createdAt_为_0_表示未知_不覆盖已存的真值`() {
        meta.rows["00002"] = meta("00002", createdAt = 1_500_000_000_000L)
        auth.serve(resp(createdAt = 0L))

        login()

        // 0 不是"1970 年成立"。拿它当枚举下界会一路枚举到 1970 打爆服务端。
        assertEquals(1_500_000_000_000L, meta.rows["00002"]!!.createdAt)
    }

    @Test
    fun `登录时间走定向_UPDATE_写服务端的权威值`() {
        auth.serve(resp(previousLoginAt = 111L, currentLoginAt = 222L))

        login()

        assertEquals(listOf(Triple("00002", 111L, 222L)), meta.loginRecords)
    }

    // ==================== "离线期间有别的设备登录过" ====================

    @Test
    fun `previousLoginAt_与本地记录不符时给出警告`() {
        meta.rows["00002"] = meta("00002", lastLoginAt = 100L)
        auth.serve(resp(previousLoginAt = 999L))

        val r = login() as SessionRepository.LoginOutcome.Success

        assertNotNull(r.warning)
        assertTrue(r.warning!!.contains("别的设备"))
        // 是警告不是失败：登录确实成功了
        assertTrue(repo.state.value is SessionRepository.SessionState.Online)
    }

    @Test
    fun `previousLoginAt_相符时不警告`() {
        meta.rows["00002"] = meta("00002", lastLoginAt = 100L)
        auth.serve(resp(previousLoginAt = 100L))

        assertNull((login() as SessionRepository.LoginOutcome.Success).warning)
    }

    @Test
    fun `首次登录时不警告_因为没有本地记录可比`() {
        auth.serve(resp(previousLoginAt = 999L))

        assertNull((login() as SessionRepository.LoginOutcome.Success).warning)
    }

    // ==================== 游标 ====================

    @Test
    fun `登录时带上本地游标_漏传等于每次都拿_FULL`() {
        storeKey = "00002"
        meta.rows["00002"] = meta("00002", priceVersion = 41, stockVersion = 17)
        auth.serve(resp())

        login()

        // 服务端只有收到游标才 setSyncData；传 0 就是每次登录都拉全量商品
        assertEquals(41, auth.lastPriceVersion)
        assertEquals(17, auth.lastStockVersion)
    }

    @Test
    fun `本地没有游标时传_0`() {
        auth.serve(resp())

        login()

        assertEquals(0, auth.lastPriceVersion)
        assertEquals(0, auth.lastStockVersion)
    }

    // ==================== 登出 ====================

    @Test
    fun `登出保留作用域_不切回___local__`() {
        auth.serve(resp())
        login()
        assertEquals("00002", storeKey)

        repo.logout()

        // 登出后新产生的离线数据仍然归这家店：它最终要上传到那家店的订单文件，
        // 而上传需要那家店的 token。切回 __local__ 会让这些单变成无主数据。
        assertEquals("00002", storeKey)
        assertNull(currentCreds)
        assertTrue(repo.state.value is SessionRepository.SessionState.Offline)
        assertTrue("登出不删数据", meta.rows.containsKey("00002"))
    }

    // ==================== 自动登录 ====================

    @Test
    fun `自动登录_员工凭据走_loginEmployee_端点`() {
        session.saved["00002"] = creds(level = "B", username = "clerk1")
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "00002")
        auth.serve(resp(username = "clerk1", level = "B", ownerName = "alice"))

        val r = runBlocking { repo.autoLogin() }

        assertTrue(r is SessionRepository.LoginOutcome.Success)
        assertEquals(1, auth.employeeCalls.size)
        assertEquals(0, auth.ownerCalls.size)
        assertEquals(Triple("00002", "clerk1", "pw"), auth.employeeCalls.single())
    }

    @Test
    fun `自动登录_店主凭据走_login_端点`() {
        session.saved["00002"] = creds(level = "OWNER", username = "alice")
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "00002")
        auth.serve(resp())

        runBlocking { repo.autoLogin() }

        assertEquals(1, auth.ownerCalls.size)
        assertTrue(auth.employeeCalls.isEmpty())
    }

    @Test
    fun `自动登录_被踢过的店铺拒绝_绝不自动重登`() {
        session.saved["00002"] = creds()
        session.kicked += "00002"
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "00002")

        val r = runBlocking { repo.autoLogin() }

        // 单会话策略下自动重登会和新登录方互相踢，形成风暴
        assertTrue(r is SessionRepository.LoginOutcome.Kicked)
        assertTrue("被闩死就不该发请求", auth.ownerCalls.isEmpty() && auth.employeeCalls.isEmpty())
    }

    @Test
    fun `自动登录_没有凭据时返回_null_而不是失败`() {
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "00002")

        assertNull(runBlocking { repo.autoLogin() })
        assertTrue(auth.ownerCalls.isEmpty())
    }

    @Test
    fun `从没登录过时自动登录返回_null`() {
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "")

        assertNull(runBlocking { repo.autoLogin() })
    }

    // ==================== 错误分类 ====================

    @Test
    fun `_401_归到凭据不对`() {
        auth.throwOnNext = ApiError.Unauthorized(UnauthorizedReason.BAD_CREDENTIALS, "用户名或密码错误")

        assertTrue(login() is SessionRepository.LoginOutcome.BadCredentials)
    }

    @Test
    fun `_429_标记为不可重试`() {
        auth.throwOnNext = ApiError.RateLimited("操作过于频繁")

        val r = login() as SessionRepository.LoginOutcome.Unreachable

        // 桶按 IP 共享、Guava 平滑桶几乎没有突发额度，自动重试只会把整店一起锁死
        assertFalse(r.retryable)
    }

    @Test
    fun `断网标记为可重试`() {
        auth.throwOnNext = ApiError.Network(java.io.IOException("timeout"))

        assertTrue((login() as SessionRepository.LoginOutcome.Unreachable).retryable)
    }

    @Test
    fun `_500_把追踪号记进审计`() {
        auth.throwOnNext = ApiError.ServerError("ab12cd34", "服务器内部错误（追踪号 ab12cd34）")

        val r = login() as SessionRepository.LoginOutcome.Unreachable

        assertTrue(r.msg.contains("ab12cd34"))
        assertTrue(audits.any { "ab12cd34" in it })
    }

    @Test
    fun `响应形状错误归到_Malformed_而不是可重试`() {
        auth.throwOnNext = ApiError.MalformedResponse("不是 JSON 信封")

        assertTrue(login() is SessionRepository.LoginOutcome.Malformed)
    }

    @Test
    fun `缺_token_或缺_merchantCode_归到_Malformed`() {
        auth.serve(resp(token = null))
        assertTrue(login() is SessionRepository.LoginOutcome.Malformed)

        auth.serve(resp(merchantCode = "  "))
        assertTrue(login() is SessionRepository.LoginOutcome.Malformed)
    }

    @Test
    fun `空用户名或空密码不发请求`() {
        assertTrue(login("", "pw") is SessionRepository.LoginOutcome.BadCredentials)
        assertTrue(login("alice", "") is SessionRepository.LoginOutcome.BadCredentials)
        assertTrue(auth.ownerCalls.isEmpty() && auth.employeeCalls.isEmpty())
    }

    // ==================== 副作用 ====================

    @Test
    fun `登录成功后刷新_enumFloor_并记住自动登录`() {
        auth.serve(resp(createdAt = 1_600_000_000_000L))

        login()

        // createdAt 是 enumFloor 的输入：下界 = max(min(createdAt 所在月, 本地最早订单月), 2020-01)
        assertEquals(listOf("00002" to 1_600_000_000_000L), enumFloors)
        assertEquals(listOf("00002" to LoginMode.AUTO), session.remembers)
    }

    // ==================== 登录后的启动序列 ====================

    @Test
    fun `启动同步必须在刷完_enumFloor_之后`() {
        auth.serve(resp())

        login()

        // 顺序错了的后果：调度器按旧的（更高的）enumFloor 枚举月份，
        // 补传历史订单所在的那些月份不在范围里 → **永远不会被同步**，
        // 而第二台设备也就永远看不到它们。两边各自看都是对的。
        assertEquals(listOf("enumFloor:00002", "onLoggedIn:00002"), calls)
    }

    @Test
    fun `登录失败时不启动同步`() {
        auth.throwOnNext = ApiError.Network(java.io.IOException("x"))

        login()

        assertTrue("失败不该启动同步：$calls", calls.none { it.startsWith("onLoggedIn") })
    }

    @Test
    fun `ownerName_拿不到时不启动同步`() {
        auth.serve(resp(username = "clerk1", level = "A", ownerName = null))

        login("clerk1", "pw", code = "00002")

        // 没有 ownerName 就拼不出 fileId，启动了也只会往错误的文件里写
        assertTrue(calls.none { it.startsWith("onLoggedIn") })
        assertTrue(calls.none { it.startsWith("enumFloor") })
    }

    @Test
    fun `收养被拒时不启动同步`() {
        occupiedTables = setOf("product_local")
        auth.serve(resp())

        login()

        assertTrue(calls.none { it.startsWith("onLoggedIn") })
    }

    @Test
    fun `登出时断开_SSE_但不动作用域`() {
        auth.serve(resp())
        login()
        calls.clear()

        repo.logout()

        // token 已经没了，留着连接只会在下一次心跳拿一个 401，
        // 而 401 会触发自动重登 —— 用户刚登出就被登回去。
        assertEquals(listOf("onLoggedOut"), calls)
        assertEquals("00002", storeKey)
    }

    @Test
    fun `自动登录成功后同样要启动同步`() {
        session.saved["00002"] = creds()
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "00002")
        auth.serve(resp())

        repo.startUp()

        assertEquals(listOf("enumFloor:00002", "onLoggedIn:00002"), calls)
    }

    @Test
    fun `凭据保存失败不影响登录成功_但记审计`() {
        session.saveFails = true
        auth.serve(resp())

        val r = login()

        // Keystore 在少数 ROM / OTA 之后不可用。这不是登录失败——本次会话照常可用，
        // 只是下次要重新输密码。不记审计的话用户会以为"自动登录坏了"而查不出原因。
        assertTrue(r is SessionRepository.LoginOutcome.Success)
        assertTrue(audits.any { "凭据保存失败" in it })
    }

    // ==================== 状态机与冷启动 ====================

    @Test
    fun `loginAsync_立刻返回并把状态推到_Online`() {
        auth.serve(resp())

        repo.loginAsync("alice", "pw", null)

        val s = repo.state.value
        assertTrue("期望 Online，实际 $s", s is SessionRepository.SessionState.Online)
        assertEquals("alice", (s as SessionRepository.SessionState.Online).shopName)
    }

    @Test
    fun `loginAsync_在_SigningIn_期间不重复发请求`() {
        // 登录限速是 5/min 的平滑桶，用户连点两下就能把自己锁在 429 里 12 秒
        auth.blockInFlight = true
        auth.serve(resp())
        repo.loginAsync("alice", "pw", null)
        assertEquals(SessionRepository.SessionState.SigningIn, repo.state.value)

        repo.loginAsync("alice", "pw", null)

        assertEquals(1, auth.ownerCalls.size)
    }

    @Test
    fun `登录失败时作用域不变_用户照常能继续离线开单`() {
        storeKey = "00007"
        auth.throwOnNext = ApiError.Network(java.io.IOException("x"))

        repo.loginAsync("alice", "pw", null)

        val s = repo.state.value
        assertTrue("期望 Failed，实际 $s", s is SessionRepository.SessionState.Failed)
        assertTrue((s as SessionRepository.SessionState.Failed).outcome is SessionRepository.LoginOutcome.Unreachable)
        // 失败**不该动作用域**：动了的话用户界面上会变成另一家店的空数据
        assertEquals("00007", storeKey)
        assertNull("失败不该建会话", currentCreds)
    }

    @Test
    fun `startUp_ASK_停在等待用户选择`() {
        session.settingsValue = AppSettings(loginMode = LoginMode.ASK)

        repo.startUp()

        assertEquals(SessionRepository.SessionState.AskChoice, repo.state.value)
        assertTrue(auth.ownerCalls.isEmpty())
    }

    @Test
    fun `startUp_OFFLINE_直接进离线且恢复上次的作用域`() {
        session.settingsValue = AppSettings(loginMode = LoginMode.OFFLINE, lastStoreKey = "00007")

        repo.startUp()

        // 登出不切回 __local__，所以冷启动必须显式恢复，否则界面上是空数据
        assertEquals("00007", storeKey)
        assertEquals(SessionRepository.SessionState.Offline("00007"), repo.state.value)
        assertTrue(auth.ownerCalls.isEmpty())
    }

    @Test
    fun `startUp_AUTO_静默自动登录`() {
        session.saved["00002"] = creds()
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "00002")
        auth.serve(resp())

        repo.startUp()

        assertEquals(1, auth.ownerCalls.size)
        assertTrue(repo.state.value is SessionRepository.SessionState.Online)
    }

    @Test
    fun `startUp_AUTO_但没有凭据时降级成离线_不弹框挡人`() {
        session.settingsValue = AppSettings(loginMode = LoginMode.AUTO, lastStoreKey = "00002")

        repo.startUp()

        assertEquals(SessionRepository.SessionState.Offline("00002"), repo.state.value)
    }

    @Test
    fun `chooseOffline_持久化选择_下次不再问`() {
        repo.chooseOffline()

        // 必须持久化：否则每次启动都要再问一遍，用户第三次就会烦
        assertEquals(1, session.offlineChoiceCount)
        assertEquals(LoginMode.OFFLINE, session.settingsValue.loginMode)
        assertTrue(repo.state.value is SessionRepository.SessionState.Offline)
    }

    private fun creds(level: String = "OWNER", username: String = "alice") = Credentials(
        username = username, password = "pw", token = "old", merchantCode = "00002",
        ownerName = "alice", level = level, perms = emptySet(), loginAt = 0L, savedAt = 0L,
    )

    // ==================== 替身 ====================

    private class FakeAuth : AuthApi {
        val ownerCalls = mutableListOf<String>()
        val employeeCalls = mutableListOf<Triple<String, String, String>>()
        var lastPriceVersion = -1
        var lastStockVersion = -1
        var throwOnNext: Throwable? = null
        /** true 时第一次调用不返回，用来制造"仍在 SigningIn"的窗口。 */
        var blockInFlight = false
        private val script = ArrayDeque<LoginResponseDto>()
        private var blocked = false

        fun serve(r: LoginResponseDto) {
            script += r
        }

        override suspend fun login(
            username: String, password: String, priceVersion: Int, stockVersion: Int,
        ): LoginResponseDto {
            ownerCalls += username
            lastPriceVersion = priceVersion
            lastStockVersion = stockVersion
            if (blockInFlight && !blocked) {
                blocked = true
                kotlinx.coroutines.awaitCancellation()
            }
            return next()
        }

        override suspend fun loginEmployee(
            merchantCode: String, employeeUsername: String, password: String,
            priceVersion: Int, stockVersion: Int,
        ): LoginResponseDto {
            employeeCalls += Triple(merchantCode, employeeUsername, password)
            lastPriceVersion = priceVersion
            lastStockVersion = stockVersion
            return next()
        }

        private fun next(): LoginResponseDto {
            throwOnNext?.let { throwOnNext = null; throw it }
            return script.removeFirstOrNull() ?: throw AssertionError("脚本用完了：登录被多调了一次")
        }
    }

    private class FakeSessionStore : SessionStore {
        var settingsValue = AppSettings()
        val saved = HashMap<String, Credentials>()
        val kicked = mutableSetOf<String>()
        val remembers = mutableListOf<Pair<String, LoginMode>>()
        var offlineChoiceCount = 0
        var saveFails = false

        override suspend fun awaitReady() {}
        override fun settings(): AppSettings = settingsValue
        override suspend fun loadCredentials(storeKey: String) = saved[storeKey]
        override suspend fun saveCredentials(creds: Credentials): Boolean {
            if (saveFails) return false
            saved[creds.merchantCode] = creds
            return true
        }

        override suspend fun isKicked(storeKey: String) = storeKey in kicked
        override suspend fun rememberLogin(storeKey: String, mode: LoginMode) {
            remembers += storeKey to mode
            settingsValue = settingsValue.copy(loginMode = mode, lastStoreKey = storeKey)
        }

        override suspend fun rememberOfflineChoice() {
            offlineChoiceCount++
            settingsValue = settingsValue.copy(loginMode = LoginMode.OFFLINE)
        }
    }

    /** 对齐 SQL 的可观测语义：`upsert` 是 **REPLACE**（整行覆盖）。 */
    private class FakeStoreMetaDao : StoreMetaDao {
        val rows = LinkedHashMap<String, StoreMeta>()
        val loginRecords = mutableListOf<Triple<String, Long, Long>>()
        private val flows = HashMap<String, MutableStateFlow<StoreMeta?>>()

        override suspend fun upsert(m: StoreMeta) {
            rows[m.storeKey] = m
        }

        override suspend fun get(storeKey: String) = rows[storeKey]
        override fun observe(storeKey: String): Flow<StoreMeta?> =
            flows.getOrPut(storeKey) { MutableStateFlow(rows[storeKey]) }

        override suspend fun getAll() = rows.values.sortedByDescending { it.adoptedAt }
        override suspend fun getAllStoreKeys() = rows.keys.toList()

        override suspend fun setPriceVersion(storeKey: String, v: Int) {
            rows[storeKey]?.let { rows[storeKey] = it.copy(priceVersion = v) }
        }

        override suspend fun setStockVersion(storeKey: String, v: Int) {
            rows[storeKey]?.let { rows[storeKey] = it.copy(stockVersion = v) }
        }

        override suspend fun lowerEnumFloor(storeKey: String, year: Int, month: Int): Int {
            val cur = rows[storeKey] ?: return 0
            // SQL 的 WHERE 只允许往前推
            val older = cur.enumFloorYear > year || (cur.enumFloorYear == year && cur.enumFloorMonth > month)
            if (!older) return 0
            rows[storeKey] = cur.copy(enumFloorYear = year, enumFloorMonth = month)
            return 1
        }

        override suspend fun getEnumFloor(storeKey: String): EnumFloor? =
            rows[storeKey]?.let { EnumFloor(it.enumFloorYear, it.enumFloorMonth) }

        override suspend fun setPerms(storeKey: String, permsCsv: String) {
            rows[storeKey]?.let { rows[storeKey] = it.copy(permsCsv = permsCsv) }
        }

        override suspend fun recordLogin(storeKey: String, previousLoginAt: Long, currentLoginAt: Long) {
            loginRecords += Triple(storeKey, previousLoginAt, currentLoginAt)
            rows[storeKey]?.let {
                rows[storeKey] = it.copy(lastLoginAt = currentLoginAt, previousLoginAt = previousLoginAt)
            }
        }

        override suspend fun adoptStoreKey(oldKey: String, newKey: String, now: Long) {
            rows.remove(oldKey)?.let { rows[newKey] = it.copy(storeKey = newKey, adoptedAt = now) }
        }
    }
}
