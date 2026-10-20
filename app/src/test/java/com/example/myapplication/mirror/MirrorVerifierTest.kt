package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.lock.StripedMutex
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.OrderFileApi
import com.example.myapplication.net.Service1Api
import com.example.myapplication.net.UnauthorizedReason
import com.example.myapplication.net.dto.OrderSyncRequestDto
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * [MirrorVerifier] —— 唯一的端到端对账手段（方案 §7.2）。
 *
 * ## 这里盯的是三类"报错了也没用"的失败方式
 *
 *  1. **假一致**：拿一份陈旧的临时副本去比、或者读不出来时静默返回 Identical。
 *     校验工具说"没问题"而其实有问题，比没有校验工具危险得多 —— 用户会停止怀疑。
 *  2. **假分歧**：一次并发 ADD 落在下载与比对之间、或者把"本地只是落后"报成损坏。
 *     狼喊三次就没人再信它。
 *  3. **校验本身改坏了被校验的东西**：把服务器的字节落进活动镜像、或者顺手改了游标。
 *     所以 [FakeStateStore] 的每个写方法都直接抛。
 *
 * ## 最关键的一条用例
 *
 * [版本号相同但字节不同是真分歧]。那正是 `ensureFileBlocking`(:712) 重建空文件的后果：
 * Lua 把版本推到 N+1、校验和按旧值累加，服务器文件与本地镜像**永久分歧而版本号相同**。
 * 守卫不比字节、三重自检是自比、游标对得上 —— 全都不会响。只有逐字节能发现。
 */
class MirrorVerifierTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var mirrorRoot: File
    private lateinit var archiveRoot: File
    private lateinit var tmpRoot: File

    private val storeKey = "00002"
    private val fileId = "B:alice:2028-03"
    private val aFileId = "A:alice:2028"

    private val api = FakeApi()
    private val states = mutableMapOf<Pair<String, String>, MirrorState>()
    private val audits = mutableListOf<String>()

    private lateinit var verifier: MirrorVerifier

    private val line1 = """{"order_uuid":"11111111-2222-3333-4444-555555555555","writer":"alice","status":1}"""
    private val line2 = """{"order_uuid":"22222222-3333-4444-5555-666666666666","writer":"bob","status":1}"""

    @Before
    fun setUp() {
        mirrorRoot = tmp.newFolder("mirror")
        archiveRoot = tmp.newFolder("mirror-archive")
        tmpRoot = tmp.newFolder("mirror-tmp")
        verifier = MirrorVerifier(api, locator(), FakeStateStore(states), tmpRoot, StripedMutex()) { audits += it }
    }

    private fun locator() = MirrorLocator(mirrorRoot, archiveRoot)

    // ==================== 构造 ====================

    /** 服务器磁盘上那个文件应有的字节：4 字节大端头 + 每行一个订单 + LF。 */
    private fun serverFile(version: Int, vararg lines: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteOps.encodeHeader(version))
        for (l in lines) {
            out.write(l.toByteArray(Charsets.UTF_8))
            out.write(ByteOps.LF.toInt())
        }
        return out.toByteArray()
    }

    /** 写活动镜像。**不**写游标 —— 两者由各用例分别安排，才能造出"游标与磁盘不符"。 */
    private fun writeLocal(bytes: ByteArray, syncState: SyncState = SyncState.LIVE) {
        val m = locator().resolve(storeKey, fileId, syncState)
        m.file.parentFile?.mkdirs()
        m.file.writeBytes(bytes)
    }

    private fun localFile(syncState: SyncState = SyncState.LIVE) =
        locator().resolve(storeKey, fileId, syncState).file

    private fun cursor(version: Int, bytes: Long, syncState: SyncState = SyncState.LIVE) {
        states[storeKey to fileId] =
            MirrorState(version, "17-0", 12345, bytes, 1_758_000_000_000L, syncState)
    }

    private fun tmpCopy() = File(tmpRoot, "verify-$storeKey-${fileId.replace(':', '_')}.mirror")

    private fun asDiverged(v: MirrorVerifier.Verdict): MirrorVerifier.Verdict.Diverged {
        assertTrue("应是 Diverged，实际 $v", v is MirrorVerifier.Verdict.Diverged)
        return v as MirrorVerifier.Verdict.Diverged
    }

    /**
     * **独立**算一遍第一个差异下标。故意不复用 [MirrorVerifier.diff] ——
     * 用被测代码去构造被测代码的期望值，那条断言就变成自证了。
     */
    private fun firstDiffOf(a: ByteArray, b: ByteArray): Long? {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) if (a[i] != b[i]) return i.toLong()
        return if (a.size == b.size) null else common.toLong()
    }

    // ==================== 一致 ====================

    @Test
    fun 逐字节一致时报_Identical() = runBlocking {
        val bytes = serverFile(7, line1, line2)
        writeLocal(bytes)
        cursor(7, bytes.size.toLong())
        api.serveFull(7, bytes)

        val v = verifier.verify(storeKey, fileId)

        assertEquals(MirrorVerifier.Verdict.Identical(7, bytes.size.toLong()), v)
        // 结论进变更日志。fileId 里有店主名是正常的，但**订单内容**（uuid、writer、金额）不许进去 ——
        // 变更日志是给人报障用的，会被贴到群里。
        val a = audits.single()
        assertTrue(a.contains("一致"))
        assertFalse(a.contains("11111111"))
        assertFalse(a.contains("order_uuid"))
    }

    @Test
    fun 请求恒为_version0_与空_lastStreamId() = runBlocking {
        // 这是整个工具的地基：带上真实游标的话服务器会回 LATEST 或 INCREMENTAL，
        // 一个字节都比不了，而工具会兴高采烈地报"一致"。
        val bytes = serverFile(87, line1)
        writeLocal(bytes)
        cursor(87, bytes.size.toLong())
        api.serveFull(87, bytes)

        verifier.verify(storeKey, fileId)

        assertFullProbe(api.requests.single(), "B", 2028, 3)
    }

    /** `OrderSyncRequestDto` 不是 data class，只能逐字段比。 */
    private fun assertFullProbe(req: OrderSyncRequestDto, type: String, year: Int, month: Int?) {
        assertEquals(type, req.type)
        assertEquals(year, req.year)
        assertEquals(month, req.month)
        assertEquals("必须发 version=0，否则服务器不会回 FULL", 0, req.version)
        assertEquals("必须发空 lastStreamId，否则服务器不会回 FULL", "", req.lastStreamId)
    }

    @Test
    fun A_类的月份必须是_null_否则永远_400() = runBlocking {
        // A 类没有月份维度（NO_MONTH=0），而服务端校验 `month < 1 || month > 12` → 400。
        val bytes = serverFile(3, line1)
        val m = locator().resolve(storeKey, aFileId, SyncState.LIVE)
        m.file.parentFile?.mkdirs()
        m.file.writeBytes(bytes)
        api.serveFull(3, bytes)

        val v = verifier.verify(storeKey, aFileId)

        assertEquals(MirrorVerifier.Verdict.Identical(3, bytes.size.toLong()), v)
        assertFullProbe(api.requests.single(), "A", 2028, null)
    }

    @Test
    fun 校验绝不碰活动镜像_也绝不改游标() = runBlocking {
        // FakeStateStore 的写方法一律抛，所以"没改游标"由它保证；这里再钉一次"没改字节"。
        val bytes = serverFile(9, line1)
        writeLocal(bytes)
        cursor(9, bytes.size.toLong())
        // 服务器比本地多一条 —— 会报 Behind，正是最容易"顺手落地"的时机
        api.serveFull(10, serverFile(10, line1, line2))

        val v = verifier.verify(storeKey, fileId)

        assertTrue(v is MirrorVerifier.Verdict.Behind)
        assertEquals("活动镜像被改了", bytes.toList(), localFile().readBytes().toList())
        assertEquals("游标被改了", 9, states.getValue(storeKey to fileId).version)
    }

    @Test
    fun 一致时临时副本被清掉_不留_26MB_垃圾() = runBlocking {
        val bytes = serverFile(4, line1)
        writeLocal(bytes)
        cursor(4, bytes.size.toLong())
        api.serveFull(4, bytes)

        verifier.verify(storeKey, fileId)

        assertFalse("临时副本没删", tmpCopy().exists())
        assertEquals(emptyList<File>(), tmpRoot.listFiles()?.toList() ?: emptyList<File>())
    }

    // ==================== 版本头也在比对范围内 ====================

    @Test
    fun 内容相同但版本头不同也是分歧() = runBlocking {
        // 方案 §7.2 明确要求"含前 4 字节版本头"。只比内容的话，
        // `ensureFileBlocking` 重建出来的文件（版本号已推高、内容是空的）会被判成一致。
        writeLocal(serverFile(6, line1))
        cursor(6, localFile().length())
        api.serveFull(7, serverFile(7, line1))

        val d = asDiverged(verifier.verify(storeKey, fileId))

        // ⚠ 大端小版本号的差异落在头部**最后一个**字节：`00 00 00 06` vs `00 00 00 07`。
        // 断言 0 是错的 —— 前三个字节确实相同。
        assertEquals("分歧点应当在版本头的低字节上", 3L, d.firstDiff)
        assertEquals(6, d.localVersion)
        assertEquals(7, d.serverVersion)
    }

    @Test
    fun 版本号相同但字节不同是真分歧() = runBlocking {
        // ★ 最关键的一条：等长覆写打错偏移不报错、长度不变、校验和自洽、游标对得上。
        val good = serverFile(12, line1, line2)
        // 改一个字节，且**不改长度**：等长覆写打错偏移正是这个形状。
        val at = 4 + line1.toByteArray(Charsets.UTF_8).size + 20L   // 第二行里 `"writer":"bob"` 的 b
        val bad = good.copyOf().also { it[at.toInt()] = 'X'.code.toByte() }
        assertEquals("构造前提：长度必须相同", good.size, bad.size)
        assertEquals("构造前提：差异必须正好在 at", at, firstDiffOf(bad, good))
        writeLocal(bad)
        cursor(12, bad.size.toLong())
        api.serveFull(12, good)

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals(at, d.firstDiff)
        assertEquals(good.size.toLong(), d.serverBytes)
        assertEquals(bad.size.toLong(), d.localBytes)
        assertEquals("长度相同，所以长度对不出问题", d.localBytes, d.serverBytes)
        assertEquals(12, d.localVersion)
        assertEquals(12, d.serverVersion)
        assertEquals("游标版本要一并报出来，才能区分是镜像坏了还是 Room 坏了", 12, d.cursorVersion)
    }

    @Test
    fun 游标与磁盘版本不符时两者都要出现在报告里() = runBlocking {
        val bytes = serverFile(20, line1)
        writeLocal(bytes)
        cursor(18, bytes.size.toLong())   // 游标停在 18，磁盘是 20
        api.serveFull(20, bytes.copyOf().also { it[10] = 0x41 })

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals(20, d.localVersion)
        assertEquals(18, d.cursorVersion)
        assertEquals(10L, d.firstDiff)
    }

    // ==================== 落后 ≠ 损坏 ====================

    @Test
    fun 本地是服务器的严格前缀时报_Behind() = runBlocking {
        val local = serverFile(5, line1)
        writeLocal(local)
        cursor(5, local.size.toLong())
        api.serveFull(6, serverFile(6, line1, line2))

        val v = verifier.verify(storeKey, fileId)

        assertEquals(
            MirrorVerifier.Verdict.Behind(5, 6, local.size.toLong(), local.size.toLong() + line2.length + 1),
            v,
        )
        assertFalse(tmpCopy().exists())
    }

    @Test
    fun 服务器更短时报_Diverged_而不是_Behind() = runBlocking {
        // 本地多出服务器没有的尾部。同版本 ⇒ 不可能是"落后"。
        val local = serverFile(5, line1, line2)
        writeLocal(local)
        cursor(5, local.size.toLong())
        api.serveFull(5, serverFile(5, line1))

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals("分歧点就是较短那个文件的末尾", d.serverBytes, d.firstDiff)
        assertTrue(d.localBytes > d.serverBytes)
    }

    @Test
    fun 本地更短而版本号相同时也是真分歧() = runBlocking {
        // 加一行必然把版本 +1，所以"同版本但本地少一截"不可能是落后，只能是损坏。
        // 若这里报 Behind，调度器就会以为跑一次同步能补齐，而真正的分歧永远不被隔离。
        val local = serverFile(5, line1)
        writeLocal(local)
        cursor(5, local.size.toLong())
        api.serveFull(5, serverFile(5, line1, line2))

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals(local.size.toLong(), d.firstDiff)
        assertEquals(5, d.localVersion)
        assertEquals(5, d.serverVersion)
    }

    @Test
    fun 本地镜像连版本头都凑不齐时直接判分歧() = runBlocking {
        // 半写：进程在 `appendBytes` 与 `writeHeaderVersion` 之间被杀，或磁盘满。
        // 这时"按内容比"没有意义 —— 偏移 4 之后什么都不存在，
        // 而 `readHeaderVersion()` 只会给 0，比下去会把截断报成"版本 0 与版本 N 的分歧"。
        val bytes = serverFile(33, line1, line2)
        writeLocal(bytes.copyOfRange(0, 2))
        cursor(33, bytes.size.toLong())
        api.serveFull(33, bytes)

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals(2L, d.localBytes)
        assertEquals(bytes.size.toLong(), d.serverBytes)
        assertEquals("截断的镜像读不出版本，报 0 比报一个假数字诚实", 0, d.localVersion)
        assertEquals(33, d.serverVersion)
        assertEquals(33, d.cursorVersion)
    }

    @Test
    fun 本地版本更高时报_LocalAhead_且服务器那份绝不落地() = runBlocking {
        // 服务器丢过数据。此时让 FULL 落地 = 用较短的文件覆盖唯一更全的副本，不可逆。
        val local = serverFile(87, line1, line2)
        writeLocal(local)
        cursor(87, local.size.toLong())
        api.serveFull(5, serverFile(5, line1))

        val v = verifier.verify(storeKey, fileId)

        assertEquals(MirrorVerifier.Verdict.LocalAhead(87, 5, local.size.toLong(), local.size.toLong() - line2.length - 1), v)
        assertEquals(local.toList(), localFile().readBytes().toList())
        assertFalse(tmpCopy().exists())
    }

    // ==================== 两侧缺失 ====================

    @Test
    fun 服务器已无此文件时报_ServerMissing() = runBlocking {
        val local = serverFile(31, line1)
        writeLocal(local)
        cursor(31, local.size.toLong())
        api.throwNext = ApiError.NotFound("订单文件不存在")

        val v = verifier.verify(storeKey, fileId)

        assertEquals(MirrorVerifier.Verdict.ServerMissing(31, local.size.toLong()), v)
        assertEquals("本地那份是仅存副本，绝不允许被删", local.toList(), localFile().readBytes().toList())
    }

    @Test
    fun 本地没有镜像时报_LocalMissing() = runBlocking {
        val bytes = serverFile(2, line1)
        api.serveFull(2, bytes)

        val v = verifier.verify(storeKey, fileId)

        assertEquals(MirrorVerifier.Verdict.LocalMissing(2, bytes.size.toLong()), v)
        assertFalse(tmpCopy().exists())
    }

    @Test
    fun ARCHIVED_解析到归档区_并如实报_ServerMissing() = runBlocking {
        val local = serverFile(40, line1)
        writeLocal(local, SyncState.ARCHIVED)
        cursor(40, local.size.toLong(), SyncState.ARCHIVED)
        api.throwNext = ApiError.NotFound("订单文件不存在")

        val v = verifier.verify(storeKey, fileId)

        assertEquals(MirrorVerifier.Verdict.ServerMissing(40, local.size.toLong()), v)
        // 归档镜像在 mirror-archive/，且**一个字节都没被动过**
        val archived = localFile(SyncState.ARCHIVED)
        assertTrue("应解析到归档区：$archived", archived.path.startsWith(archiveRoot.path))
        assertEquals(local.toList(), archived.readBytes().toList())
        assertFalse("活动区不该凭空多出一个文件", localFile(SyncState.LIVE).exists())
    }

    // ==================== 服务器文件为空（最常见的月份） ====================

    @Test
    fun 服务器回_LATEST_时按四字节空头校验_本地也空则一致() = runBlocking {
        // `version=0` 得到 LATEST ⇒ 服务器的 latest 也是 0 ⇒ 文件只有 4 字节头。
        // 没有响应体可比，就照服务器的说法造一份 —— 否则"这个月没单"根本校验不了。
        writeLocal(ByteOps.encodeHeader(0))
        cursor(0, 4)
        api.serveLatest(0)

        assertEquals(MirrorVerifier.Verdict.Identical(0, 4), verifier.verify(storeKey, fileId))
    }

    @Test
    fun 服务器回_LATEST_而本地有内容时是真分歧() = runBlocking {
        writeLocal(serverFile(0, line1))
        cursor(0, localFile().length())
        api.serveLatest(0)

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals("服务器只有 4 字节头，分歧点就在第 4 字节", 4L, d.firstDiff)
        assertEquals(4L, d.serverBytes)
    }

    // ==================== 陈旧副本与响应形状 ====================

    @Test
    fun 上一次留下的临时副本不会被当成这次的结果() = runBlocking {
        // 若不先删，这里就会拿一份"恰好与本地一致"的陈旧字节去比 → 假 Identical。
        val local = serverFile(11, line1)
        writeLocal(local)
        cursor(11, local.size.toLong())
        tmpRoot.mkdirs()
        tmpCopy().writeBytes(local)                       // 陈旧副本 == 本地
        api.serveFull(11, serverFile(11, line1, line2))   // 服务器其实多一条

        val v = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals(local.size.toLong(), v.firstDiff)
        assertEquals("服务器副本是这次下载的那份", (local.size.toLong() + line2.length + 1), v.serverBytes)
    }

    @Test
    fun version0_却拿到_INCREMENTAL_报_Failed_而不是当成一致() = runBlocking {
        writeLocal(serverFile(3, line1))
        api.serveIncremental(3, "18-0")

        val v = verifier.verify(storeKey, fileId)

        assertTrue(v is MirrorVerifier.Verdict.Failed)
        v as MirrorVerifier.Verdict.Failed
        assertTrue(v.msg.contains("INCREMENTAL"))
        assertFalse(v.retriable)
        assertFalse(tmpCopy().exists())
    }

    @Test
    fun 响应头版本与响应体版本头不符时报_Failed() = runBlocking {
        // 两者同源于服务器磁盘上同一个文件。不等说明传输被截断或被中间层改过 ——
        // 不挡下来的话，接下来的比对会把这件事报成"本地镜像坏了"。
        writeLocal(serverFile(7, line1))
        api.serveFull(9, serverFile(7, line1))

        val v = verifier.verify(storeKey, fileId)

        assertTrue(v is MirrorVerifier.Verdict.Failed)
        v as MirrorVerifier.Verdict.Failed
        assertTrue(v.msg.contains("不符"))
        assertFalse(tmpCopy().exists())
    }

    // ==================== 错误分类 ====================

    @Test
    fun 限速与_401_不可重试_网络与_500_可重试() = runBlocking {
        writeLocal(serverFile(1, line1))

        api.throwNext = ApiError.RateLimited("操作过于频繁")
        val rate = verifier.verify(storeKey, fileId) as MirrorVerifier.Verdict.Failed
        assertFalse(rate.retriable)

        api.throwNext = ApiError.Unauthorized(UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, null)
        val un = verifier.verify(storeKey, fileId) as MirrorVerifier.Verdict.Failed
        assertFalse(un.retriable)
        assertTrue(un.msg.contains("TOKEN_INVALID_OR_EXPIRED"))

        api.throwNext = ApiError.Network(IOException("timeout"))
        val net = verifier.verify(storeKey, fileId) as MirrorVerifier.Verdict.Failed
        assertTrue(net.retriable)

        api.throwNext = ApiError.ServerError("ab12cd34", "服务器内部错误")
        val err = verifier.verify(storeKey, fileId) as MirrorVerifier.Verdict.Failed
        assertTrue(err.retriable)
        assertTrue("追踪号必须留在文案里，否则没法报障", err.msg.contains("ab12cd34"))

        assertEquals("失败也要清掉半下载的副本", emptyList<File>(), tmpRoot.listFiles()?.toList() ?: emptyList<File>())
    }

    @Test
    fun 非法_fileId_报_Failed_且一个请求都不发() = runBlocking {
        // `C:*` 是服务端的完成记录文件，客户端既拉不到也不该拉（见 OrderFileId 的类注释）。
        val v = verifier.verify(storeKey, "C:alice:2028-03") as MirrorVerifier.Verdict.Failed

        assertFalse(v.retriable)
        assertEquals(emptyList<OrderSyncRequestDto>(), api.requests)
    }

    // ==================== 样本与 diff 原语 ====================

    @Test
    fun 分歧样本是十六进制_且正好取自分歧点() = runBlocking {
        val good = serverFile(15, line1)
        val bad = good.copyOf().also { it[6] = 0xFF.toByte() }
        writeLocal(bad)
        api.serveFull(15, good)

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals(6L, d.firstDiff)
        assertTrue("本地样本应从分歧点起：${d.localSample}", d.localSample.startsWith("ff"))
        assertEquals(good[6].let { "%02x".format(it) }, d.serverSample.substringBefore(' '))
        // 分歧点在末尾附近时样本自动变短，绝不越界
        assertTrue(d.localSample.split(' ').size <= 16)
    }

    @Test
    fun 本地比服务器长时样本说明缺了多少字节() = runBlocking {
        val local = serverFile(15, line1, line2)
        writeLocal(local)
        api.serveFull(15, serverFile(15, line1))

        val d = asDiverged(verifier.verify(storeKey, fileId))

        assertEquals(d.serverBytes, d.firstDiff)
        assertTrue("服务器侧应报越界：${d.serverSample}", d.serverSample.startsWith("<末尾之后"))
    }

    @Test
    fun Diverged_时服务器副本被留作证据_路径固定不堆积() = runBlocking {
        writeLocal(serverFile(15, line1))
        api.serveFull(15, serverFile(15, line2))

        val first = asDiverged(verifier.verify(storeKey, fileId))
        assertTrue(first.serverCopy.exists())
        assertEquals(tmpCopy(), first.serverCopy)

        // 再校验一次：同名覆盖，不会攒出一堆 26MB 的副本
        api.serveFull(15, serverFile(15, line2))
        val second = asDiverged(verifier.verify(storeKey, fileId))
        assertEquals(first.serverCopy, second.serverCopy)
        assertEquals(1, tmpRoot.listFiles()!!.size)
    }

    @Test
    fun diff_跨块边界也找得到() = runBlocking {
        // 64KB 一块。差异放在第三块里，顺带证明它不是"只比了第一块就返回一致"。
        val filler = ByteArray(150_000) { (it % 251).toByte() }
        val a = ByteArrayOutputStream().apply { write(ByteOps.encodeHeader(1)); write(filler) }.toByteArray()
        val b = a.copyOf().also { it[149_999] = (it[149_999] + 1).toByte() }
        val fa = tmp.newFile("a.bin").apply { writeBytes(a) }
        val fb = tmp.newFile("b.bin").apply { writeBytes(b) }

        val d = verifier.diff(fa, fb)

        assertEquals(149_999L, d.firstDiff)
        assertEquals(149_999L, d.compared)
    }

    @Test
    fun diff_完全一致时_firstDiff_为_null() = runBlocking {
        val bytes = serverFile(1, line1, line2)
        val fa = tmp.newFile("a.bin").apply { writeBytes(bytes) }
        val fb = tmp.newFile("b.bin").apply { writeBytes(bytes) }

        val d = verifier.diff(fa, fb)

        assertNull(d.firstDiff)
        assertEquals(bytes.size.toLong(), d.compared)
    }

    @Test
    fun diff_长度不同但公共部分全同时分歧点是较短那个的末尾() = runBlocking {
        val fa = tmp.newFile("a.bin").apply { writeBytes(serverFile(1, line1, line2)) }
        val fb = tmp.newFile("b.bin").apply { writeBytes(serverFile(1, line1)) }

        val d = verifier.diff(fa, fb)

        assertEquals(fb.length(), d.firstDiff)
        assertEquals(fb.length(), d.compared)
    }

    @Test
    fun diff_读不出来时抛_绝不静默报一致() = runBlocking {
        val fa = tmp.newFile("a.bin").apply { writeBytes(serverFile(1, line1)) }

        val e = runCatching { verifier.diff(fa, File(tmpRoot, "不存在.bin")) }.exceptionOrNull()

        assertTrue("应抛 IOException，实际 $e", e is IOException)
    }

    // ==================== 批量 ====================

    @Test
    fun verifyAll_等长同序且串行发请求() = runBlocking {
        val b1 = serverFile(1, line1)
        writeLocal(b1)
        val aBytes = serverFile(2, line2)
        val am = locator().resolve(storeKey, aFileId, SyncState.LIVE)
        am.file.parentFile?.mkdirs()
        am.file.writeBytes(aBytes)

        // 两个 fileId 各排一步：B 一致，A 分歧
        api.serveFull(1, b1)
        api.serveFull(2, serverFile(2, line1))

        val rs = verifier.verifyAll(storeKey, listOf(fileId, aFileId))

        assertEquals(listOf(fileId, aFileId), rs.map { it.first })
        assertTrue(rs[0].second is MirrorVerifier.Verdict.Identical)
        assertTrue(rs[1].second is MirrorVerifier.Verdict.Diverged)
        assertEquals(2, api.requests.size)
    }

    // ==================== 替身 ====================

    private class FakeApi : OrderFileApi {
        sealed class Step {
            data class Full(val version: Int, val streamId: String, val body: ByteArray) : Step()
            data class Latest(val version: Int, val streamId: String) : Step()
            data class Incremental(val version: Int, val streamId: String) : Step()
        }

        val requests = mutableListOf<OrderSyncRequestDto>()
        val script = ArrayDeque<Step>()
        var throwNext: Throwable? = null

        fun serveFull(version: Int, body: ByteArray, streamId: String = "$version-0") {
            script += Step.Full(version, streamId, body)
        }

        fun serveLatest(version: Int, streamId: String = "") { script += Step.Latest(version, streamId) }

        fun serveIncremental(version: Int, streamId: String) { script += Step.Incremental(version, streamId) }

        override suspend fun syncOrderFile(
            request: OrderSyncRequestDto,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ): Service1Api.SyncOutcome {
            requests += request
            throwNext?.let {
                throwNext = null
                throw it
            }
            val step = script.removeFirstOrNull()
                ?: throw AssertionError("脚本用完了：测试少排了一步（已发 ${requests.size} 次请求）")
            return when (step) {
                is Step.Full -> {
                    onFull(
                        Service1Api.FullFileBody(
                            step.version, step.streamId, step.body.size.toLong(),
                            Buffer().write(step.body),
                        )
                    )
                    Service1Api.SyncOutcome.Full(step.version, step.streamId)
                }

                is Step.Latest -> Service1Api.SyncOutcome.Latest(step.version, step.streamId)
                is Step.Incremental ->
                    Service1Api.SyncOutcome.Incremental(step.version, step.streamId, emptyList())
            }
        }

        override suspend fun probeAfter409(
            type: String,
            year: Int,
            month: Int?,
            onFull: suspend (Service1Api.FullFileBody) -> Unit,
        ): Service1Api.SyncOutcome = throw AssertionError("校验发的是 version=0，不该走到 409 探测")
    }

    /**
     * **每个写方法都抛**：校验是只读动作。它若改了游标，
     * 一次"看起来没问题"的校验就会把 DIVERGED 状态悄悄抹掉，之后调度器不再排 FULL。
     */
    private class FakeStateStore(private val map: MutableMap<Pair<String, String>, MirrorState>) : MirrorStateStore {
        override suspend fun read(storeKey: String, fileId: String): MirrorState? = map[storeKey to fileId]

        override suspend fun commit(record: MirrorCommitRecord) =
            throw AssertionError("校验绝不许改状态：commit(${record.fileId})")

        override suspend fun commitFull(storeKey: String, fileId: String, state: MirrorState, mirror: Mirror) =
            throw AssertionError("校验绝不许改状态：commitFull($fileId)")

        override suspend fun commitDiverged(storeKey: String, fileId: String, reason: String) =
            throw AssertionError("校验绝不许改状态：commitDiverged($reason)")

        override suspend fun commitArchived(storeKey: String, fileId: String, reason: String) =
            throw AssertionError("校验绝不许改状态：commitArchived($reason)")
    }
}
