package com.example.myapplication.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.data.Product
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * 商品合并视图 + 倒排索引搜索的 instrumented 测试。
 *
 * 这些查询是 `UNION ALL` + `NOT IN` 子查询 + `LEFT JOIN` + `GROUP BY HAVING` 的组合，
 * 在 JVM 上跑不了（Room 要真实的 Android SQLite）。写错的后果是"商品搜不出来"或
 * "同一条码在列表里出现两次"，而且**都不报错**，所以要真机验证。
 */
@RunWith(AndroidJUnit4::class)
class ProductSearchTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ProductDao
    private val store = "00002"
    private val other = "00003"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.productDao()
    }

    @After
    fun tearDown() = db.close()

    private fun tokens(barcode: String, name: String) =
        ProductTokenizer.tokenizeWithPositions(name).map { (t, p) -> ProductToken(store, t, barcode, p) }

    private fun local(barcode: String, name: String, price: String, boxPrice: String? = null, boxSize: Int = 0) =
        ProductLocal(
            store, barcode, name, BigDecimal(price), boxPrice?.let { BigDecimal(it) }, boxSize,
            stock = 0, orderable = true, pushState = "PENDING",
            createdAtLocal = 0, updatedAtLocal = 0, lastPushError = null,
        )

    private fun synced(barcode: String, name: String, price: String, stock: Int = 0) =
        ProductSynced(store, barcode, name, BigDecimal(price), null, 0, stock, true, 0)

    private suspend fun putLocal(p: ProductLocal) =
        dao.putLocalWithTokens(p, tokens(p.barcode, p.name))

    private suspend fun putSynced(p: ProductSynced) =
        dao.putSyncedWithTokens(p, tokens(p.barcode, p.name))

    // ==================== 合并视图 ====================

    @Test
    fun `本地商品能被读出来`() = runBlocking {
        putLocal(local("690", "可口可乐330ml", "3.50"))
        val all = dao.getAllProductsList(store)
        assertEquals(1, all.size)
        val p = all.single()
        assertEquals("可口可乐330ml", p.name)
        assertEquals(Product.ORIGIN_LOCAL, p.origin)
        assertEquals(BigDecimal("3.50"), p.price)
    }

    @Test
    fun `同一条码两边都有时_synced_优先_且只出现一次`() = runBlocking {
        // 这是"分区 + 合并视图"的核心语义：服务器是权威。
        // 漏掉 NOT IN 子查询的话同一条码会出现两次，开单时选中哪条取决于排序，不可预测。
        putLocal(local("690", "本地旧名字", "3.50"))
        putSynced(synced("690", "服务器名字", "4.00", stock = 7))

        val all = dao.getAllProductsList(store)
        assertEquals("同一条码必须只出现一次", 1, all.size)
        val p = all.single()
        assertEquals(Product.ORIGIN_SYNCED, p.origin)
        assertEquals("服务器名字", p.name)
        assertEquals(BigDecimal("4.00"), p.price)
        assertEquals(7, p.stock)
    }

    @Test
    fun `按条码精确查询在多商品时只返回一条`() = runBlocking {
        // 这条专门守住"过滤条件被拼进 LEFT JOIN 的 ON 子句"那个 bug：
        // ON 不过滤左表的行，所以查询会返回全部商品，而且不报错。
        // 只有一条商品时这个 bug 看不出来，必须有多条才暴露。
        putLocal(local("690", "可口可乐330ml", "3.50"))
        putLocal(local("691", "百事可乐", "3.00"))
        putLocal(local("692", "雪碧", "3.00"))

        val p = dao.getProductByBarcode(store, "691")
        assertNotNull(p)
        assertEquals("百事可乐", p!!.name)
        assertNull(dao.getProductByBarcode(store, "不存在的条码"))
    }

    @Test
    fun `按名字_LIKE_搜索在多商品时只返回匹配的`() = runBlocking {
        putLocal(local("690", "可口可乐330ml", "3.50"))
        putLocal(local("691", "百事可乐", "3.00"))
        putLocal(local("692", "雪碧", "3.00"))
        assertEquals(2, dao.searchByName(store, "可乐").size)
        assertEquals(1, dao.searchByName(store, "雪碧").size)
        assertEquals(0, dao.searchByName(store, "不存在的商品").size)
    }

    @Test
    fun `按条码集合批量查询只返回集合内的`() = runBlocking {
        putLocal(local("690", "可口可乐", "3.50"))
        putLocal(local("691", "百事可乐", "3.00"))
        putLocal(local("692", "雪碧", "3.00"))
        val got = dao.getProductsByBarcodes(store, listOf("690", "692"))
        assertEquals(setOf("690", "692"), got.map { it.barcode }.toSet())
    }

    @Test
    fun `不同店铺的数据互不可见`() = runBlocking {
        putLocal(local("690", "本店可乐", "3.50"))
        // 另一个店铺写一条同条码的商品
        dao.putLocalWithTokens(
            local("690", "别店可乐", "9.99").copy(storeKey = other),
            ProductTokenizer.tokenizeWithPositions("别店可乐").map { (t, p) -> ProductToken(other, t, "690", p) },
        )
        val mine = dao.getAllProductsList(store)
        assertEquals(1, mine.size)
        assertEquals("本店可乐", mine.single().name)
        assertEquals(1, dao.getAllProductsList(other).size)
        assertEquals("别店可乐", dao.getAllProductsList(other).single().name)
    }

    @Test
    fun `category_与_note_来自_product_extra_且_FULL_整表替换后仍然活着`() = runBlocking {
        // 服务器没有 category/note 字段。若把它们放进 product_synced，
        // 每次 FULL 都会清空用户手填的分类，而且不报错。
        putLocal(local("690", "可口可乐330ml", "3.50"))
        dao.upsertExtra(ProductExtra(store, "690", "饮料", "常备"))
        assertEquals("饮料", dao.getAllProductsList(store).single().category)
        assertEquals("常备", dao.getAllProductsList(store).single().note)

        // 模拟一次商品 FULL：整表替换 product_synced
        dao.replaceSyncedWithTokens(listOf(synced("690", "可口可乐330ml", "3.50")), tokens("690", "可口可乐330ml"), store)

        val p = dao.getAllProductsList(store).single()
        assertEquals(Product.ORIGIN_SYNCED, p.origin)
        assertEquals("FULL 之后 category 必须还在", "饮料", p.category)
        assertEquals("FULL 之后 note 必须还在", "常备", p.note)
    }

    @Test
    fun `FULL_整表替换碰不到未同步区`() = runBlocking {
        // 这条测试证明的是用户提出的那个判断：**根因是共用一张表**。
        // 分区之后 FULL 在结构上就删不掉本地手录商品，不依赖代码记得先检查标记列。
        putLocal(local("LOCAL1", "手录商品甲", "1.00"))
        putLocal(local("LOCAL2", "手录商品乙", "2.00"))
        putSynced(synced("SYNC1", "服务器商品", "3.00"))
        assertEquals(3, dao.getAllProductsList(store).size)

        // 服务器返回**空的**商品表（新客户刚注册时就是这样）
        dao.replaceSyncedWithTokens(emptyList(), emptyList(), store)

        val after = dao.getAllProductsList(store)
        assertEquals("FULL 之后本地手录商品必须一条不少", 2, after.size)
        assertEquals(setOf("LOCAL1", "LOCAL2"), after.map { it.barcode }.toSet())
        assertEquals(0, dao.countSynced(store))
        assertEquals(2, dao.countLocal(store))
    }

    @Test
    fun `FULL_之后倒排索引对本地商品仍然有效`() = runBlocking {
        putLocal(local("LOCAL1", "手录可乐", "1.00"))
        dao.replaceSyncedWithTokens(emptyList(), emptyList(), store)
        // replaceSynced 会重建整个店铺的索引，本地商品的索引必须被重建而不是被清掉
        assertEquals(1, dao.searchTokenHits(store, listOf("可", "乐"), 1, 50).size)
        assertEquals("LOCAL1", dao.searchTokenHits(store, listOf("可", "乐"), 1, 50).single().barcode)
    }

    @Test
    fun `推送成功后本地行被_synced_遮住_删掉本地行不改变可见结果`() = runBlocking {
        putLocal(local("690", "可口可乐330ml", "3.50"))
        putSynced(synced("690", "可口可乐330ml", "3.50"))
        assertEquals(1, dao.getAllProductsList(store).size)
        // 中间态：本地行还在，但已被遮住 —— 这就是"自清"能安全异步进行的原因
        dao.removeLocalWithTokens(store, "690")
        val after = dao.getAllProductsList(store)
        assertEquals(1, after.size)
        assertEquals(Product.ORIGIN_SYNCED, after.single().origin)
    }

    // ==================== 倒排索引搜索 ====================

    @Test
    fun `按名字搜到商品`() = runBlocking {
        putLocal(local("690", "可口可乐330ml", "3.50"))
        putLocal(local("691", "百事可乐", "3.00"))
        putLocal(local("692", "雪碧", "3.00"))

        val hits = dao.searchTokenHits(store, ProductTokenizer.tokenizeQuery("可乐"), 1, 50)
        assertEquals(setOf("690", "691"), hits.map { it.barcode }.toSet())
    }

    @Test
    fun `命中的_token_数越多排越前`() = runBlocking {
        putLocal(local("A", "可乐", "1"))
        putLocal(local("B", "可口可乐", "1"))
        putLocal(local("C", "雪碧", "1"))
        val hits = dao.searchTokenHits(store, ProductTokenizer.tokenizeQuery("可口可乐"), 1, 50)
        assertEquals("B", hits.first().barcode)
        // 查询「可口可乐」去重后是 可/口/乐 三个 token，B「可口可乐」三个全中，A「可乐」中两个
        assertEquals(3, hits.first().hits)
        assertEquals("A", hits[1].barcode)
        assertEquals(2, hits[1].hits)
    }

    @Test
    fun `数字串作为完整_token_存在_短数字查询不命中`() = runBlocking {
        putLocal(local("690", "可口可乐330ml", "3.50"))
        // 查 330 命中
        assertEquals(1, dao.searchTokenHits(store, listOf("330"), 1, 50).size)
        // 查 33 / 30 / 3 都不命中 —— 与 v3 的 (?<!\d)N(?!\d) 正则语义等价，
        // 但不需要在 SQL 里跑正则（SQLite 也没有原生正则）
        assertEquals(0, dao.searchTokenHits(store, listOf("33"), 1, 50).size)
        assertEquals(0, dao.searchTokenHits(store, listOf("30"), 1, 50).size)
        assertEquals(0, dao.searchTokenHits(store, listOf("3"), 1, 50).size)
    }

    @Test
    fun `条码前缀搜索能搜出来`() = runBlocking {
        // 修的是 v3 的 bug：matchesNum 收了 barcode 参数却从未使用，
        // 而 OrderViewModel 又对纯数字输入直接返回空 → 条码根本搜不出来
        putLocal(local("6901234567890", "可口可乐330ml", "3.50"))
        putLocal(local("6901234567891", "雪碧330ml", "3.50"))
        putLocal(local("8801234567890", "韩国某商品", "9.00"))

        assertEquals(2, dao.searchByBarcodePrefix(store, "690123456789", 50).size)
        assertEquals(2, dao.searchByBarcodePrefix(store, "690", 50).size)
        assertEquals(1, dao.searchByBarcodePrefix(store, "880", 50).size)
        assertEquals(0, dao.searchByBarcodePrefix(store, "999", 50).size)
    }

    @Test
    fun `minHits_可以要求全部_token_都命中`() = runBlocking {
        putLocal(local("A", "可口可乐", "1"))
        putLocal(local("B", "可翻页", "1"))     // 只有「可」
        val tokens = ProductTokenizer.tokenizeQuery("可乐")
        assertEquals(2, dao.searchTokenHits(store, tokens, minHits = 1, limit = 50).size)
        assertEquals(1, dao.searchTokenHits(store, tokens, minHits = 2, limit = 50).size)
        assertEquals("A", dao.searchTokenHits(store, tokens, minHits = 2, limit = 50).single().barcode)
    }

    @Test
    fun `pos_记录了字符位置_相邻加分才有依据`() = runBlocking {
        putLocal(local("A", "可口可乐", "1"))
        val pos = dao.tokenPositions(store, listOf("可", "乐"))
        // ORDER BY barcode, pos → 「可」在 0 和 2，「乐」在 3。
        // 有了这些位置才能判断"可(2) 与 乐(3) 相邻"，从而给「可口可乐」加相邻分。
        assertEquals(
            listOf("可" to 0, "可" to 2, "乐" to 3),
            pos.map { it.token to it.pos }
        )
        assertTrue(pos.all { it.barcode == "A" })
    }

    // ==================== 删除与墓碑 ====================

    @Test
    fun `删除商品会同时清掉它的索引`() = runBlocking {
        putLocal(local("690", "可口可乐", "3.50"))
        assertEquals(1, dao.searchTokenHits(store, listOf("可"), 1, 50).size)
        dao.removeLocalWithTokens(store, "690")
        assertEquals("索引没清干净就会搜出已经不存在的商品",
            0, dao.searchTokenHits(store, listOf("可"), 1, 50).size)
        assertTrue(dao.getAllProductsList(store).isEmpty())
    }

    @Test
    fun `墓碑记下已删商品的名字`() = runBlocking {
        // 商品删掉之后，历史订单里只剩一串条码 —— 墓碑让它仍然可读
        dao.insertTombstone(ProductTombstone(store, "690", "可口可乐330ml", 123L))
        assertEquals("可口可乐330ml", dao.tombstoneName(store, "690"))
        assertNull(dao.tombstoneName(store, "999"))
        // IGNORE 策略：重复插入不覆盖（保留最早的名字）
        dao.insertTombstone(ProductTombstone(store, "690", "后来改的名字", 456L))
        assertEquals("可口可乐330ml", dao.tombstoneName(store, "690"))
    }

    // ==================== 上线闸门 ====================

    @Test
    fun `countPendingPush_是首次_FULL_之前的闸门依据`() = runBlocking {
        putLocal(local("A", "甲", "1"))
        putLocal(local("B", "乙", "2"))
        assertEquals(2, dao.countPendingPush(store))
        dao.setLocalPushState(store, "A", "PUSHED", null)
        assertEquals(1, dao.countPendingPush(store))
        // 计数 > 0 就必须拦下首次 FULL 走引导，不能直接整表替换
        assertTrue(dao.countPendingPush(store) > 0)
        assertFalse(dao.getAllProductsList(store).isEmpty())
    }
}
