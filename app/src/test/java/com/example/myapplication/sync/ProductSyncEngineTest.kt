package com.example.myapplication.sync

import com.example.myapplication.data.Product
import com.example.myapplication.db.ProductLocal
import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.db.ProductTokenizer
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.dto.ProductSnapshotDto
import com.example.myapplication.net.dto.StreamFields
import com.example.myapplication.net.dto.SyncDataResponseDto
import com.example.myapplication.sync.ProductSyncEngine.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal

/**
 * `ProductSyncEngine` 的行为测试。
 *
 * ## 为什么这里全是 fake 而不是真 HTTP + 真 Room
 *
 * 引擎的**决策分支**很多（FULL 的墓碑、INCREMENTAL 的版本空洞、`action=ADD` 缺 name 要补拉、
 * SERVER_BEHIND 必须交给用户二选一、回推的分批与重试），每一条错了都是静默的商品/库存错误。
 * 真 HTTP 要设备与服务端，真 Room 要 instrumented 测试（项目没引 Robolectric），
 * 两者都进不了每次构建。所以这里只测编排；SQL 语义由 androidTest 那侧覆盖。
 * fake 照抄了什么、没照抄什么，写在 [FakeProductDao] 与 [FakeStockDao] 的类注释里。
 *
 * ## 顺序不变量怎么测（[assertRowsBeforeObservations]）
 *
 * 「商品行必须先于库存观测写好」这条在数值上**测不出来**：`writeFromPriceEntry` 会把
 * `existing.stock` 原样带到新行、`fetchUnknown` 用的是服务端**当前**值，两条路径都会顺手
 * 把反序写坏的值修回来。所以现在这个 bug 被别的代码掩盖着 —— 一旦那些代码被重构就会露出来，
 * 而届时没有任何数值断言会变红。只能直接断言调用顺序，理由记在 [FakeStockDao.ops] 上。
 */
class ProductSyncEngineTest {

    private val cola = "6901234567890"
    private val water = "6901234567891"
    private val tea = "6901234567892"
    private val oct = "B:alice:2026-10"

    // ==================== FULL ====================

    @Test
    fun `FULL_整表替换已同步区_本地行与扩展字段一条不动`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedSynced(cola, "可口可乐330ml", stock = 5)
        w.seedSynced(water, "矿泉水", stock = 5)
        w.seedLocal(tea, "冰红茶", stock = 3)
        w.seedExtra(cola, category = "饮料", note = "冷藏")

        val out = w.engine.apply(
            sk,
            full(9, 4, mapOf(cola to snap(name = "可口可乐330ml", stock = 40))),
        ) as Outcome.Applied

        assertEquals(1, out.productsChanged)
        assertEquals("water 从快照里消失了", 1, out.deleted)
        assertEquals(1, out.stockObservations)
        assertRowsBeforeObservations(w.ops)

        assertEquals(40, w.ledger.effectiveStock(sk, cola))
        assertNull("不在快照里就是删了", w.ledger.effectiveStock(sk, water))
        assertEquals("这是分区设计的全部意义：FULL 结构上碰不到 product_local",
            3, w.ledger.effectiveStock(sk, tea))
        assertNotNull(w.products.getLocal(sk, tea))

        val row = w.products.getProductByBarcode(sk, cola)!!
        assertEquals("product_extra 是 FULL 永远不碰的那张表", "饮料", row.category)
        assertEquals("冷藏", row.note)
        assertEquals(9, w.meta.rows[sk]!!.priceVersion)
        assertEquals(4, w.meta.rows[sk]!!.stockVersion)
    }

    @Test
    fun `FULL_给消失的条码写墓碑_且保留第一次的名字`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedSynced(cola, "可口可乐330ml")

        w.engine.apply(sk, full(1, 1, emptyMap()))
        assertEquals("可口可乐330ml", w.products.tombstoneName(sk, cola))

        // 删 → 用新名字重建 → 再删。`insertTombstone` 是 IGNORE，保留**第一次**的名字。
        // 这是有意的：历史订单显示旧名比显示不出名字好，而"改名后又删"这种事没人会去纠正墓碑。
        w.engine.apply(sk, full(2, 2, mapOf(cola to snap(name = "可乐（改过名）"))))
        w.engine.apply(sk, full(3, 3, emptyMap()))
        assertEquals("可口可乐330ml", w.products.tombstoneName(sk, cola))
    }

    @Test
    fun `同一份_FULL_重投递不双算也不把边界推过头`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        // ⚠ 订单文件必须在 FULL **之前**就存在。`applyAbsoluteBatch` 读 `file_state` 推边界，
        // 而一个文件都还没同步过时它一行都不写（缺行的语义 = `IFNULL(...,0)`，等价于边界 0）。
        // 于是"先 FULL 再拿到订单"这条时序下，边界要靠**下一次**观测才补得上 ——
        // 中间那段时间库存偏低（安全的一侧，但数字不对）。
        w.stock.putFileVersion(sk, oct, 3)
        val resp = full(9, 4, mapOf(cola to snap(name = "可口可乐330ml", stock = 40)))

        assertEquals(1, (w.engine.apply(sk, resp) as Outcome.Applied).stockObservations)
        assertEquals(3, w.stock.boundaryAt(sk, oct, cola))

        // 这笔卖出在 v2 <= 边界 3 → 已被那次观测吸收，不该再扣
        w.stock.addOrder(sk, FakeOrder(oct, "u1", appliedFileVersion = 2, items = listOf(FakeItem(cola, 5))))
        assertEquals(40, w.ledger.effectiveStock(sk, cola))

        // SSE 重连后同一份 FULL 再来一次（版本号没变 → 去重键相同）
        val again = w.engine.apply(sk, resp) as Outcome.Applied
        assertEquals("整批撞去重 → 一条都没吸收", 0, again.stockObservations)
        assertEquals("⚠ 关键：applied==0 时一次都不推边界", 3, w.stock.boundaryAt(sk, oct, cola))
        assertEquals(40, w.ledger.effectiveStock(sk, cola))
        assertEquals(1, w.stock.applyLog.size)
    }

    @Test
    fun `FULL_缺_products_时返回_Malformed_且一个字都不写`() = runBlocking {
        val w = FakeWorld()
        w.seedMeta()
        w.seedSynced(cola, "可口可乐330ml", stock = 5)

        val out = w.engine.apply(w.storeKey, SyncDataResponseDto("FULL", 9, 4, null, null, null))

        assertTrue(out is Outcome.Malformed)
        assertEquals(1, w.products.countSynced(w.storeKey))
        assertTrue("游标也不许动", w.meta.cursorWrites.isEmpty())
    }

    @Test
    fun `FULL_不碰改动队列`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedSynced(cola, "可口可乐330ml", price = "3.50")
        w.seedPending(cola, op = ProductPendingChange.OP_UPSERT, name = "可口可乐330ml", price = "4.00")

        w.engine.apply(sk, full(1, 1, mapOf(cola to snap(name = "可口可乐330ml", price = "3.50"))))

        assertEquals("离线改动还没推上去，FULL 把它抹回服务器值之后队列必须还在",
            1, w.products.countPendingChanges(sk))
        assertEquals(BigDecimal("3.50"), w.products.getProductByBarcode(sk, cola)!!.price)
    }

    @Test
    fun `FULL_快照缺_name_时回落旧名字_再不行是空串而不是丢行`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedSynced(cola, "可口可乐330ml", stock = 7)

        // 协议上 FULL 一定带 name（只有两条属性流不带）。这里防的是"服务端改了形状"。
        w.engine.apply(sk, full(1, 1, mapOf(cola to snap(name = null, stock = 7))))
        assertEquals("可口可乐330ml", w.products.getProductByBarcode(sk, cola)!!.name)

        // 旧行也没有时：空名字**看得见**，整行消失看不见 ——
        // 消失意味着 `effectiveStock` 变 null，于是它从补货清单里静默消失。
        w.engine.apply(sk, full(2, 2, mapOf(water to snap(name = null, stock = 3))))
        val row = w.products.getProductByBarcode(sk, water)
        assertNotNull(row)
        assertEquals("", row!!.name)
        assertEquals(3, w.ledger.effectiveStock(sk, water))
    }

    @Test
    fun `FULL_之后本地行仍然搜得出来`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedLocal(tea, "冰红茶")
        w.seedSynced(cola, "可口可乐330ml")

        w.engine.apply(sk, full(1, 1, mapOf(water to snap(name = "矿泉水"))))

        // ⚠ `replaceSyncedWithTokens` 里的 `deleteAllTokens` 会把**本地行**的索引一起清掉
        // （两张表共用 `product_token`），默认实现体负责把它们重建回来。
        // 漏了这一步的话：本地商品还在列表里，却怎么搜都搜不出来，且不报错。
        assertEquals(listOf(tea),
            w.products.searchTokenHits(sk, ProductTokenizer.tokenizeQuery("冰红茶"), 1, 10).map { it.barcode })
        assertEquals(2, w.products.getAllProductsList(sk).size)
    }

    @Test
    fun `FULL_的商品行必须先于库存观测写好`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        // FULL 的第一步是 `clearSynced` —— 反序的话每条 `writeSyncedStock` 都命中 0 行，
        // 基线一个字都写不进去，而去重键已经烧掉、边界已经推过去了。
        val snapshot = (1..5).associate { "B$it" to snap(name = "商品$it", stock = it) }

        w.engine.apply(sk, full(1, 1, snapshot))

        assertRowsBeforeObservations(w.ops)
        assertEquals(5, w.stock.applyLog.size)
        (1..5).forEach { assertEquals(it, w.ledger.effectiveStock(sk, "B$it")) }
    }

    // ==================== INCREMENTAL ====================

    @Test
    fun `INCREMENTAL_的商品行必须先于库存观测写好`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.api.putServer(tea, "冰红茶", price = "3.00", boxPrice = "30.00", boxSize = 10, stock = 30)

        // 一次商品新增会**同时**在两条流里各产生一条（版本号各自独立）。
        // 库存那条带一个绝对观测，而商品行是价格那条（ADD → 补拉）建的。
        w.engine.apply(
            sk,
            incremental(
                priceVersion = 5, stockVersion = 5,
                price = listOf(priceEntry(5, tea, StreamFields.ACTION_ADD)),
                stock = listOf(stockEntry(5, tea, 30)),
            ),
        )

        assertRowsBeforeObservations(w.ops)
        assertEquals(30, w.ledger.effectiveStock(sk, tea))
    }

    @Test
    fun `UPDATE_只改价格三兄弟_名字库存可订状态保持原值`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedSynced(cola, "可口可乐330ml", price = "3.50", stock = 7, orderable = false)

        val out = w.engine.apply(
            sk,
            incremental(
                priceVersion = 5, stockVersion = 0,
                price = listOf(priceEntry(5, cola, StreamFields.ACTION_UPDATE,
                    newPrice = "4.00", newBoxPrice = "48.00", newBoxSize = 12)),
            ),
        ) as Outcome.Applied

        assertEquals(1, out.productsChanged)
        val p = w.products.getProductByBarcode(sk, cola)!!
        assertEquals(BigDecimal("4.00"), p.price)
        assertEquals(BigDecimal("48.00"), p.boxPrice)
        assertEquals(12, p.boxSize)
        assertEquals("属性流不带 name（陷阱三）", "可口可乐330ml", p.name)
        assertEquals("属性流不带 stock", 7, p.stock)
        assertEquals("属性流不带 orderable", false, p.orderable)
        assertEquals(5, w.meta.rows[sk]!!.priceVersion)
        assertTrue(w.api.getProductCalls.isEmpty())
    }

    @Test
    fun `DELETE_条目没有_new_price_也不会把价格写空`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedSynced(cola, "可口可乐330ml", price = "3.50")

        // ⚠ 陷阱一：DELETE 的价格条目里 `new_price`/`new_box_price`/`new_box_size` **不存在**。
        // 先读这三个键再按 action 分支的话，会把 null 写进一个还活着的商品 ——
        // 表现为"价格静默变空"，而在本引擎里表现为一条坏条目 → 无谓的 FULL。
        val out = w.engine.apply(
            sk,
            incremental(priceVersion = 5, stockVersion = 0,
                price = listOf(priceEntry(5, cola, StreamFields.ACTION_DELETE))),
        )

        assertTrue("不是 HoleDetected：这条 entry 完全合法", out is Outcome.Applied)
        assertEquals(1, (out as Outcome.Applied).deleted)
        assertNull(w.products.getProductByBarcode(sk, cola))
        assertEquals("可口可乐330ml", w.products.tombstoneName(sk, cola))
        assertEquals(5, w.meta.rows[sk]!!.priceVersion)
    }

    @Test
    fun `new_box_price_的空串等于不按整箱卖_不是整箱零元`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedSynced(cola, "可口可乐330ml", price = "3.50", boxPrice = "48.00", boxSize = 12)

        w.engine.apply(
            sk,
            incremental(priceVersion = 5, stockVersion = 0,
                price = listOf(priceEntry(5, cola, StreamFields.ACTION_UPDATE,
                    newPrice = "3.50", newBoxPrice = "", newBoxSize = 0))),
        )

        val p = w.products.getProductByBarcode(sk, cola)!!
        // ⚠ 陷阱二：`"".toBigDecimal()` 抛异常，`?: ZERO` 会把商品变成"整箱白送"。
        assertNull(p.boxPrice)
        assertEquals(0, p.boxSize)
        assertFalse(p.sellsByBox)
    }

    @Test
    fun `陌生条码的_ADD_触发补拉_整行落地并推边界`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.api.putServer(tea, "冰红茶", price = "3.00", boxPrice = "30.00", boxSize = 10,
            stock = 25, orderable = true, updatedAt = 123L)
        // 本地已经有一笔这个条码的卖出（离线开的单，v2）
        w.stock.putFileVersion(sk, oct, 6)
        w.stock.addOrder(sk, FakeOrder(oct, "u1", appliedFileVersion = 2, items = listOf(FakeItem(tea, 4))))

        val out = w.engine.apply(
            sk,
            incremental(priceVersion = 5, stockVersion = 0,
                price = listOf(priceEntry(5, tea, StreamFields.ACTION_ADD))),
        ) as Outcome.Applied

        assertEquals(listOf(tea), w.api.getProductCalls)
        assertEquals(1, out.productsChanged)

        val p = w.products.getProductByBarcode(sk, tea)!!
        assertEquals("冰红茶", p.name)
        assertEquals(BigDecimal("3.00"), p.price)
        assertEquals(10, p.boxSize)
        assertTrue(p.orderable)
        assertEquals("ProductDataDto 是唯一带 updatedAt 的形状", 123L, p.updatedAt)
        assertEquals(Product.ORIGIN_SYNCED, p.origin)

        // 整行写入本身就是一次绝对观测 → 必须配套推边界，否则 u1 那 4 个会被再扣一次
        assertEquals(25, w.ledger.effectiveStock(sk, tea))
        assertEquals(6, w.stock.boundaryAt(sk, oct, tea))
        assertEquals(StockLedger.SOURCE_PRODUCT_FETCH, w.stock.applyLog.single().source)
        // 去重键用**属性流的 version**：同一条 entry 重投递时得到同一个键
        assertEquals(StockLedger.DedupKeys.stream("PRICE", 5), w.stock.applyLog.single().dedupKey)
    }

    @Test
    fun `补拉得到_404_时按一次删除处理_不造幽灵商品`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)

        // 别的设备 ADD 之后又删了它，两条 entry 一前一后进同一批（或分两批）。
        // 服务端 404 = 权威答案："现在没有这个商品"。
        val out = w.engine.apply(
            sk,
            incremental(priceVersion = 5, stockVersion = 0,
                price = listOf(priceEntry(5, cola, StreamFields.ACTION_ADD))),
        )

        assertTrue(out is Outcome.Applied)
        assertEquals(1, (out as Outcome.Applied).productsChanged)
        // ⚠ 凭空造一行的话，那是一个没有名字、没有 orderable 的幽灵商品：
        // 列表里显示空名字，开单页选不中，而且它从此占住一个条码。
        assertNull(w.products.getProductByBarcode(sk, cola))
        assertEquals(5, w.meta.rows[sk]!!.priceVersion)
    }

    @Test
    fun `补拉失败时按坏条目处理_保留现场不推游标`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.api.getProductError = ApiError.Network(IOException("connection reset"))

        val out = w.engine.apply(
            sk,
            incremental(priceVersion = 5, stockVersion = 0,
                price = listOf(priceEntry(5, tea, StreamFields.ACTION_ADD))),
        ) as Outcome.HoleDetected

        assertTrue(out.resetPriceCursor)
        assertEquals(0, w.meta.rows[sk]!!.priceVersion)
        assertTrue("理由要说得让人看得懂", out.reason.contains("解析不了"))
        assertNull(w.products.getProductByBarcode(sk, tea))
        assertTrue(w.stock.applyLog.isEmpty())
    }

    @Test
    fun `陌生条码超过阈值就改成强制一次_FULL`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        val n = ProductSyncEngine.MAX_ADD_FETCHES + 1
        (1..n).forEach { w.api.putServer("B$it", "商品$it") }

        val out = w.engine.apply(
            sk,
            incremental(priceVersion = n, stockVersion = 0,
                price = (1..n).map { priceEntry(it, "B$it", StreamFields.ACTION_ADD) }),
        ) as Outcome.HoleDetected

        // 400 条商品逐个 GET 是 400 次串行 HTTP 往返，一个 FULL 只有 ~40KB
        assertEquals(ProductSyncEngine.MAX_ADD_FETCHES, w.api.getProductCalls.size)
        assertTrue(out.resetPriceCursor)
        assertEquals(0, w.meta.rows[sk]!!.priceVersion)
        assertTrue(out.reason.contains("FULL"))
    }

    @Test
    fun `价格流有空洞时只把价格游标归零_库存游标照常推进`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4, stockVersion = 4)
        w.seedSynced(cola, "可口可乐330ml", price = "3.50", stock = 7)

        // 服务端版本 9，但收到的条目最大只到 5 —— 中间被 `PRICE_STREAM_MAXLEN` 裁掉了
        val out = w.engine.apply(
            sk,
            incremental(
                priceVersion = 9, stockVersion = 6,
                price = listOf(priceEntry(5, cola, StreamFields.ACTION_UPDATE, newPrice = "4.00", newBoxSize = 0)),
                stock = listOf(stockEntry(6, cola, 20)),
            ),
        ) as Outcome.HoleDetected

        assertTrue(out.resetPriceCursor)
        assertFalse("两条游标各自独立，别把没坏的那条也归零", out.resetStockCursor)
        assertEquals(0, w.meta.rows[sk]!!.priceVersion)
        assertEquals(6, w.meta.rows[sk]!!.stockVersion)
        // ⚠ 已经应用的条目**保留**：全是绝对值覆盖，幂等，应用了只会更接近真值。
        assertEquals(20, w.ledger.effectiveStock(sk, cola))
        assertEquals(BigDecimal("4.00"), w.products.getProductByBarcode(sk, cola)!!.price)
        assertEquals(1, out.appliedPriceEntries)
        assertEquals(1, out.appliedStockObservations)
    }

    @Test
    fun `服务端版本更高却一条条目都没给_就是空洞`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4, stockVersion = 4)

        val out = w.engine.apply(sk, incremental(priceVersion = 7, stockVersion = 4)) as Outcome.HoleDetected

        assertTrue(out.resetPriceCursor)
        assertFalse(out.resetStockCursor)
        assertEquals("⚠ 只「不推进」是不够的：被裁掉的条目永远回不来，每次同步都报同一个洞",
            0, w.meta.rows[sk]!!.priceVersion)
    }

    @Test
    fun `条目重投递不双算库存`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedSynced(cola, "可口可乐330ml", price = "3.50", stock = 7)
        w.stock.putFileVersion(sk, oct, 3)
        w.stock.addOrder(sk, FakeOrder(oct, "u1", appliedFileVersion = 2, items = listOf(FakeItem(cola, 4))))

        val resp = incremental(
            priceVersion = 5, stockVersion = 5,
            price = listOf(priceEntry(5, cola, StreamFields.ACTION_UPDATE, newPrice = "4.00", newBoxSize = 0)),
            stock = listOf(stockEntry(5, cola, 30)),
        )
        w.engine.apply(sk, resp)
        assertEquals(30, w.ledger.effectiveStock(sk, cola))   // u1 在 v2 <= 边界 3 → 已吸收
        assertEquals(3, w.stock.boundaryAt(sk, oct, cola))

        w.engine.apply(sk, resp)                              // SSE 重连后重投递
        assertEquals(30, w.ledger.effectiveStock(sk, cola))
        assertEquals("去重命中 → 边界不许再推", 3, w.stock.boundaryAt(sk, oct, cola))
        assertEquals(1, w.stock.applyLog.size)
        assertEquals(BigDecimal("4.00"), w.products.getProductByBarcode(sk, cola)!!.price)
    }

    @Test
    fun `解析不了的条目不计入_maxSeen_于是变成空洞而不是静默跳过`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedSynced(cola, "可口可乐330ml", price = "3.50")

        // 少了 `version`（协议变了 / 服务端换了字段名）
        val broken = mapOf(StreamFields.BARCODE to cola, StreamFields.ACTION to StreamFields.ACTION_UPDATE,
            StreamFields.NEW_PRICE to "9.90", StreamFields.NEW_BOX_SIZE to "0")

        val out = w.engine.apply(sk, incremental(priceVersion = 5, stockVersion = 0,
            price = listOf(broken))) as Outcome.HoleDetected

        assertEquals(0, w.meta.rows[sk]!!.priceVersion)
        assertEquals("⚠ 计进 maxSeen 就等于宣布「这一条我处理过了」→ 变更永久丢失",
            0, out.appliedPriceEntries)
        assertEquals(BigDecimal("3.50"), w.products.getProductByBarcode(sk, cola)!!.price)
    }

    @Test
    fun `服务器删掉条码时不许删掉没推过的本地行`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedSynced(cola, "可口可乐330ml")
        w.seedLocal(cola, "我的可乐", pushState = ProductLocal.PUSH_PENDING)

        w.engine.apply(sk, incremental(priceVersion = 5, stockVersion = 0,
            price = listOf(priceEntry(5, cola, StreamFields.ACTION_DELETE))))

        val local = w.products.getLocal(sk, cola)
        assertNotNull("没推过的本地行是用户自己的数据，服务器上同条码的商品被删与它无关", local)
        assertEquals("我的可乐", local!!.name)
        assertEquals("synced 走了 → 合并视图透出本地行",
            Product.ORIGIN_LOCAL, w.products.getProductByBarcode(sk, cola)!!.origin)
        // ⚠ `removeSyncedWithTokens` 按 barcode 删索引，会把活下来的本地行的索引一起带走
        assertEquals(listOf(cola),
            w.products.searchTokenHits(sk, ProductTokenizer.tokenizeQuery("我的可乐"), 1, 10).map { it.barcode })
    }

    @Test
    fun `服务器删掉条码时已推送的本地副本跟着删`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedSynced(cola, "可口可乐330ml")
        w.seedLocal(cola, "可口可乐330ml", pushState = ProductLocal.PUSH_PUSHED)

        w.engine.apply(sk, incremental(priceVersion = 5, stockVersion = 0,
            price = listOf(priceEntry(5, cola, StreamFields.ACTION_DELETE))))

        // PUSHED 的本地行只是服务器那行的副本（R1.3：推送成功后就该删掉）
        assertNull(w.products.getLocal(sk, cola))
        assertNull(w.products.getProductByBarcode(sk, cola))
    }

    @Test
    fun `已推送的本地行在服务器认识它之后被清掉_没推过的保留`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 4)
        w.seedLocal(cola, "可口可乐330ml", price = "3.50", stock = 7, pushState = ProductLocal.PUSH_PUSHED)
        w.seedLocal(water, "矿泉水", pushState = ProductLocal.PUSH_PENDING)

        w.engine.apply(sk, incremental(priceVersion = 6, stockVersion = 0, price = listOf(
            priceEntry(5, cola, StreamFields.ACTION_UPDATE, newPrice = "4.00", newBoxSize = 12),
            priceEntry(6, water, StreamFields.ACTION_UPDATE, newPrice = "2.00", newBoxSize = 0),
        )))

        assertNull("冗余了，留着会让 countPendingPush 一直显示「N 条待推送」", w.products.getLocal(sk, cola))
        assertEquals(Product.ORIGIN_SYNCED, w.products.getProductByBarcode(sk, cola)!!.origin)
        assertEquals("stock 是从本地行带过来的（属性流不带 stock）",
            7, w.products.getProductByBarcode(sk, cola)!!.stock)

        assertNotNull("PUSH_PENDING 是用户还没同意上传的数据，删掉就是丢数据；" +
            "留着也不出错（合并视图 synced 优先，它被遮住了）", w.products.getLocal(sk, water))
    }

    // ==================== SERVER_BEHIND / 未知类型 ====================

    @Test
    fun `SERVER_BEHIND_一个字都不写_尤其是游标`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 40, stockVersion = 30)
        w.seedSynced(cola, "可口可乐330ml", stock = 7)
        w.seedLocal(tea, "冰红茶")

        val out = w.engine.apply(sk, SyncDataResponseDto("SERVER_BEHIND", 3, 2, null, null, null))
            as Outcome.ServerBehind

        assertEquals(3, out.serverPriceVersion)
        assertEquals(2, out.serverStockVersion)
        assertEquals(40, out.localPriceVersion)
        assertEquals(30, out.localStockVersion)

        // ⚠ 这是本分支唯一的陷阱，触发条件是"照抄 INCREMENTAL 的收尾代码"：
        // 把响应里那个**更小**的版本号写进游标 = 往回拨 → 下次必然 FULL →
        // 用服务器那份更短的数据覆盖客户端唯一那份更完整的副本，不可逆。
        assertTrue(w.meta.cursorWrites.isEmpty())
        assertEquals(40, w.meta.rows[sk]!!.priceVersion)
        assertEquals(30, w.meta.rows[sk]!!.stockVersion)
        assertEquals(1, w.products.countSynced(sk))
        assertEquals(1, w.products.countLocal(sk))
        assertEquals(7, w.ledger.effectiveStock(sk, cola))
    }

    @Test
    fun `LATEST_与未知类型都落进_UnknownType_且不动本地数据`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta(priceVersion = 40)
        w.seedSynced(cola, "可口可乐330ml")

        // 商品同步的三个分支是 SERVER_BEHIND / FULL / INCREMENTAL，**没有 LATEST**
        //（那是订单文件与目录同步的取值）。收到它说明服务端改了协议。
        assertEquals("LATEST", (w.engine.apply(sk, SyncDataResponseDto("LATEST", 40, 40, null, null, null))
            as Outcome.UnknownType).type)
        assertEquals("WHATEVER", (w.engine.apply(sk, SyncDataResponseDto("WHATEVER", 1, 1, null, null, null))
            as Outcome.UnknownType).type)

        // ⚠ 绝不能默认当成 FULL —— FULL 会覆盖本地数据，误判一次就是不可逆的丢失
        assertTrue(w.meta.cursorWrites.isEmpty())
        assertEquals(1, w.products.countSynced(sk))
    }

    @Test
    fun `store_meta_缺行时返回_Malformed_连_FULL_也不许落地`() = runBlocking {
        val w = FakeWorld()          // 故意不 seedMeta

        val out = w.engine.apply(w.storeKey, full(9, 4, mapOf(cola to snap(name = "可口可乐330ml"))))

        assertTrue(out is Outcome.Malformed)
        // `setPriceVersion` 是 UPDATE：没有行时命中 0 行、**不报错**，于是游标永远停在 0
        // → 每次同步都 FULL 一遍（400 条商品 × 8 台设备，每次登录都来一轮）。
        // 所以这一行商品也不许写：写了就是一批永远追不上游标的数据。
        assertEquals(0, w.products.countSynced(w.storeKey))
        assertTrue(w.stock.applyLog.isEmpty())
    }

    // ==================== pushOutbox ====================

    @Test
    fun `回推按_100_条分批`() = runBlocking {
        val w = FakeWorld()
        w.seedMeta()
        (1..250).forEach { w.seedLocal("B%03d".format(it), "商品$it", stock = it) }

        val r = w.engine.pushOutbox(w.storeKey, "batch-1")

        assertEquals(250, r.pushed)
        assertTrue(r.ok)
        // 服务端 `@Size(max=100)`：超了直接 400，不是截断
        assertEquals(listOf(100, 100, 50), w.api.upsertBatches.map { it.size })
    }

    @Test
    fun `每条请求都带全字段_尤其是_orderable`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedLocal(cola, "可口可乐330ml", price = "3.50", boxPrice = "48.00", boxSize = 12,
            stock = 9, orderable = false)
        w.seedPending(water, op = ProductPendingChange.OP_UPSERT, name = "矿泉水",
            price = "2.00", stock = 4, orderable = true)

        w.engine.pushOutbox(sk, "b1")

        val all = w.api.upsertBatches.flatten()
        assertEquals(2, all.size)
        val c = all.first { it.barcode == cola }
        // ⚠ 服务端 `ProductAddRequest.orderable` 是原始 `boolean`：JSON 里漏掉就是 false，
        // 商品被静默改成不可订。DTO 把它声明成非空只挡住"漏传"，挡不住"抄错方向"。
        assertFalse(c.orderable)
        assertEquals("3.50", c.price)
        assertEquals("48.00", c.boxPrice)
        assertEquals(12, c.boxSize)
        assertEquals(9, c.stock)
        assertEquals("可口可乐330ml", c.name)
        val t = all.first { it.barcode == water }
        assertTrue(t.orderable)
        assertEquals("2.00", t.price)
    }

    @Test
    fun `改动队列与本地表撞同一条码时_队列优先`() = runBlocking {
        val w = FakeWorld()
        w.seedMeta()
        w.seedLocal(cola, "旧名字", price = "1.00", stock = 1)
        w.seedPending(cola, op = ProductPendingChange.OP_UPSERT, name = "新名字", price = "9.90", stock = 2)

        w.engine.pushOutbox(w.storeKey, "b1")

        val req = w.api.upsertBatches.flatten().single()
        assertEquals("待推送的改动是用户**更晚**的意图", "新名字", req.name)
        assertEquals("9.90", req.price)
        assertEquals(2, req.stock)
    }

    @Test
    fun `DELETE_被_403_挡住时保留队列并记下原因`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedPending(cola, op = ProductPendingChange.OP_DELETE)
        w.api.deleteError = ApiError.Forbidden("只有店主可以删除商品")

        val r = w.engine.pushOutbox(sk, "b1")

        assertEquals(0, r.deleted)
        val f = r.failures.single()
        assertEquals(cola, f.barcode)
        // `ProductController:53` 是 `isOwner(ma)` 门控，**不看权限位** → 员工排的 DELETE 永远推不上去。
        // 但换店主登录就能推，所以**不是**永久失败；UI 要提前把入口禁用掉，否则员工会当成 bug。
        assertFalse(f.permanent)
        assertEquals(1, w.products.countPendingDeletes(sk))
        assertEquals(1, w.products.pendingChange(sk, cola)!!.attemptCount)
        assertEquals("只有店主能删除商品", w.products.pendingChange(sk, cola)!!.lastError)
    }

    @Test
    fun `DELETE_得到_404_算成功`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedPending(cola, op = ProductPendingChange.OP_DELETE)
        w.api.deleteError = ApiError.NotFound("商品不存在")

        val r = w.engine.pushOutbox(sk, "b1")

        assertEquals("服务器上已经没有它了 = 目标达成，别再排队", 1, r.deleted)
        assertTrue(r.ok)
        assertEquals(0, w.products.countPendingChanges(sk))
    }

    @Test
    fun `DELETE_重试到上限就标成永久失败_且不再打服务端`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedPending(cola, op = ProductPendingChange.OP_DELETE,
            attemptCount = ProductSyncEngine.MAX_PUSH_ATTEMPTS)

        val r = w.engine.pushOutbox(sk, "b1")

        assertTrue(r.failures.single().permanent)
        assertTrue(w.api.deleteCalls.isEmpty())
        assertEquals("队列里留着，人工处理", 1, w.products.countPendingDeletes(sk))
    }

    @Test
    fun `batch-upsert_失败时整批标记_且一条库存都不吸收`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedLocal(cola, "可口可乐330ml", stock = 5)
        w.seedPending(water, op = ProductPendingChange.OP_UPSERT, name = "矿泉水")
        w.stock.putFileVersion(sk, oct, 3)
        w.api.batchUpsertError = ApiError.ServerError("trace-42", "内部错误")

        val r = w.engine.pushOutbox(sk, "b1")

        assertEquals(0, r.pushed)
        assertFalse(r.ok)
        assertEquals(2, r.failures.size)
        assertEquals(ProductLocal.PUSH_FAILED, w.products.getLocal(sk, cola)!!.pushState)
        assertEquals("内部错误", w.products.getLocal(sk, cola)!!.lastPushError)
        assertEquals(1, w.products.pendingChange(sk, water)!!.attemptCount)
        assertEquals("改动队列不许清", 1, w.products.countPendingChanges(sk))
        // ⚠ 没推上去 = 服务器上没有这个绝对值 = 它不是一次观测。
        // 顺手吸收的话边界被推过去，本地那笔卖出就再也不会被扣。
        assertTrue(w.stock.applyLog.isEmpty())
        assertTrue(w.stock.boundary.isEmpty())
    }

    @Test
    fun `推送成功把推上去的库存当一次观测吸收_复用_batchId_重推不双算`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedLocal(cola, "可口可乐330ml", stock = 40)
        w.stock.putFileVersion(sk, oct, 3)
        w.stock.addOrder(sk, FakeOrder(oct, "u1", appliedFileVersion = 2, items = listOf(FakeItem(cola, 5))))

        // 推之前：cola 只在 product_local（基线 40），边界缺行 = 0 → u1 那 5 个还要扣
        assertEquals(35, w.ledger.effectiveStock(sk, cola))

        val r = w.engine.pushOutbox(sk, "b1")
        assertEquals(1, r.pushed)
        assertEquals(ProductLocal.PUSH_PUSHED, w.products.getLocal(sk, cola)!!.pushState)
        // R2：推上去的 stock 是客户端算的绝对值，服务端按绝对值落地 → 它立刻就是服务器上的真值
        assertEquals(40, w.ledger.effectiveStock(sk, cola))
        assertEquals(3, w.stock.boundaryAt(sk, oct, cola))
        assertEquals(1, w.stock.applyLog.size)

        // 模拟"HTTP 超时、客户端没记下成功"：行还是 PENDING，同一批重推。
        // batchId 必须复用（见 `DedupKeys.batchUpsert`）。
        w.resetPushState(cola)
        val again = w.engine.pushOutbox(sk, "b1")
        assertEquals(1, again.pushed)
        assertEquals("撞去重 → 一条都没吸收", 1, w.stock.applyLog.size)
        assertEquals(3, w.stock.boundaryAt(sk, oct, cola))
        assertEquals(40, w.ledger.effectiveStock(sk, cola))
    }

    @Test
    fun `换新_batchId_重推会把边界推过头_这就是必须复用的原因`() = runBlocking {
        val w = FakeWorld()
        val sk = w.storeKey
        w.seedMeta()
        w.seedLocal(cola, "可口可乐330ml", stock = 40)
        w.stock.putFileVersion(sk, oct, 3)
        w.engine.pushOutbox(sk, "b1")
        assertEquals(40, w.ledger.effectiveStock(sk, cola))

        // 两次推送之间又来了订单文件同步与一笔卖出（v4）
        w.stock.bumpFileVersion(sk, oct)                        // → 4
        w.stock.addOrder(sk, FakeOrder(oct, "u2", appliedFileVersion = 4, items = listOf(FakeItem(cola, 6))))
        assertEquals(34, w.ledger.effectiveStock(sk, cola))

        // ❌ 反例：调用方给重试换了一个新 batchId
        w.resetPushState(cola)
        w.engine.pushOutbox(sk, "b2")

        // 同一次观测被吸收了两次 → 边界推到 4 → u2 那 6 个"被基线吸收"了，
        // 可它从没进过基线（推上去的 40 是 u2 之前的值）→ 库存偏**高**。
        // 偏高是不安全的那一侧：偏低会在补货清单上被看见，偏高要等到卖断货才发现。
        assertEquals(2, w.stock.applyLog.size)
        assertEquals(4, w.stock.boundaryAt(sk, oct, cola))
        assertEquals("⚠ 应该是 34", 40, w.ledger.effectiveStock(sk, cola))
    }

    // ==================== 构造响应用的小工具 ====================

    private fun snap(
        name: String? = "商品",
        price: String? = "1.00",
        boxPrice: String? = null,
        boxSize: Int? = 0,
        stock: Int? = 0,
        orderable: Boolean? = true,
    ) = ProductSnapshotDto(null, name, price, boxPrice, boxSize, stock, orderable)

    private fun full(priceVersion: Int, stockVersion: Int, products: Map<String, ProductSnapshotDto>) =
        SyncDataResponseDto("FULL", priceVersion, stockVersion, null, null, products)

    /** ⚠ `SyncDataResponseDto` 的构造顺序是 `stockStreamEntries` 在 `priceStreamEntries` **之前**。 */
    private fun incremental(
        priceVersion: Int?,
        stockVersion: Int?,
        price: List<Map<String, String>>? = null,
        stock: List<Map<String, String>>? = null,
    ) = SyncDataResponseDto("INCREMENTAL", priceVersion, stockVersion, stock, price, null)

    private fun priceEntry(
        version: Int,
        barcode: String,
        action: String,
        newPrice: String? = null,
        newBoxPrice: String? = null,
        newBoxSize: Int? = null,
    ): Map<String, String> {
        val m = linkedMapOf(
            StreamFields.VERSION to version.toString(),
            StreamFields.BARCODE to barcode,
            StreamFields.ACTION to action,
        )
        newPrice?.let { m[StreamFields.NEW_PRICE] = it }
        // `""` 是合法值（= 不按整箱卖），所以这里只判 null 不判 blank
        newBoxPrice?.let { m[StreamFields.NEW_BOX_PRICE] = it }
        newBoxSize?.let { m[StreamFields.NEW_BOX_SIZE] = it.toString() }
        return m
    }

    private fun stockEntry(version: Int, barcode: String, newStock: Int) = mapOf(
        StreamFields.VERSION to version.toString(),
        StreamFields.BARCODE to barcode,
        StreamFields.NEW_STOCK to newStock.toString(),
    )

    /**
     * 断言「商品行全部写在库存观测之前」。
     *
     * 一边没发生时无从谈顺序（例如 FULL 快照为空、或纯回推路径），直接放过。
     */
    private fun assertRowsBeforeObservations(ops: List<String>) {
        val lastRow = ops.indexOfLast { it.startsWith("row:") }
        val firstObs = ops.indexOfFirst { it.startsWith("obs:") }
        if (lastRow < 0 || firstObs < 0) return
        assertTrue("最后一个 `row:` 在 $lastRow，第一个 `obs:` 在 $firstObs —— 顺序反了：$ops",
            lastRow < firstObs)
    }
}
