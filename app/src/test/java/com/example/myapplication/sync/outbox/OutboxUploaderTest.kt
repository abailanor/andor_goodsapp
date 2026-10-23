package com.example.myapplication.sync.outbox

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.lock.StripedMutex
import com.example.myapplication.db.OrderIndexEntity
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.mirror.FakeOrderFileDao
import com.example.myapplication.mirror.MirrorCommitter
import com.example.myapplication.mirror.MirrorLocator
import com.example.myapplication.mirror.RoomMirrorStateStore
import com.example.myapplication.mirror.SyncState
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.OrderFileApi
import com.example.myapplication.net.Service1Api
import com.example.myapplication.net.UnauthorizedReason
import com.example.myapplication.net.dto.OrderAddResultDto
import com.example.myapplication.net.dto.OrderSyncRequestDto
import com.example.myapplication.net.dto.StockChangeEntryDto
import com.example.myapplication.sync.FakeStockDao
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.OrderFileSyncEngine
import com.example.myapplication.sync.StatsProjection
import com.example.myapplication.sync.StockLedger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.TimeZone

/**
 * [OutboxUploader] 的**错误分类**与 **IN_FLIGHT 崩溃恢复**。
 *
 * ## 为什么这个类的测试值得写这么厚
 *
 * 它犯的错全都是**静默且不可恢复**的。服务端 `order_write.lua` 里没有任何 uuid 去重：
 * 把"其实已经收下了"误判成"没收下"就会重推，同一个 `order_uuid` 在服务器文件里出现两行，
 * 镜像比服务器长，此后所有偏移错位 —— 而等长覆写不报错、文件长度不变、校验和自洽，
 * **没有任何机制会发现**。所以这里逐条钉住"每个错误码到底意味着服务端收没收下"。
 *
 * 与之对称的另一半（状态机的 CAS 与 `OUTBOX` 桶的加减）在 [OutboxStoreTest]；
 * `OUTBOX → FILE:` 的搬桶在 `RoomMirrorStateStoreTest`。三处刻意不重叠。
 *
 * ## 偏移是**独立算**的
 *
 * 服务端返回的 `uuidOffset` / `statusOffset` 由 [FakeApi] 按"追加到该文件当前尾部"推算，
 * **不用** `OrderJsonScanner`、也不用 `SerializedOrder` 自报的下标 ——
 * 用它们的话 `verifyAgainstServer` 那条断言就只是把自己的输出抄回来，
 * 而这个交叉校验存在的唯一意义就是"两边独立实现，不一致就说明有一方错了"。
 * 中文商品名（6 个汉字，每个多占 2 字节）正是为了让字符下标与字节下标分岔。
 */
class OutboxUploaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeOrderFileDao()
    private val stockDao = FakeStockDao()
    private val audits = mutableListOf<String>()
    private val utc = TimeZone.getTimeZone("UTC")
    private var clock = 1_000L

    private lateinit var mirrorRoot: File
    private lateinit var archiveRoot: File
    private lateinit var quarantineRoot: File
    private lateinit var stateStore: RoomMirrorStateStore
    private lateinit var committer: MirrorCommitter
    private lateinit var uploader: OutboxUploader

    private val api = FakeApi()
    private val ledger = StockLedger(stockDao, now = { clock })

    /** `syncFile` 的脚本。用完就抛，不返回默认值 —— 理由与 [FakeApi] 相同。 */
    private val syncScript = ArrayDeque<OrderFileSyncEngine.Outcome>()
    private val syncedFiles = mutableListOf<String>()

    /**
     * 在 `syncFile` **执行期间**跑的钩子。
     *
     * 真实的同步会把 ADD 消息落进镜像、并由 `applyAdded → promoteFromOutbox` 翻状态与搬桶。
     * 这里 `syncFile` 被替身接管，所以那些副作用必须由测试在**正确的时刻**补上 ——
     * 时机很关键：`recoverInFlight` 是先读 `outboxByState(IN_FLIGHT)` 再同步的，
     * 若在调用 `recoverInFlight` 之前就把行翻成 PROMOTED，它根本不会进待恢复列表，
     * 测试会"通过"但断言的场景早已不是原本那个。
     */
    private var duringSync: (() -> Unit)? = null

    private var uuidSeq = 0
    private fun nextUuid(): String {
        uuidSeq++
        return "aaaaaaaa-bbbb-cccc-dddd-" + uuidSeq.toString(16).padStart(12, '0')
    }

    private val storeKey = "00002"
    /** **店主**名，不是登录名。拼 fileId 用它 —— 见 `OutboxContribution.partsOf` 的警告。 */
    private val ownerName = "alice"

    /** 2026-09-15T00:00:00Z。 */
    private val sept15 = 1789430400000L
    private val partsB = OrderFileId.Parts(OrderFileId.TYPE_B, ownerName, 2026, 9)
    private val fileIdB = partsB.fileId
    private val fileScope = StatsProjection.fileScope(fileIdB)
    private val cola = "6901234567890"

    private val store = OutboxStore(
        dao = dao,
        inTransaction = { body -> body() },
        timeZone = { utc },
        now = { clock },
        uuidFactory = { nextUuid() },
        audit = { audits += it },
    )

    @Before
    fun setUp() {
        mirrorRoot = tmp.newFolder("mirror")
        archiveRoot = tmp.newFolder("mirror-archive")
        quarantineRoot = tmp.newFolder("mirror-quarantine")
        stateStore = RoomMirrorStateStore(
            dao = dao,
            inTransaction = { body -> body() },
            timeZone = { utc },
            now = { clock },
            audit = { audits += it },
        )
        committer = MirrorCommitter(
            locator = MirrorLocator(mirrorRoot, archiveRoot),
            quarantineRoot = quarantineRoot,
            locks = StripedMutex(),
            stateStore = stateStore,
            wallClock = { clock },
            audit = { audits += it },
        )
        uploader = OutboxUploader(
            api = api,
            store = store,
            committer = committer,
            syncFile = { _, fid ->
                syncedFiles += fid
                val step = syncScript.removeFirstOrNull()
                    ?: throw AssertionError("脚本用完了：syncFile 被多调了一次（已同步 ${syncedFiles.size} 次）")
                duringSync?.invoke()
                step
            },
            dao = dao,
            stockLedger = ledger,
            audit = { audits += it },
        )
    }

    // ==================== 构造测试数据 ====================

    private fun line(
        barcode: String = cola,
        name: String = "可口可乐330ml",
        qty: Int = 14,
        amount: String = "52.00",
    ) = OrderLine(barcode, name, qty, amount, "1.00")

    private fun req(
        fileType: String = OrderFileId.TYPE_B,
        year: Int = 2026,
        month: Int? = 9,
        lines: List<OrderLine> = listOf(line()),
        remarkText: String? = null,
    ) = OrderGroupAssembler.Request(fileType, year, month, "alice", lines, sept15, null, remarkText)

    /** 入一笔单到 outbox，返回它的 uuid。 */
    private fun newOrder(
        r: OrderGroupAssembler.Request = req(),
        missing: List<String> = emptyList(),
    ): String = runBlocking { store.enqueue(storeKey, ownerName, r, missing).rows.single().orderUuid }

    private fun uuidOf(json: String): String = json.substringAfter("\"order_uuid\":\"").substringBefore('"')

    /** uuid **值**的字节下标（独立于 `OrderJsonScanner`）。 */
    private fun uuidIdx(json: String, uuid: String): Int = ByteOps.utf8Len(json.substring(0, json.indexOf(uuid)))

    /** status **数字**的字节下标（独立于 `OrderJsonScanner`）。 */
    private fun statusIdx(json: String): Int =
        ByteOps.utf8Len(json.substring(0, json.indexOf("\"status\":") + "\"status\":".length))

    /**
     * 排一步"服务端会怎么应答"。
     *
     * 偏移与版本号由 [FakeApi] 自己按文件尾推算 —— 空镜像只有 4 字节大端头，
     * 且 `guardAdd` 要求 `msgVersion == headerVersion + 1`，所以首条 ADD 的版本必须是 **1**。
     */
    private fun serveAdd(
        fileId: String? = this.fileIdB,
        uuidOverride: String? = null,
        uuidOffsetOverride: Long? = null,
        statusOffsetOverride: Long? = null,
        omitUuid: Boolean = false,
        omitOffsets: Boolean = false,
        stockChanges: List<StockChangeEntryDto>? = null,
    ) {
        api.serve { json ->
            val real = uuidOf(json)
            val fid = fileId ?: fileIdB
            val lineStart = api.lineStartOf(fid)
            val version = api.nextVersionOf(fid)
            OrderAddResultDto(
                fileId = fileId,
                orderUuid = if (omitUuid) null else (uuidOverride ?: real),
                // ⚠ 偏移一律按**真的那个 uuid** 算：`uuidOverride` 只是响应里回显的字段，
                // 拿它去 json 里搜会搜不到（那正是"回显不符"那条用例要制造的情形）。
                uuidOffset = if (omitOffsets) null else (uuidOffsetOverride ?: lineStart + uuidIdx(json, real)),
                statusOffset = if (omitOffsets) null else (statusOffsetOverride ?: lineStart + statusIdx(json)),
                version = if (omitOffsets) null else version,
                streamId = "$version-0",
                stockChanges = stockChanges,
            )
        }
    }

    private fun upload(uuid: String): OutboxUploader.UploadResult =
        runBlocking { uploader.uploadOne(storeKey, ownerName, uuid) }

    private fun state(uuid: String): String? = dao.outboxState(storeKey, uuid)

    private fun indexOf(uuid: String, fileId: String = fileIdB): OrderIndexEntity? =
        runBlocking { dao.orderIndex(storeKey, fileId, uuid) }

    private fun outboxTotal(metric: String): Long =
        dao.contribTotal(storeKey, StatsProjection.OUTBOX_SCOPE, metric)

    private fun fileTotal(metric: String): Long = dao.contribTotal(storeKey, fileScope, metric)

    /** 让行进入 IN_FLIGHT（write-ahead 已落盘）。 */
    private fun inFlight(uuid: String) {
        assertNotNull(runBlocking { store.takeOff(storeKey, uuid) })
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
    }

    /** 假装"一次同步已经把这个 uuid 建进索引了"。 */
    private fun seedIndex(uuid: String, fileId: String = fileIdB) = runBlocking {
        dao.insertOrderIndex(
            OrderIndexEntity(
                storeKey = storeKey, fileId = fileId, orderUuid = uuid, writer = "alice", status = 1,
                orderTs = sept15, totalCents = 5200, manualTotal = false, itemCount = 1,
                lineStart = 4, uuidOffset = 19, statusOffset = 104, remarkOffset = null,
                remark = null, appliedFileVersion = 1, cancelAppliedVersion = null, groupId = null,
            )
        )
    }

    /** 假装"同步期间 applyAdded → promoteFromOutbox 已经把这一行提升了"。 */
    private fun promoteDuringSync(uuid: String) {
        duringSync = {
            seedIndex(uuid)
            runBlocking { dao.markOutboxPromoted(storeKey, uuid) }
        }
    }

    private fun recover(): List<OutboxUploader.Recovery> =
        runBlocking { uploader.recoverInFlight(storeKey, ownerName) }

    private fun payloadOf(uuid: String): String =
        String(dao.outbox[storeKey to uuid]!!.payload, Charsets.UTF_8)

    // ==================== 成功路径 ====================

    @Test
    fun `上传成功_行转_PROMOTED_贡献从_OUTBOX_桶搬到_FILE_桶`() {
        val uuid = newOrder()
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        serveAdd()

        val r = upload(uuid)

        assertTrue("期望 Promoted，实际 $r（审计：$audits）", r is OutboxUploader.UploadResult.Promoted)
        assertEquals(1, (r as OutboxUploader.UploadResult.Promoted).version)
        assertEquals(OrderOutboxEntity.PROMOTED, state(uuid))
        // 搬桶：OUTBOX 归零、FILE: 接住。**净值为零**是加减对称的唯一可观测判据。
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(5200L, fileTotal(StatsProjection.METRIC_AMOUNT))
        assertEquals(1L, fileTotal(StatsProjection.METRIC_ORDERS))
        assertEquals(14L, fileTotal(StatsProjection.METRIC_PIECES))
    }

    @Test
    fun `上传成功_order_index_记下服务端给的权威偏移与游标`() {
        val uuid = newOrder()
        serveAdd()

        upload(uuid)

        val idx = indexOf(uuid)
        assertNotNull("索引行没建出来（审计：$audits）", idx)
        // 偏移必须用服务端给的，不是自己推算的（推算只在 FULL 恢复时才不得已为之）
        val json = payloadOf(uuid)
        assertEquals(4L + uuidIdx(json, uuid), idx!!.uuidOffset)
        assertEquals(4L + statusIdx(json), idx.statusOffset)
        assertEquals(4L, idx.lineStart)
        assertEquals(1, idx.appliedFileVersion)
        // 游标推进到响应的 streamId
        val st = runBlocking { stateStore.read(storeKey, fileIdB) }!!
        assertEquals(1, st.version)
        assertEquals("1-0", st.lastStreamId)
        // 镜像长度 = 4 字节头 + payload + 1 个 LF
        assertEquals(4L + dao.outbox[storeKey to uuid]!!.payload.size + 1, st.mirrorBytes)
        assertEquals(SyncState.LIVE, st.syncState)
    }

    @Test
    fun `A_类订单的_month_传_null_而不是_0`() {
        // A 类没有月份，而服务端 validateTypeAndMonth 对 month<1||month>12 直接 400
        val uuid = newOrder(req(fileType = OrderFileId.TYPE_A, month = null, remarkText = "进货一批"))
        val aFileId = OrderFileId.Parts(OrderFileId.TYPE_A, ownerName, 2026, OrderFileId.NO_MONTH).fileId
        serveAdd(fileId = aFileId)

        val r = upload(uuid)

        assertTrue("期望 Promoted，实际 $r（审计：$audits）", r is OutboxUploader.UploadResult.Promoted)
        val call = api.adds.single()
        assertEquals(OrderFileId.TYPE_A, call.type)
        assertNull("A 类必须传 null，传 0 会拿 400", call.month)
        // A 类不产生营收贡献：桶里一行都不该有
        assertEquals(0L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `发出去的字节就是库里存的那一份`() {
        val uuid = newOrder()
        val stored = dao.outbox[storeKey to uuid]!!.payload
        serveAdd()

        upload(uuid)

        // 重推时重新序列化会让字节变掉 → 偏移变掉 → remarkOffset 全错
        assertEquals(String(stored, Charsets.UTF_8), api.adds.single().payload)
    }

    // ==================== 库存对账（不对称） ====================

    @Test
    fun `响应的_stockChanges_只在本地偏低时告警`() {
        // 本地偏高是正常的：别的设备此后又卖了货，本地还没同步到。
        // 对称地写（!= 就告警）会让每次正常的多设备使用都报一次假警，用户三次之后就再也不看告警了。
        val b1 = "6900000000001"
        val b2 = "6900000000002"
        val low = newOrder(req(lines = listOf(line(barcode = b1))))
        val high = newOrder(req(lines = listOf(line(barcode = b2))))
        stockDao.putSynced(storeKey, b1, 100)
        stockDao.putSynced(storeKey, b2, 100)

        serveAdd(stockChanges = listOf(StockChangeEntryDto(b1, 120)))
        upload(low)
        assertTrue("本地偏低必须告警（审计：$audits）", audits.any { "库存对账" in it && b1 in it })

        audits.clear()
        serveAdd(stockChanges = listOf(StockChangeEntryDto(b2, 90)))
        upload(high)
        assertFalse("本地偏高是正常的，不该告警（审计：$audits）", audits.any { "库存对账" in it })
    }

    @Test
    fun `stockChanges_有一条缺字段时跳过它并记审计_不影响其它条`() {
        val uuid = newOrder()
        serveAdd(
            stockChanges = listOf(
                StockChangeEntryDto(null, 5),
                StockChangeEntryDto(cola, null),
            )
        )

        val r = upload(uuid)

        // 缺字段是服务端形状问题，不该让整笔上传失败
        assertTrue("期望 Promoted，实际 $r（审计：$audits）", r is OutboxUploader.UploadResult.Promoted)
        assertEquals(2, audits.count { "缺字段" in it })
    }

    // ==================== 错误分类：确定没提交 ====================

    @Test
    fun `_429_退回_READY_不计尝试次数_并要求中止整批`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.RateLimited("操作过于频繁，请稍后再试")

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.RetryLater)
        // 429 的桶按 IP 共享（整店一起被锁死），且 Guava 平滑桶几乎没有突发额度
        assertTrue((r as OutboxUploader.UploadResult.RetryLater).abortBatch)
        assertEquals(OrderOutboxEntity.READY, state(uuid))
        // 不是这一单的问题：计了的话一次早上的重登风暴就能把全店的单烧进 FAILED_PERMANENT
        assertEquals(0, dao.outbox[storeKey to uuid]!!.attemptCount)
        // 钱还在 OUTBOX 桶里（退回 READY 不搬桶）
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `_401_退回_READY_不计尝试次数_并要求中止整批`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.Unauthorized(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, null)

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.RetryLater)
        assertTrue((r as OutboxUploader.UploadResult.RetryLater).abortBatch)
        assertEquals(OrderOutboxEntity.READY, state(uuid))
        assertEquals(0, dao.outbox[storeKey to uuid]!!.attemptCount)
        assertTrue(r.error.contains("重新登录"))
    }

    @Test
    fun `_403_退回_READY_并要求中止整批_因为权限是每请求实时读的`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.Forbidden("权限不足")

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.RetryLater)
        assertTrue((r as OutboxUploader.UploadResult.RetryLater).abortBatch)
        assertEquals(OrderOutboxEntity.READY, state(uuid))
    }

    // ==================== 错误分类：重推也不会有不同结果 ====================

    @Test
    fun `_413_转_FAILED_PERMANENT_因为正确反应是拆单而不是重试`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.PayloadTooLarge("请求体超过大小上限（20KB）")

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.FailedPermanent)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, state(uuid))
        // 同样大小的 body 只会再拿一个 413，白烧三次退避之后还是这里
        assertTrue(audits.any { "永久失败" in it })
    }

    @Test
    fun `_400_转_FAILED_PERMANENT`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.BadRequest("请求参数有误")

        assertTrue(upload(uuid) is OutboxUploader.UploadResult.FailedPermanent)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, state(uuid))
    }

    @Test
    fun `_404_转_FAILED_PERMANENT_因为那只可能是接口路径不存在`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.NotFound("资源不存在")

        assertTrue(upload(uuid) is OutboxUploader.UploadResult.FailedPermanent)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, state(uuid))
    }

    // ==================== 错误分类：结果未知（最关键的一组） ====================

    @Test
    fun `网络超时_留在_IN_FLIGHT_绝不退回_READY`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.Network(IOException("timeout"))

        // uploadOne 自己会 takeOff（READY → IN_FLIGHT），所以这里**不能**预先置位，
        // 否则 CAS 抢不到、直接 Skipped，永远走不到 API 那一步。
        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        // ⚠ 退回 READY 就等于授权重推 → 服务端不查重 → 重复订单 + 重复扣库存
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        assertEquals(1, dao.outbox[storeKey to uuid]!!.attemptCount)
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `_500_留在_IN_FLIGHT_并把追踪号记进审计`() {
        val uuid = newOrder()
        api.throwOnNext = ApiError.ServerError("ab12cd34", "服务器内部错误（追踪号 ab12cd34，请提供给运维查日志）")

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        // 500 的响应体只有一个追踪号。丢了它就没法对服务端日志报障。
        assertTrue(audits.any { "ab12cd34" in it })
    }

    @Test
    fun `响应形状错误_当成不知道_而不是当成没提交`() {
        // MalformedResponse 是 **200 之后**解析失败 —— 服务端极可能已经写进文件了
        val uuid = newOrder()
        api.throwOnNext = ApiError.MalformedResponse("不是 JSON 信封")

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
    }

    @Test
    fun `意外收到_409_时按不知道处理`() {
        // /add 不会返回 409（409 只在 sync）。真收到了说明有东西不对，保守处理。
        val uuid = newOrder()
        api.throwOnNext = ApiError.Conflict("冲突", isOrderFileServerBehind = false)

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
    }

    // ==================== 响应校验 ====================

    @Test
    fun `服务端回显的_uuid_与本地不符时不落地`() {
        val uuid = newOrder()
        serveAdd(uuidOverride = "ffffffff-ffff-ffff-ffff-ffffffffffff")

        val r = upload(uuid)

        // 落地就是把一笔订单记到别人名下，而且偏移全是错的
        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        assertNull("不该建索引行", indexOf(uuid))
        assertEquals(0L, fileTotal(StatsProjection.METRIC_AMOUNT))
        assertTrue(audits.any { "不符" in it })
    }

    @Test
    fun `响应缺_uuid_时留在_IN_FLIGHT`() {
        val uuid = newOrder()
        serveAdd(omitUuid = true)

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
    }

    @Test
    fun `响应缺偏移或版本号时留在_IN_FLIGHT_等一次_FULL_用推算偏移重建`() {
        val uuid = newOrder()
        serveAdd(omitOffsets = true)

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        assertNull(indexOf(uuid))
    }

    @Test
    fun `服务端把订单写进了另一个_fileId_时转_FAILED_PERMANENT_且不再重推`() {
        // 这是"ownerName 存成了登录名"那个地雷的可观测症状。
        // 重推会不断往一个本地永不同步的文件里追加 —— 只有这台设备知道那份数据。
        val uuid = newOrder()
        serveAdd(fileId = "B:bob:2026-09")

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.FailedPermanent)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, state(uuid))
        assertTrue(audits.any { "ownerName" in it })
        // 本地镜像一个字节都不该动
        assertNull(runBlocking { stateStore.read(storeKey, fileIdB) })
    }

    @Test
    fun `偏移交叉校验失败时不落地_留在_IN_FLIGHT`() {
        val uuid = newOrder()
        // statusOffset 差 1 字节：两边独立实现算出了不同的行起点，说明有一方错了
        serveAdd(statusOffsetOverride = api.lineStartOf(fileIdB) + statusIdx(payloadOf(uuid)) + 1)

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Unknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        assertNull(indexOf(uuid))
        assertTrue(audits.any { "偏移交叉校验失败" in it })
    }

    // ==================== write-ahead 与本地守卫 ====================

    @Test
    fun `行不是_READY_时一个字节都不发`() {
        val uuid = newOrder()
        inFlight(uuid)
        api.adds.clear()

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.Skipped)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, (r as OutboxUploader.UploadResult.Skipped).state)
        assertTrue("CAS 没抢到就不许发请求", api.adds.isEmpty())
    }

    @Test
    fun `行不存在时_Skipped_报_MISSING_而不是抛`() {
        val r = upload("ffffffff-ffff-ffff-ffff-ffffffffffff")

        assertEquals("MISSING", (r as OutboxUploader.UploadResult.Skipped).state)
        assertTrue(api.adds.isEmpty())
    }

    @Test
    fun `库里的_payload_扫不出偏移时拒发并转_FAILED_PERMANENT`() {
        val uuid = newOrder()
        // 直接篡改 BLOB：模拟"库里的字节已经坏了"。
        // 发出去的话服务端会用它自己的 indexOf 兜底算偏移，两边不一致就是静默错位。
        dao.outbox[storeKey to uuid] = dao.outbox[storeKey to uuid]!!
            .copy(payload = ByteOps.utf8("这不是 JSON"))

        val r = upload(uuid)

        assertTrue(r is OutboxUploader.UploadResult.FailedPermanent)
        assertTrue("绝不能把坏字节发出去", api.adds.isEmpty())
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, state(uuid))
    }

    // ==================== recoverInFlight ====================

    @Test
    fun `没有_IN_FLIGHT_行时一个请求都不发`() {
        newOrder()   // READY，不该被恢复流程碰

        assertTrue(recover().isEmpty())
        assertTrue(syncedFiles.isEmpty())
        assertTrue(api.adds.isEmpty())
    }

    @Test
    fun `同步后在_order_index_里找到它_当成功`() {
        val uuid = newOrder()
        inFlight(uuid)
        // 提升必须发生在 sync **期间**：recoverInFlight 是先读 IN_FLIGHT 列表再同步的
        promoteDuringSync(uuid)
        syncScript += OrderFileSyncEngine.Outcome.Applied(applied = 1, echoes = 0, version = 1, streamId = "1-0")

        val out = recover()

        assertEquals(listOf(OutboxUploader.Recovery.Confirmed(uuid)), out)
        assertTrue("服务端已经有了，绝不能再发一次", api.adds.isEmpty())
        assertEquals(listOf(fileIdB), syncedFiles)
    }

    @Test
    fun `找到索引行但_outbox_还没_PROMOTED_时大声报不变量被破坏`() {
        // FILE: 桶已经算过这笔钱了（索引行与贡献是一起插的），而 OUTBOX 桶还算着它
        // → 营业额永久多算一笔。本类不自己去搬桶：那会造出第二个搬桶点。
        val uuid = newOrder()
        inFlight(uuid)
        duringSync = { seedIndex(uuid) }   // 只建索引，不提升
        syncScript += OrderFileSyncEngine.Outcome.UpToDate(version = 1, streamId = "1-0")

        val out = recover()

        val missed = out.single()
        assertTrue(missed is OutboxUploader.Recovery.PromoteMissed)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, (missed as OutboxUploader.Recovery.PromoteMissed).state)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        assertTrue(audits.any { "不变量被破坏" in it })
        // 绝不自己搬桶
        assertEquals(5200L, outboxTotal(StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `同步后确认服务端没有它_于是重发一次`() {
        val uuid = newOrder()
        inFlight(uuid)
        syncScript += OrderFileSyncEngine.Outcome.UpToDate(version = 0, streamId = "")
        serveAdd()

        val out = recover()

        val resent = out.single()
        assertTrue(resent is OutboxUploader.Recovery.Resent)
        assertTrue((resent as OutboxUploader.Recovery.Resent).result is OutboxUploader.UploadResult.Promoted)
        assertEquals(1, api.adds.size)
        assertEquals(OrderOutboxEntity.PROMOTED, state(uuid))
    }

    @Test
    fun `重发时不再走_takeOff_因为行已经是_IN_FLIGHT`() {
        // takeOff 的 CAS 要求 READY，走它会让这一单永远卡在 IN_FLIGHT
        val uuid = newOrder()
        inFlight(uuid)
        syncScript += OrderFileSyncEngine.Outcome.Rebuilt(version = 0, streamId = "")
        serveAdd()

        recover()

        assertEquals(1, api.adds.size)
        assertEquals(OrderOutboxEntity.PROMOTED, state(uuid))
    }

    @Test
    fun `未知结果已到_MAX_ATTEMPTS_时放弃_而不是无限重推`() {
        val uuid = newOrder()
        inFlight(uuid)
        repeat(OrderOutboxEntity.MAX_ATTEMPTS) {
            runBlocking { store.noteUnknownResult(storeKey, uuid, "超时 $it") }
        }
        syncScript += OrderFileSyncEngine.Outcome.UpToDate(version = 0, streamId = "")

        val out = recover()

        assertTrue(out.single() is OutboxUploader.Recovery.GaveUp)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, state(uuid))
        assertTrue(api.adds.isEmpty())
    }

    @Test
    fun `放弃只发生在确认过服务端没有它之后`() {
        // 到顶了但索引里**有**这一单 → 是成功，不是失败。
        // 判反了会让用户看到"上传失败"就删掉重开一张，而服务器上那张还在。
        val uuid = newOrder()
        inFlight(uuid)
        repeat(OrderOutboxEntity.MAX_ATTEMPTS) {
            runBlocking { store.noteUnknownResult(storeKey, uuid, "超时 $it") }
        }
        promoteDuringSync(uuid)
        syncScript += OrderFileSyncEngine.Outcome.UpToDate(version = 1, streamId = "1-0")

        assertEquals(listOf(OutboxUploader.Recovery.Confirmed(uuid)), recover())
        assertEquals(OrderOutboxEntity.PROMOTED, state(uuid))
    }

    @Test
    fun `同步本身失败时原样留在_IN_FLIGHT`() {
        val uuid = newOrder()
        inFlight(uuid)
        syncScript += OrderFileSyncEngine.Outcome.Failed("网络失败", retriable = true)

        val out = recover()

        // 猜错会重复订单，等一等只会晚一点
        assertTrue(out.single() is OutboxUploader.Recovery.StillUnknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        assertTrue(api.adds.isEmpty())
    }

    @Test
    fun `SERVER_BEHIND_时原样留在_IN_FLIGHT_交用户决定`() {
        val uuid = newOrder()
        inFlight(uuid)
        syncScript += OrderFileSyncEngine.Outcome.ServerBehind(localVersion = 9, serverVersion = 3)

        assertTrue(recover().single() is OutboxUploader.Recovery.StillUnknown)
        assertEquals(OrderOutboxEntity.IN_FLIGHT, state(uuid))
        assertTrue(api.adds.isEmpty())
    }

    @Test
    fun `该月已归档时不重推_直接交人工`() {
        // 重推会让服务端按需重建一个已归档的文件（ensureFileBlocking），
        // 而本地再也不会同步它 → 只有这台设备知道那份数据
        val uuid = newOrder()
        inFlight(uuid)
        syncScript += OrderFileSyncEngine.Outcome.Archived(fileIdB, localVersion = 4)

        val out = recover()

        assertTrue(out.single() is OutboxUploader.Recovery.GaveUp)
        assertEquals(OrderOutboxEntity.FAILED_PERMANENT, state(uuid))
        assertTrue(api.adds.isEmpty())
        assertTrue(audits.any { "已归档" in it })
    }

    @Test
    fun `同一个_fileId_的多笔_IN_FLIGHT_只同步一次`() {
        val a = newOrder()
        val b = newOrder(req(lines = listOf(line(barcode = "6909999999999"))))
        inFlight(a)
        inFlight(b)
        // 只排一步：多同步一次就会因为脚本用完而抛
        syncScript += OrderFileSyncEngine.Outcome.UpToDate(version = 0, streamId = "")
        serveAdd()
        serveAdd()

        recover()

        // 一次 FULL 可能是几 MB，而同一个月的十笔单共享同一个文件
        assertEquals(listOf(fileIdB), syncedFiles)
        assertEquals(2, api.adds.size)
        assertEquals(OrderOutboxEntity.PROMOTED, state(a))
        assertEquals(OrderOutboxEntity.PROMOTED, state(b))
    }

    // ==================== uploadReady ====================

    @Test
    fun `按_createdAt_顺序批量上传`() {
        clock = 1_000L
        val a = newOrder()
        clock = 2_000L
        val b = newOrder(req(lines = listOf(line(barcode = "6909999999999"))))
        serveAdd()
        serveAdd()

        val report = runBlocking { uploader.uploadReady(storeKey, ownerName) }

        assertEquals("审计：$audits", 2, report.promoted)
        assertEquals(2, report.attempted)
        assertFalse(report.aborted)
        assertEquals(listOf(a, b), api.adds.map { uuidOf(it.payload) })
        // 两行都进了同一个镜像：第二行的行起点在第一行之后
        assertEquals(4L, indexOf(a)!!.lineStart)
        assertTrue(indexOf(b)!!.lineStart > indexOf(a)!!.lineStart)
    }

    @Test
    fun `_429_时中止整批并报告剩余条数`() {
        val a = newOrder()
        val b = newOrder(req(lines = listOf(line(barcode = "6901111111111"))))
        val c = newOrder(req(lines = listOf(line(barcode = "6902222222222"))))
        api.throwOnNext = ApiError.RateLimited("太频繁")
        serveAdd()
        serveAdd()

        val report = runBlocking { uploader.uploadReady(storeKey, ownerName) }

        // 429 的桶按 IP 共享，继续发只会把整店一起锁死
        assertTrue(report.aborted)
        assertEquals(1, report.retryLater)
        assertEquals(2, report.remaining)
        assertEquals(1, api.adds.size)
        assertEquals(OrderOutboxEntity.READY, state(a))
        assertEquals(OrderOutboxEntity.READY, state(b))
        assertEquals(OrderOutboxEntity.READY, state(c))
    }

    @Test
    fun `limit_截断_剩下的留给下一轮`() {
        val uuids = (1..3).map { newOrder(req(lines = listOf(line(barcode = "69012345678$it")))) }
        serveAdd()
        serveAdd()

        val report = runBlocking { uploader.uploadReady(storeKey, ownerName, limit = 2) }

        assertEquals("审计：$audits", 2, report.promoted)
        assertEquals(1, report.remaining)
        assertEquals(2, api.adds.size)
        assertEquals(OrderOutboxEntity.READY, state(uuids[2]))
    }

    @Test
    fun `没有_READY_行时一个请求都不发`() {
        newOrder(missing = listOf(cola))   // BLOCKED，不该被上传

        val report = runBlocking { uploader.uploadReady(storeKey, ownerName) }

        assertEquals(0, report.attempted)
        assertTrue(api.adds.isEmpty())
    }

    @Test
    fun `limit_必须为正`() {
        try {
            runBlocking { uploader.uploadReady(storeKey, ownerName, limit = 0) }
            fail("limit=0 应当抛")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("limit"))
        }
    }

    // ==================== HTTP 替身 ====================

    /**
     * 按脚本逐步应答 `/add` 的 [OrderFileApi]，并**自己维护服务端的文件尾**。
     *
     * ## 为什么要维护文件尾
     *
     * 真实服务端是"把这一行追加到当前文件末尾，版本 +1"，响应里的偏移由**它**算。
     * 替身若每次都用固定的 `lineStart=4, version=1`，第二笔上传就会被 `guardAdd`
     * 判成 DIVERGED（增量链有空洞）—— 测试会红，而红的原因是替身不像服务端，不是被测代码错了。
     *
     * ⚠ 脚本用完就**抛 AssertionError**，不返回默认值：
     * 上传器在"结果未知 → 重推"这类路径上会多发一次请求，
     * 若替身默默返回一个成功的 `OrderAddResultDto`，测试会绿着通过，
     * 而断言的东西早已不是原本那个场景 —— 那正是本类最要防的"重复上传"。
     *
     * ⚠ 文件尾**无条件推进**，即使客户端之后拒绝了这个响应。这才是服务端的行为，
     * 也正是"响应回显不符/偏移对不上"这些用例真实的样子：字节已经落盘了，客户端不认而已。
     */
    private class FakeApi : OrderFileApi {

        data class AddCall(val type: String, val year: Int, val month: Int?, val payload: String)

        val adds = mutableListOf<AddCall>()
        private val script = ArrayDeque<(String) -> OrderAddResultDto>()
        private val fileLen = HashMap<String, Long>()
        private val fileVer = HashMap<String, Int>()
        var throwOnNext: Throwable? = null

        fun serve(f: (String) -> OrderAddResultDto) {
            script += f
        }

        /** 该文件当前的字节长度。空文件只有 4 字节大端版本头。 */
        fun lineStartOf(fileId: String): Long = fileLen[fileId] ?: 4L

        /** 下一次追加会得到的版本号。 */
        fun nextVersionOf(fileId: String): Int = (fileVer[fileId] ?: 0) + 1

        private fun advance(fileId: String, lineBytes: Int) {
            fileLen[fileId] = lineStartOf(fileId) + lineBytes
            fileVer[fileId] = nextVersionOf(fileId)
        }

        override suspend fun addOrder(
            type: String,
            year: Int,
            month: Int?,
            payloadJson: String,
        ): OrderAddResultDto {
            adds += AddCall(type, year, month, payloadJson)
            throwOnNext?.let {
                throwOnNext = null
                throw it
            }
            val next = script.removeFirstOrNull()
                ?: throw AssertionError("脚本用完了：/add 被多调了一次（已发 ${adds.size} 次）")
            val result = next(payloadJson)
            advance(result.fileId.orEmpty(), ByteOps.utf8Len(payloadJson) + 1)
            return result
        }

        /** 上传器**从不**自己同步（那是 `SyncScheduler` 的事），它只通过 `syncFile` 参数请求同步。 */
        override suspend fun syncOrderFile(
            request: OrderSyncRequestDto,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ): Service1Api.SyncOutcome = throw AssertionError("上传器不该直接调 /sync：${request.type}")

        override suspend fun probeAfter409(
            type: String,
            year: Int,
            month: Int?,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ): Service1Api.SyncOutcome = throw AssertionError("上传器不该调 409 探测")
    }
}
