package com.example.myapplication.gate

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.lock.StripedMutex
import com.example.myapplication.mirror.FakeOrderFileDao
import com.example.myapplication.mirror.MirrorCommitter
import com.example.myapplication.mirror.MirrorLocator
import com.example.myapplication.mirror.MirrorVerifier
import com.example.myapplication.mirror.RoomMirrorStateStore
import com.example.myapplication.mirror.SyncState
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.HttpClients
import com.example.myapplication.net.Service1Api
import com.example.myapplication.net.dto.OrderAddResultDto
import com.example.myapplication.net.dto.OrderPatchRequestDto
import com.example.myapplication.sync.OrderFileSyncEngine
import com.example.myapplication.sync.outbox.OrderDraft
import com.example.myapplication.sync.outbox.OrderLine
import com.example.myapplication.sync.outbox.StableOrderJson
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 方案 §7.2 的**硬验收门**：本地镜像与服务端磁盘文件**逐字节**一致（含前 4 字节版本头）。
 *
 * ## 为什么这是一道门，而不是一个用例
 *
 * 等长覆写打错偏移时：不报错、文件长度不变、校验和自洽、`file_state` 游标对得上。
 * 服务端的 WAL 与 checksum 机制**检测不出语义破坏**（README §9）。此后每一次取消、
 * 每一次改备注都会打在**别的订单**的字节上，而且是静默的、不可恢复的。
 *
 * `orderfile:checksum:<fileId>` 客户端读不到（无接口），三重自检是磁盘与 `file_state`
 * 的**自比**。所以唯一能证明"我和服务器真的一样"的手段，就是把服务器的磁盘文件拿来 diff。
 *
 * **这道门不通过，P7 之后的任何 UI 都不许动**（方案 §九）。统计、库存、补货全部建立在
 * 镜像与服务器同形之上；建在流沙上的功能做得再多也只是把错误放大。
 *
 * ## 跑它需要什么
 *
 * 一个**真的** service1 在 [BASE] 上跑着（默认 `https://localhost:8443`，即 `tls` profile），
 * 外加同一个 Redis。没跑的话本类**整体 skip**（`assumeTrue`），不会让常规套件变红。
 *
 * ```
 * cd C:/Users/hhl/IdeaProjects/service1
 * ./gradlew bootRun --args='--spring.profiles.active=tls'
 * ./gradlew :app:testDebugUnitTest --tests '*ByteIdentityGateTest*'
 * ```
 *
 * 服务端磁盘目录用 `-Ds1.data=...` 覆盖，默认 `C:/Users/hhl/IdeaProjects/service1/data/orders`。
 * 这要求测试进程与 service1 在**同一台机器**上 —— 逐字节 diff 必须能读到对方落盘的字节，
 * 而协议本身不提供"给我你的磁盘原文"这种接口（FULL 是同一份字节，但那就成了自证）。
 *
 * ## 账户从哪来
 *
 * 专用的 `gate2028`，由 [provision] 现场建（`register` → service2 审批 → 登录）。
 * **不复用 `alice`**：`test_all.py` 会把它的密码从 `Alice@123` 改成 `NewAlice@123`（I 套件 :851），
 * 还会把管理员 `serverb` 改名成 `serverb3`（L 套件 :986）。那套参照测试跑过之后，
 * 任何写死的凭据都不可信。
 *
 * 审批走 **service2** 而不是管理员：`Service2CredentialService.verifyAndRotate` 在
 * `newCredential` 为空时不改任何状态，而 `security:service2:cred:previous` 槽里存的正是
 * `application.properties:30` 的默认值（被轮换过一次，默认值落到了 previous 槽）——
 * 所以这条路是只读的，不会把别人的凭证挤掉。
 *
 * ## ⚠ `order_uuid` 必须每次运行都不同
 *
 * 服务端 `parseOrderJson` **不查重**，同一个 uuid 上传两次就在文件里出现两行
 * （README §9 明确警告过）。而服务器磁盘文件跨运行**持久**，所以这里用随机 uuid。
 * 失败时 uuid 会打进断言消息，便于去服务端文件里定位那一行。
 *
 * ## ⚠ 限速
 *
 * 登录桶是 `RateLimiter.create(5/60.0)` + `maxBurstSeconds=1.0` → 只能存 0.083 个令牌，
 * 实际是"严格每 12 秒一个"。所以**整个类只登录一次**（token 存在伴生对象里），
 * 每个测试方法各登一次的话从第二个方法起就是一路 429。
 * 同理，[reachable] 用 **GET** 探活：`RateLimitService` 的桶在控制器方法里，
 * 405 根本走不到那儿，而 POST 一个假用户名会白白吃掉一个登录令牌。
 */
class ByteIdentityGateTest {

    companion object {
        // `System.getProperty(k, def)` 在 Kotlin 眼里返回 `String?`（JDK 的 nullability 标注），
        // 所以用 `?:` 而不是两参重载 —— 否则 BASE 是可空类型，后面每处拼接都要 `!!`。
        private val BASE: String = System.getProperty("s1.base") ?: "https://localhost:8443"
        private val SERVER_DATA =
            File(System.getProperty("s1.data") ?: "C:/Users/hhl/IdeaProjects/service1/data/orders")

        private const val OWNER = "gate2028"
        private const val PWD = "Gate@2028x"

        /** `application.properties:30` 的明文默认值。见类注释"账户从哪来"。 */
        private const val S2_CRED = "service2-internal-api-key-change-in-production"

        /**
         * 用 2028 年：`test_all.py` 用 2027、`SyncProtocolIntegrationTest` 用 2031/2032/2033。
         * 撞年份的后果是两套测试互相往同一个文件里追加，diff 会失败但原因完全指不到本类。
         */
        private const val YEAR = 2028
        private const val MONTH = 3

        private const val B_FILE_ID = "B:$OWNER:$YEAR-03"
        private const val A_FILE_ID = "A:$OWNER:$YEAR"

        /** 比 12 秒的令牌间隔再宽一点，免得刚好卡在边界上又吃一次 429。 */
        private const val RATE_LIMIT_WAIT_MS = 14_000L

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val gson = Gson()

        /** 跨方法复用的会话。见类注释"限速"。 */
        private var shared: Session? = null
    }

    /** 一次登录换来的东西。[api] 的 tokenProvider 恒返回同一个 token（单会话，全程不换）。 */
    private class Session(val api: Service1Api, val storeKey: String)

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var session: Session
    private lateinit var api: Service1Api

    private lateinit var locator: MirrorLocator
    private lateinit var locks: StripedMutex
    private lateinit var dao: FakeOrderFileDao
    private lateinit var engine: OrderFileSyncEngine
    private lateinit var verifier: MirrorVerifier

    /** 审计行。分歧时把它一并打出来，比只看字节差好定位。 */
    private val audits = mutableListOf<String>()

    private var clock = 1_758_000_000_000L

    @Before
    fun setUp() {
        val client = HttpClients.apiClient(HttpClients.trustAnchorFromPem(caPem()))
        assumeTrue("service1 没在跑（$BASE），跳过逐字节验收门", reachable(client))

        session = shared ?: provision(client).also { shared = it }
        api = session.api

        locator = MirrorLocator(tmp.newFolder("mirror"), tmp.newFolder("mirror-archive"))
        locks = StripedMutex()
        dao = FakeOrderFileDao()
        val stateStore = RoomMirrorStateStore(
            dao = dao,
            // 单线程测试：直接跑。生产实现是 db.withTransaction（见 RoomMirrorStateStore.of）
            inTransaction = { body -> body() },
            timeZone = { TimeZone.getTimeZone("UTC") },
            now = { clock },
            audit = { audits += it },
        )
        val committer = MirrorCommitter(
            locator = locator,
            quarantineRoot = tmp.newFolder("mirror-quarantine"),
            locks = locks,
            stateStore = stateStore,
            wallClock = { clock },
            audit = { audits += it },
        )
        engine = OrderFileSyncEngine(api, committer, stateStore, dao, { clock }, { audits += it })
        verifier = MirrorVerifier(api, locator, stateStore, tmp.newFolder("mirror-tmp"), locks) { audits += it }
    }

    // ==================== 方向一：对端写，本地同步 ====================

    /**
     * 对端（用**手写 JSON**，不经 `StableOrderJson`）下单、取消、改备注，
     * 本地引擎同步之后，镜像必须与服务端磁盘文件逐字节相同。
     *
     * 对端的 payload 刻意手写：如果两边都用同一个序列化器，那么"序列化器产出了服务端
     * 会悄悄改形的字节"这类问题就永远测不出来 —— 两边一起错，diff 照样通过。
     */
    @Test
    fun 方向一_对端下单取消改备注_同步后与服务器磁盘逐字节一致() = runBlocking {
        // --- 对端写 B 类（销售单）---
        val b1 = peer.addB(trickyName = false)
        val b2 = peer.addB(trickyName = true)
        val b3 = peer.addB(trickyName = false)

        // 取消 b2：`patchLength=1, patchContent="0"`，与 OrderFileService:383-391 的
        // 库存回退条件逐字对齐（少一个条件就不回退库存）。
        peer.cancel(b2)

        // --- 对端写 A 类（进货单）并改备注 ---
        val a1 = peer.addA("首笔进货备注")
        // ⚠ 改备注也**只能**用 action=CANCEL（服务端 :361 只接受这一个值），
        // 于是流消息里的 type 恒为 CANCEL，客户端只能按偏移三分支判定。这里正好压那条路径。
        peer.rewriteRemark(a1, "改过一次的进货备注")

        // --- 本地同步 ---
        assertSyncOk(B_FILE_ID)
        assertSyncOk(A_FILE_ID)

        // --- 逐字节比对（含前 4 字节版本头）---
        assertSameBytes("B:$YEAR-03", serverBytes(bServerRel()), mirrorBytes(B_FILE_ID))
        assertSameBytes("A:$YEAR", serverBytes(aServerRel()), mirrorBytes(A_FILE_ID))

        // 独立复核：`MirrorVerifier` 走的是另一条路径（重新 FULL 到 mirror-tmp 再 diff），
        // 它必须也判 Identical。两边不一致说明其中一个工具在骗人。
        assertVerifierIdentical(B_FILE_ID)
        assertVerifierIdentical(A_FILE_ID)

        // 派生索引也要跟上：字节一致但 order_index 缺行，统计照样是错的。
        // ⚠ 只数**本方法**下的那几笔：服务端磁盘文件跨方法、跨运行持久，
        // 数全部行的话结果取决于 JUnit 的方法顺序与这个文件已经被跑过几次。
        val mine = listOf(b1.uuid, b2.uuid, b3.uuid)
        assertEquals("三笔 B 类没都进 order_index", 3, dao.orders.values.count { it.orderUuid in mine })
        assertEquals("被取消的那笔 status 没落地（uuid=${b2.uuid}）", 0, orderOf(b2.uuid).status)
        assertEquals("没取消的两笔 status 必须是 1", listOf(1, 1), listOf(orderOf(b1.uuid).status, orderOf(b3.uuid).status))

        // 改备注走的是 §3.9 的第二分支：只更新备注，**不动 status、不动统计**
        val a = orderOf(a1.uuid)
        assertEquals("改备注把 status 也动了（uuid=${a1.uuid}）", 1, a.status)
        assertEquals("改备注把总额也动了", 12000L, a.totalCents)
        assertTrue("新备注没进 order_index：${a.remark}", a.remark?.contains("改过一次的进货备注") == true)
    }

    private fun orderOf(uuid: String) =
        dao.orders.values.singleOrNull { it.orderUuid == uuid }
            ?: throw AssertionError("order_index 里没有 uuid=$uuid；现有 ${dao.orders.values.map { it.orderUuid }}")

    // ==================== 方向二：本地写，对端读 ====================

    /**
     * 反过来：本地用**生产路径**（`StableOrderJson` + `Service1Api.addOrder/patchOrder`）写，
     * 再同步回来，与服务器磁盘逐字节比对。
     *
     * 这一方向覆盖的是"我们自己产出的字节服务端会不会改形"，与方向一互补。
     */
    @Test
    fun 方向二_本地下单取消改备注_与服务器磁盘逐字节一致() = runBlocking {
        val uuidB = UUID.randomUUID().toString()
        val serB = StableOrderJson.serialize(
            OrderDraft(
                orderUuid = uuidB,
                writer = OWNER,
                items = listOf(
                    OrderLine("6901234567890", "可口可乐330ml", 14, "52.00", "1.00"),
                    OrderLine("6909876543210", "雪碧\"大瓶\"\\促销", 3, "13.50", "1.00"),
                ),
                total = "65.50",
                ts = clock,
                groupId = "a3f9c21b",
            )
        )
        val resB = api.addOrder("B", YEAR, MONTH, String(serB.payload, UTF_8))
        // 服务端算出来的偏移是权威值，必须与我们自己算的字节下标对得上
        StableOrderJson.verifyAgainstServer(serB, need(resB.uuidOffset, "uuidOffset"), need(resB.statusOffset, "statusOffset"))

        val uuidA = UUID.randomUUID().toString()
        val serA = StableOrderJson.serialize(
            OrderDraft(
                orderUuid = uuidA,
                writer = OWNER,
                items = listOf(OrderLine("6901234567890", "可口可乐330ml", 40, "120.00", "1.00")),
                total = "120.00",
                ts = clock,
                // 与对端路径同形：`grp` 紧挨在 `remark` 前面，是"remarkOffset 推早了"最先吃掉的字段
                groupId = "a3f9c21b",
                remark = ByteOps.padRemark("本地写的进货备注"),
            )
        )
        val resA = api.addOrder("A", YEAR, null, String(serA.payload, UTF_8))
        StableOrderJson.verifyAgainstServer(serA, need(resA.uuidOffset, "uuidOffset"), need(resA.statusOffset, "statusOffset"))

        // 取消 B 单
        patch("B", MONTH, uuidB, need(resB.uuidOffset, "uuidOffset"), need(resB.statusOffset, "statusOffset"), "0")

        // 改 A 单备注：`remarkOffset = uuidOffset + (r − u)`（README §2.5）
        val remarkOffset = need(resA.uuidOffset, "uuidOffset") +
            (need(serA.remarkValueByteIndex, "remarkValueByteIndex") - serA.uuidValueByteIndex).toLong()
        val newRemark = ByteOps.padRemark("本地改过的进货备注")
        assertEquals("备注必须恒 150 字节，否则等长覆写会变成变长插入", ByteOps.REMARK_WIDTH, ByteOps.utf8Len(newRemark))
        patch("A", null, uuidA, need(resA.uuidOffset, "uuidOffset"), remarkOffset, newRemark)

        // ⚠ 结构性复核，与对端路径同一条判据。这一处尤其不能省：
        // `remarkOffset` 是从**生产代码**的 `SerializedOrder.remarkValueByteIndex` 推出来的，
        // 而"读回 remarkOffset 处 150 字节"是自证（打在哪都读得回来）。
        // 只有把服务端那一行重新解析出来，才知道 `remarkValueByteIndex` 有没有算错。
        assertRemarkLanded(
            disk = serverBytes(aServerRel()),
            uuid = uuidA,
            uuidOffset = need(resA.uuidOffset, "uuidOffset"),
            remarkOffset = remarkOffset,
            paddedValue = newRemark,
            newText = "本地改过的进货备注",
        )

        assertSyncOk(B_FILE_ID)
        assertSyncOk(A_FILE_ID)

        assertSameBytes("B:$YEAR-03", serverBytes(bServerRel()), mirrorBytes(B_FILE_ID))
        assertSameBytes("A:$YEAR", serverBytes(aServerRel()), mirrorBytes(A_FILE_ID))
        assertVerifierIdentical(B_FILE_ID)
        assertVerifierIdentical(A_FILE_ID)

        // 派生索引：字节一致但 order_index 缺行/错行，统计照样是错的。按 uuid 限定，
        // 与 JUnit 方法顺序、这个文件被跑过几次都无关。
        assertEquals("本地写的 B 单没进 order_index", 0, orderOf(uuidB).status)
        assertEquals("改备注把 status 也动了", 1, orderOf(uuidA).status)
        assertTrue("新备注没进 order_index：${orderOf(uuidA).remark}", orderOf(uuidA).remark?.contains("本地改过的进货备注") == true)
    }

    // ==================== FULL 与 INCREMENTAL 两条落地路径 ====================

    /**
     * 先 FULL 落地，再让对端追加一笔走 INCREMENTAL，两次都要逐字节一致。
     *
     * 这两条路径写文件的代码**完全不同**：FULL 是 `replaceAllStreaming` 整份落地，
     * INCREMENTAL 是 `appendBytes` 追加一行。只测 FULL 的话，追加路径上
     * 少写一个 LF、或者 `lineStart` 差一个字节，都不会被发现 ——
     * 而 `lineStart` 差一个字节正是"此后每次取消都打在别的订单上"的成因。
     */
    @Test
    fun FULL_之后再_INCREMENTAL_两次落地都逐字节一致() = runBlocking {
        peer.addB(trickyName = false)
        assertSyncOk(B_FILE_ID)
        val afterFull = mirrorBytes(B_FILE_ID)
        assertSameBytes("FULL 之后", serverBytes(bServerRel()), afterFull)

        // 第二次同步必须是 INCREMENTAL：游标已经在手上了，服务端不会再给 FULL。
        val late = peer.addB(trickyName = true)
        val outcome = engine.sync(session.storeKey, B_FILE_ID)
        assertTrue(
            "第二轮应当走增量，实际 $outcome。若是 Rebuilt，说明服务端把订单流裁剪了" +
                "（`stream:order` 的保留窗口容不下这次的 lastStreamId）—— 那是环境问题，不是镜像问题。\n审计：$audits",
            outcome is OrderFileSyncEngine.Outcome.Applied,
        )
        assertEquals("增量没应用到那笔新订单", 1, (outcome as OrderFileSyncEngine.Outcome.Applied).applied)

        val afterInc = mirrorBytes(B_FILE_ID)
        assertTrue("增量落地后镜像没变长", afterInc.size > afterFull.size)
        // 内容区的前缀必须原样保留：追加不许改写已有字节。
        // ⚠ 从 HEADER_LEN 起比 —— 版本头**本来就要变**（版本 +1），把它算进来会得到
        // "增量改写了前面的字节"这个完全相反的结论。
        assertArrayEquals(
            "增量落地改写了已有的订单字节",
            afterFull.copyOfRange(ByteOps.HEADER_LEN, afterFull.size),
            afterInc.copyOfRange(ByteOps.HEADER_LEN, afterFull.size),
        )
        assertEquals(
            "版本号没推进",
            ByteOps.decodeHeader(afterFull.copyOf(ByteOps.HEADER_LEN)) + 1,
            ByteOps.decodeHeader(afterInc.copyOf(ByteOps.HEADER_LEN)),
        )
        assertTrue(
            "新订单（uuid=${late.uuid}）没落到镜像尾部",
            String(afterInc, UTF_8).contains("\"order_uuid\":\"${late.uuid}\""),
        )
        assertSameBytes("INCREMENTAL 之后", serverBytes(bServerRel()), afterInc)
    }

    // ==================== 服务端原样保留字节（方案 §7.3(a)）====================

    /**
     * 证明方案 §1.2 的结论：**items 带中文 `name` 不需要改服务端**。
     *
     * 服务端 `OrderFileService:256` 追加的是 `(rawJson + "\n").getBytes(UTF_8)` —— 客户端发来的
     * 原始字节；`extractItems(:1064)` 只取 `barcode` 与 `quantity`，`name` 从未被触碰；
     * 偏移由 Jackson 在 **UTF-8 字节数组**上解析（`:974`/`:981`）再经 `charToByteOffset(:1157)`
     * 换算回来。
     *
     * 这三件事都只有真连一次才知道，所以写在这里而不是靠读代码下结论。
     */
    @Test
    fun 服务端原样保留中文引号反斜杠_且偏移不被_UTF8_多字节带偏() = runBlocking {
        val o = peer.addB(trickyName = true)
        val disk = serverBytes(bServerRel())
        val uuidOffset = need(o.res.uuidOffset, "uuidOffset")
        val statusOffset = need(o.res.statusOffset, "statusOffset")

        // (1) 那一行的字节 == 上传字节 + 一个 LF，逐字节，不是"解析后相等"
        val lineStart = (uuidOffset - o.uuidValueIndex.toLong()).toInt()
        assertTrue("行首算出来在版本头里面：$lineStart", lineStart >= ByteOps.HEADER_LEN)
        val expect = o.payload + ByteOps.LF
        assertArrayEquals(
            "服务端没有原样落盘。上传=${String(o.payload, UTF_8)}",
            expect,
            disk.copyOfRange(lineStart, lineStart + expect.size),
        )

        // (2) 用响应里的偏移去读，读出来的必须正好是 uuid 与 status
        assertEquals(o.uuid, String(disk, uuidOffset.toInt(), 36, UTF_8))
        assertEquals('1'.code.toByte(), disk[statusOffset.toInt()])

        // (3) ⚠ 这一条保证上面两条不是白写的：中文名必须让**字符下标与字节下标分岔**。
        // 不分岔的话，用字符下标算偏移也能通过，而 `test_all.py` 的 E2/E10 正是踩了这个坑
        // （Python 的 str 下标是码点不是字节，方案 R6）。
        val text = String(o.payload, UTF_8)
        val charStatusIdx = text.indexOf("\"status\":") + "\"status\":".length
        assertNotEquals(
            "payload 里的中文没让字符下标与字节下标分岔，这条用例证明不了任何事",
            o.statusValueIndex.toLong(),
            charStatusIdx.toLong(),
        )
        assertEquals("服务端给的 statusOffset 与我们独立算的字节下标不符", o.statusValueIndex.toLong(), statusOffset - lineStart)

        // (4) 引号与反斜杠真的被服务端按 JSON 解析了（不是当成裸字节存进去读不出来）
        val parsed = gson.fromJson(text, JsonObject::class.java)
        assertEquals(
            "可乐\"大瓶\"\\促销",
            parsed.getAsJsonObject("content").getAsJsonArray("items")[0].asJsonObject.get("name").asString,
        )
    }

    /**
     * 用 `/add` 返回的 `statusOffset` 发 CANCEL，服务端那个字节必须真的变成 `0`、版本 +1。
     *
     * 偏移错一个字节时这一步**不会报错**（等长覆写打在任何位置都返回 200），
     * 所以断言必须落在磁盘字节上，不能落在 HTTP 状态码上。
     */
    @Test
    fun 用响应里的_statusOffset_发取消_服务端那个字节真的变了() = runBlocking {
        val o = peer.addB(trickyName = true)
        val statusOffset = need(o.res.statusOffset, "statusOffset")
        val before = serverBytes(bServerRel())
        val versionBefore = ByteOps.decodeHeader(before.copyOf(ByteOps.HEADER_LEN))

        peer.cancel(o)

        val after = serverBytes(bServerRel())
        assertEquals("取消不该改变文件长度", before.size, after.size)
        assertEquals('1'.code.toByte(), before[statusOffset.toInt()])
        assertEquals("status 字节没变成 0", '0'.code.toByte(), after[statusOffset.toInt()])
        assertEquals(
            "版本号没推进",
            versionBefore + 1,
            ByteOps.decodeHeader(after.copyOf(ByteOps.HEADER_LEN)),
        )

        // 除了那一个字节，内容区其余部分必须一模一样 —— 等长覆写打歪了会改到别处。
        // ⚠ 从 HEADER_LEN 起比：版本头**本来就要变**（版本 +1），把它算进来会得到两个差异。
        val diffs = (ByteOps.HEADER_LEN until before.size).filter { before[it] != after[it] }
        assertEquals("改动落在了这些偏移上：$diffs", listOf(statusOffset.toInt()), diffs)
    }

    // ==================== 对端驱动器 ====================

    /** 一笔已上传订单的凭据。偏移全部取自服务端响应（权威值）。 */
    private class Added(
        val uuid: String,
        val payload: ByteArray,
        val uuidValueIndex: Int,
        val statusValueIndex: Int,
        val remarkValueIndex: Int?,
        val res: OrderAddResultDto,
    )

    /**
     * "对端"。用**手写 JSON** 直接打 HTTP，绕开 `StableOrderJson` 与整个引擎 ——
     * 见 [方向一_对端下单取消改备注_同步后与服务器磁盘逐字节一致] 的注释。
     */
    private inner class Peer {

        fun addB(trickyName: Boolean): Added {
            val uuid = UUID.randomUUID().toString()
            val name = if (trickyName) TRICKY_NAME_JSON else "农夫山泉550ml"
            // 手写、单行、零空白。字段顺序与 StableOrderJson 一致（order_uuid 必须第一，
            // 否则 normalizeUuidOffset(:1122) 的 indexOf 兜底会算错）。
            return send(
                "B", MONTH, uuid,
                "{\"order_uuid\":\"$uuid\",\"writer\":\"$OWNER\",\"content\":{\"items\":[" +
                    "{\"barcode\":\"6901234567890\",\"name\":\"$name\",\"quantity\":14,\"amount\":\"52.00\"}" +
                    "],\"total\":\"52.00\",\"ts\":$clock},\"status\":1}",
            )
        }

        fun addA(remarkText: String): Added {
            val uuid = UUID.randomUUID().toString()
            val remark = ByteOps.padRemark(remarkText)
            assertEquals(ByteOps.REMARK_WIDTH, ByteOps.utf8Len(remark))
            return send(
                "A", null, uuid,
                "{\"order_uuid\":\"$uuid\",\"writer\":\"$OWNER\",\"content\":{\"items\":[" +
                    "{\"barcode\":\"6901234567890\",\"productName\":\"可口可乐330ml\",\"quantity\":40,\"amount\":\"120.00\"}" +
                    "],\"total\":\"120.00\",\"ts\":$clock},\"status\":1,\"grp\":\"a3f9c21b\",\"remark\":\"$remark\"}",
            )
        }

        private fun send(type: String, month: Int?, uuid: String, json: String): Added {
            val payload = ByteOps.utf8(json)
            val res = runBlocking { api.addOrder(type, YEAR, month, json) }
            assertNotNull("服务端没回 uuidOffset（$type/$YEAR/$month）", res.uuidOffset)
            assertNotNull("服务端没回 statusOffset", res.statusOffset)
            return Added(
                uuid = uuid,
                payload = payload,
                uuidValueIndex = byteIndexAfter(payload, "\"order_uuid\":\""),
                statusValueIndex = byteIndexAfter(payload, "\"status\":"),
                remarkValueIndex = optionalByteIndex(payload, "\"remark\":\""),
                res = res,
            )
        }

        /** 取消：`patchLength=1, patchContent="0"`，打在服务端给的 `statusOffset` 上。 */
        fun cancel(o: Added) {
            val isA = o.res.fileId?.startsWith("A") == true
            runBlocking {
                api.patchOrder(
                    OrderPatchRequestDto(
                        type = if (isA) "A" else "B",
                        year = YEAR,
                        month = if (isA) null else MONTH,
                        action = OrderPatchRequestDto.ACTION_CANCEL,
                        uuidOffset = need(o.res.uuidOffset, "uuidOffset"),
                        uuid = o.uuid,
                        patchOffset = need(o.res.statusOffset, "statusOffset"),
                        patchLength = 1,
                        patchContent = "0",
                    )
                )
            }
        }

        /**
         * 改 A 类备注。**等长**覆写 150 字节。
         *
         * `remarkOffset` 不在 `/add` 响应里，只能按 README §2.5 从自己上传的 payload 推：
         * `remarkOffset = uuidOffset + (r − u)`。推错一个字节，这次覆写就会改到别的字段上，
         * 而且服务端返回 200。所以推完立刻读磁盘核对。
         */
        fun rewriteRemark(o: Added, newText: String) {
            val r = o.remarkValueIndex ?: throw AssertionError("这笔订单没有 remark，无从改起")
            val value = ByteOps.padRemark(newText)
            assertEquals(ByteOps.REMARK_WIDTH, ByteOps.utf8Len(value))
            val remarkOffset = need(o.res.uuidOffset, "uuidOffset") + (r - o.uuidValueIndex).toLong()
            runBlocking {
                api.patchOrder(
                    OrderPatchRequestDto(
                        type = "A", year = YEAR, month = null,
                        action = OrderPatchRequestDto.ACTION_CANCEL,
                        uuidOffset = need(o.res.uuidOffset, "uuidOffset"),
                        uuid = o.uuid,
                        patchOffset = remarkOffset,
                        patchLength = ByteOps.REMARK_WIDTH,
                        patchContent = value,
                    )
                )
            }
            assertRemarkLanded(
                disk = serverBytes(aServerRel()),
                uuid = o.uuid,
                uuidOffset = need(o.res.uuidOffset, "uuidOffset"),
                remarkOffset = remarkOffset,
                paddedValue = value,
                newText = newText,
            )
        }
    }

    /**
     * 改完 A 类备注之后，从**服务端磁盘**把那一行重新解析出来核对。
     *
     * ## ⚠ 为什么不能只"读回 remarkOffset 处的 150 字节"
     *
     * 那是**自证**：等长覆写打在哪个偏移都会成功，读回同一个偏移自然就是你刚写的字节。
     * 偏移推早 14 字节时，写进去的备注会吃掉 `"grp"` 的尾巴与整个 `,"remark":"` 键名，
     * 而"读回 remarkOffset"照样通过 —— 文件坏了，检查却绿了。
     *
     * 真正能抓住它的是**结构性**判据：那一行必须仍然是合法 JSON，`remark` 键必须还在、
     * 值必须是新文本，而**排在 remark 前面的字段一个都不许变**。
     */
    private fun assertRemarkLanded(
        disk: ByteArray,
        uuid: String,
        uuidOffset: Long,
        remarkOffset: Long,
        paddedValue: String,
        newText: String,
    ) {
        assertEquals(
            "备注字节没写进 remarkOffset=$remarkOffset",
            paddedValue,
            String(disk, remarkOffset.toInt(), ByteOps.REMARK_WIDTH, UTF_8),
        )
        // ⚠ 行定位只用服务端给的 `uuidOffset`（权威值），不碰任何本地推算的下标 ——
        // 否则"生产代码把 remarkValueByteIndex 算错了"这个正是我们要抓的 bug，会把定位一起带偏。
        val line = lineAt(disk, uuidOffset)
        assertEquals("order_uuid 被覆写吃掉了", uuid, line.get("order_uuid")?.asString)
        assertEquals("status 被覆写吃掉了", 1, line.get("status")?.asInt)
        assertEquals("grp 被覆写吃掉了（remarkOffset 推早了的典型症状）", "a3f9c21b", line.get("grp")?.asString)
        val remark = line.get("remark")
        assertNotNull("remark 键不见了 —— 覆写打在了键名上", remark)
        assertEquals("备注值不对", paddedValue, remark!!.asString)
        assertTrue("备注里没有新文本：${remark.asString}", remark.asString.startsWith(newText))
    }

    /** 按 `uuidOffset` 在磁盘字节里定位所在行并解析。行边界按 LF 找，不用任何生产扫描器。 */
    private fun lineAt(disk: ByteArray, uuidOffset: Long): JsonObject {
        var s = uuidOffset.toInt()
        // ⚠ 下界是 HEADER_LEN（4），**不是 0**：文件第一行的前面没有 LF，退到 0 会把
        // 4 字节二进制版本头圈进文本。Gson 把 NUL 当空白跳过，于是不报 malformed，
        // 而是报一句完全指不到真因的 "Expected JsonObject but was JsonPrimitive" ——
        // 实测（2026-10-25，JDK23 + Gson 2.10.1）与这个坑一模一样，曾把方向二
        // 坑成"测试莫名其妙失败、生产代码却没问题"。
        // 顺带：版本头第 4 字节可能是 0x0A（版本号 10），所以判据用 s > HEADER_LEN
        // 而不是 disk[HEADER_LEN - 1] != LF。
        while (s > ByteOps.HEADER_LEN && disk[s - 1] != ByteOps.LF) s--
        var e = uuidOffset.toInt()
        while (e < disk.size && disk[e] != ByteOps.LF) e++
        return gson.fromJson(String(disk, s, e - s, UTF_8), JsonObject::class.java)
    }

    private val peer get() = Peer()

    /**
     * JSON 里的 `name` 值原文。解码后是 `可乐"大瓶"\促销`。
     *
     * 三个字节层面的风险点各占一个：多字节 UTF-8（中文）、需要转义的 `"`、需要转义的 `\`。
     */
    private val TRICKY_NAME_JSON = "可乐\\\"大瓶\\\"\\\\促销"

    // ==================== 断言工具 ====================

    private fun <T> need(v: T?, what: String): T = assertNotNull("服务端没回 $what", v).let { v!! }

    private fun assertSyncOk(fileId: String) {
        val outcome = runBlocking { engine.sync(session.storeKey, fileId) }
        assertTrue(
            "同步 $fileId 失败：$outcome\n审计：$audits",
            outcome is OrderFileSyncEngine.Outcome.UpToDate ||
                outcome is OrderFileSyncEngine.Outcome.Applied ||
                outcome is OrderFileSyncEngine.Outcome.Rebuilt,
        )
    }

    private fun assertVerifierIdentical(fileId: String) {
        val v = runBlocking { verifier.verify(session.storeKey, fileId) }
        assertTrue("MirrorVerifier 判 $fileId = $v\n审计：$audits", v is MirrorVerifier.Verdict.Identical)
    }

    private fun mirrorBytes(fileId: String): ByteArray {
        val m = locator.resolve(session.storeKey, fileId, SyncState.LIVE).file
        assertTrue("本地镜像不存在：${m.path}", m.exists())
        return m.readBytes()
    }

    private fun bServerRel() = "kehu/$OWNER/$YEAR/%02d.txt".format(MONTH)
    private fun aServerRel() = "kehu/$OWNER/$YEAR.txt"

    private fun serverBytes(rel: String): ByteArray {
        val f = File(SERVER_DATA, rel)
        assertTrue("服务端磁盘文件不存在：${f.path}（用 -Ds1.data= 指定 service1 的 data/orders）", f.exists())
        return f.readBytes()
    }

    /**
     * 逐字节比对，**含前 4 字节版本头**（方案 §7.2 明确要求）。
     *
     * 失败时打出第一个差异偏移与两边的十六进制窗口 —— 只说"数组不相等"的话，
     * 面对一个几 KB 的文件根本无从下手。
     */
    private fun assertSameBytes(label: String, server: ByteArray, local: ByteArray) {
        if (server.contentEquals(local)) return
        val n = minOf(server.size, local.size)
        var i = 0
        while (i < n && server[i] == local[i]) i++
        throw AssertionError(
            "$label 逐字节不一致：" +
                "服务端 ${server.size}B / 本地 ${local.size}B，首个差异在偏移 $i\n" +
                "  服务端 ${hex(server, i)}\n" +
                "  本地   ${hex(local, i)}\n" +
                "  版本头 服务端 ${headerOf(server)} / 本地 ${headerOf(local)}\n" +
                "  审计：$audits"
        )
    }

    private fun headerOf(b: ByteArray) =
        if (b.size >= ByteOps.HEADER_LEN) ByteOps.decodeHeader(b.copyOf(ByteOps.HEADER_LEN)).toString() else "不足 4 字节"

    private fun hex(bytes: ByteArray, at: Int): String {
        val from = maxOf(0, at - 8)
        val to = minOf(bytes.size, at + 8)
        val sb = StringBuilder()
        for (i in from until to) {
            if (i == at) sb.append('[')
            sb.append("%02x".format(bytes[i]))
            if (i == at) sb.append(']')
            sb.append(' ')
        }
        return sb.toString().trimEnd()
    }

    private fun patch(type: String, month: Int?, uuid: String, uuidOffset: Long, patchOffset: Long, patchContent: String) {
        runBlocking {
            api.patchOrder(
                OrderPatchRequestDto(
                    type = type, year = YEAR, month = month,
                    action = OrderPatchRequestDto.ACTION_CANCEL,
                    uuidOffset = uuidOffset, uuid = uuid,
                    patchOffset = patchOffset,
                    // ⚠ 服务端要的是 **UTF-8 字节长度**，不是字符数。中文备注下两者差三倍。
                    patchLength = ByteOps.utf8Len(patchContent),
                    patchContent = patchContent,
                )
            )
        }
    }

    // ==================== 字节下标（独立实现，不用生产扫描器）====================

    /**
     * `key` 之后第一个字节的下标 —— 也就是"值"的起点。**已经把 key 的长度加进去了**，
     * 调用方不要再加一次（先前就在这里把 `uuidValueIndex` 算成了 29 而不是 15，
     * 于是行首推到了上一条订单里去）。
     */
    private fun byteIndexAfter(hay: ByteArray, key: String): Int =
        optionalByteIndex(hay, key) ?: throw AssertionError("payload 里找不到 $key")

    private fun optionalByteIndex(hay: ByteArray, key: String): Int? {
        val needle = ByteOps.utf8(key)
        if (needle.isEmpty() || hay.size < needle.size) return null
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i + needle.size
        }
        return null
    }

    // ==================== 供给链 ====================

    private fun caPem(): ByteArray = resolve(
        "src/main/res/raw/s1_ca.pem",
        "app/src/main/res/raw/s1_ca.pem",
        "../tools/pki/ca.pem",
    ).readBytes()

    /** 与 `HttpClientsTest.resolve` 同一套：Gradle 的工作目录是模块目录，Studio 有时是项目根。 */
    private fun resolve(vararg rel: String): File {
        for (p in rel) {
            val f = File(p)
            if (f.exists()) return f
        }
        throw IllegalStateException("找不到 ${rel.toList()} 中的任何一个")
    }

    /**
     * 探活。**用 GET**：限速桶在控制器方法体内，而 GET 打一个 POST-only 的映射会得到
     * 405（压根不进控制器），所以不吃登录令牌。POST 一个假用户名会吃掉一个 —— 而桶是
     * 严格 12 秒一个，探活四次就把真正的登录挤成 429 了。
     */
    private fun reachable(client: OkHttpClient): Boolean = runCatching {
        raw(client, "GET", "/api/auth/login", null).first
    }.isSuccess

    private fun raw(
        client: OkHttpClient,
        method: String,
        path: String,
        body: String?,
        token: String? = null,
    ): Pair<Int, String> {
        val rb = Request.Builder().url(BASE + path)
        // ⚠ OkHttp 的 `method("POST", null)` 直接抛 IllegalArgumentException。
        // 空请求体也要给一个长度为 0 的 body。
        val reqBody = when {
            body != null -> body.toRequestBody(JSON)
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(JSON)
        }
        rb.method(method, reqBody)
        token?.let { rb.header("Authorization", "Bearer $it") }
        client.newCall(rb.build()).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun provision(client: OkHttpClient): Session {
        val anon = Service1Api(client, { BASE }, { null })

        val attempt = runCatching { runBlocking { anon.login(OWNER, PWD, 0, 0) } }
        val login = when (val e = attempt.exceptionOrNull()) {
            null -> attempt.getOrThrow()
            is ApiError.RateLimited -> {
                sleepRateLimit()
                runBlocking { anon.login(OWNER, PWD, 0, 0) }
            }
            is ApiError.Unauthorized -> {
                registerAndApprove(client, anon)
                // 审批刚写完 Redis，登录桶也要留间隔（严格 12 秒一个令牌）
                sleepRateLimit()
                runBlocking { anon.login(OWNER, PWD, 0, 0) }
            }
            else -> throw e!!
        }

        val token = requireNotNull(login.token) { "登录响应里没有 token" }
        val code = requireNotNull(login.merchantCode) { "登录响应里没有 merchantCode（storeKey 从哪来？）" }
        // 缺权限会变成 403 而不是字节差异 —— 测试会"失败"，却把人指向完全错误的方向。
        val missing = setOf("ORDER_A", "ORDER_B", "SYNC_SUB") - login.permSet()
        assertTrue("$OWNER 缺权限 $missing（实际 ${login.permSet()}），验收门跑不了", missing.isEmpty())
        return Session(Service1Api(client, { BASE }, { token }), code)
    }

    private fun registerAndApprove(client: OkHttpClient, anon: Service1Api) {
        runCatching { runBlocking { anon.register(OWNER, PWD, "逐字节验收门专用") } }
            .onFailure {
                // 409 = 名字已占用（可能已审批通过，也可能还挂在待审批里）。两种都继续，
                // 由审批的结果决定；已经建好的账户会让审批报"用户名已存在于商家表中"。
                if (it !is ApiError.Conflict) throw it
            }

        val (s2status, s2body) = raw(client, "POST", "/api/service2/login", """{"credential":"$S2_CRED"}""")
        assertEquals(
            "service2 登录失败（$s2status）。默认凭证可能已被轮换出 previous 槽 —— " +
                "Redis 的 security:service2:cred:current 只存 SHA-256，反推不出原文。",
            200, s2status,
        )
        val s2token = gson.fromJson(s2body, JsonObject::class.java)
            .getAsJsonObject("data").get("token").asString

        val (apStatus, apBody) = raw(client, "POST", "/api/service2/approve-registration/$OWNER", null, s2token)
        assertTrue("审批 $OWNER 失败：$apStatus $apBody", apStatus == 200 || apBody.contains("已存在"))
    }

    private fun sleepRateLimit() {
        try {
            TimeUnit.MILLISECONDS.sleep(RATE_LIMIT_WAIT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
