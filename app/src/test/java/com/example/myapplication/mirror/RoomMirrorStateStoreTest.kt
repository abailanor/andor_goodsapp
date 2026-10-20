package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.sync.StatsProjection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone

/**
 * `RoomMirrorStateStore` 的编排逻辑：**幂等**与**统计的加减对称**。
 *
 * 这个类里的每一条断言都对应一种"算错了但不会报错"的情形：
 *  - 贡献加了两次 → 营业额偏大；
 *  - 取消回退了两次 → 营业额偏小甚至变负；
 *  - `alreadyApplied` 时跳过 Room → 那一笔永久丢失；
 *  - FULL 重建时没清掉旧贡献 → 每做一次全量就翻一倍。
 *
 * SQL 语义本身（`IGNORE` 真的返回 -1、`SUM()` 没行时真的是 NULL）由
 * androidTest 的 `OrderFileDaoTest` 对着真 Room 覆盖。
 */
class RoomMirrorStateStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeOrderFileDao()
    private val audits = mutableListOf<String>()
    private val utc = TimeZone.getTimeZone("UTC")
    private var clock = 1_000L

    private val store = RoomMirrorStateStore(
        dao = dao,
        // 单线程测试：直接跑，不需要真事务。生产实现是 db.withTransaction（见 RoomMirrorStateStore.of）
        inTransaction = { body -> body() },
        timeZone = { utc },
        now = { clock },
        audit = { audits += it },
    )

    private val storeKey = "00002"
    private val fileId = "B:alice:2026-09"
    private val scope = StatsProjection.fileScope(fileId)

    private val uuid1 = "11111111-2222-3333-4444-555555555555"
    private val uuid2 = "22222222-3333-4444-5555-666666666666"

    /** 2026-09-15T00:00:00Z。 */
    private val sept15 = 1789430400000L
    private val dimKey = "2026-09-15|w:alice"

    // ==================== 构造测试数据 ====================

    private fun order(
        uuid: String = uuid1,
        writer: String = "alice",
        status: Int = 1,
        total: String = "52.00",
        ts: Long = sept15,
        barcode: String = "6901234567890",
        name: String = "可口可乐330ml",
        qty: Int = 14,
        remark: String? = null,
    ): String = buildString {
        // ⚠ 分三段拼而不是写成一个大 raw string：`"""…{"items":""""` 结尾那四个引号里
        // 有一个属于内容，会把 items 数组变成一个**字符串** —— Gson 解不开，
        // 于是每一条 ADD 都走"解析失败"分支，而这个失败看起来像 store 的 bug。
        append("""{"order_uuid":"$uuid","writer":"$writer","content":{"items":""")
        append("""[{"barcode":"$barcode","name":"$name","quantity":$qty,"amount":"$total"}]""")
        append(""","total":"$total","ts":$ts},"status":$status""")
        if (remark != null) append(""","remark":"$remark"""")
        append("}")
    }

    /** 从原文里把 uuid 抠出来。让 `addedRecord` 不可能与 `order(uuid = …)` 对不上。 */
    private fun uuidIn(json: String): String =
        json.substringAfter("\"order_uuid\":\"").substringBefore('"')

    /**
     * 独立地算 uuid 值的字节下标（字符下标 + `utf8Len`），
     * **不用** `OrderJsonScanner` —— 用它的话这条断言就只是把自己的输出抄回来。
     */
    private fun uuidIndexOf(json: String, uuid: String): Int =
        ByteOps.utf8Len(json.substring(0, json.indexOf(uuid)))

    private fun statusIndexOf(json: String): Int =
        ByteOps.utf8Len(json.substring(0, json.indexOf("\"status\":") + "\"status\":".length))

    /**
     * 模拟 `/add` 响应或流消息给出的那条 ADD。
     *
     * 偏移在这里**手工推算**（字符下标 → `utf8Len`），不用 `OrderJsonScanner`：
     * 生产路径上它们来自服务端，与被测代码无关，所以测试自己算一遍才有意义。
     *
     * @param uuidOffset @param statusOffset 原文里根本没有那两个字段时（解析失败用例）必须显式给 ——
     *        服务端是先解析成功才返回偏移的，而这一用例模拟的是"偏移有效、原文却解不开"，
     *        两者并不矛盾：别的客户端写的订单可以带我们读不懂的 `content`。
     */
    private fun addedRecord(
        json: String,
        uuid: String = uuidIn(json),
        version: Int = 1,
        lineStart: Long = 4L,
        fileId: String = this.fileId,
        alreadyApplied: Boolean = false,
        uuidOffset: Long = lineStart + uuidIndexOf(json, uuid),
        statusOffset: Long = lineStart + statusIndexOf(json),
    ) = MirrorCommitRecord(
        storeKey = storeKey,
        fileId = fileId,
        state = MirrorState(version, "$version-0", 0, lineStart + ByteOps.utf8Len(json) + 1, null, SyncState.LIVE),
        order = OrderDelta.Added(
            orderUuid = uuid,
            lineStart = lineStart,
            uuidOffset = uuidOffset,
            statusOffset = statusOffset,
            // 服务端不返回它，客户端按 README §2.5 的 `uuidOffset + (r - u)` 推算
            remarkOffset = if ("\"remark\":\"" in json) {
                lineStart + uuidIndexOf(json, uuid) + remarkDelta(json, uuid)
            } else {
                null
            },
            payload = ByteOps.utf8(json),
            appliedFileVersion = version,
        ),
        alreadyApplied = alreadyApplied,
    )

    private fun patchedRecord(
        patchOffset: Long,
        kind: PatchKind,
        orderUuid: String? = uuid1,
        newStatusDigit: Int? = null,
        newRemark: String? = null,
        cancelAppliedVersion: Int? = null,
        version: Int = 2,
        fileId: String = this.fileId,
    ) = MirrorCommitRecord(
        storeKey = storeKey,
        fileId = fileId,
        state = MirrorState(version, "$version-0", 0, 0, null, SyncState.LIVE),
        order = OrderDelta.Patched(
            orderUuid = orderUuid,
            patchOffset = patchOffset,
            patchKind = kind,
            newStatusDigit = newStatusDigit,
            newRemark = newRemark,
            cancelAppliedVersion = cancelAppliedVersion,
        ),
        alreadyApplied = false,
    )

    private fun amount(metric: String = StatsProjection.METRIC_AMOUNT, key: String = dimKey) =
        dao.contribValue(storeKey, scope, key, metric)

    private fun mirrorWith(vararg jsons: String): Mirror {
        val m = Mirror(File(tmp.root, "B_2026-09.mirror"))
        m.ensureCreated()
        for (j in jsons) m.appendLine(j)
        return m
    }

    private fun fullState(version: Int, mirror: Mirror) = MirrorState(
        version = version,
        lastStreamId = "$version-0",
        checksum = mirror.checksumContent(),
        mirrorBytes = mirror.length(),
        firstSyncedAt = null,
        syncState = SyncState.LIVE,
    )

    // ==================== ADD ====================

    @Test
    fun `ADD_建索引行_明细行_与三条贡献`() = runBlocking {
        store.commit(addedRecord(order()))

        val row = dao.order(storeKey, fileId, uuid1)
        assertNotNull(row)
        assertEquals("alice", row!!.writer)
        assertEquals(1, row.status)
        assertEquals(5200L, row.totalCents)
        assertEquals(1, row.itemCount)
        assertEquals(sept15, row.orderTs)
        assertNull(row.cancelAppliedVersion)
        // 偏移来自服务端，原样落库
        assertEquals(4L + uuidIndexOf(order(), uuid1), row.uuidOffset)
        assertEquals(4L + statusIndexOf(order()), row.statusOffset)

        assertEquals(1, dao.itemCount(storeKey, fileId, uuid1))
        assertEquals(14, dao.piecesOf(storeKey, fileId, uuid1))

        assertEquals(5200L, amount())
        assertEquals(1L, amount(StatsProjection.METRIC_ORDERS))
        assertEquals(14L, amount(StatsProjection.METRIC_PIECES))
    }

    @Test
    fun `ADD_重复投递_贡献只加一次`() = runBlocking {
        // SSE 回声 + HTTP 响应会把同一条消息送来两次
        store.commit(addedRecord(order()))
        store.commit(addedRecord(order()))

        assertEquals(5200L, amount())
        assertEquals(1L, amount(StatsProjection.METRIC_ORDERS))
        assertEquals(14L, amount(StatsProjection.METRIC_PIECES))
        assertEquals(1, dao.itemCount(storeKey, fileId, uuid1))
        assertTrue(audits.any { "已存在" in it })
    }

    @Test
    fun `alreadyApplied_为_true_时_Room_仍然补齐`() = runBlocking {
        // 崩溃恢复：文件已 fsync、Room 事务还没跑就被杀了。重启后重发同一条消息，
        // MirrorCommitter 会判 ALREADY_APPLIED 并带上 alreadyApplied=true —— 这时**必须**建索引。
        // 跳过就会让 order_index 与统计永久少一笔，而且不报错。
        store.commit(addedRecord(order(), alreadyApplied = true))

        assertNotNull(dao.order(storeKey, fileId, uuid1))
        assertEquals(5200L, amount())
        assertEquals(1L, amount(StatsProjection.METRIC_ORDERS))
    }

    @Test
    fun `幂等判据是_索引行插进去了没有_不是_alreadyApplied_标记`() = runBlocking {
        // 两个方向的组合都必须对：
        //  - 已有行 + alreadyApplied=false（双投递）→ 不加
        //  - 没有行 + alreadyApplied=true （崩溃重发）→ 加
        store.commit(addedRecord(order(), alreadyApplied = true))
        store.commit(addedRecord(order(), alreadyApplied = false))
        assertEquals("已有行时不管标记是什么都不能再加一次", 5200L, amount())
    }

    @Test
    fun `A_类订单建索引但不产生营收贡献`() = runBlocking {
        val aFile = "A:alice:2026"
        store.commit(addedRecord(order(), fileId = aFile))

        assertNotNull(dao.order(storeKey, aFile, uuid1))
        assertEquals(1, dao.itemCount(storeKey, aFile, uuid1))
        // 向丽华金龙进货不是本店的收入
        assertEquals(0, dao.contrib.values.count { it.scope == StatsProjection.fileScope(aFile) })
    }

    @Test
    fun `解析失败的_ADD_仍然建索引行_但不计入统计`() = runBlocking {
        // 偏移是服务端给的、一定有效。少了索引行这一单在历史页看不见，
        // 而且它的 statusOffset 无人记录 → 用户取消不了它。
        val garbage = "{ not json at all"
        store.commit(
            addedRecord(
                garbage,
                uuid = uuid1,
                uuidOffset = 4L,
                statusOffset = 4L + ByteOps.utf8Len(garbage),
            )
        )

        val row = dao.order(storeKey, fileId, uuid1)
        assertNotNull("偏移可用，所以索引行必须建出来", row)
        assertEquals(4L + ByteOps.utf8Len(garbage), row!!.statusOffset)
        assertEquals(0, dao.itemCount(storeKey, fileId, uuid1))
        assertEquals("金额一概不猜", 0, dao.contrib.size)
        assertTrue(audits.any { "解析失败" in it })
    }

    @Test
    fun `items_被整体丢弃时记审计`() = runBlocking {
        // quantity 不是整数 → 与服务端 extractItems 的宽容路径同形，items 清空
        val bad = """{"order_uuid":"$uuid1","writer":"alice","content":{"items":[{"barcode":"690","quantity":3.0}],"total":"52.00","ts":$sept15},"status":1}"""
        store.commit(addedRecord(bad))
        assertEquals(0, dao.itemCount(storeKey, fileId, uuid1))
        assertTrue(audits.any { "items 被整体丢弃" in it })
    }

    @Test
    fun `游标总是被写_即使_order_为_null`() = runBlocking {
        store.commit(
            MirrorCommitRecord(
                storeKey = storeKey,
                fileId = fileId,
                state = MirrorState(9, "9-0", 123, 456L, 777L, SyncState.LIVE),
                order = null,
                alreadyApplied = false,
            )
        )
        val fs = dao.fileState(storeKey, fileId)
        assertNotNull(fs)
        assertEquals(9, fs!!.version)
        assertEquals("9-0", fs.lastStreamId)
        assertEquals(123, fs.checksum)
        assertEquals(456L, fs.mirrorBytes)
        assertEquals(777L, fs.firstSyncedAt)
        assertEquals("LIVE", fs.syncState)
        assertEquals("B", fs.fileType)
        assertEquals(2026, fs.year)
        assertEquals(9, fs.month)
        assertEquals(clock, fs.lastSyncAttemptAt)
    }

    // ==================== PATCH：取消 ====================

    @Test
    fun `取消_1到0_把贡献减回去_净值归零`() = runBlocking {
        val json = order()
        store.commit(addedRecord(json))
        assertEquals(5200L, amount())

        store.commit(
            patchedRecord(
                patchOffset = 4L + statusIndexOf(json),
                kind = PatchKind.STATUS,
                newStatusDigit = 0,
                cancelAppliedVersion = 2,
            )
        )

        assertEquals(0L, amount())
        assertEquals(0L, amount(StatsProjection.METRIC_ORDERS))
        assertEquals(0L, amount(StatsProjection.METRIC_PIECES))
        val row = dao.order(storeKey, fileId, uuid1)!!
        assertEquals(0, row.status)
        assertEquals(2, row.cancelAppliedVersion)
    }

    @Test
    fun `取消_重复投递_不会减两次`() = runBlocking {
        val json = order()
        store.commit(addedRecord(json))
        val p = patchedRecord(
            patchOffset = 4L + statusIndexOf(json),
            kind = PatchKind.STATUS,
            newStatusDigit = 0,
            cancelAppliedVersion = 2,
        )
        store.commit(p)
        store.commit(p)
        store.commit(p)

        assertEquals("减两次会变成 -5200，营业额凭空少一笔", 0L, amount())
        assertEquals(0L, amount(StatsProjection.METRIC_ORDERS))
    }

    @Test
    fun `回退用的是_原订单_的字段_不是取消那一刻的`() = runBlocking {
        // 两笔同一天同一个人的订单，金额不同。取消第一笔必须只减掉它自己的 52.00。
        store.commit(addedRecord(order(total = "52.00"), version = 1))
        store.commit(addedRecord(order(uuid = uuid2, total = "30.00", qty = 3), version = 2))
        assertEquals(8200L, amount())

        store.commit(
            patchedRecord(
                // orderUuid 已知时偏移不参与定位；真正的判据是 firstCancel 与索引行里的字段
                patchOffset = 4L + statusIndexOf(order(total = "52.00")),
                kind = PatchKind.STATUS,
                orderUuid = uuid1,
                newStatusDigit = 0,
                cancelAppliedVersion = 3,
            )
        )
        assertEquals(3000L, amount())
        assertEquals(1L, amount(StatsProjection.METRIC_ORDERS))
        assertEquals(3L, amount(StatsProjection.METRIC_PIECES))
    }

    @Test
    fun `总部标完成_1到2_不回退统计`() = runBlocking {
        // adminProcessOrder 打的补丁与取消同形（1 字节落在 statusOffset），
        // 但那一单货确实卖出去了，钱不能退。
        val json = order()
        store.commit(addedRecord(json))
        store.commit(
            patchedRecord(
                patchOffset = 4L + statusIndexOf(json),
                kind = PatchKind.STATUS,
                newStatusDigit = 2,
                cancelAppliedVersion = null,
            )
        )
        assertEquals(5200L, amount())
        assertEquals(2, dao.order(storeKey, fileId, uuid1)!!.status)
        assertNull(dao.order(storeKey, fileId, uuid1)!!.cancelAppliedVersion)
    }

    @Test
    fun `补丁字节不是数字时保持原状态_不写垃圾进去`() = runBlocking {
        val json = order()
        store.commit(addedRecord(json))
        store.commit(
            patchedRecord(
                patchOffset = 4L + statusIndexOf(json),
                kind = PatchKind.STATUS,
                newStatusDigit = null,          // 服务端不会发，但流消息不该被假设
                cancelAppliedVersion = null,
            )
        )
        assertEquals(1, dao.order(storeKey, fileId, uuid1)!!.status)
        assertEquals(5200L, amount())
    }

    // ==================== PATCH：备注 ====================

    @Test
    fun `改备注_更新文本_且不动统计`() = runBlocking {
        val aFile = "A:alice:2026"
        val pad = " ".repeat(ByteOps.REMARK_WIDTH)
        val json = order(remark = pad)
        store.commit(addedRecord(json, fileId = aFile))

        val remarkOffset = 4L + uuidIndexOf(json, uuid1) + remarkDelta(json)
        store.commit(
            patchedRecord(
                patchOffset = remarkOffset,
                kind = PatchKind.REMARK,
                newRemark = "补货 3 件",
                fileId = aFile,
            )
        )
        assertEquals("补货 3 件", dao.order(storeKey, aFile, uuid1)!!.remark)
        // 把改备注误判成取消会错误回退库存与统计 —— 而且不会报错
        assertEquals(1, dao.order(storeKey, aFile, uuid1)!!.status)
        assertNull(dao.order(storeKey, aFile, uuid1)!!.cancelAppliedVersion)
    }

    /** remark 值的字节下标减去 uuid 值的字节下标（README §2.5 的 `r - u`）。 */
    private fun remarkDelta(json: String, uuid: String = uuid1): Int {
        val r = json.indexOf("\"remark\":\"") + "\"remark\":\"".length
        val u = json.indexOf(uuid)
        return ByteOps.utf8Len(json.substring(0, r)) - ByteOps.utf8Len(json.substring(0, u))
    }

    @Test
    fun `备注清空时写_null_而不是空串`() = runBlocking {
        val json = order(remark = " ".repeat(ByteOps.REMARK_WIDTH))
        store.commit(addedRecord(json, fileId = "A:alice:2026"))
        store.commit(
            patchedRecord(
                patchOffset = 0L,     // orderUuid 已知时偏移不参与定位
                kind = PatchKind.REMARK,
                newRemark = null,     // MirrorCommitter 对全空格备注就是这么给的
                fileId = "A:alice:2026",
            )
        )
        assertNull(dao.order(storeKey, "A:alice:2026", uuid1)!!.remark)
    }

    // ==================== PATCH：不带 uuid ====================

    @Test
    fun `补丁不带_uuid_时按_statusOffset_反查`() = runBlocking {
        val json = order()
        store.commit(addedRecord(json))
        store.commit(
            patchedRecord(
                patchOffset = 4L + statusIndexOf(json),
                kind = PatchKind.STATUS,
                orderUuid = null,       // 流消息里的 CANCEL 可能不带 order_uuid
                newStatusDigit = 0,
                cancelAppliedVersion = 2,
            )
        )
        assertEquals(0, dao.order(storeKey, fileId, uuid1)!!.status)
        assertEquals(0L, amount())
    }

    @Test
    fun `补丁不带_uuid_时按_remarkOffset_反查`() = runBlocking {
        val aFile = "A:alice:2026"
        val json = order(remark = " ".repeat(ByteOps.REMARK_WIDTH))
        store.commit(addedRecord(json, fileId = aFile))
        val remarkOffset = 4L + uuidIndexOf(json, uuid1) + remarkDelta(json)
        store.commit(
            patchedRecord(
                patchOffset = remarkOffset,
                kind = PatchKind.REMARK,
                orderUuid = null,
                newRemark = "新备注",
                fileId = aFile,
            )
        )
        assertEquals("新备注", dao.order(storeKey, aFile, uuid1)!!.remark)
    }

    @Test
    fun `偏移不在任何订单上时不猜_只记审计`() = runBlocking {
        store.commit(addedRecord(order()))
        store.commit(
            patchedRecord(
                patchOffset = 999_999L,
                kind = PatchKind.UNKNOWN,
                orderUuid = null,
            )
        )
        assertEquals("UNKNOWN 不改索引", 1, dao.order(storeKey, fileId, uuid1)!!.status)
        assertEquals(5200L, amount())
        assertTrue(audits.any { "找不到对应的索引行" in it })
    }

    // ==================== FULL ====================

    @Test
    fun `commitFull_从镜像重建索引_偏移指向文件里真正的字节`() = runBlocking {
        val j1 = order()
        val j2 = order(uuid = uuid2, writer = "bob", total = "30.00", qty = 3)
        val m = mirrorWith(j1, j2)
        store.commitFull(storeKey, fileId, fullState(7, m), m)

        assertEquals(2, dao.countOrderIndexesOfFile(storeKey, fileId))
        for ((json, uuid) in listOf(j1 to uuid1, j2 to uuid2)) {
            val row = dao.order(storeKey, fileId, uuid)!!
            // 端到端验证：拿存下来的偏移去文件里读，读出来的必须就是那个字段
            assertEquals(json.substring(json.indexOf(uuid), json.indexOf(uuid) + uuid.length),
                String(m.readAt(row.uuidOffset, ByteOps.utf8Len(uuid)), Charsets.UTF_8))
            val statusByte = m.readAt(row.statusOffset, 1)[0].toInt().toChar()
            assertEquals('1', statusByte)
            assertEquals(7, row.appliedFileVersion)
        }
        // bob 的那一笔落在自己的 dimKey 上，不能被并进 alice 的
        assertEquals(3000L, amount(key = StatsProjection.dayKey("bob", sept15, utc)))
        assertEquals(5200L, amount())
    }

    @Test
    fun `commitFull_已取消的订单不计入统计_且_cancelAppliedVersion_等于_FULL_版本`() = runBlocking {
        val live = order()
        val dead = order(uuid = uuid2, status = 0, total = "30.00", qty = 3)
        val m = mirrorWith(live, dead)
        store.commitFull(storeKey, fileId, fullState(7, m), m)

        // 增量路径上它先 +1 再 -1，净值 0；重投影必须得到同一个数
        assertEquals(5200L, amount())
        assertEquals(1L, amount(StatsProjection.METRIC_ORDERS))
        assertEquals(14L, amount(StatsProjection.METRIC_PIECES))

        val row = dao.order(storeKey, fileId, uuid2)!!
        assertEquals(0, row.status)
        // ⚠ 两个版本相等 → 无论吸收边界落在哪里，`> 边界` 都同时成立或同时不成立，
        // 净增量恒为 0。只填 appliedFileVersion 的话这笔已取消的扣减会被再算一次 → 库存偏低。
        assertEquals(7, row.cancelAppliedVersion)
        assertEquals(7, row.appliedFileVersion)
        assertNull(dao.order(storeKey, fileId, uuid1)!!.cancelAppliedVersion)
    }

    @Test
    fun `commitFull_不会与增量贡献叠加`() = runBlocking {
        // 先走增量，再对同一个文件做一次 FULL —— 最典型的"翻倍"来源
        store.commit(addedRecord(order()))
        assertEquals(5200L, amount())

        val m = mirrorWith(order())
        store.commitFull(storeKey, fileId, fullState(1, m), m)
        assertEquals(5200L, amount())
        assertEquals(1L, amount(StatsProjection.METRIC_ORDERS))

        // 再来一次，仍然不变
        store.commitFull(storeKey, fileId, fullState(1, m), m)
        assertEquals(5200L, amount())
        assertEquals(1, dao.countOrderIndexesOfFile(storeKey, fileId))
        assertEquals(1, dao.itemCount(storeKey, fileId, uuid1))
    }

    @Test
    fun `commitFull_会清掉镜像里已经不存在的订单的贡献`() = runBlocking {
        store.commit(addedRecord(order()))
        assertEquals(5200L, amount())

        // 服务器上这一行没了（运维重写过文件）
        val m = mirrorWith(order(uuid = uuid2, total = "30.00", qty = 3))
        store.commitFull(storeKey, fileId, fullState(5, m), m)

        assertEquals(3000L, dao.contribTotal(storeKey, scope, StatsProjection.METRIC_AMOUNT))
        assertNull(dao.order(storeKey, fileId, uuid1))
        assertEquals(0, dao.items.count { it.orderUuid == uuid1 })
    }

    @Test
    fun `commitFull_遇到解析不了的行时跳过它并记审计_但游标仍然前进`() = runBlocking {
        val m = mirrorWith(order(), "{ 这一行坏了", order(uuid = uuid2, total = "30.00", qty = 3))
        store.commitFull(storeKey, fileId, fullState(4, m), m)

        assertEquals(2, dao.countOrderIndexesOfFile(storeKey, fileId))
        assertTrue(audits.any { "解析失败" in it })
        // ⚠ 游标必须提交：不提交的话下次同步又是 FULL，永远在这个文件上打转
        assertEquals(4, dao.fileState(storeKey, fileId)!!.version)
    }

    @Test
    fun `commitFull_文件里有重复_uuid_时记审计`() = runBlocking {
        // 服务端不查重：某台设备盲重试过 /add 就会留下两行同 uuid。
        // 主键容不下两行，第一行赢 —— 但那必须是**看得见**的。
        val m = mirrorWith(order(), order())
        store.commitFull(storeKey, fileId, fullState(2, m), m)

        assertEquals(1, dao.countOrderIndexesOfFile(storeKey, fileId))
        assertTrue(audits.any { "行数对不上" in it })
    }

    @Test
    fun `commitFull_写游标与自检值`() = runBlocking {
        val m = mirrorWith(order())
        store.commitFull(storeKey, fileId, fullState(11, m), m)
        val fs = dao.fileState(storeKey, fileId)!!
        assertEquals(11, fs.version)
        assertEquals("11-0", fs.lastStreamId)
        assertEquals(m.checksumContent(), fs.checksum)
        assertEquals(m.length(), fs.mirrorBytes)
        assertEquals("LIVE", fs.syncState)
    }

    @Test
    fun `commitFull_保留原来的_firstSyncedAt`() = runBlocking {
        // MirrorCommitter 传进来的 state 已经带上了 prev.firstSyncedAt；
        // 这里钉住 store 不会把它冲掉（三态语义靠它区分"从没同步过"与"同步过但没单"）
        val m = mirrorWith(order())
        store.commitFull(
            storeKey, fileId,
            fullState(3, m).copy(firstSyncedAt = 42L),
            m,
        )
        assertEquals(42L, dao.fileState(storeKey, fileId)!!.firstSyncedAt)
    }

    @Test
    fun `空镜像的_FULL_产出_0_行_0_贡献_但游标照写`() = runBlocking {
        val m = Mirror(File(tmp.root, "empty.mirror"))
        m.ensureCreated()
        store.commitFull(storeKey, fileId, fullState(1, m), m)
        assertEquals(0, dao.countOrderIndexesOfFile(storeKey, fileId))
        assertEquals(0, dao.contrib.size)
        val fs = dao.fileState(storeKey, fileId)!!
        assertEquals(1, fs.version)
        assertEquals(clock, fs.lastSyncAttemptAt)
        // ⚠ firstSyncedAt 是"同步过但没单"与"从没拉过"的唯一分界，FULL 不该把它冲掉
        assertNull("这个文件此前从未同步过", fs.firstSyncedAt)
    }

    // ==================== DIVERGED ====================

    @Test
    fun `commitDiverged_归零游标但保留_firstSyncedAt`() = runBlocking {
        store.commit(
            MirrorCommitRecord(
                storeKey = storeKey,
                fileId = fileId,
                state = MirrorState(87, "87-0", 999, 4096L, 555L, SyncState.LIVE),
                order = null,
                alreadyApplied = false,
            )
        )
        store.commitDiverged(storeKey, fileId, "守卫判定分歧")

        val s = store.read(storeKey, fileId)!!
        assertEquals(SyncState.DIVERGED, s.syncState)
        // 归零是故意的：即使调度器有 bug，version=0 + lastStreamId=null 也保证下次走 FULL
        assertEquals(0, s.version)
        assertNull(s.lastStreamId)
        assertEquals(0, s.checksum)
        assertEquals(4L, s.mirrorBytes)
        assertEquals("清掉它的话 FULL 自愈之后这个月第一次同步是什么时候就永久丢了", 555L, s.firstSyncedAt)
    }

    @Test
    fun `commitDiverged_在还没有行时补插一行_firstSyncedAt_为_null`() = runBlocking {
        store.commitDiverged(storeKey, fileId, "第一次同步就分歧")
        val s = store.read(storeKey, fileId)!!
        assertEquals(SyncState.DIVERGED, s.syncState)
        assertEquals(0, s.version)
        assertNull("这个月从未成功同步过，与'同步过但没单'必须区分开", s.firstSyncedAt)
        val fs = dao.fileState(storeKey, fileId)!!
        assertEquals("B", fs.fileType)
        assertEquals(2026, fs.year)
        assertEquals(9, fs.month)
    }

    @Test
    fun `commitDiverged_不清索引与贡献_它们仍是目前最好的信息`() = runBlocking {
        store.commit(addedRecord(order()))
        store.commitDiverged(storeKey, fileId, "自检不符")
        assertNotNull(dao.order(storeKey, fileId, uuid1))
        assertEquals(5200L, amount())
    }

    // ==================== read ====================

    @Test
    fun `read_没有记录时返回_null`() = runBlocking {
        assertNull(store.read(storeKey, fileId))
    }

    @Test
    fun `read_把_syncState_字面量映射回枚举`() = runBlocking {
        for (st in SyncState.values()) {
            dao.fileStates[storeKey to fileId] = baseFileState(st.name)
            assertEquals(st, store.read(storeKey, fileId)!!.syncState)
        }
    }

    @Test
    fun `read_遇到未知的_syncState_按_DIVERGED_处理_那是自愈的一侧`() = runBlocking {
        dao.fileStates[storeKey to fileId] = baseFileState("WAT")
        assertEquals(SyncState.DIVERGED, store.read(storeKey, fileId)!!.syncState)
        assertTrue(audits.any { "未知值" in it })
    }

    private fun baseFileState(syncState: String) = FileStateEntity(
        storeKey = storeKey,
        fileId = fileId,
        fileType = "B",
        year = 2026,
        month = 9,
        version = 5,
        lastStreamId = "5-0",
        checksum = 42,
        mirrorBytes = 100L,
        firstSyncedAt = 1L,
        syncState = syncState,
        lastSyncAttemptAt = 2L,
    )
}
