package com.example.myapplication.sync

import com.example.myapplication.db.EnumFloor
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderIndexEntity
import com.example.myapplication.db.StoreMeta
import com.example.myapplication.db.StoreMetaDao
import com.example.myapplication.mirror.FakeOrderFileDao
import com.example.myapplication.mirror.SyncState
import com.example.myapplication.net.UnauthorizedReason
import com.example.myapplication.sync.OrderFileSyncEngine.Outcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * [SyncScheduler] 的三通道与 `enumFloor`（方案 §3.4 + R2）。
 *
 * ## 这里盯的是三类"漏了也不报错"
 *
 *  1. **枚举范围**：只带近两个月的话，第三台设备登录时看不到更早的订单，
 *     两台设备的数据永久分歧 —— 而每一台自己看都是"同步成功"。
 *  2. **该跳过的没跳过**：归档月份每轮都吃一次 409 + 一次探测；
 *     "拉过但那年那月没单"的月份每轮都吃一次 FULL 探测。都是纯浪费，且看不出异常。
 *  3. **该停的没停**：429 之后继续发会把服务端的 IP 桶一直顶满，
 *     同店其它设备一起被锁死（方案 R4）；401 之后继续发只是刷日志。
 *
 * 引擎与自检都以**函数**注入，所以断言可以直接盯 fileId 序列本身。
 * 引擎自己的分支由 [OrderFileSyncEngineTest] 覆盖，两边不重复。
 */
class SyncSchedulerTest {

    private val dao = FakeOrderFileDao()
    private val meta = FakeStoreMetaDao()
    private val audits = mutableListOf<String>()
    private val utc = TimeZone.getTimeZone("UTC")

    /** 记录发出的 fileId，顺序即调用顺序（parallelism=1 时确定）。 */
    private val calls = mutableListOf<String>()
    private val script = HashMap<String, Outcome>()
    private var defaultOutcome: Outcome = Outcome.UpToDate(1, "tail")

    /** 自检被调用的 (fileId, verifyChecksum)。 */
    private val checks = mutableListOf<Pair<String, Boolean>>()
    private var checkResult = true

    private val storeKey = "00002"
    private val owner = "alice"

    // 2026-10-20T00:00:00Z（"今天"）
    private val oct20 = 1_792_454_400_000L

    // 2027-01-05T00:00:00Z，用来测跨年
    private val jan5_2027 = 1_799_107_200_000L

    // 2025-03-10T00:00:00Z，用来测"离线先用、后买服务"
    private val mar10_2025 = 1_741_564_800_000L

    private var clock = oct20

    private lateinit var sched: SyncScheduler

    @Before
    fun setUp() {
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2026, 10))
        sched = newScheduler(parallelism = 1)
    }

    /** 并发度单独可调：中止那两条用例要靠 chunk 边界把"哪些没发"钉死。 */
    private fun newScheduler(parallelism: Int) = SyncScheduler(
        syncOne = { sk, fileId ->
            assertEquals(storeKey, sk)
            calls += fileId
            script[fileId] ?: defaultOutcome
        },
        checkOne = { _, fileId, verify ->
            checks += fileId to verify
            checkResult
        },
        dao = dao,
        storeMetaDao = meta,
        timeZone = { utc },
        now = { clock },
        audit = { audits += it },
        io = Dispatchers.Unconfined,
        parallelism = parallelism,
    )

    // ==================== 测试辅助 ====================

    private fun storeMeta(
        createdAt: Long = 0L,
        enumFloor: SyncMonth = SyncMonth(2020, 1),
    ) = StoreMeta(
        storeKey = storeKey,
        ownerName = owner,
        username = owner,
        level = "OWNER",
        createdAt = createdAt,
        enumFloorYear = enumFloor.year,
        enumFloorMonth = enumFloor.month,
        priceVersion = 0,
        stockVersion = 0,
        lastLoginAt = 0,
        previousLoginAt = 0,
        adoptedAt = 0,
        permsCsv = "",
        adminCode = "00001",
    )

    private fun b(year: Int, month: Int) = OrderFileId.ofB(owner, year, month)

    private fun a(year: Int) = OrderFileId.ofA(owner, year)

    /** 直接往 FakeDao 里塞一行 `file_state`（模拟"这个月以前同步过"）。 */
    private fun seedState(
        fileId: String,
        syncState: SyncState = SyncState.LIVE,
        firstSyncedAt: Long? = 1_700_000_000_000L,
        version: Int = 3,
    ) {
        val parts = OrderFileId.parse(fileId)
        dao.fileStates[storeKey to fileId] = FileStateEntity(
            storeKey = storeKey,
            fileId = fileId,
            fileType = parts.type,
            year = parts.year,
            month = parts.month,
            version = version,
            lastStreamId = "cur",
            checksum = 0,
            mirrorBytes = 4,
            firstSyncedAt = firstSyncedAt,
            syncState = syncState.name,
            lastSyncAttemptAt = 0,
        )
    }

    private fun seedOrder(orderUuid: String, orderTs: Long) {
        dao.orders[Triple(storeKey, b(2025, 3), orderUuid)] = OrderIndexEntity(
            storeKey = storeKey,
            fileId = b(2025, 3),
            orderUuid = orderUuid,
            writer = owner,
            status = 1,
            orderTs = orderTs,
            totalCents = 5200,
            manualTotal = false,
            itemCount = 1,
            lineStart = 4,
            uuidOffset = 19,
            statusOffset = 100,
            remarkOffset = null,
            remark = null,
            appliedFileVersion = 1,
            cancelAppliedVersion = null,
            groupId = null,
        )
    }

    private fun floor(): SyncMonth = SyncMonth(meta.rows[storeKey]!!.enumFloorYear, meta.rows[storeKey]!!.enumFloorMonth)

    // ==================== enumFloor ====================

    @Test
    fun `enumFloor_取_createdAt_所在月`() = runBlocking {
        val f = sched.refreshEnumFloor(storeKey, createdAt = oct20)
        assertEquals(SyncMonth(2026, 10), f)
        assertEquals(SyncMonth(2026, 10), floor())
    }

    @Test
    fun `enumFloor_被本地最早订单往前推_这是离线补传场景`() = runBlocking {
        // 2025-03 开始离线用、2026-10 才买服务 ⇒ createdAt=2026-10。
        // 照 README §3.2 从 createdAt 枚举的话，补传上去的 2025-03..2026-09
        // 永远不会被同步，第二台设备看不到它们 —— 数据永久分歧。
        seedOrder("u1", mar10_2025)
        val f = sched.refreshEnumFloor(storeKey, createdAt = oct20)
        assertEquals(SyncMonth(2025, 3), f)
        assertEquals(SyncMonth(2025, 3), floor())
    }

    @Test
    fun `enumFloor_只会往前推_不会往后收`() = runBlocking {
        // 卸载重装之后 order_index 空了，但 store_meta 里的下界还在。
        // 现算出的 2026-10 比库里的 2025-03 晚，SQL 的 WHERE 守卫必须挡住它。
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2025, 3))
        val f = sched.refreshEnumFloor(storeKey, createdAt = oct20)
        assertEquals(SyncMonth(2025, 3), f)
        // 守卫在 SQL 的 WHERE 里，不在调用方，所以写入照样会发一次、只是不生效
        assertEquals(Triple(storeKey, 2026, 10), meta.lowerCalls.single())
        assertEquals("下界被往后收了 —— 那些补传过的月份又不同步了", SyncMonth(2025, 3), floor())
    }

    @Test
    fun `enumFloor_createdAt_未知时退回当前月_而不是_1970`() = runBlocking {
        // createdAt=0 是 README §4.1 明确允许的。不设下界的话后台补同步会枚举
        // 672 个月，每次都在服务端建一个 4 字节空文件。
        val f = sched.refreshEnumFloor(storeKey, createdAt = 0L)
        assertEquals(SyncMonth(2026, 10), f)
        assertTrue(audits.any { it.contains("createdAt 未知") })
    }

    @Test
    fun `enumFloor_被硬下界夹住_不早于_2020_01`() = runBlocking {
        seedOrder("u1", 946_684_800_000L)   // 2000-01-01，明显是垃圾值
        val f = sched.refreshEnumFloor(storeKey, createdAt = 0L)
        assertEquals(SyncScheduler.HARD_FLOOR, f)
        assertEquals(SyncMonth(2020, 1), f)
    }

    @Test
    fun `enumFloor_orderTs_为_0_的订单不参与取最小`() = runBlocking {
        // orderTs=0 表示"时间未知"（别的客户端不写 content.ts）。
        // 算进 MIN 的话结果恒为 0 → 下界恒被夹到 2020-01 → 每轮枚举 80 多个月。
        seedOrder("u1", 0L)
        val f = sched.refreshEnumFloor(storeKey, createdAt = oct20)
        assertEquals(SyncMonth(2026, 10), f)
    }

    @Test
    fun `enumFloor_extra_可以把它推得更早_P5_的_outbox_要用`() = runBlocking {
        val f = sched.refreshEnumFloor(storeKey, createdAt = oct20, extra = SyncMonth(2024, 7))
        assertEquals(SyncMonth(2024, 7), f)
    }

    @Test
    fun `enumFloor_离线模式没有_store_meta_行时返回_null_不崩`() = runBlocking {
        meta.rows.clear()
        assertNull(sched.refreshEnumFloor("nope", createdAt = oct20))
        assertEquals(SyncScheduler.HARD_FLOOR, sched.enumFloor("nope"))
    }

    // ==================== 通道一：急同步 ====================

    @Test
    fun `急同步_发当月与上月的_B_加当年的_A`() = runBlocking {
        sched.urgentSync(storeKey)
        assertEquals(listOf(b(2026, 10), b(2026, 9), a(2026)), calls)
    }

    @Test
    fun `急同步_跨年时带上去年那个_A_类文件`() = runBlocking {
        clock = jan5_2027
        sched.urgentSync(storeKey)
        // 上月是 2026-12 ⇒ 去年的进货单文件也要拉，否则 12 月的进货单永远停在"待处理"
        assertEquals(listOf(b(2027, 1), b(2026, 12), a(2027), a(2026)), calls)
    }

    @Test
    fun `急同步_已归档的月份一个请求都不发`() = runBlocking {
        seedState(b(2026, 9), syncState = SyncState.ARCHIVED)
        val r = sched.urgentSync(storeKey)
        assertEquals(listOf(b(2026, 10), a(2026)), calls)
        assertEquals(1, r.skippedArchived)
        assertEquals(2, r.attempted)
    }

    @Test
    fun `离线模式没有_store_meta_时急同步直接返回_不发请求`() = runBlocking {
        meta.rows.clear()
        val r = sched.urgentSync(storeKey)
        assertTrue(calls.isEmpty())
        assertNotNull(r.aborted)
    }

    // ==================== 通道二：按需 ====================

    @Test
    fun `按需同步_只发那一个月的_B_加那一年的_A`() = runBlocking {
        sched.syncPeriod(storeKey, 2026, 3)
        assertEquals(listOf(b(2026, 3), a(2026)), calls)
    }

    @Test
    fun `按需同步_可以拉下界之前的月份_用户翻到了就该给`() = runBlocking {
        // enumFloor 只是**后台**通道的下界。用户在历史页翻到 2024-05 时
        // 拿"不在枚举范围里"当理由拒绝，表现就是"这个月永远是空的"。
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2026, 1))
        sched.syncPeriod(storeKey, 2024, 5)
        assertEquals(listOf(b(2024, 5), a(2024)), calls)
    }

    // ==================== 通道三：后台补同步 ====================

    @Test
    fun `后台补同步_覆盖下界到当月的全部月份_不只是近两个月`() = runBlocking {
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2026, 7))
        sched.backgroundCatchUp(storeKey)
        // 新月优先：用户最可能翻最近的月份，而最老的大概率前几轮已经补掉了
        assertEquals(
            listOf(b(2026, 10), b(2026, 9), b(2026, 8), b(2026, 7), a(2026)),
            calls,
        )
    }

    @Test
    fun `后台补同步_跳过已同步过的月份_包括那年那月没单的`() = runBlocking {
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2026, 8))
        // firstSyncedAt 非 null 且 version=0 ⇒ 拉过了、那月确实没单。
        // 用 version==0 判的话这种月份会被**永远**重拉，每次都是一趟 FULL 探测。
        seedState(b(2026, 9), version = 0)
        val r = sched.backgroundCatchUp(storeKey)
        assertEquals(listOf(b(2026, 10), b(2026, 8), a(2026)), calls)
        assertEquals(1, r.skippedDone)
    }

    @Test
    fun `后台补同步_DIVERGED_的月份要补_它正等着_FULL_自愈`() = runBlocking {
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2026, 9))
        // commitDiverged 保留了 firstSyncedAt，但把游标归零了。
        // 只看 firstSyncedAt 的话这个文件永远不会被重同步，镜像就一直坏着。
        seedState(b(2026, 9), syncState = SyncState.DIVERGED)
        sched.backgroundCatchUp(storeKey)
        assertTrue(calls.contains(b(2026, 9)))
    }

    @Test
    fun `后台补同步_归档的月份永久出局`() = runBlocking {
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2026, 8))
        seedState(b(2026, 8), syncState = SyncState.ARCHIVED)
        seedState(a(2026), syncState = SyncState.ARCHIVED)
        val r = sched.backgroundCatchUp(storeKey)
        assertEquals(listOf(b(2026, 10), b(2026, 9)), calls)
        assertEquals(2, r.skippedArchived)
    }

    @Test
    fun `后台补同步_预算截断时必须记审计_不许静默`() = runBlocking {
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2026, 1))
        val r = sched.backgroundCatchUp(storeKey, maxFiles = 3)
        assertEquals(3, calls.size)
        assertEquals(3, r.attempted)
        // 静默截断的表现是"某些月份永远同步不到"，而日志里什么都看不到
        val line = audits.single { it.contains("预算 3 用尽") }
        assertTrue("审计里要写清跳过了多少个：$line", line.contains("跳过 8 个文件"))
    }

    @Test
    fun `后台补同步_下界距今过远时夹到枚举上限并告警`() = runBlocking {
        // enumFloor 被写坏成 1970-01 时不能真的发 672 次请求
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(1970, 1))
        sched.backgroundCatchUp(storeKey, maxFiles = 1)
        assertTrue(audits.any { it.contains("超过 240 个月") })
        assertEquals(1, calls.size)
    }

    @Test
    fun `后台补同步_下界在未来时什么都不发_而不是抛异常`() = runBlocking {
        // 时钟被改过（或 enumFloor 被写成明年）。抛异常会让 WorkManager 一直重试。
        meta.rows[storeKey] = storeMeta(enumFloor = SyncMonth(2027, 5))
        val r = sched.backgroundCatchUp(storeKey)
        assertTrue(calls.isEmpty())
        assertEquals(0, r.attempted)
        assertNull(r.aborted)
    }

    // ==================== 中止 ====================

    @Test
    fun `_429_中止整批_后面的文件一个都不发`() = runBlocking {
        sched = newScheduler(parallelism = 2)
        script[b(2026, 10)] = Outcome.RateLimited("429")
        val r = sched.urgentSync(storeKey)
        // chunk1 = [B10, B09] 都发出去了；chunk2 = [A2026] 被中止标志挡住
        assertEquals(listOf(b(2026, 10), b(2026, 9)), calls)
        assertNotNull(r.aborted)
        assertTrue(r.aborted!!.contains("429"))
        // 429 也算一次失败，但 retriable 恒 false：调用方该等窗口过去，不该退避重试
        val f = r.failed.single()
        assertEquals(b(2026, 10), f.fileId)
        assertFalse(f.retriable)
        assertTrue(r.needsAttention)
    }

    @Test
    fun `_401_中止整批_因为后面每个请求都会_401`() = runBlocking {
        sched = newScheduler(parallelism = 2)
        script[b(2026, 10)] = Outcome.NeedsRelogin(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, mayAutoRelogin = true)
        val r = sched.urgentSync(storeKey)
        assertEquals(listOf(b(2026, 10), b(2026, 9)), calls)
        assertNotNull(r.aborted)
        val item = r.needsRelogin.single()
        assertEquals(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, item.reason)
        assertTrue(item.mayAutoRelogin)
    }

    @Test
    fun `被踢下线时_mayAutoRelogin_必须是_false`() = runBlocking {
        // KICKED 之后自动重登会形成"每半小时一次的自杀循环"（方案 §3.11）。
        // SSE 的 KICKED 事件归类成 SESSION_TAKEN_OVER（见 ApiError.kt 那个枚举的注释）。
        script[b(2026, 10)] = Outcome.NeedsRelogin(UnauthorizedReason.SESSION_TAKEN_OVER, mayAutoRelogin = false)
        val r = sched.urgentSync(storeKey)
        assertFalse(r.needsRelogin.single().mayAutoRelogin)
    }

    @Test
    fun `断网或_500_只记这一个_其余月份照跑`() = runBlocking {
        // 这是某个 fileId 自己的问题，中止整批会让别的月份白等一轮
        sched = newScheduler(parallelism = 2)
        script[b(2026, 10)] = Outcome.Failed("500 traceId=abc", retriable = true)
        val r = sched.urgentSync(storeKey)
        assertEquals(3, calls.size)
        assertNull(r.aborted)
        val f = r.failed.single()
        assertTrue(f.msg.contains("traceId=abc"))
        assertTrue(f.retriable)
    }

    @Test
    fun `SERVER_BEHIND_不中止也不自动处理_必须问用户`() = runBlocking {
        // 两个出口（接受服务器版本 / 回推本地尾部）都会丢数据或改写服务器
        script[b(2026, 9)] = Outcome.ServerBehind(localVersion = 87, serverVersion = 61)
        val r = sched.urgentSync(storeKey)
        assertEquals(3, calls.size)
        assertNull(r.aborted)
        val sb = r.serverBehind.single()
        assertEquals(b(2026, 9), sb.fileId)
        assertEquals(87, sb.localVersion)
        assertEquals(61, sb.serverVersion)
        assertTrue(r.needsAttention)
    }

    @Test
    fun `本次新判成归档的文件要计数_UI_据此把该月置灰`() = runBlocking {
        script[b(2026, 9)] = Outcome.Archived(b(2026, 9), localVersion = 12)
        val r = sched.urgentSync(storeKey)
        assertEquals(3, r.attempted)
        assertEquals("新判归档要单独计数：UI 据此把该月置灰、只显示、不允许再往里开单", 1, r.archived)
        assertEquals(2, r.upToDate)
        // 归档是**正常的终态**（运维删了过期文件），不是需要用户操心的事
        assertFalse(r.needsAttention)
    }

    // ==================== 三重自检 ====================

    @Test
    fun `启动自检_只查当月与上月_且不算_checksum`() = runBlocking {
        // 全量抽检对几十 MB 镜像是流式求和（30~80ms/个），放在启动路径上就是 ANR
        sched.selfCheckRecent(storeKey)
        assertEquals(listOf(b(2026, 10) to false, b(2026, 9) to false), checks)
    }

    @Test
    fun `全量自检_查所有非归档文件_并算_checksum`() = runBlocking {
        seedState(b(2026, 10))
        seedState(b(2026, 9))
        seedState(b(2026, 8), syncState = SyncState.ARCHIVED)
        seedState(a(2026), syncState = SyncState.DIVERGED)

        val bad = sched.selfCheckAll(storeKey)

        // ARCHIVED 必须排除：归档月份本来就可能没有本地镜像，selfCheck 对它返回 false
        // 只是"报一下"。把它列进来，调用方会去排一次 FULL —— 而 FULL 会覆盖那份
        // 本该冻结的归档镜像。
        assertEquals(3, checks.size)
        assertFalse(checks.any { it.first == b(2026, 8) })
        assertTrue(checks.all { it.second })
        assertTrue(bad.isEmpty())
    }

    @Test
    fun `全量自检_把不通过的文件报出来`() = runBlocking {
        seedState(b(2026, 10))
        seedState(b(2026, 9))
        checkResult = false
        val bad = sched.selfCheckAll(storeKey)
        assertEquals(2, bad.size)
        assertTrue(audits.any { it.contains("三重自检不通过 2 个文件") })
    }

    @Test
    fun `自检抛异常时按不通过处理_不让整轮自检崩掉`() = runBlocking {
        seedState(b(2026, 10))
        sched = SyncScheduler(
            syncOne = { _, _ -> defaultOutcome },
            checkOne = { _, _, _ -> throw java.io.IOException("disk gone") },
            dao = dao,
            storeMetaDao = meta,
            timeZone = { utc },
            now = { clock },
            audit = { audits += it },
            io = Dispatchers.Unconfined,
            parallelism = 1,
        )
        val bad = sched.selfCheckAll(storeKey)
        assertEquals(listOf(b(2026, 10)), bad)
        assertTrue(audits.any { it.contains("自检抛异常") })
    }

    // ==================== SyncMonth ====================

    @Test
    fun `SyncMonth_跨年进退位正确`() {
        assertEquals(SyncMonth(2027, 1), SyncMonth(2026, 12).next())
        assertEquals(SyncMonth(2026, 12), SyncMonth(2027, 1).prev())
        assertEquals(SyncMonth(2026, 2), SyncMonth(2026, 1).next())
    }

    @Test
    fun `SyncMonth_monthsBackTo_新月在前且含两端`() {
        assertEquals(
            listOf(SyncMonth(2026, 3), SyncMonth(2026, 2), SyncMonth(2026, 1)),
            SyncMonth(2026, 3).monthsBackTo(SyncMonth(2026, 1)),
        )
        assertEquals(
            listOf(SyncMonth(2026, 1), SyncMonth(2025, 12)),
            SyncMonth(2026, 1).monthsBackTo(SyncMonth(2025, 12)),
        )
        assertTrue(SyncMonth(2026, 1).monthsBackTo(SyncMonth(2026, 2)).isEmpty())
        assertEquals(listOf(SyncMonth(2026, 5)), SyncMonth(2026, 5).monthsBackTo(SyncMonth(2026, 5)))
    }

    @Test
    fun `SyncMonth_toString_恒两位_与_fileId_的月份段一致`() {
        // `2026-9` 与服务端的 `2026-09` 是两个不同的文件，而两个都能正常同步，
        // 只是数据被劈成两半 —— 没有任何一处会报错。
        assertEquals("2026-09", SyncMonth(2026, 9).toString())
        assertEquals("2026-09", OrderFileId.parse(b(2026, 9)).let { "${it.year}-${it.month.toString().padStart(2, '0')}" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `SyncMonth_拒绝非法月份`() {
        SyncMonth(2026, 13)
    }

    @Test
    fun `SyncMonth_monthsBackTo_倒数到_1970_01_就地停住`() {
        // `store_meta.enumFloor*` 被写坏成 1970-01 是运维做得出来的事。
        // 撞 require(year in 1970..) 抛异常的话，一次后台补同步会在 WorkManager 里反复重试。
        assertEquals(
            listOf(SyncMonth(1970, 3), SyncMonth(1970, 2), SyncMonth(1970, 1)),
            SyncMonth(1970, 3).monthsBackTo(SyncMonth.MIN),
        )
        // 下界就是 MIN 本身：一个元素，且不会去 prev()
        assertEquals(listOf(SyncMonth.MIN), SyncMonth.MIN.monthsBackTo(SyncMonth.MIN))
    }

    @Test
    fun `SyncMonth_monthsBackTo_limit_从新月那端截断`() {
        // 后台补同步靠它把"下界距今 682 个月"夹到 240 个，且夹掉的是**最老**的
        val capped = SyncMonth(2026, 10).monthsBackTo(SyncMonth(1970, 1), limit = 3)
        assertEquals(listOf(SyncMonth(2026, 10), SyncMonth(2026, 9), SyncMonth(2026, 8)), capped)
        assertTrue(SyncMonth(2026, 10).monthsBackTo(SyncMonth(2026, 1), limit = 0).isEmpty())
    }

    // ==================== 测试替身 ====================

    /**
     * [StoreMetaDao] 的内存实现。
     *
     * ⚠ [lowerEnumFloor] 必须照抄 SQL 里那个 `AND (enumFloorYear > :year OR …)` 守卫。
     * 抄成无条件写入的话，"下界只往前推"这条不变量就测不到了 ——
     * 而它正是"卸载重装后补传过的月份又不同步了"这个 bug 的唯一防线。
     */
    private class FakeStoreMetaDao : StoreMetaDao {
        val rows = LinkedHashMap<String, StoreMeta>()
        val lowerCalls = mutableListOf<Triple<String, Int, Int>>()

        override suspend fun upsert(meta: StoreMeta) {
            rows[meta.storeKey] = meta
        }

        override suspend fun get(storeKey: String): StoreMeta? = rows[storeKey]

        override fun observe(storeKey: String): Flow<StoreMeta?> = flowOf(rows[storeKey])

        override suspend fun getAll(): List<StoreMeta> = rows.values.sortedByDescending { it.adoptedAt }

        override suspend fun getAllStoreKeys(): List<String> = rows.keys.toList()

        override suspend fun setPriceVersion(storeKey: String, v: Int) {
            rows[storeKey]?.let { rows[storeKey] = it.copy(priceVersion = v) }
        }

        override suspend fun setStockVersion(storeKey: String, v: Int) {
            rows[storeKey]?.let { rows[storeKey] = it.copy(stockVersion = v) }
        }

        override suspend fun lowerEnumFloor(storeKey: String, year: Int, month: Int): Int {
            lowerCalls += Triple(storeKey, year, month)
            val cur = rows[storeKey] ?: return 0
            val earlier = cur.enumFloorYear > year ||
                (cur.enumFloorYear == year && cur.enumFloorMonth > month)
            if (!earlier) return 0
            rows[storeKey] = cur.copy(enumFloorYear = year, enumFloorMonth = month)
            return 1
        }

        override suspend fun getEnumFloor(storeKey: String): EnumFloor? =
            rows[storeKey]?.let { EnumFloor(it.enumFloorYear, it.enumFloorMonth) }

        override suspend fun setPerms(storeKey: String, permsCsv: String) {
            rows[storeKey]?.let { rows[storeKey] = it.copy(permsCsv = permsCsv) }
        }

        override suspend fun recordLogin(storeKey: String, previousLoginAt: Long, currentLoginAt: Long) {
            rows[storeKey]?.let {
                rows[storeKey] = it.copy(lastLoginAt = currentLoginAt, previousLoginAt = previousLoginAt)
            }
        }

        override suspend fun adoptStoreKey(oldKey: String, newKey: String, now: Long) {
            val cur = rows.remove(oldKey) ?: return
            rows[newKey] = cur.copy(storeKey = newKey, adoptedAt = now)
        }
    }
}
