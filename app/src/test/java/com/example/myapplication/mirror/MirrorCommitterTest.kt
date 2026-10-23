package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.lock.StripedMutex
import com.example.myapplication.sync.outbox.OrderDraft
import com.example.myapplication.sync.outbox.OrderLine
import com.example.myapplication.sync.outbox.StableOrderJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
import java.io.ByteArrayInputStream
import java.io.File

/**
 * [MirrorCommitter] 的测试。这是"顺序 / 互斥 / 幂等"三条不变量的唯一执行点，
 * 所以这里的断言比别处都死。
 */
class MirrorCommitterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var mirrorRoot: File
    private lateinit var archiveRoot: File
    private lateinit var quarantineRoot: File
    private lateinit var store: FakeStore
    private lateinit var committer: MirrorCommitter
    private val audits = mutableListOf<String>()

    private val storeKey = "00002"
    private val fileId = "B:alice:2026-09"

    /** 固定时钟，让 firstSyncedAt 可断言。 */
    private var now = 1_758_000_000_000L

    @Before
    fun setUp() {
        mirrorRoot = tmp.newFolder("mirror")
        archiveRoot = tmp.newFolder("mirror-archive")
        quarantineRoot = tmp.newFolder("mirror-quarantine")
        store = FakeStore()
        committer = MirrorCommitter(
            locator = MirrorLocator(mirrorRoot, archiveRoot),
            quarantineRoot = quarantineRoot,
            locks = StripedMutex(),
            stateStore = store,
            wallClock = { now },
            audit = { audits.add(it) },
        )
    }

    // ==================== 测试替身 ====================

    /**
     * 内存版 [MirrorStateStore]。
     *
     * `onCommit` 钩子用来在"Room 提交那一刻"回看镜像文件的真实状态，
     * 以此证明**文件先写、游标后提交**这个顺序（[顺序_提交游标时文件内容与版本头都已落盘]）。
     */
    private class FakeStore : MirrorStateStore {
        val states = mutableMapOf<String, MirrorState>()
        val commits = mutableListOf<MirrorCommitRecord>()
        val fullCommits = mutableListOf<Triple<String, String, MirrorState>>()
        val diverged = mutableListOf<Pair<String, String>>()
        val archived = mutableListOf<Pair<String, String>>()
        var failNextCommits = 0
        var onCommit: ((MirrorCommitRecord) -> Unit)? = null

        private fun k(store: String, fileId: String) = "$store|$fileId"

        override suspend fun read(storeKey: String, fileId: String): MirrorState? = states[k(storeKey, fileId)]

        override suspend fun commit(record: MirrorCommitRecord) {
            if (failNextCommits > 0) {
                failNextCommits--
                throw IllegalStateException("模拟：文件已落盘但 Room 事务前进程被杀")
            }
            onCommit?.invoke(record)
            states[k(record.storeKey, record.fileId)] = record.state
            commits.add(record)
        }

        override suspend fun commitFull(storeKey: String, fileId: String, state: MirrorState, mirror: Mirror) {
            states[k(storeKey, fileId)] = state
            fullCommits.add(Triple(storeKey, fileId, state))
        }

        override suspend fun commitDiverged(storeKey: String, fileId: String, reason: String) {
            // 与真实实现一致：标 DIVERGED + 归零游标（取证靠隔离文件，不靠数据库留旧值）
            states[k(storeKey, fileId)] = MirrorState(0, null, 0, 4L, states[k(storeKey, fileId)]?.firstSyncedAt, SyncState.DIVERGED)
            diverged.add(storeKey to reason)
        }

        override suspend fun commitArchived(storeKey: String, fileId: String, reason: String) {
            // 与真实实现一致：**只改 syncState**，游标/校验和/长度/firstSyncedAt 全部冻结。
            // 归零是 commitDiverged 才做的事，两者混了就会在运维恢复文件时踩坑。
            val cur = states[k(storeKey, fileId)]
                ?: MirrorState(0, null, 0, 0L, null, SyncState.ARCHIVED)
            states[k(storeKey, fileId)] = cur.copy(syncState = SyncState.ARCHIVED)
            archived.add(storeKey to reason)
        }

        fun state(fileId: String) = states[k("00002", fileId)]
    }

    private fun draft(uuid: String, name: String = "可口可乐330ml", qty: Int = 14) = OrderDraft(
        orderUuid = uuid,
        writer = "alice",
        items = listOf(
            OrderLine(barcode = "6901234567890", name = name, quantity = qty, amount = "52.00", "1.00")
        ),
        total = "52.00",
        ts = 1_758_000_000_000L,
    )

    private val uuid1 = "11111111-2222-3333-4444-555555555555"
    private val uuid2 = "22222222-3333-4444-5555-666666666666"

    private fun activeMirror(syncState: SyncState = SyncState.LIVE) =
        MirrorLocator(mirrorRoot, archiveRoot).resolve(storeKey, fileId, syncState)

    /**
     * 模拟"服务端返回的偏移"发一条 ADD。
     *
     * @param lineStart 这一行**应当**落在的绝对偏移。默认取当前 EOF（首次追加即 4）。
     *                  重复投递同一消息时必须显式传第一次的值——服务端两次返回的偏移是一样的，
     *                  这正是幂等守卫能识别"已应用"的前提。
     */
    private suspend fun add(
        uuid: String,
        version: Int,
        streamId: String? = "1758000000000-0",
        draft: OrderDraft = draft(uuid),
        lineStart: Long? = null,
    ): MirrorCommitter.Result {
        val s = StableOrderJson.serialize(draft)
        val m = activeMirror()
        m.ensureCreated()
        val start = lineStart ?: m.length()
        return committer.commitAdd(
            storeKey = storeKey,
            fileId = fileId,
            orderUuid = uuid,
            msgVersion = version,
            payloadBytes = s.payload,
            uuidValueByteIndex = s.uuidValueByteIndex,
            remarkValueByteIndex = s.remarkValueByteIndex,
            serverUuidOffset = start + s.uuidValueByteIndex,
            serverStatusOffset = start + s.statusValueByteIndex,
            newStreamId = streamId,
        )
    }

    // ==================== 首次追加 ====================

    @Test
    fun `首次追加_文件字节是_大端头_加_原文_加_单个_LF`() = runBlocking {
        val s = StableOrderJson.serialize(draft(uuid1))
        val r = add(uuid1, version = 1)

        assertTrue(r is MirrorCommitter.Result.Committed)
        assertTrue((r as MirrorCommitter.Result.Committed).applied)

        val expected = ByteOps.encodeHeader(1) + s.payload + byteArrayOf(0x0A)
        assertArrayEquals(expected, activeMirror().file.readBytes())
    }

    @Test
    fun `首次追加_状态被正确写入`() = runBlocking {
        val s = StableOrderJson.serialize(draft(uuid1))
        add(uuid1, version = 1, streamId = "1758000000123-0")

        val st = store.state(fileId)!!
        assertEquals(1, st.version)
        assertEquals("1758000000123-0", st.lastStreamId)
        assertEquals(4L + s.payload.size + 1, st.mirrorBytes)
        assertEquals(ByteOps.sumBytes(s.payload + ByteOps.LF), st.checksum)
        assertEquals(now, st.firstSyncedAt)
        assertEquals(SyncState.LIVE, st.syncState)
    }

    @Test
    fun `首次追加_校验和与磁盘实算一致`() = runBlocking {
        add(uuid1, version = 1)
        assertEquals(store.state(fileId)!!.checksum, activeMirror().checksumContent())
    }

    @Test
    fun `索引偏移与服务端语义一致_uuidOffset_处读回_36_字节正是_uuid`() = runBlocking {
        add(uuid1, version = 1)
        val rec = store.commits.single()
        val delta = rec.order as OrderDelta.Added
        val m = activeMirror()

        assertEquals(uuid1, String(m.readAt(delta.uuidOffset, 36), Charsets.UTF_8))
        assertEquals('1'.code.toByte(), m.readAt(delta.statusOffset, 1)[0])
        assertEquals(4L, delta.lineStart)
        assertEquals(m.readLine(delta.lineStart), String(delta.payload, Charsets.UTF_8))
    }

    @Test
    fun `中文商品名不会让_statusOffset_错位`() = runBlocking {
        // 这条专门验证多字节字符：汉字 3 字节、String.length 是 1，
        // 任何一处用错了都会让 statusOffset 偏移，之后所有补丁打到别的订单上。
        val longChinese = draft(uuid1, name = "云南白药气雾剂保险子装".repeat(4))
        val s = StableOrderJson.serialize(longChinese)
        add(uuid1, version = 1, draft = longChinese)

        val delta = store.commits.single().order as OrderDelta.Added
        val m = activeMirror()
        assertEquals(
            "statusOffset 处必须是状态数字 '1'，实际是 '${String(m.readAt(delta.statusOffset, 1), Charsets.UTF_8)}'",
            '1'.code.toByte(), m.readAt(delta.statusOffset, 1)[0]
        )
        assertEquals(
            "statusOffset 必须等于 行起点 + payload 内的下标",
            delta.lineStart + s.statusValueByteIndex, delta.statusOffset
        )
    }

    // ==================== 幂等：重复投递 ====================

    @Test
    fun `同一条_ADD_提交三次_文件只变一次但_Room_提交三次`() = runBlocking {
        val s = StableOrderJson.serialize(draft(uuid1))

        add(uuid1, version = 1)
        val lenAfterFirst = activeMirror().length()
        val checksumAfterFirst = store.state(fileId)!!.checksum

        // 第二次：手动构造与第一次相同的偏移（重复投递时服务端给的偏移是一样的）
        val r2 = committer.commitAdd(
            storeKey, fileId, uuid1, 1, s.payload, s.uuidValueByteIndex, s.remarkValueByteIndex,
            serverUuidOffset = 4L + s.uuidValueByteIndex,
            serverStatusOffset = 4L + s.statusValueByteIndex,
            newStreamId = "1758000000999-0",
        )
        val r3 = committer.commitAdd(
            storeKey, fileId, uuid1, 1, s.payload, s.uuidValueByteIndex, s.remarkValueByteIndex,
            serverUuidOffset = 4L + s.uuidValueByteIndex,
            serverStatusOffset = 4L + s.statusValueByteIndex,
            newStreamId = "1758000001000-0",
        )

        assertEquals("文件长度只应变一次", lenAfterFirst, activeMirror().length())
        assertEquals("校验和只应变一次", checksumAfterFirst, store.state(fileId)!!.checksum)
        assertEquals(1, store.state(fileId)!!.version)
        assertEquals(3, store.commits.size)

        assertTrue(store.commits[0].alreadyApplied.not())
        assertTrue("第二次必须标记为 alreadyApplied", store.commits[1].alreadyApplied)
        assertTrue("第三次必须标记为 alreadyApplied", store.commits[2].alreadyApplied)
        // 但游标仍要前进（响应/消息带了更新的 streamId）
        assertEquals("1758000001000-0", store.state(fileId)!!.lastStreamId)
        assertTrue(r2 is MirrorCommitter.Result.Committed && !(r2 as MirrorCommitter.Result.Committed).applied)
        assertTrue(r3 is MirrorCommitter.Result.Committed && !r3.applied)
    }

    @Test
    fun `崩溃重入_文件已写但_Room_未提交_重来时被识别为已应用并补齐_Room`() = runBlocking {
        store.failNextCommits = 1
        try {
            add(uuid1, version = 1)
            fail("应当抛出模拟异常")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("进程被杀"))
        }

        // 此刻：文件已经落盘（内容 + 版本头），但 Room 里没有记录
        assertEquals(1, activeMirror().readHeaderVersion())
        assertTrue(activeMirror().length() > 4)
        assertNull(store.state(fileId))
        assertEquals(0, store.commits.size)

        // 重启后同一条消息再来一次。**偏移必须与第一次相同** —— 服务端两次返回的偏移是一样的，
        // 这正是幂等守卫能识别"已应用"的前提。
        store.failNextCommits = 0
        val r = add(uuid1, version = 1, lineStart = 4L)
        assertTrue(r is MirrorCommitter.Result.Committed)
        assertFalse("必须识别为已应用，不能再写一次文件", (r as MirrorCommitter.Result.Committed).applied)

        assertEquals("Room 必须被补齐", 1, store.commits.size)
        assertTrue(store.commits.single().alreadyApplied)
        val st = store.state(fileId)!!
        assertEquals(1, st.version)
        assertEquals(activeMirror().length(), st.mirrorBytes)
        assertEquals(activeMirror().checksumContent(), st.checksum)
        assertNotNull("order_index 必须被补录，否则统计永久少一笔", st)
        assertTrue(store.commits.single().order is OrderDelta.Added)
    }

    @Test
    fun `崩溃恢复会以磁盘为准重建状态并留下审计痕迹`() = runBlocking {
        // reconcileStateFromDisk 的存在理由：文件已 fsync、Room 事务没跑就被杀，
        // 重启后 Room 里没有记录（回退成 fresh()，version=0）。
        // 直接拿它 copy 会把 version 写成 0，下次同步永远停在旧版本，且**不报错**。
        store.failNextCommits = 1
        try {
            add(uuid1, version = 1)
        } catch (_: IllegalStateException) {
        }
        assertNull(store.state(fileId))

        add(uuid1, version = 1, lineStart = 4L)

        assertTrue("崩溃恢复必须留下审计痕迹", audits.any { it.contains("状态对账") })
        val st = store.state(fileId)!!
        assertEquals("version 必须来自磁盘而不是 fresh()", 1, st.version)
        assertEquals(activeMirror().length(), st.mirrorBytes)
        assertEquals(activeMirror().checksumContent(), st.checksum)
    }

    @Test
    fun `正常双投递不触发磁盘重算`() = runBlocking {
        // HTTP 响应 + SSE 回声是常态。state 与磁盘一致时必须直接返回，
        // 否则每次双投递都要流式重算几十 MB 的校验和。
        add(uuid1, version = 1)
        val auditsBefore = audits.size

        val s = StableOrderJson.serialize(draft(uuid1))
        committer.commitAdd(
            storeKey, fileId, uuid1, 1, s.payload, s.uuidValueByteIndex, s.remarkValueByteIndex,
            4L + s.uuidValueByteIndex, 4L + s.statusValueByteIndex, "echo-0",
        )

        assertEquals("不该触发对账", auditsBefore, audits.size)
        assertEquals(1, store.state(fileId)!!.version)
        assertEquals("echo-0", store.state(fileId)!!.lastStreamId)
    }

    // ==================== 顺序：先文件、后游标 ====================

    @Test
    fun `顺序_提交游标时文件内容与版本头都已落盘`() = runBlocking {
        // 这是"游标提交必须在镜像写入之后"的直接证明。
        // 反过来的话进程在两步之间被杀就会"游标已前进而文件没改"，那段变更永久丢失且无法自愈。
        val s = StableOrderJson.serialize(draft(uuid1))
        var observedHeader = -1
        var observedLength = -1L
        var observedTail: ByteArray? = null

        store.onCommit = {
            val m = activeMirror()
            observedHeader = m.readHeaderVersion()
            observedLength = m.length()
            observedTail = m.readAt(m.length() - 1, 1)
        }

        add(uuid1, version = 1)

        assertEquals("提交游标时版本头必须已经写好", 1, observedHeader)
        assertEquals("提交游标时内容必须已经写完", 4L + s.payload.size + 1, observedLength)
        assertArrayEquals("提交游标时行尾 LF 必须已经落盘", byteArrayOf(0x0A), observedTail)
    }

    @Test
    fun `顺序_版本头写在内容之后_半写状态能被守卫抓住`() = runBlocking {
        // 手工制造"内容写了、版本头没写"的半写状态
        val s = StableOrderJson.serialize(draft(uuid1))
        val m = activeMirror()
        m.ensureCreated()
        m.appendBytes(s.payload + ByteOps.LF)     // 只写内容，故意不写版本头
        assertEquals(0, m.readHeaderVersion())

        val r = committer.commitAdd(
            storeKey, fileId, uuid1, 1, s.payload, s.uuidValueByteIndex, s.remarkValueByteIndex,
            serverUuidOffset = 4L + s.uuidValueByteIndex,
            serverStatusOffset = 4L + s.statusValueByteIndex,
            newStreamId = "x-0",
        )
        assertTrue("半写状态必须判 DIVERGED 而不是硬追加", r is MirrorCommitter.Result.Diverged)
        assertEquals(SyncState.DIVERGED, store.state(fileId)!!.syncState)
    }

    // ==================== 连续追加 ====================

    @Test
    fun `连续两条_第二条的行起点与偏移都正确`() = runBlocking {
        val s1 = StableOrderJson.serialize(draft(uuid1))
        add(uuid1, version = 1)
        add(uuid2, version = 2, draft = draft(uuid2, name = "百事可乐"))

        val m = activeMirror()
        val d1 = store.commits[0].order as OrderDelta.Added
        val d2 = store.commits[1].order as OrderDelta.Added

        assertEquals(4L, d1.lineStart)
        assertEquals(4L + s1.payload.size + 1, d2.lineStart)
        assertEquals(uuid1, String(m.readAt(d1.uuidOffset, 36), Charsets.UTF_8))
        assertEquals(uuid2, String(m.readAt(d2.uuidOffset, 36), Charsets.UTF_8))
        assertEquals('1'.code.toByte(), m.readAt(d2.statusOffset, 1)[0])
        assertEquals(2, store.state(fileId)!!.version)
    }

    // ==================== 补丁 ====================

    private suspend fun addThenGetStatusOffset(): Pair<OrderDelta.Added, Mirror> {
        add(uuid1, version = 1)
        return (store.commits.single().order as OrderDelta.Added) to activeMirror()
    }

    @Test
    fun `状态补丁_1_到_0_字节改了_长度不变_且带_cancelAppliedVersion`() = runBlocking {
        val (delta, m) = addThenGetStatusOffset()
        val lenBefore = m.length()

        val r = committer.commitPatch(
            storeKey = storeKey, fileId = fileId, msgVersion = 2,
            patchOffset = delta.statusOffset, patchContentBytes = ByteOps.utf8("0"),
            orderUuid = uuid1,
            knownStatusOffset = delta.statusOffset, knownRemarkOffset = delta.remarkOffset,
            newStreamId = "1758000000456-0",
        )

        assertTrue(r is MirrorCommitter.Result.Committed)
        assertEquals(lenBefore, m.length())
        assertEquals('0'.code.toByte(), m.readAt(delta.statusOffset, 1)[0])
        assertEquals(2, store.state(fileId)!!.version)

        val p = store.commits.last().order as OrderDelta.Patched
        assertEquals(PatchKind.STATUS, p.patchKind)
        assertEquals("1→0 必须带 cancelAppliedVersion，库存与统计才会回退", 2, p.cancelAppliedVersion)
        assertEquals("order_index.status 要靠它更新", 0, p.newStatusDigit)
    }

    @Test
    fun `状态补丁的校验和被增量更新且与磁盘实算一致`() = runBlocking {
        val (delta, m) = addThenGetStatusOffset()
        val sumBefore = store.state(fileId)!!.checksum
        committer.commitPatch(
            storeKey, fileId, 2, delta.statusOffset, ByteOps.utf8("0"), uuid1,
            delta.statusOffset, delta.remarkOffset, "s-0",
        )
        // '1'(0x31) → '0'(0x30)，校验和应当减 1
        assertEquals(sumBefore - 1, store.state(fileId)!!.checksum)
        assertEquals(store.state(fileId)!!.checksum, m.checksumContent())
    }

    @Test
    fun `非_1_到_0_的状态补丁_不带_cancelAppliedVersion`() = runBlocking {
        // 服务端 OrderFileService:383-391 只在 1→0 时回退库存。
        // 客户端条件更宽就会在服务端没回退的情况下回退，长期偏离。
        val (delta, m) = addThenGetStatusOffset()
        m.patchAt(delta.statusOffset, ByteOps.utf8("2"))       // 先手工把它变成 2
        // 手动把状态回写，让 file_state 的 checksum 保持同步（模拟这是已知状态）
        committer.commitPatch(
            storeKey, fileId, 2, delta.statusOffset, ByteOps.utf8("0"), uuid1,
            delta.statusOffset, delta.remarkOffset, "s-0",
        )
        val p = store.commits.last().order as OrderDelta.Patched
        assertEquals(PatchKind.STATUS, p.patchKind)
        assertNull("2→0 不是取消，不能回退库存", p.cancelAppliedVersion)
    }

    @Test
    fun `总部标完成_1_到_2_带新状态数字但不带_cancelAppliedVersion`() = runBlocking {
        // 服务端 `OrderFileService:498` 的总部处理路径：patchLength=1、打在 statusOffset、
        // 内容是 '2'（COMPLETE）或 '3'（DELETE_COMPLETED），流消息的 `type` **不是** CANCEL。
        // 它与取消**同形**，所以只能靠"旧字节是不是 '1' 且新字节是不是 '0'"决定回退与否。
        //
        // 少了 newStatusDigit 的话 order_index.status 会永远停在 1，
        // 历史页把总部已处理的进货单显示成待处理 —— 而且不会报错。
        val (delta, _) = addThenGetStatusOffset()
        committer.commitPatch(
            storeKey, fileId, 2, delta.statusOffset, ByteOps.utf8("2"), uuid1,
            delta.statusOffset, delta.remarkOffset, "s-0",
        )
        val p = store.commits.last().order as OrderDelta.Patched
        assertEquals(PatchKind.STATUS, p.patchKind)
        assertEquals(2, p.newStatusDigit)
        assertNull("1→2 不是取消，绝不能回退库存与统计", p.cancelAppliedVersion)
    }

    @Test
    fun `状态补丁的内容不是数字时_newStatusDigit_为_null_而不是写进垃圾值`() = runBlocking {
        // 服务端不会发这种补丁，但流消息是服务端给的、客户端不该假设它永远合法。
        // 写进一个 'A'-'0' = 17 之类的值在 UI 上看不出异常，只会让按状态筛选悄悄漏单。
        val (delta, _) = addThenGetStatusOffset()
        committer.commitPatch(
            storeKey, fileId, 2, delta.statusOffset, byteArrayOf(0x41), uuid1,   // 'A'
            delta.statusOffset, delta.remarkOffset, "s-0",
        )
        val p = store.commits.last().order as OrderDelta.Patched
        assertEquals(PatchKind.STATUS, p.patchKind)
        assertNull(p.newStatusDigit)
        assertNull(p.cancelAppliedVersion)
    }

    @Test
    fun `备注补丁_150_字节_被识别为_REMARK_且不回退库存`() = runBlocking {
        // A 类订单：remark 必须出生即 150 字节
        val aFileId = "A:alice:2026"
        val remark = ByteOps.padRemark("尽快发货，谢谢老板")
        val d = OrderDraft(
            orderUuid = uuid1, writer = "alice",
            items = listOf(
                OrderLine(
                    "6901234567890", "可口可乐330ml", 14, "52.00",
                    unitPrice = "3.50", productName = "可口可乐330ml",
                )
            ),
            total = "52.00", ts = now, remark = remark,
        )
        val s = StableOrderJson.serialize(d)
        committer.commitAdd(
            storeKey, aFileId, uuid1, 1, s.payload, s.uuidValueByteIndex, s.remarkValueByteIndex,
            serverUuidOffset = 4L + s.uuidValueByteIndex,
            serverStatusOffset = 4L + s.statusValueByteIndex,
            newStreamId = "a-0",
        )
        val delta = store.commits.single().order as OrderDelta.Added
        assertNotNull("A 类订单必须有 remarkOffset", delta.remarkOffset)

        // README §2.5：remarkOffset = uuidOffset + (r - u)
        assertEquals(
            delta.uuidOffset + (s.remarkValueByteIndex!! - s.uuidValueByteIndex),
            delta.remarkOffset
        )
        // 该处读回 150 字节正是备注原文
        val m = MirrorLocator(mirrorRoot, archiveRoot).resolve(storeKey, aFileId, SyncState.LIVE)
        assertEquals(remark, String(m.readAt(delta.remarkOffset!!, 150), Charsets.UTF_8))

        val newRemark = ByteOps.padRemark("改：明天下午三点前送到")
        val lenBefore = m.length()
        committer.commitPatch(
            storeKey, aFileId, 2, delta.remarkOffset!!, ByteOps.utf8(newRemark), uuid1,
            delta.statusOffset, delta.remarkOffset, "a-1",
        )
        val p = store.commits.last().order as OrderDelta.Patched
        assertEquals(PatchKind.REMARK, p.patchKind)
        assertNull("改备注绝不能被当成取消去回退库存与统计", p.cancelAppliedVersion)
        assertEquals(lenBefore, m.length())
        assertEquals(newRemark, String(m.readAt(delta.remarkOffset!!, 150), Charsets.UTF_8))
    }

    @Test
    fun `落在未知偏移的补丁_标记_UNKNOWN_并写审计日志_不猜`() = runBlocking {
        val (delta, _) = addThenGetStatusOffset()
        // 挑一个**在文件范围内**但既不是 statusOffset 也不是 remarkOffset 的位置。
        // （超出文件范围会被守卫判 DIVERGED，走不到分类那一步。）
        val odd = delta.uuidOffset + 2
        committer.commitPatch(
            storeKey, fileId, 2, odd, ByteOps.utf8("x"), uuid1,
            delta.statusOffset, delta.remarkOffset, "s-0",
        )
        val p = store.commits.last().order as OrderDelta.Patched
        assertEquals(PatchKind.UNKNOWN, p.patchKind)
        assertNull("UNKNOWN 绝不能回退库存与统计", p.cancelAppliedVersion)
        assertTrue("UNKNOWN 必须留下审计痕迹", audits.any { it.contains("UNKNOWN") })
    }

    @Test
    fun `补丁重复投递_第二次判已应用且文件不变`() = runBlocking {
        val (delta, m) = addThenGetStatusOffset()
        committer.commitPatch(storeKey, fileId, 2, delta.statusOffset, ByteOps.utf8("0"), uuid1,
            delta.statusOffset, delta.remarkOffset, "s-0")
        val lenAfter = m.length()
        val sumAfter = store.state(fileId)!!.checksum

        val r = committer.commitPatch(storeKey, fileId, 2, delta.statusOffset, ByteOps.utf8("0"), uuid1,
            delta.statusOffset, delta.remarkOffset, "s-1")
        assertFalse((r as MirrorCommitter.Result.Committed).applied)
        assertTrue(store.commits.last().alreadyApplied)
        assertEquals(lenAfter, m.length())
        assertEquals(sumAfter, store.state(fileId)!!.checksum)
    }

    // ==================== DIVERGED ====================

    @Test
    fun `分歧时镜像被隔离到_quarantine_且证据保留`() = runBlocking {
        add(uuid1, version = 1)
        // 手工把文件改坏：追加一段我们不认识的内容
        val m = activeMirror()
        m.appendBytes(ByteOps.utf8("垃圾数据\n"))

        val s = StableOrderJson.serialize(draft(uuid2))
        val r = committer.commitAdd(
            storeKey, fileId, uuid2, 2, s.payload, s.uuidValueByteIndex, s.remarkValueByteIndex,
            serverUuidOffset = 4L + s.uuidValueByteIndex,     // 期望落在 4，但文件已经更长了
            serverStatusOffset = 4L + s.statusValueByteIndex,
            newStreamId = "x-0",
        )

        assertTrue(r is MirrorCommitter.Result.Diverged)
        val d = r as MirrorCommitter.Result.Diverged
        assertNotNull("必须留下隔离文件", d.quarantinedTo)
        assertTrue(d.quarantinedTo!!.exists())
        assertFalse("活动镜像必须被移走", m.file.exists())
        // 证据里能看到那段垃圾数据
        assertTrue(String(d.quarantinedTo!!.readBytes(), Charsets.UTF_8).contains("垃圾数据"))
        assertTrue(audits.any { it.contains("DIVERGED") })
    }

    @Test
    fun `分歧后状态是_DIVERGED_且游标归零_保证下次一定走_FULL`() = runBlocking {
        add(uuid1, version = 1)
        activeMirror().appendBytes(ByteOps.utf8("垃圾\n"))
        val s = StableOrderJson.serialize(draft(uuid2))
        committer.commitAdd(storeKey, fileId, uuid2, 2, s.payload, s.uuidValueByteIndex,
            s.remarkValueByteIndex, 4 + s.uuidValueByteIndex.toLong(),
            4 + s.statusValueByteIndex.toLong(), "x-0")

        val st = store.state(fileId)!!
        assertEquals(SyncState.DIVERGED, st.syncState)
        assertEquals(0, st.version)
        assertNull(st.lastStreamId)
        assertEquals(1, store.diverged.size)
    }

    // ==================== ARCHIVED ====================

    @Test
    fun `归档月份拒绝任何写入`() = runBlocking {
        // 服务端把该月文件删了 → 409 → version=0 探测得 LATEST → 归档
        add(uuid1, version = 1)
        val m = activeMirror()
        m.moveTo(File(archiveRoot, Mirror.relativePath(storeKey, "alice", fileId)))
        store.states["00002|$fileId"] = store.state(fileId)!!.copy(syncState = SyncState.ARCHIVED)

        val r = add(uuid2, version = 2)
        assertTrue("归档是终态，不接受写入", r is MirrorCommitter.Result.Diverged)
        assertTrue((r as MirrorCommitter.Result.Diverged).reason.contains("已归档"))
    }

    @Test
    fun `archive_把镜像原样搬进归档区_且游标冻结不归零`() = runBlocking {
        // 版本号必须逐条 +1：guardAdd 把 `msgVersion != headerVersion + 1` 判成
        // DIVERGED（增量链有空洞），所以不能一步跳到 7。
        add(uuid1, version = 1, streamId = "keep-me-5")
        add(uuid2, version = 2, streamId = "keep-me-6")
        val live = activeMirror()
        val liveBytes = live.file.readBytes()

        assertTrue(committer.archive(storeKey, fileId, "409 探测得 LATEST"))

        // 活动区必须清空：留着的话下次 FULL 会把服务器**较短**的字节流
        // 写进这个"本该冻结"的文件，而调用方以为它是只读证据。
        assertFalse(live.file.exists())
        val archived = activeMirror(SyncState.ARCHIVED)
        assertTrue(archived.file.exists())
        assertArrayEquals("搬移必须原样，不许改写一个字节", liveBytes, archived.file.readBytes())

        val st = store.state(fileId)!!
        assertEquals(SyncState.ARCHIVED, st.syncState)
        assertEquals("归档不得归零 version：归零后运维恢复文件会触发 FULL 覆盖归档镜像", 2, st.version)
        assertEquals("归档不得清掉 lastStreamId", "keep-me-6", st.lastStreamId)
        assertEquals(1, store.archived.size)
        assertTrue(store.diverged.isEmpty())
    }

    @Test
    fun `archive_本地从未下载过这个月时也要标记`() = runBlocking {
        // 懒同步下很常见：这个月还没轮到下载，服务端就已经把它删了。
        // 不标记的话调度器会永远去同步一个不存在的文件，每次都吃一个 409 + 一次探测。
        assertTrue(committer.archive(storeKey, fileId, "409 探测得 LATEST"))
        assertEquals(SyncState.ARCHIVED, store.state(fileId)!!.syncState)
        assertEquals(1, store.archived.size)
    }

    @Test
    fun `自检_归档镜像丢失时只报不判分歧`() = runBlocking {
        // diverge 会**归零游标**，而运维哪天恢复了文件，version=0 → FULL → 覆盖归档镜像。
        // 也就是说"自检发现文件不见了"这个动作本身会解除归档，而且不报错。
        add(uuid1, version = 1)
        add(uuid2, version = 2)
        committer.archive(storeKey, fileId, "test")
        activeMirror(SyncState.ARCHIVED).file.delete()

        assertFalse(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
        assertEquals(SyncState.ARCHIVED, store.state(fileId)!!.syncState)
        assertEquals(2, store.state(fileId)!!.version)
        assertTrue("归档状态不得被自检解除", store.diverged.isEmpty())
    }

    // ==================== FULL ====================

    @Test
    fun `commitFull_整体替换并写入游标与自检值`() = runBlocking {
        add(uuid1, version = 1)
        val s = StableOrderJson.serialize(draft(uuid2))
        val serverBytes = ByteOps.encodeHeader(9) + s.payload + byteArrayOf(0x0A)

        val r = committer.commitFull(storeKey, fileId, ByteArrayInputStream(serverBytes), 9, "full-stream-0")

        assertTrue(r is MirrorCommitter.Result.Committed)
        assertArrayEquals(serverBytes, activeMirror().file.readBytes())
        val st = store.state(fileId)!!
        assertEquals(9, st.version)
        assertEquals("FULL 的游标只能从 x-stream-id 响应头拿，必须存下来", "full-stream-0", st.lastStreamId)
        assertEquals(serverBytes.size.toLong(), st.mirrorBytes)
        assertEquals(ByteOps.sumBytes(serverBytes, 4), st.checksum)
        assertEquals(SyncState.LIVE, st.syncState)
        assertEquals(1, store.fullCommits.size)
    }

    @Test
    fun `commitFull_保留原有的_firstSyncedAt`() = runBlocking {
        add(uuid1, version = 1)
        val firstSynced = store.state(fileId)!!.firstSyncedAt
        now += 999_999
        committer.commitFull(storeKey, fileId,
            ByteArrayInputStream(ByteOps.encodeHeader(2)), 2, "s")
        assertEquals("firstSyncedAt 是三态语义的一部分，FULL 不得重置它",
            firstSynced, store.state(fileId)!!.firstSyncedAt)
    }

    @Test
    fun `commitFull_能把_DIVERGED_状态救回_LIVE`() = runBlocking {
        add(uuid1, version = 1)
        activeMirror().appendBytes(ByteOps.utf8("垃圾\n"))
        val s = StableOrderJson.serialize(draft(uuid2))
        committer.commitAdd(storeKey, fileId, uuid2, 2, s.payload, s.uuidValueByteIndex,
            s.remarkValueByteIndex, 4 + s.uuidValueByteIndex.toLong(),
            4 + s.statusValueByteIndex.toLong(), "x-0")
        assertEquals(SyncState.DIVERGED, store.state(fileId)!!.syncState)

        val serverBytes = ByteOps.encodeHeader(2) + s.payload + byteArrayOf(0x0A)
        committer.commitFull(storeKey, fileId, ByteArrayInputStream(serverBytes), 2, "healed-0")
        assertEquals(SyncState.LIVE, store.state(fileId)!!.syncState)
        assertEquals(2, store.state(fileId)!!.version)
    }

    // ==================== 三重自检 ====================

    @Test
    fun `自检_状态一致时通过`() = runBlocking {
        add(uuid1, version = 1)
        assertTrue(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
    }

    @Test
    fun `自检_内容被外部改动_校验和不符_判分歧`() = runBlocking {
        add(uuid1, version = 1)
        // 绕过 committer 直接改文件（模拟别的代码路径写坏了镜像）
        val m = activeMirror()
        m.patchAt(m.length() - 2, ByteOps.utf8("Z"))
        assertFalse(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
        assertEquals(SyncState.DIVERGED, store.state(fileId)!!.syncState)
    }

    @Test
    fun `自检_长度不符_即使不算校验和也能抓住`() = runBlocking {
        add(uuid1, version = 1)
        activeMirror().appendBytes(ByteOps.utf8("多出来的一行\n"))
        assertFalse(committer.selfCheck(storeKey, fileId, verifyChecksum = false))
    }

    @Test
    fun `自检_版本头不符_能抓住`() = runBlocking {
        add(uuid1, version = 1)
        activeMirror().writeHeaderVersion(77)
        assertFalse(committer.selfCheck(storeKey, fileId, verifyChecksum = false))
    }

    @Test
    fun `自检_镜像文件丢失_判分歧`() = runBlocking {
        add(uuid1, version = 1)
        activeMirror().file.delete()
        assertFalse(committer.selfCheck(storeKey, fileId, verifyChecksum = true))
    }

    @Test
    fun `自检_没有状态记录时视为通过（尚未同步的月份）`() = runBlocking {
        assertTrue(committer.selfCheck(storeKey, "B:alice:2020-01", verifyChecksum = true))
    }

    @Test
    fun `自检_已经是_DIVERGED_的直接返回_false_不重复隔离`() = runBlocking {
        add(uuid1, version = 1)
        activeMirror().writeHeaderVersion(77)
        committer.selfCheck(storeKey, fileId, verifyChecksum = false)
        val divergedCount = store.diverged.size
        assertFalse(committer.selfCheck(storeKey, fileId, verifyChecksum = false))
        assertEquals(divergedCount, store.diverged.size)
    }
}
