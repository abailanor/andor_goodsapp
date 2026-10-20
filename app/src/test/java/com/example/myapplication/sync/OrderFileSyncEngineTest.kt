package com.example.myapplication.sync

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.lock.StripedMutex
import com.example.myapplication.mirror.FakeOrderFileDao
import com.example.myapplication.mirror.MirrorCommitter
import com.example.myapplication.mirror.MirrorLocator
import com.example.myapplication.mirror.RoomMirrorStateStore
import com.example.myapplication.mirror.SyncState
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.OrderFileApi
import com.example.myapplication.net.Service1Api
import com.example.myapplication.net.UnauthorizedReason
import com.example.myapplication.net.dto.OrderStreamFields
import com.example.myapplication.net.dto.OrderStreamMessageDto
import com.example.myapplication.net.dto.OrderSyncRequestDto
// 同包也要显式 import：typealias 指向嵌套 sealed class 时 `Outcome.Failed` 解析不了
import com.example.myapplication.sync.OrderFileSyncEngine.Outcome
import com.example.myapplication.sync.outbox.OrderDraft
import com.example.myapplication.sync.outbox.OrderLine
import com.example.myapplication.sync.outbox.StableOrderJson
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.TimeZone

/**
 * [OrderFileSyncEngine] 的状态机（方案 §3.4）。
 *
 * ## 这里的断言盯的是三类"错了也不报错"
 *
 *  1. **游标**：存空串 / 存逐条 id / 链中断后照样推进 —— 每一种都会让某些消息
 *     永远不再被投递，统计静默少一笔。
 *  2. **镜像字节**：ADD 的 `lineStart` 由 `uuidOffset − 本地扫出的行内下标` 换算。
 *     差一个字节，此后每次取消都打在别的订单上，而等长覆写不报错、长度不变、校验和自洽。
 *  3. **FULL 的破坏力**：409 → 探测得 FULL 时**绝不能落地**那个 body ——
 *     服务器那份更短，而本地镜像是唯一更全的副本。
 *
 * SQL 语义由 androidTest 的 `OrderFileDaoTest` 覆盖；`RoomMirrorStateStore` 的幂等与
 * 统计加减对称由 [com.example.myapplication.mirror.RoomMirrorStateStoreTest] 覆盖。
 * 这里用的是**真的** `RoomMirrorStateStore` + 真的 `MirrorCommitter` + 真的镜像文件，
 * 只有 DAO 与 HTTP 是替身 —— 状态机的每个分支都要能一路走到磁盘。
 */
class OrderFileSyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var mirrorRoot: File
    private lateinit var archiveRoot: File
    private lateinit var quarantineRoot: File

    private val dao = FakeOrderFileDao()
    private val audits = mutableListOf<String>()
    private val utc = TimeZone.getTimeZone("UTC")
    private var clock = 1_758_000_000_000L
    private val api = FakeApi()

    private val stateStore = RoomMirrorStateStore(
        dao = dao,
        // 单线程测试：直接跑。生产实现是 db.withTransaction（见 RoomMirrorStateStore.of）
        inTransaction = { body -> body() },
        timeZone = { utc },
        now = { clock },
        audit = { audits += it },
    )

    private lateinit var committer: MirrorCommitter
    private lateinit var engine: OrderFileSyncEngine

    private val storeKey = "00002"
    private val fileId = "B:alice:2026-09"
    private val scope = StatsProjection.fileScope(fileId)

    private val uuid1 = "11111111-2222-3333-4444-555555555555"
    private val uuid2 = "22222222-3333-4444-5555-666666666666"

    /** 2026-09-15T00:00:00Z。 */
    private val sept15 = 1789430400000L
    private val dimKey = "2026-09-15|w:alice"

    @Before
    fun setUp() {
        mirrorRoot = tmp.newFolder("mirror")
        archiveRoot = tmp.newFolder("mirror-archive")
        quarantineRoot = tmp.newFolder("mirror-quarantine")
        committer = MirrorCommitter(
            locator = MirrorLocator(mirrorRoot, archiveRoot),
            quarantineRoot = quarantineRoot,
            locks = StripedMutex(),
            stateStore = stateStore,
            wallClock = { clock },
            audit = { audits += it },
        )
        engine = OrderFileSyncEngine(api, committer, stateStore, dao, { clock }, { audits += it })
    }

    // ==================== 构造测试数据 ====================

    private class Ser(val payload: ByteArray, val json: String, val uuidIdx: Int, val statusIdx: Int)

    private fun draft(uuid: String) = OrderDraft(
        orderUuid = uuid,
        writer = "alice",
        items = listOf(OrderLine(barcode = "6901234567890", name = "可口可乐330ml", quantity = 14, amount = "52.00")),
        total = "52.00",
        ts = sept15,
    )

    /**
     * 序列化一张订单，并**独立地**算出 uuid 值与 status 数字的字节下标。
     *
     * 不用 `SerializedOrder` 自己报的那两个下标，也不用 `OrderJsonScanner`：
     * 生产路径上偏移是**服务端**给的，测试必须自己算一遍才有交叉校验的意义。
     * 中文名（6 个汉字，每个多占 2 字节）正是为了让字符下标与字节下标分岔。
     */
    private fun ser(uuid: String): Ser {
        val payload = StableOrderJson.serialize(draft(uuid)).payload
        val json = String(payload, Charsets.UTF_8)
        return Ser(
            payload = payload,
            json = json,
            uuidIdx = ByteOps.utf8Len(json.substring(0, json.indexOf(uuid))),
            statusIdx = ByteOps.utf8Len(json.substring(0, json.indexOf("\"status\":") + "\"status\":".length)),
        )
    }

    /** 服务端磁盘上那个文件应有的字节（4 字节大端头 + 每行一个订单 + LF）。 */
    private fun serverBytes(version: Int, vararg uuids: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteOps.encodeHeader(version))
        for (u in uuids) {
            out.write(ser(u).payload)
            out.write(ByteOps.LF.toInt())
        }
        return out.toByteArray()
    }

    /** 用一次 FULL 把本地种成"v[version]、游标 [cursor]、若干订单"。 */
    private suspend fun seed(version: Int, cursor: String?, vararg uuids: String) {
        committer.commitFull(
            storeKey, fileId,
            ByteArrayInputStream(serverBytes(version, *uuids)),
            version, cursor,
        )
    }

    private fun addMsg(
        id: String,
        uuid: String,
        version: Int,
        lineStart: Long,
        msgFileId: String = fileId,
    ): OrderStreamMessageDto {
        val m = ser(uuid)
        return OrderStreamMessageDto(id, mapOf(
            OrderStreamFields.FILE_ID to msgFileId,
            OrderStreamFields.VERSION to version.toString(),
            OrderStreamFields.TYPE to OrderStreamFields.TYPE_ADD,
            OrderStreamFields.ORDER_UUID to uuid,
            OrderStreamFields.ORDER_JSON to m.json,
            OrderStreamFields.UUID_OFFSET to (lineStart + m.uuidIdx).toString(),
            OrderStreamFields.STATUS_OFFSET to (lineStart + m.statusIdx).toString(),
            OrderStreamFields.OPERATOR to "alice",
            OrderStreamFields.TIMESTAMP to sept15.toString(),
        ))
    }

    private fun patchMsg(
        id: String,
        uuid: String?,
        version: Int,
        offset: Long,
        content: String,
        declaredLen: Int? = ByteOps.utf8Len(content),
        type: String = OrderStreamFields.TYPE_CANCEL,
    ): OrderStreamMessageDto {
        // LinkedHashMap 而不是 mapOf：uuid 为 null 时那个键必须**缺席**，
        // 不能是 `order_uuid -> null`（服务端不会发这种形状）。
        val f = LinkedHashMap<String, String>()
        f[OrderStreamFields.FILE_ID] = fileId
        f[OrderStreamFields.VERSION] = version.toString()
        f[OrderStreamFields.TYPE] = type
        if (uuid != null) f[OrderStreamFields.ORDER_UUID] = uuid
        f[OrderStreamFields.UUID_OFFSET] = offset.toString()
        f[OrderStreamFields.PATCH_OFFSET] = offset.toString()
        if (declaredLen != null) f[OrderStreamFields.PATCH_LENGTH] = declaredLen.toString()
        f[OrderStreamFields.PATCH_CONTENT] = content
        f[OrderStreamFields.OPERATOR] = "alice"
        f[OrderStreamFields.TIMESTAMP] = sept15.toString()
        return OrderStreamMessageDto(id, f)
    }

    private fun locator() = MirrorLocator(mirrorRoot, archiveRoot)
    private fun liveMirror() = locator().resolve(storeKey, fileId, SyncState.LIVE)
    private fun archiveMirror() = locator().resolve(storeKey, fileId, SyncState.ARCHIVED)
    private fun state() = runBlocking { stateStore.read(storeKey, fileId)!! }

    // ==================== HTTP 替身 ====================

    /**
     * 按脚本逐步应答的 [OrderFileApi]。
     *
     * ⚠ 脚本用完就**抛 AssertionError**，不返回一个默认值：
     * 状态机会在"链中断 → 强制 FULL"这类路径上多发一次请求，
     * 若替身默默返回 LATEST，测试会绿着通过而断言的东西早已不是原本那个场景。
     */
    private class FakeApi : OrderFileApi {

        sealed class Step {
            data class Latest(val version: Int, val streamId: String) : Step()
            data class Incremental(
                val version: Int, val streamId: String, val messages: List<OrderStreamMessageDto>,
            ) : Step()

            data class Full(val version: Int, val streamId: String, val body: ByteArray) : Step()
        }

        val requests = mutableListOf<OrderSyncRequestDto>()
        val probes = mutableListOf<Triple<String, Int, Int?>>()
        /** FULL 响应体。留着是为了断言"409 探测时一个字节都没被读走"。 */
        val fullBodies = mutableListOf<Buffer>()
        private val script = ArrayDeque<Step>()
        var throwOnNext: Throwable? = null

        fun latest(version: Int, streamId: String) { script += Step.Latest(version, streamId) }

        fun incremental(version: Int, streamId: String, vararg m: OrderStreamMessageDto) {
            script += Step.Incremental(version, streamId, m.toList())
        }

        fun full(version: Int, streamId: String, body: ByteArray) { script += Step.Full(version, streamId, body) }

        override suspend fun syncOrderFile(
            request: OrderSyncRequestDto,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ): Service1Api.SyncOutcome {
            requests += request
            return serve(onFull)
        }

        override suspend fun probeAfter409(
            type: String,
            year: Int,
            month: Int?,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ): Service1Api.SyncOutcome {
            probes += Triple(type, year, month)
            return serve(onFull)
        }

        private suspend fun serve(
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ): Service1Api.SyncOutcome {
            throwOnNext?.let {
                throwOnNext = null
                throw it
            }
            val step = script.removeFirstOrNull()
                ?: throw AssertionError("脚本用完了：测试少排了一步（已发 ${requests.size} 次请求、${probes.size} 次探测）")
            return when (step) {
                is Step.Latest -> Service1Api.SyncOutcome.Latest(step.version, step.streamId)
                is Step.Incremental ->
                    Service1Api.SyncOutcome.Incremental(step.version, step.streamId, step.messages)

                is Step.Full -> {
                    val buf = Buffer().write(step.body)
                    fullBodies += buf
                    onFull(Service1Api.FullFileBody(step.version, step.streamId, step.body.size.toLong(), buf))
                    Service1Api.SyncOutcome.Full(step.version, step.streamId)
                }
            }
        }
    }

    // ==================== 请求形状 ====================

    @Test
    fun `请求带上本地游标_服务端据此决定给增量还是全量`() = runBlocking {
        seed(5, "cur-5", uuid1)
        api.latest(5, "cur-5")

        engine.sync(storeKey, fileId)

        val req = api.requests.single()
        assertEquals("B", req.type)
        assertEquals(2026, req.year)
        assertEquals(9, req.month)
        assertEquals(5, req.version)
        assertEquals("cur-5", req.lastStreamId)
    }

    @Test
    fun `A_类请求的_month_是_null_不是_0`() = runBlocking {
        // 传 0 会撞上服务端 `month < 1 || month > 12` 的校验 → 400，
        // 于是"同步某一年的进货单"永远失败，而错误信息完全指不到这里。
        api.latest(0, "")

        engine.sync(storeKey, "A:alice:2026")

        val req = api.requests.single()
        assertEquals("A", req.type)
        assertEquals(2026, req.year)
        assertNull(req.month)
    }

    @Test
    fun `从未同步过时_version_发_0_游标发_null`() = runBlocking {
        api.latest(0, "")
        engine.sync(storeKey, fileId)
        val req = api.requests.single()
        assertEquals(0, req.version)
        assertNull(req.lastStreamId)
    }

    @Test
    fun `非法_fileId_直接失败_一个请求都不发`() = runBlocking {
        // C 类是服务端内部账本，客户端同步不到（见 OrderFileId 的类注释）
        val r = engine.sync(storeKey, "C:2026-09")
        assertTrue(r is Outcome.Failed)
        assertFalse((r as Outcome.Failed).retriable)
        assertTrue(api.requests.isEmpty())
    }

    // ==================== LATEST ====================

    @Test
    fun `LATEST_只覆盖保存_streamId_不动镜像`() = runBlocking {
        seed(5, "old-tail", uuid1)
        val before = liveMirror().file.readBytes()
        api.latest(5, "new-tail")

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.UpToDate(5, "new-tail"), r)
        assertArrayEquals(before, liveMirror().file.readBytes())
        assertEquals("new-tail", state().lastStreamId)
        assertEquals(5, state().version)
        assertEquals("不许因为一次 LATEST 就重发请求", 1, api.requests.size)
    }

    @Test
    fun `LATEST_的空串游标不会盖掉已有游标`() = runBlocking {
        // 服务端在流为空时返回 tailId = ""（order_sync.lua:47）。照抄会把游标清空
        // → 下次 lastStreamId 为空 → 无条件 FULL。一个 26MB 的文件被反复全量下载，
        // 现象只是"同步很慢"，没有任何报错。
        seed(5, "keep-me", uuid1)
        api.latest(5, "")

        engine.sync(storeKey, fileId)

        assertEquals("keep-me", state().lastStreamId)
        assertEquals(5, state().version)
    }

    @Test
    fun `LATEST_在从未同步过的月份上落一个空镜像并写下_firstSyncedAt`() = runBlocking {
        // 只写游标行的话，三重自检会发现"file_state 有记录但镜像文件不存在"→ 判 DIVERGED，
        // 于是"服务端确实没有这个月的单"被记成"本地镜像坏了"，然后无休止地排 FULL。
        api.latest(0, "")

        val r = engine.sync(storeKey, fileId)

        assertTrue(r is Outcome.UpToDate)
        val row = dao.fileStates[storeKey to fileId]!!
        // 三态里的第二态：firstSyncedAt 非空 + version=0 = "同步过了，但那个月确实没单"
        assertEquals(clock, row.firstSyncedAt)
        assertEquals(0, row.version)
        assertEquals("LIVE", row.syncState)
        assertEquals(ByteOps.HEADER_LEN.toLong(), liveMirror().length())
        assertEquals(0, liveMirror().readHeaderVersion())
        assertTrue(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
    }

    @Test
    fun `LATEST_能把_DIVERGED_救回_LIVE`() = runBlocking {
        seed(5, "c", uuid1)
        liveMirror().writeHeaderVersion(77)      // 绕过 committer 改坏文件
        assertFalse(committer.selfCheck(storeKey, fileId, verifyChecksum = false))
        assertEquals(SyncState.DIVERGED, state().syncState)
        assertEquals("分歧后游标归零", 0, state().version)

        api.latest(0, "")
        engine.sync(storeKey, fileId)

        assertEquals(SyncState.LIVE, state().syncState)
        assertEquals(ByteOps.HEADER_LEN.toLong(), liveMirror().length())
        assertTrue(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
    }

    // ==================== INCREMENTAL ====================

    @Test
    fun `INCREMENTAL_按顺序应用两条_ADD_镜像与索引都对得上`() = runBlocking {
        val m1 = ser(uuid1)
        val m2 = ser(uuid2)
        val line1 = ByteOps.HEADER_LEN.toLong()
        val line2 = line1 + m1.payload.size + 1L
        api.incremental(2, "tail-9",
            addMsg("1-0", uuid1, version = 1, lineStart = line1),
            addMsg("2-0", uuid2, version = 2, lineStart = line2))

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Applied(applied = 2, echoes = 0, version = 2, streamId = "tail-9"), r)
        // ⚠ 这条是全类的核心断言：镜像必须与服务端将落盘的字节**逐字节**相同（含 4 字节头）
        assertArrayEquals(serverBytes(2, uuid1, uuid2), liveMirror().file.readBytes())
        // 索引行的偏移就是服务端给的那两个，不是本地猜的
        val row = dao.order(storeKey, fileId, uuid2)!!
        assertEquals(line2 + m2.uuidIdx, row.uuidOffset)
        assertEquals(line2 + m2.statusIdx, row.statusOffset)
        assertEquals(line2, row.lineStart)
        assertEquals("游标一律存队尾 id", "tail-9", state().lastStreamId)
        assertEquals(10400L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))
        assertEquals(28L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_PIECES))
        assertTrue(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
    }

    @Test
    fun `重复投递被记成回声_镜像与统计都只算一次`() = runBlocking {
        // SSE 回声与 HTTP 响应会把同一条消息投递两次；队尾游标也可能比已应用的最后一条旧。
        api.incremental(1, "t1", addMsg("1-0", uuid1, version = 1, lineStart = 4L))
        engine.sync(storeKey, fileId)
        val after1 = liveMirror().file.readBytes()

        api.incremental(1, "t1", addMsg("1-0", uuid1, version = 1, lineStart = 4L))
        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Applied(applied = 0, echoes = 1, version = 1, streamId = "t1"), r)
        assertArrayEquals("重复的 ADD 绝不能追加第二行", after1, liveMirror().file.readBytes())
        assertEquals(5200L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))
        assertEquals(1, dao.countOrderIndexesOfFile(storeKey, fileId))
    }

    @Test
    fun `链中一条无法应用时立刻停手并改走_FULL`() = runBlocking {
        // ⚠ 必须 break 而不是 continue：跳过一条 ADD 会让下一条的 lineStart != 文件长度
        // → guardAdd → DIVERGED → 整个月的镜像被隔离。
        api.incremental(3, "tail",
            addMsg("1-0", uuid1, version = 1, lineStart = 4L),
            OrderStreamMessageDto("2-0", mapOf(
                OrderStreamFields.FILE_ID to fileId,
                OrderStreamFields.VERSION to "2",
                OrderStreamFields.TYPE to "BRAND_NEW_TYPE",
            )))
        api.full(3, "tail2", serverBytes(3, uuid1, uuid2))

        val r = engine.sync(storeKey, fileId)

        assertTrue("应当降级成 FULL 重建", r is Outcome.Rebuilt)
        assertEquals(2, api.requests.size)
        // 强制 FULL 的请求必须是两个字面量
        assertEquals(0, api.requests[1].version)
        assertEquals("", api.requests[1].lastStreamId)
        assertArrayEquals(serverBytes(3, uuid1, uuid2), liveMirror().file.readBytes())
        assertTrue(audits.any { it.contains("增量链在第 2/2 条中断") })
    }

    @Test
    fun `属于别的文件的消息被拒绝`() = runBlocking {
        // 同一条 stream 里混着多个月份，Lua 已经按 file_id 过滤（:85）。
        // 万一过滤失效，把别的月份的订单追加进这个镜像就是静默的字节级破坏。
        api.incremental(1, "t", addMsg("1-0", uuid1, version = 1, lineStart = 4L, msgFileId = "B:alice:2026-08"))
        api.full(1, "t2", serverBytes(1))

        engine.sync(storeKey, fileId)

        assertEquals(2, api.requests.size)
        assertEquals(0, dao.countOrderIndexesOfFile(storeKey, fileId))
        assertTrue(audits.any { it.contains("属于另一个文件") })
    }

    @Test
    fun `链应用完但版本没追上时走_FULL`() = runBlocking {
        // 不校验就返回成功的话，游标已推到队尾，下次只会拿到 LATEST ——
        // 差的那几条**永远不会再被投递**，统计静默少一笔。
        api.incremental(9, "tail", addMsg("1-0", uuid1, version = 1, lineStart = 4L))
        api.full(9, "t2", serverBytes(9, uuid1))

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Rebuilt(9, "t2"), r)
        assertTrue(audits.any { it.contains("版本没追上") })
    }

    // ==================== 补丁 ====================

    @Test
    fun `取消补丁回退统计与状态`() = runBlocking {
        seed(1, "c", uuid1)
        val st = dao.order(storeKey, fileId, uuid1)!!
        api.incremental(2, "t2", patchMsg("2-0", uuid1, version = 2, offset = st.statusOffset, content = "0"))

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Applied(1, 0, 2, "t2"), r)
        assertEquals("镜像里那个字节必须真的变成 '0'", '0'.code.toByte(), liveMirror().readAt(st.statusOffset, 1)[0])
        assertEquals(0, dao.order(storeKey, fileId, uuid1)!!.status)
        assertEquals(2, dao.order(storeKey, fileId, uuid1)!!.cancelAppliedVersion)
        assertEquals("取消必须回退统计", 0L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))
        assertTrue(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
    }

    @Test
    fun `补丁不带_uuid_时按偏移反查索引行`() = runBlocking {
        // FULL 重建会跳过解析失败的行，那一单的 uuid 不在 order_index 里，
        // 但它确实躺在镜像里、确实会被补丁打到。
        seed(1, "c", uuid1)
        val st = dao.order(storeKey, fileId, uuid1)!!
        api.incremental(2, "t2", patchMsg("2-0", uuid = null, version = 2, offset = st.statusOffset, content = "0"))

        engine.sync(storeKey, fileId)

        assertEquals(0, dao.order(storeKey, fileId, uuid1)!!.status)
        assertEquals(0L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `总部标完成也是_1_字节补丁_但不许回退统计`() = runBlocking {
        // COMPLETE / DELETE_COMPLETED 与取消**同形**（1 字节打在 statusOffset 上），
        // 判据一律是偏移与长度 + 旧字节是不是 '1'、新字节是不是 '0'。
        seed(1, "c", uuid1)
        val st = dao.order(storeKey, fileId, uuid1)!!
        api.incremental(2, "t2", patchMsg("2-0", uuid1, version = 2, offset = st.statusOffset,
            content = "2", type = OrderStreamFields.TYPE_COMPLETE))

        engine.sync(storeKey, fileId)

        assertEquals(2, dao.order(storeKey, fileId, uuid1)!!.status)
        assertNull("1→2 不是取消", dao.order(storeKey, fileId, uuid1)!!.cancelAppliedVersion)
        assertEquals("A 类订单被总部标完成时营收必须原样保留",
            5200L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))
    }

    @Test
    fun `UNKNOWN_补丁_镜像照写但触发_FULL_重建索引`() = runBlocking {
        seed(1, "c", uuid1)
        // 既不是 statusOffset 也不是 remarkOffset：可能是别的设备用了我们不知道的偏移，
        // 或者 order_index 里的偏移过期了。**不猜。**
        val bogus = ByteOps.HEADER_LEN.toLong() + ser(uuid1).uuidIdx + 3L
        api.incremental(2, "t2", patchMsg("2-0", uuid1, version = 2, offset = bogus, content = "0"))
        api.full(2, "t3", serverBytes(2, uuid1))

        val r = engine.sync(storeKey, fileId)

        assertTrue(r is Outcome.Rebuilt)
        assertTrue(audits.any { it.contains("UNKNOWN 补丁") })
        // FULL 之后镜像与服务端一致，索引也是重建的
        assertArrayEquals(serverBytes(2, uuid1), liveMirror().file.readBytes())
    }

    @Test
    fun `patch_length_与内容字节数矛盾时拒绝_而不是照其中一个分类`() = runBlocking {
        seed(1, "c", uuid1)
        val st = dao.order(storeKey, fileId, uuid1)!!
        // 声明 150（备注宽度）但内容只有 1 字节：照声明分类会判成 REMARK，
        // 照内容分类会判成 STATUS —— 两个结论相反，所以只能拒绝 + FULL。
        api.incremental(2, "t2",
            patchMsg("2-0", uuid1, version = 2, offset = st.statusOffset, content = "0", declaredLen = 150))
        api.full(2, "t3", serverBytes(2, uuid1))

        engine.sync(storeKey, fileId)

        assertEquals(2, api.requests.size)
        assertTrue(audits.any { it.contains("长度自相矛盾") })
        assertArrayEquals(serverBytes(2, uuid1), liveMirror().file.readBytes())
    }

    // ==================== FULL ====================

    @Test
    fun `FULL_整体替换镜像与索引_统计不叠加`() = runBlocking {
        seed(3, "old", uuid1)
        api.full(5, "t5", serverBytes(5, uuid2))

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Rebuilt(5, "t5"), r)
        assertArrayEquals(serverBytes(5, uuid2), liveMirror().file.readBytes())
        assertNull("旧订单的索引行必须被换掉", dao.order(storeKey, fileId, uuid1))
        assertNotNull(dao.order(storeKey, fileId, uuid2))
        // 两笔订单同一天同一个 writer，所以 dimKey 相同：没清旧贡献的话这里会是 10400
        assertEquals(5200L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))
        assertEquals("FULL 的游标只能从 x-stream-id 响应头拿", "t5", state().lastStreamId)
        assertTrue(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
    }

    @Test
    fun `强制_FULL_时服务端说文件是空的_也要落空镜像并清索引`() = runBlocking {
        seed(3, "old", uuid1)
        api.incremental(4, "t",
            OrderStreamMessageDto("9-0", mapOf(
                OrderStreamFields.FILE_ID to fileId,
                OrderStreamFields.VERSION to "4",
                OrderStreamFields.TYPE to "BRAND_NEW_TYPE",
            )))
        // 强制 FULL 拿到 LATEST ⇒ latest 也是 0 ⇒ 服务端这个文件是空的
        api.latest(0, "")

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Rebuilt(0, null), r)
        assertEquals(ByteOps.HEADER_LEN.toLong(), liveMirror().length())
        assertEquals(0, dao.countOrderIndexesOfFile(storeKey, fileId))
        assertEquals(0L, dao.contribTotal(storeKey, scope, StatsProjection.METRIC_AMOUNT))
    }

    // ==================== 409 ====================

    @Test
    fun `409_探测得_LATEST_归档_镜像搬走_游标与贡献冻结`() = runBlocking {
        seed(7, "keep-6", uuid1)
        assertEquals(5200L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))

        api.throwOnNext = ApiError.Conflict("客户端版本领先于服务器", isOrderFileServerBehind = true)
        api.latest(0, "")

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Archived(fileId, 7), r)
        assertEquals("探测必须走 version=0 那一路", listOf(Triple("B", 2026, 9)), api.probes)
        val st = state()
        assertEquals(SyncState.ARCHIVED, st.syncState)
        assertEquals("游标原样冻结不归零", 7, st.version)
        assertEquals("keep-6", st.lastStreamId)
        // 镜像搬进归档区，活动区清空（留着的话下次 FULL 会覆盖那份"本该冻结"的证据）
        assertFalse(liveMirror().file.exists())
        assertArrayEquals(serverBytes(7, uuid1), archiveMirror().file.readBytes())
        // 归档只是"不再同步"，**不是"不再统计"**
        assertEquals(5200L, dao.contribValue(storeKey, scope, dimKey, StatsProjection.METRIC_AMOUNT))
        assertTrue(dao.contrib.values.all { it.frozen == 1 })
        assertEquals("索引行保留，历史页还要看", 1, dao.countOrderIndexesOfFile(storeKey, fileId))
    }

    @Test
    fun `归档之后再同步_一个请求都不发`() = runBlocking {
        seed(7, "c", uuid1)
        committer.archive(storeKey, fileId, "test")

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.Archived(fileId, 7), r)
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `409_探测得_FULL_报_ServerBehind_并且一个字节都不落地`() = runBlocking {
        seed(87, "c", uuid1)
        val before = liveMirror().file.readBytes()

        api.throwOnNext = ApiError.Conflict("落后", isOrderFileServerBehind = true)
        api.full(61, "srv", ByteOps.encodeHeader(61))

        val r = engine.sync(storeKey, fileId)

        assertEquals(Outcome.ServerBehind(87, 61), r)
        // ⚠ 最关键的一条：服务器那份**较短**的文件绝不能覆盖本地唯一的完整副本
        assertArrayEquals(before, liveMirror().file.readBytes())
        assertEquals("响应体一个字节都不该被读走", 4L, api.fullBodies.single().size)
        assertEquals(SyncState.LIVE, state().syncState)
        assertEquals(87, state().version)
        assertEquals(1, dao.countOrderIndexesOfFile(storeKey, fileId))
    }

    @Test
    fun `acceptServerVersion_强制_FULL_并记下丢弃了本地尾部`() = runBlocking {
        seed(87, "c", uuid1)
        api.full(61, "srv", serverBytes(61))

        val r = engine.acceptServerVersion(storeKey, fileId)

        assertEquals(Outcome.Rebuilt(61, "srv"), r)
        val req = api.requests.single()
        assertEquals(0, req.version)
        assertEquals("", req.lastStreamId)
        assertEquals(ByteOps.HEADER_LEN.toLong(), liveMirror().length())
        assertEquals(0, dao.countOrderIndexesOfFile(storeKey, fileId))
        assertTrue(audits.any { it.contains("接受服务器版本") })
    }

    // ==================== 错误分类 ====================

    @Test
    fun `429_报_RateLimited_且绝不自动重试`() = runBlocking {
        // 桶是按 IP 的（整店共享），重试只会把窗口顶满，让同店其它设备一起被锁死。
        api.throwOnNext = ApiError.RateLimited("请求过于频繁")

        val r = engine.sync(storeKey, fileId)

        assertTrue(r is Outcome.RateLimited)
        assertEquals(1, api.requests.size)
    }

    @Test
    fun `401_报_NeedsRelogin_并带上能否自动重登`() = runBlocking {
        api.throwOnNext = ApiError.Unauthorized(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, null)
        val r1 = engine.sync(storeKey, fileId) as Outcome.NeedsRelogin
        assertEquals(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, r1.reason)
        assertTrue(r1.mayAutoRelogin)

        api.throwOnNext = ApiError.Unauthorized(UnauthorizedReason.SESSION_TAKEN_OVER, "该账户已在别处登录，请重新登录")
        val r2 = engine.sync(storeKey, fileId) as Outcome.NeedsRelogin
        assertFalse("被顶号不能自动重登，否则会形成每半小时一次的互踢循环", r2.mayAutoRelogin)
    }

    @Test
    fun `断网报可重试的_Failed`() = runBlocking {
        api.throwOnNext = ApiError.Network(IOException("timeout"))
        val r = engine.sync(storeKey, fileId) as Outcome.Failed
        assertTrue(r.retriable)
    }

    @Test
    fun `500_把追踪号与时间一起记进审计`() = runBlocking {
        // 500 的响应体只有追踪号，没有异常消息。丢了追踪号就没法报障。
        clock = 1_758_000_123_456L
        api.throwOnNext = ApiError.ServerError("trace-abc", "内部错误")

        val r = engine.sync(storeKey, fileId) as Outcome.Failed

        assertTrue(r.retriable)
        assertTrue(r.msg.contains("trace-abc"))
        assertTrue(audits.any { it.contains("trace-abc") && it.contains("1758000123456") })
    }

    @Test
    fun `400_不可重试_并留下审计`() = runBlocking {
        // 最常见的原因是 A 类把 month=0 发出去了（见 OrderFileId.Parts.monthOrNull）
        api.throwOnNext = ApiError.BadRequest("参数有误")
        val r = engine.sync(storeKey, fileId) as Outcome.Failed
        assertFalse(r.retriable)
        assertTrue(audits.any { it.contains("BadRequest") })
    }

    @Test
    fun `不是_SERVER_BEHIND_的_409_不走归档探测`() = runBlocking {
        api.throwOnNext = ApiError.Conflict("用户名冲突", isOrderFileServerBehind = false)

        val r = engine.sync(storeKey, fileId)

        assertTrue(r is Outcome.Failed)
        assertFalse((r as Outcome.Failed).retriable)
        assertTrue("只有 SERVER_BEHIND 才该探测", api.probes.isEmpty())
    }
}
