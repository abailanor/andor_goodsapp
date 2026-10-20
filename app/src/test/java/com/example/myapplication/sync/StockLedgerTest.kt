package com.example.myapplication.sync

import com.example.myapplication.db.StockObservation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `StockLedger` 的行为测试。被测到的是：
 *
 *  - `applyAbsolute` / `applyAbsoluteBatch` 的编排（去重 → 写基线 →
 *    推**该条码**在所有 fileId 上的边界 —— 两个维度都不能少，见
 *    `StockAbsorbBoundaryEntity` 的类注释与 [X_的观测不许抬高_Y_的库存]）；
 *  - `StockLedger` 的四则运算与 `reconcile` 的不对称规则；
 *  - `DedupKeys` 的构造规则。
 *
 * fake 的能力边界（哪些是照抄 SQL、哪些没被测到）写在 [FakeStockDao] 的类注释里。
 * 同一个 fake 也被 `ProductSyncEngineTest` 用 —— 那边要断言的正是
 * "商品行必须在库存观测**之前**写好"，而这个断言需要一个能看出边界有没有推的 DAO。
 */
class StockLedgerTest {

    private var clock = 1_000L
    private val dao = FakeStockDao()
    private val ledger = StockLedger(dao, now = { clock })

    private val store = "kehu"
    private val cola = "6901234567890"
    private val oct = "B:alice:2026-10"

    // ==================== effectiveStock ====================

    @Test
    fun `查不到的条码返回 null 而不是 0`() = runBlocking {
        // ⚠ 用 0 代替 null 的后果是静默的：补货筛选的条件是"库存 < 阈值"，
        // 于是**每一个已删商品**都会进补货清单（阈值总是 > 0），
        // 而清单里还显示不出名字（商品表里已经没有它了）。
        assertNull(ledger.effectiveStock(store, cola))
        assertTrue(ledger.effectiveStockAll(store).isEmpty())
    }

    @Test
    fun `没有订单时有效库存等于基线`() = runBlocking {
        dao.putSynced(store, cola, 100)
        assertEquals(100, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `已同步区优先于本地区_与商品页的合并视图一致`() = runBlocking {
        // 两张表都有同一条码时必须以 product_synced 为准（服务器权威）。
        // 若这里反过来，开单页显示的库存与补货筛选算出来的库存会对不上，
        // 而两边各自看都"是对的"。
        dao.putSynced(store, cola, 100)
        dao.putLocal(store, cola, 7)
        assertEquals(100, ledger.effectiveStock(store, cola))
        assertEquals(100, ledger.effectiveStockAll(store)[cola])
    }

    @Test
    fun `边界之后的卖出被扣掉_边界之内的不再扣`() = runBlocking {
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 9)
        // 边界 5：v<=5 的订单已被那次绝对观测吸收，v>5 的还没。
        dao.setBoundary(store, oct, 5, cola)
        dao.addOrder(store, FakeOrder(oct, "u-inside", appliedFileVersion = 5, items = listOf(FakeItem(cola, 2))))
        dao.addOrder(store, FakeOrder(oct, "u-after", appliedFileVersion = 6, items = listOf(FakeItem(cola, 3))))
        assertEquals(100 - 3, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `边界落在下单与取消之间时回退不丢`() = runBlocking {
        // ⚠ 这是整个模型里最容易写错、且**错了不报错**的一处。
        //
        // 下单在 v10（已被边界 15 之前那次绝对观测吸收 → 不该再扣），
        // 取消在 v20（观测之后才发生 → 服务器把货退回去了，而我们的基线是 v15 那一刻的，
        // 所以必须把这笔加回来）。
        //
        // 直觉写法"只统计 status=1 的订单"会把这一整行跳过（它现在 status=0），
        // 于是 +3 永久丢失，库存偏低 3，没有任何日志。
        dao.putSynced(store, cola, 10)
        dao.putFileVersion(store, oct, 20)
        dao.setBoundary(store, oct, 15, cola)
        dao.addOrder(
            store,
            FakeOrder(oct, "u-split", appliedFileVersion = 10, cancelAppliedVersion = 20,
                items = listOf(FakeItem(cola, 3))),
        )
        assertEquals("卖出不该重复扣、回退必须加回来", 10 + 3, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `取消与下单都在边界之内时净贡献为零`() = runBlocking {
        dao.putSynced(store, cola, 10)
        dao.putFileVersion(store, oct, 20)
        dao.setBoundary(store, oct, 15, cola)
        dao.addOrder(
            store,
            FakeOrder(oct, "u-old", appliedFileVersion = 10, cancelAppliedVersion = 12,
                items = listOf(FakeItem(cola, 3))),
        )
        assertEquals(10, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `每个_fileId_的边界各自独立`() = runBlocking {
        // 只推当月的边界，上个月那笔就会被再扣一遍 —— 这正是
        // `applyAbsolute` 必须推**所有** fileId 的原因。
        val sep = "B:alice:2026-09"
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, sep, 8)
        dao.putFileVersion(store, oct, 8)
        dao.setBoundary(store, sep, 8, cola)   // 已吸收
        dao.setBoundary(store, oct, 3, cola)    // 未吸收
        dao.addOrder(store, FakeOrder(sep, "u-sep", 5, items = listOf(FakeItem(cola, 4))))
        dao.addOrder(store, FakeOrder(oct, "u-oct", 5, items = listOf(FakeItem(cola, 6))))
        assertEquals("只有十月那笔该被扣", 100 - 6, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `A_类订单不参与销售扣减`() = runBlocking {
        // A 类是向丽华金龙进货，它的库存变化由总部那边管，
        // 服务端 `order_write.lua` 只对 B 类 HSET 库存。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, "A:alice:2026", 9)
        dao.setBoundary(store, "A:alice:2026", 0, cola)
        dao.addOrder(store, FakeOrder("A:alice:2026", "u-a", 5, items = listOf(FakeItem(cola, 40))))
        assertEquals(100, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `同一订单的多行分别归到各自条码`() = runBlocking {
        val water = "6901234567891"
        dao.putSynced(store, cola, 100)
        dao.putSynced(store, water, 50)
        dao.putFileVersion(store, oct, 9)
        // 边界是 per-barcode 的：两个条码各要一行，否则落进 `IFNULL(...,0)` 的兜底。
        // 这里兜底与显式 0 语义相同，但显式写出来才能让"漏了一行"这种错在读测试时看得出来。
        dao.setBoundary(store, oct, 0, cola)
        dao.setBoundary(store, oct, 0, water)
        dao.addOrder(
            store,
            FakeOrder(oct, "u-multi", 5, items = listOf(FakeItem(cola, 2), FakeItem(water, 7), FakeItem(cola, 3))),
        )
        assertEquals(100 - 5, ledger.effectiveStock(store, cola))
        assertEquals(50 - 7, ledger.effectiveStock(store, water))
    }

    // ==================== effectiveStockAll ====================

    @Test
    fun `批量结果与逐条一致`() = runBlocking {
        val water = "6901234567891"
        dao.putSynced(store, cola, 100)
        dao.putLocal(store, water, 50)
        dao.putFileVersion(store, oct, 9)
        dao.setBoundary(store, oct, 0, cola)
        dao.setBoundary(store, oct, 0, water)
        dao.addOrder(store, FakeOrder(oct, "u1", 5, items = listOf(FakeItem(cola, 2), FakeItem(water, 7))))

        val all = ledger.effectiveStockAll(store)
        for ((barcode, stock) in all) {
            assertEquals("barcode=$barcode 两边必须一致", stock, ledger.effectiveStock(store, barcode))
        }
        assertEquals(setOf(cola, water), all.keys)
    }

    @Test
    fun `订单里引用了已删商品时不会出现在批量结果里`() = runBlocking {
        // 已删商品不该进补货清单。它的名字仍能从 product_tombstone 读到，
        // 所以历史订单照样显示得出来 —— 这里只是不给它算库存。
        val gone = "6900000000000"
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 9)
        dao.setBoundary(store, oct, 0, cola)
        dao.addOrder(store, FakeOrder(oct, "u1", 5, items = listOf(FakeItem(gone, 9))))

        val all = ledger.effectiveStockAll(store)
        assertEquals(mapOf(cola to 100), all)
        assertFalse("绝不能把查不到当成 0", all.containsKey(gone))
        assertNull(ledger.effectiveStock(store, gone))
    }

    @Test
    fun `批量走两次聚合查询而不是逐条查`() = runBlocking {
        // 补货清单要一次算 400 条。逐条查是 800 次全表聚合，慢一个数量级，
        // 而"能跑通"的慢实现与快实现在功能测试里长得一模一样 ——
        // 所以这里直接数调用次数。KDoc 里写的"两次聚合查询"因此是有测试兜住的。
        for (i in 0 until 20) dao.putSynced(store, "69000000000$i", i)
        dao.putFileVersion(store, oct, 9)
        dao.addOrder(store, FakeOrder(oct, "u1", 5, items = listOf(FakeItem("6900000000001", 3))))

        dao.resetCounters()
        ledger.effectiveStockAll(store)

        assertEquals(1, dao.allBaselinesCalls)
        assertEquals(1, dao.allDeltasCalls)
        assertEquals("批量路径不许退化成逐条", 0, dao.baselineCalls)
        assertEquals("批量路径不许退化成逐条", 0, dao.deltaCalls)
    }

    // ==================== applyAbsolute ====================

    @Test
    fun `同一条观测重复应用只生效一次`() = runBlocking {
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 5)

        assertTrue(ledger.applyAbsolute(store, "K1", cola, 40, "STOCK_API"))
        assertEquals(40, ledger.effectiveStock(store, cola))

        // ⚠ 第二次带的是**不同的值**。若去重用的是 REPLACE，它会静默成功并把基线改掉 ——
        // 而那正是"SSE 重连后重投递"这条最常见路径上的事。
        assertFalse(ledger.applyAbsolute(store, "K1", cola, 999, "STOCK_API"))
        assertEquals("重投递必须被去重日志挡住", 40, ledger.effectiveStock(store, cola))
        assertEquals(1, dao.applyLog.size)
    }

    @Test
    fun `重复应用不会把边界推得更远`() = runBlocking {
        // 边界被多推一次 = 那之后的订单增量被基线"吸收"掉，可基线里根本没有它们
        // → 库存**偏高**，而且没有任何报错。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 5)
        assertTrue(ledger.applyAbsolute(store, "K1", cola, 100, "STOCK_STREAM"))
        assertEquals(5, dao.boundaryAt(store, oct, cola))

        dao.files[store]!![oct] = 8                                    // 期间又来了订单
        dao.addOrder(store, FakeOrder(oct, "u-late", 7, items = listOf(FakeItem(cola, 3))))

        assertFalse(ledger.applyAbsolute(store, "K1", cola, 100, "STOCK_STREAM"))
        assertEquals("重投递不许动边界", 5, dao.boundaryAt(store, oct, cola))
        assertEquals(100 - 3, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `一次观测把该条码在所有_fileId_上的边界推到各自当前版本`() = runBlocking {
        val sep = "B:alice:2026-09"
        val aFile = "A:alice:2026"
        val water = "6901234567891"
        dao.putSynced(store, cola, 100)
        dao.putSynced(store, water, 100)
        dao.putFileVersion(store, sep, 3)
        dao.putFileVersion(store, oct, 7)
        dao.putFileVersion(store, aFile, 11)

        ledger.applyAbsolute(store, "K1", cola, 100, "PRODUCT_FULL")

        assertEquals(3, dao.boundaryAt(store, sep, cola))
        assertEquals(7, dao.boundaryAt(store, oct, cola))
        assertEquals(11, dao.boundaryAt(store, aFile, cola))
        assertNull("别的店铺不受影响", dao.boundaryAt("other-store", oct, cola))

        // ⚠ "所有 fileId"是**文件**维度，不是商品维度。water 的基线还是它自己上一次观测时的值，
        // 把它的边界也推过去等于宣布"它那些扣减已被吸收"（并没有）→ 库存偏高。
        assertNull("别的条码一行都不许写", dao.boundaryAt(store, oct, water))
        assertEquals(
            "边界行只属于被观测的那个条码",
            3, dao.boundary.count { it.key.first == store },
        )
    }

    @Test
    fun `X_的观测不许抬高_Y_的库存`() = runBlocking {
        // `StockAbsorbBoundaryEntity` 类注释里那个反例。androidTest 的 `StockDaoTest`
        // 有同名的真 SQL 版本 —— 两套刻意断言同一个场景：fake 跑得快、能进 CI 的每次构建，
        // 真 SQL 那套才证明 `DELTA_SELECT` 的 JOIN 与 `IFNULL` 兜底真的等价于这个 fake。
        val water = "6901234567891"
        dao.putSynced(store, cola, 100)     // X
        dao.putSynced(store, water, 100)    // Y
        dao.putFileVersion(store, oct, 1)
        dao.addOrder(store, FakeOrder(oct, "u1", 1, items = listOf(FakeItem(water, 3))))
        assertEquals(97, ledger.effectiveStock(store, water))
        assertEquals(100, ledger.effectiveStock(store, cola))

        assertTrue(ledger.applyAbsolute(store, "K-X", cola, 50, "STOCK_API"))
        assertEquals(50, ledger.effectiveStock(store, cola))

        assertEquals("X 的观测把 Y 的边界也推过去了 → Y 偏高 3", 97, ledger.effectiveStock(store, water))
        assertNull("Y 根本没有边界行", dao.boundaryAt(store, oct, water))
        assertEquals(1, dao.boundaryAt(store, oct, cola))
    }

    @Test
    fun `一个文件都没同步过时也能落地绝对值`() = runBlocking {
        // `fileVersions` 返回空列表。Room 对空列表 upsert 是 no-op，
        // 但基线仍然要写进去 —— 离线期手录商品、还没有任何订单文件时就是这种状态。
        dao.putLocal(store, cola, 0)
        assertTrue(ledger.applyAbsolute(store, "K1", cola, 25, "BATCH_UPSERT"))
        assertEquals(25, ledger.effectiveStock(store, cola))
        assertTrue(dao.boundary.isEmpty())
    }

    @Test
    fun `条码两张表都没有时不会凭空造一行`() = runBlocking {
        // UPDATE 命中 0 行就是 0 行。插一条新商品记录是 ProductSyncEngine 的事，
        // 库存观测无权创造商品 —— 否则一个已删条码会从库存流里"复活"进补货清单，
        // 而且没有名字。
        ledger.applyAbsolute(store, "K1", cola, 25, "STOCK_STREAM")
        assertNull(ledger.effectiveStock(store, cola))
        assertTrue(ledger.effectiveStockAll(store).isEmpty())
        assertEquals("去重日志照记，这样重投递时也不会突然生效", 1, dao.applyLog.size)
    }

    @Test
    fun `只在本地表里的条码也能落地绝对值`() = runBlocking {
        dao.putLocal(store, cola, 3)
        ledger.applyAbsolute(store, "K1", cola, 42, "BATCH_UPSERT")
        assertEquals(42, ledger.effectiveStock(store, cola))
        assertEquals("不该顺手往已同步区插行", null, dao.synced[store to cola])
    }

    @Test
    fun `判定不读时钟_更早的时间戳照样覆盖`() = runBlocking {
        // 切分依据是版本号，不是 timestamp。用时间切分是退化方案
        // （FULL 回来的 products 不带 updatedAt，只能猜）。
        // 这条测试把"不许拿 appliedAt 做判定"钉住：
        // 第二条观测的墙钟时间**更早**，但它必须生效。
        dao.putSynced(store, cola, 100)
        clock = 5_000
        ledger.applyAbsolute(store, "K1", cola, 40, "STOCK_STREAM")
        clock = 100                                     // 时光倒流
        ledger.applyAbsolute(store, "K2", cola, 25, "PRODUCT_FULL")
        assertEquals(25, ledger.effectiveStock(store, cola))
        assertEquals("appliedAt 只是给人看的", listOf(5_000L, 100L), dao.applyLog.map { it.appliedAt })
    }

    // ==================== reconcile ====================

    @Test
    fun `本地与服务器一致`() = runBlocking {
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 4)
        assertEquals(StockLedger.Result.MATCH, ledger.reconcile(store, "A1", cola, 100))
        assertEquals("一致时不该写任何东西", 0, dao.applyLog.size)
    }

    @Test
    fun `本地比服务器多是正常的_不告警也不改基线`() = runBlocking {
        // 本地还没同步到别的设备的销售。对称地写（!= 就告警）会让每次正常的
        // 多设备使用都报一次假警，用户三次之后就再也不看告警了。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 4)
        dao.setBoundary(store, oct, 0, cola)
        dao.addOrder(store, FakeOrder(oct, "u1", 3, items = listOf(FakeItem(cola, 2))))

        assertEquals(StockLedger.Result.LOCAL_AHEAD, ledger.reconcile(store, "A1", cola, 98 - 5))
        assertEquals("LOCAL_AHEAD 绝不能触发自愈", 0, dao.applyLog.size)
        assertEquals(98, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `本地比服务器少触发告警并自愈到服务器值`() = runBlocking {
        // effective < server 意味着本地扣得比服务器还多 = 双扣了，或漏吸收了一次回退。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 4)
        dao.setBoundary(store, oct, 0, cola)
        dao.addOrder(store, FakeOrder(oct, "u1", 3, items = listOf(FakeItem(cola, 9))))

        assertEquals(91, ledger.effectiveStock(store, cola))
        val r = ledger.reconcile(store, "A1", cola, 100)
        assertEquals(StockLedger.Result.LOCAL_TOO_LOW(healed = true), r)
        // 自愈 = 把服务器的绝对值当一次新观测：重置基线 + 把边界推到当前。
        // 那笔算错的扣减于是被基线吸收，必然收敛，不需要知道本地是哪里算错的。
        assertEquals(100, dao.synced[store to cola])
        assertEquals(4, dao.boundaryAt(store, oct, cola))
        assertEquals(100, ledger.effectiveStock(store, cola))
        assertEquals(StockLedger.SOURCE_RECONCILE, dao.applyLog.single().source)
    }

    @Test
    fun `自愈撞上去重时宁可保持偏低_也不把边界推过头`() = runBlocking {
        // 同一条服务器响应被处理两次（崩溃重入、或调用方重试）。第二次必须**不**自愈：
        // 那条观测已经被吸收过了，再推一次边界会把此后新落地的订单增量也算进基线，
        // 库存于是偏**高**。偏低会被补货清单看见，偏高不会 —— 所以拒绝自愈是安全的那一侧。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 4)
        dao.setBoundary(store, oct, 4, cola)
        val key = "R:u-add-1:$cola"                     // 标识"这一次响应事件"，不是标识数值

        dao.files[store]!![oct] = 5
        dao.addOrder(store, FakeOrder(oct, "u-a", 5, items = listOf(FakeItem(cola, 30))))
        assertEquals(70, ledger.effectiveStock(store, cola))
        assertEquals(StockLedger.Result.LOCAL_TOO_LOW(healed = true), ledger.reconcile(store, key, cola, 100))
        assertEquals(5, dao.boundaryAt(store, oct, cola))

        // 自愈之后又来一笔**新的**扣减。
        dao.files[store]!![oct] = 6
        dao.addOrder(store, FakeOrder(oct, "u-b", 6, items = listOf(FakeItem(cola, 30))))
        assertEquals(70, ledger.effectiveStock(store, cola))

        // 同一条响应再被处理一次。
        assertEquals(StockLedger.Result.LOCAL_TOO_LOW(healed = false), ledger.reconcile(store, key, cola, 100))
        assertEquals("撞去重就什么都不改", 5, dao.boundaryAt(store, oct, cola))
        assertEquals("u-b 的扣减绝不能被吸收掉", 70, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `已删商品返回_UNKNOWN_PRODUCT`() = runBlocking {
        // 服务器仍然会回它的库存，忽略即可 —— 不该为了对账把商品行造回来。
        assertEquals(StockLedger.Result.UNKNOWN_PRODUCT, ledger.reconcile(store, "A1", cola, 10))
        assertEquals(0, dao.applyLog.size)
    }

    // ==================== DedupKeys ====================

    @Test
    fun `商品_FULL_的_key_随两个版本号变化`() = runBlocking {
        // ⚠ 写成 "FULL:$barcode" 的话每一次 FULL 都会被当成重复而跳过，
        // 基线永远停在第一次的值上，而且没有任何报错。
        assertNotEquals(
            StockLedger.DedupKeys.productFull(5, 5, cola),
            StockLedger.DedupKeys.productFull(6, 5, cola),
        )
        assertEquals(
            StockLedger.DedupKeys.productFull(5, 5, cola),
            StockLedger.DedupKeys.productFull(5, 5, cola),
        )

        dao.putSynced(store, cola, 100)
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.productFull(5, 5, cola), cola, 60, "PRODUCT_FULL"))
        assertTrue("版本号变了就必须重新吸收",
            ledger.applyAbsolute(store, StockLedger.DedupKeys.productFull(6, 5, cola), cola, 55, "PRODUCT_FULL"))
        assertEquals(55, ledger.effectiveStock(store, cola))
        assertFalse("两个版本号都没变说明数据没变，跳过是无害的",
            ledger.applyAbsolute(store, StockLedger.DedupKeys.productFull(6, 5, cola), cola, 1, "PRODUCT_FULL"))
        assertEquals(55, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `只由库存过时触发的_FULL_也必须被吸收`() = runBlocking {
        // ⚠ 这条钉住 `productFull` 为什么要收**两个**版本号。
        //
        // `RedisSyncService.buildSyncResponse:57` 的条件是 `needFullStock || needFullPrice`：
        // 库存流被 XTRIM 裁掉、而属性流没被裁时，会回来一个 FULL，
        // 它的 `priceVersion` 与上一次 FULL **完全相同**、只有 `stockVersion` 涨了。
        //
        // 若 key 只按 priceVersion 算，这次 FULL 会被当成重复而跳过 →
        // 库存基线停在旧值上，且没有任何报错。而 FULL 里的 `products[].stock`
        // 恰恰是这次唯一的新信息。
        dao.putSynced(store, cola, 100)
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.productFull(5, 5, cola), cola, 60, "PRODUCT_FULL"))
        assertEquals(60, ledger.effectiveStock(store, cola))

        assertNotEquals(
            "priceVersion 没变、stockVersion 变了 → 必须是不同的 key",
            StockLedger.DedupKeys.productFull(5, 5, cola),
            StockLedger.DedupKeys.productFull(5, 9, cola),
        )
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.productFull(5, 9, cola), cola, 33, "PRODUCT_FULL"))
        assertEquals(33, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `库存流与属性流的_key_不会撞`() {
        // 两条流的 `version` 各自独立计数（version_stock / version_price），
        // 7 在两边都可能出现。少了 kind 这一段，属性流的第 7 条会把库存流的第 7 条顶掉。
        assertNotEquals(
            StockLedger.DedupKeys.stream("STOCK", 7),
            StockLedger.DedupKeys.stream("PRICE", 7),
        )
        assertEquals(
            StockLedger.DedupKeys.stream("STOCK", 7),
            StockLedger.DedupKeys.stream("STOCK", 7),
        )
        assertNotEquals(
            StockLedger.DedupKeys.stream("STOCK", 7),
            StockLedger.DedupKeys.stream("STOCK", 8),
        )
    }

    @Test
    fun `四类_key_互不相同`() {
        // 前缀不同 → 任何两类都不可能撞。撞了的话后一类会被静默跳过。
        val keys = listOf(
            StockLedger.DedupKeys.stream("STOCK", 1),
            StockLedger.DedupKeys.productFull(1, 1, cola),
            StockLedger.DedupKeys.batchUpsert("b1", cola),
            StockLedger.DedupKeys.stockApi("r1", cola),
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `同一个值改两次库存_两次都要生效`() = runBlocking {
        // ⚠ 这条钉住 `DedupKeys.stockApi` 的签名为什么是 (requestId, barcode)
        // 而不是看起来更自然的 (barcode, newStock)。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 1)
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.stockApi("r1", cola), cola, 100, "STOCK_API"))
        assertEquals(1, dao.boundaryAt(store, oct, cola))

        dao.files[store]!![oct] = 2
        dao.addOrder(store, FakeOrder(oct, "u1", 2, items = listOf(FakeItem(cola, 4))))
        assertEquals(96, ledger.effectiveStock(store, cola))

        // 用户又把库存设回 100。**新的用户动作 → 新的 requestId**，必须照样吸收。
        // 若 key 是按数值算的，这一次会被当成重复而跳过：基线看不出问题（本来就是 100），
        // 但边界没推 → u1 仍被扣 → 库存偏低 4，且无任何报错。
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.stockApi("r2", cola), cola, 100, "STOCK_API"))
        assertEquals(2, dao.boundaryAt(store, oct, cola))
        assertEquals("u1 已被这次观测吸收", 100, ledger.effectiveStock(store, cola))

        // 反过来，同一次动作的 HTTP 重试必须仍然被认成重复 ——
        // 否则边界会被推到 2 之后，把此后别的设备的扣减也吸收掉，库存偏高。
        assertFalse(ledger.applyAbsolute(store, StockLedger.DedupKeys.stockApi("r2", cola), cola, 100, "STOCK_API"))
        assertEquals(2, dao.boundaryAt(store, oct, cola))
    }

    @Test
    fun `不同批次的回推不会被互相顶掉`() = runBlocking {
        // 方案 R2：回推的 stock 是客户端自己算的绝对值，服务器按绝对值落地，
        // 所以推完立刻拿它当一次观测吸收掉。第二次推（用户又改了库存）必须照样生效。
        dao.putLocal(store, cola, 0)
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.batchUpsert("b1", cola), cola, 30, "BATCH_UPSERT"))
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.batchUpsert("b2", cola), cola, 45, "BATCH_UPSERT"))
        assertEquals(45, ledger.effectiveStock(store, cola))
    }

    // ==================== 方案 §7.3(d) 的交错场景 ====================

    @Test
    fun `交错场景_三笔订单_一次入库_一次_FULL_一次取消_最终等于服务器绝对值`() = runBlocking {
        // 服务器那侧的数字是手算出来的（`order_write.lua` 第 5 节：B 类订单直接
        // HSET 绝对值，不写 stream:stock）。本地必须每一步都跟得上。
        var server = 100
        dao.putSynced(store, cola, server)
        dao.putFileVersion(store, oct, 0)

        fun qtyOf(uuid: String) =
            dao.orders[store]!!.first { it.orderUuid == uuid }.items.sumOf { it.quantity }

        fun sell(uuid: String, qty: Int) {
            val v = dao.bumpFileVersion(store, oct)
            dao.addOrder(store, FakeOrder(oct, uuid, v, items = listOf(FakeItem(cola, qty))))
            server -= qty                       // 服务器直接落地绝对值
        }

        fun cancel(uuid: String) {
            val v = dao.bumpFileVersion(store, oct)
            dao.cancelOrder(store, uuid, v)
            server += qtyOf(uuid)               // 服务器在取消那一刻把货退回去
        }

        suspend fun assertStep(what: String) {
            assertEquals("$what：本地必须等于服务器", server, ledger.effectiveStock(store, cola))
        }

        // 1) 两笔卖出。B 类不写库存流，所以本地只能靠"基线 + 边界之后的增量"算出来。
        sell("u1", 2); assertStep("卖出 u1 之后")
        sell("u2", 3); assertStep("卖出 u2 之后")

        // 2) 显式入库到 200。响应里带 newStock，是一次**绝对观测**。
        server = 200
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.stockApi("put-1", cola), cola, 200, "STOCK_API"))
        assertStep("显式入库之后")
        // u1/u2 的扣减此刻被基线吸收了：边界已推到 v2，它们的 appliedFileVersion 都 <= 2。
        assertEquals(2, dao.boundaryAt(store, oct, cola))

        // 3) 第三笔卖出，落在新边界之后。
        sell("u3", 5); assertStep("卖出 u3 之后")

        // 4) 取消 u3。服务器在取消那一刻把货退回去。
        cancel("u3"); assertStep("取消 u3 之后")

        // 5) 商品 FULL（priceVersion=7），带回服务器当前的绝对值。
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.productFull(7, 7, cola), cola, server, "PRODUCT_FULL"))
        assertStep("商品 FULL 之后")
        assertEquals("FULL 把边界推到当前", 4, dao.boundaryAt(store, oct, cola))

                // 6) FULL 之后又来一笔。
        sell("u4", 1); assertStep("FULL 之后再卖一笔")

        // 7) 重投递：把第 2 步那次入库的响应再投一遍（SSE 重连最常见的事）。
        assertFalse(ledger.applyAbsolute(store, StockLedger.DedupKeys.stockApi("put-1", cola), cola, 200, "STOCK_API"))
        assertStep("重投递一次旧的绝对观测之后")

        // 8) /add 的对账：服务器说 199，本地也应该是 199。
        assertEquals(StockLedger.Result.MATCH, ledger.reconcile(store, "R1", cola, server))
        assertEquals(199, server)
    }

    // ==================== applyAbsoluteBatch ====================
    //
    // 批量版存在的唯一理由是性能（400 条商品 = 400 个事务 ≈ 800 次 fsync）。
    // 所以这里每条测试都在钉"语义与逐条版**完全一致**"—— 性能优化改出语义差异，
    // 是最难查的一类 bug：功能测试全绿，只有低端机上的用户会遇到。

    @Test
    fun `空批返回_0_且什么都不写`() = runBlocking {
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 3)
        assertEquals(0, ledger.applyAbsoluteBatch(store, emptyList(), "PRODUCT_FULL"))
        assertTrue(dao.applyLog.isEmpty())
        assertTrue("没有观测就没有边界", dao.boundary.isEmpty())
    }

    @Test
    fun `批量与逐条结果一致`() = runBlocking {
        val water = "6901234567891"
        val tea = "6901234567892"
        val sep = "B:alice:2026-09"

        // 两台一模一样的世界，一台走批量、一台走逐条。
        fun seed(d: FakeStockDao) {
            d.putSynced(store, cola, 100)
            d.putSynced(store, water, 50)
            d.putLocal(store, tea, 7)
            d.putFileVersion(store, oct, 5)
            d.putFileVersion(store, sep, 2)
            d.addOrder(store, FakeOrder(oct, "u1", 4, items = listOf(FakeItem(cola, 3), FakeItem(water, 1))))
        }
        val batched = FakeStockDao(); seed(batched)
        val oneByOne = FakeStockDao(); seed(oneByOne)

        val obs = listOf(
            StockObservation("K1", cola, 80),
            StockObservation("K2", water, 42),
            StockObservation("K3", tea, 9),
        )
        val l1 = StockLedger(batched, now = { clock })
        val l2 = StockLedger(oneByOne, now = { clock })

        assertEquals(3, l1.applyAbsoluteBatch(store, obs, "PRODUCT_FULL"))
        for (o in obs) l2.applyAbsolute(store, o.dedupKey, o.barcode, o.newStock, "PRODUCT_FULL")

        for (b in listOf(cola, water, tea)) {
            assertEquals("barcode=$b 基线必须一致", l2.effectiveStock(store, b), l1.effectiveStock(store, b))
        }
        assertEquals("边界表必须逐行一致", oneByOne.boundary, batched.boundary)
        assertEquals("去重日志条数必须一致", oneByOne.applyLog.size, batched.applyLog.size)
    }

    @Test
    fun `整批都撞去重时一次都不推边界`() = runBlocking {
        // 这条规则在批量版里比单条版**更要紧**：单条版表现为"return false"，
        // 批量版如果不特判，`appliedBarcodes` 为空但 `fileVersions` 照样读、边界照样写
        // → 把边界推过"那之后才落地的订单增量"，而那些增量并没有被这批旧基线吸收 → 库存偏**高**。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 5)
        assertEquals(1, ledger.applyAbsoluteBatch(store, listOf(StockObservation("K1", cola, 100)), "PRODUCT_FULL"))
        assertEquals(5, dao.boundaryAt(store, oct, cola))

        dao.files[store]!![oct] = 8                              // 期间又来了订单
        dao.addOrder(store, FakeOrder(oct, "u-late", 7, items = listOf(FakeItem(cola, 3))))

        assertEquals("整批重复 = 应用了 0 条", 0,
            ledger.applyAbsoluteBatch(store, listOf(StockObservation("K1", cola, 999)), "PRODUCT_FULL"))
        assertEquals("边界不许动", 5, dao.boundaryAt(store, oct, cola))
        assertEquals("基线也不许动", 100, dao.synced[store to cola])
        assertEquals(100 - 3, ledger.effectiveStock(store, cola))
    }

    @Test
    fun `部分撞去重时只给真正应用的条码推边界`() = runBlocking {
        val water = "6901234567891"
        dao.putSynced(store, cola, 100)
        dao.putSynced(store, water, 100)
        dao.putFileVersion(store, oct, 5)
        assertEquals(1, ledger.applyAbsoluteBatch(store, listOf(StockObservation("K1", cola, 100)), "PRODUCT_FULL"))

        dao.files[store]!![oct] = 6
        dao.addOrder(store, FakeOrder(oct, "u1", 6, items = listOf(FakeItem(water, 4))))

        // cola 撞去重（跳过），water 是新的（应用）。
        assertEquals(1, ledger.applyAbsoluteBatch(
            store,
            listOf(StockObservation("K1", cola, 999), StockObservation("K2", water, 60)),
            "PRODUCT_FULL",
        ))
        assertEquals("cola 重复 → 基线与边界都不动", 100, dao.synced[store to cola])
        assertEquals(5, dao.boundaryAt(store, oct, cola))
        assertEquals("water 是新观测 → 边界推到当前", 6, dao.boundaryAt(store, oct, water))
        assertEquals(60, ledger.effectiveStock(store, water))
    }

    @Test
    fun `同一条码在批内出现两次_后者覆盖前者且边界只写一份`() = runBlocking {
        // 调用方按版本号升序传入，所以"后者覆盖"就是"新值赢"。
        // 与逐条调用的结果一致（见 `批量与逐条结果一致`）。
        dao.putSynced(store, cola, 100)
        dao.putFileVersion(store, oct, 5)
        assertEquals(2, ledger.applyAbsoluteBatch(store, listOf(
            StockObservation("K1", cola, 80),
            StockObservation("K2", cola, 60),
        ), "PRODUCT_FULL"))
        assertEquals(60, dao.synced[store to cola])
        assertEquals(60, ledger.effectiveStock(store, cola))
        assertEquals("一个 (barcode,fileId) 只有一行", 1,
            dao.boundary.count { it.key == Triple(store, oct, cola) })
    }
}
