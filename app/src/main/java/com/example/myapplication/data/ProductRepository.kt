package com.example.myapplication.data

import com.example.myapplication.db.CurrentStore
import com.example.myapplication.db.LOCAL_STORE_KEY
import com.example.myapplication.db.ProductDao
import com.example.myapplication.db.ProductExtra
import com.example.myapplication.db.ProductLocal
import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.db.ProductSynced
import com.example.myapplication.db.ProductToken
import com.example.myapplication.db.ProductTokenizer
import com.example.myapplication.db.TokenPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

/**
 * 商品的读写门面。**UI 只准通过它访问商品**，不要直接拿 DAO。
 *
 * ## 相对 v3 的变化
 *
 * 1. 所有查询都带上了 `storeKey` 作用域（来自 [CurrentStore]），所以登录/登出/换店铺时
 *    `flatMapLatest` 会让所有正在观察的 Flow **自动重新查询**，不需要重建 ViewModel。
 * 2. 删掉了 v3 那个**错误的增量搜索缓存**（原 L80-245）。它有三处问题：
 *    - 场景 2（`lastquery.startsWith(query)`）注释写"从已有 results 中筛选"，
 *      但**代码里没有任何筛选**，直接复用旧结果；
 *    - 场景 3 是 `mergedMap.putAll(...)`，做的是**并集不是交集**——
 *      输入越久结果越多，与"越输越窄"的直觉相反；
 *    - `cachedProducts` 是普通 `var`，无同步，多线程下会读到半更新的值。
 *    有了倒排索引之后这层缓存没有存在价值（400 商品的索引查询是毫秒级），删掉即净收益。
 * 3. 搜索改成倒排索引 + 条码前缀两条路，修掉了"条码根本搜不出来"的 bug：
 *    v3 的 `matchesNum(productName, token, barcode)` 收了 `barcode` 参数却**从未使用**，
 *    而 `OrderViewModel.searchProducts` 又对纯数字输入直接返回空。
 *
 * ## 写入语义
 *
 * - 未推送的本地商品 → 写 `product_local`
 * - 已同步商品的改动 → 写 `product_pending_change`（outbox）**并**乐观更新 `product_synced`，
 *   推送成功后清掉 pending 行。冲突解决在 P3 的 ProductSyncEngine 里做。
 * - `product_synced` 的权威写入只来自同步协议，UI 的乐观更新会被下一次同步纠正。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProductRepository(private val productDao: ProductDao) {

    private val storeKey: String get() = CurrentStore.now

    // ==================== 读 ====================

    /** 合并视图（`product_synced` 优先），随 storeKey 变化自动重查。 */
    fun getAllProducts(): Flow<List<Product>> =
        CurrentStore.key.flatMapLatest { productDao.getAllProducts(it) }

    suspend fun getAllProductsList(): List<Product> = productDao.getAllProductsList(storeKey)

    suspend fun getProductByBarcode(barcode: String): Product? =
        productDao.getProductByBarcode(storeKey, barcode)

    suspend fun getProductsByBarcodes(barcodes: List<String>): List<Product> =
        if (barcodes.isEmpty()) emptyList() else productDao.getProductsByBarcodes(storeKey, barcodes)

    /**
     * 搜索：条码前缀 + 名字倒排索引，两路合并去重。
     *
     * 条码命中排在最前面——扫码枪/手输条码是最常见的入口，
     * 而 v3 里这条路根本不通（纯数字输入直接返回空）。
     */
    suspend fun searchProducts(query: String): List<Product> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val byBarcode = if (q.first().isDigit()) {
            productDao.searchByBarcodePrefix(storeKey, q, limit = 20)
        } else emptyList()
        val byName = searchByName(q)
        if (byBarcode.isEmpty()) return byName
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Product>(byBarcode.size + byName.size)
        (byBarcode + byName).forEach { if (seen.add(it.barcode)) out.add(it) }
        return out
    }

    /** v3 的方法名，保留以免改动所有调用点。实现已换成倒排索引。 */
    suspend fun searchProductsByCharacters(query: String): List<Product> = searchProducts(query)

    /**
     * 倒排索引搜索 + 相邻位置加分。
     *
     * 排序权重（从高到低）：
     *  1. `hits` —— 命中的不同 token 数（与 v3 的 `1000 × 命中数` 同义）
     *  2. **相邻加分** —— 查询里连续的两个 token 在商品名里也连续，每对 +1
     *     （「可乐」两字挨着的排在「可…乐」分散的前面，这是用户要求的）
     *  3. `firstPos` 升序 —— 越靠前越好
     *  4. 名字越短越好（同分时更精确的匹配排前面）
     */
    private suspend fun searchByName(query: String): List<Product> {
        val tokens = ProductTokenizer.tokenizeQuery(query)
        if (tokens.isEmpty()) return emptyList()

        val hits = productDao.searchTokenHits(storeKey, tokens, minHits = 1, limit = 200)
        if (hits.isEmpty()) return emptyList()

        val positions: Map<String, List<TokenPosition>> =
            productDao.tokenPositions(storeKey, tokens).groupBy { it.barcode }
        val products = productDao.getProductsByBarcodes(storeKey, hits.map { it.barcode })
            .associateBy { it.barcode }

        data class Scored(val product: Product, val hits: Int, val adjacency: Int, val firstPos: Int)

        return hits.mapNotNull { h ->
            val p = products[h.barcode] ?: return@mapNotNull null
            Scored(p, h.hits, adjacencyBonus(tokens, positions[h.barcode].orEmpty()), h.firstPos)
        }.sortedWith(
            compareByDescending<Scored> { it.hits }
                .thenByDescending { it.adjacency }
                .thenBy { it.firstPos }
                .thenBy { it.product.name.length }
        ).map { it.product }
    }

    /**
     * 数一下查询里有多少对**相邻** token 在商品名里也相邻。
     *
     * 例：查询「可乐」→ tokens `["可","乐"]`；名字「可口可乐」的位置是 `可:0, 乐:1, 可:2`
     * → (可,乐) 在 0/1 相邻 ✓ → 加 1 分。名字「可翻乐」→ 可:0, 乐:2 → 不相邻 → 0 分。
     */
    private fun adjacencyBonus(queryTokens: List<String>, positions: List<TokenPosition>): Int {
        if (queryTokens.size < 2 || positions.isEmpty()) return 0
        // token -> 它出现过的所有位置
        val byToken = positions.groupBy({ it.token }, { it.pos })
        var bonus = 0
        for (i in 0 until queryTokens.size - 1) {
            val a = byToken[queryTokens[i]] ?: continue
            val b = byToken[queryTokens[i + 1]] ?: continue
            // 只要存在一对 (pa, pb) 满足 pb == pa + 1 就算相邻
            val bSet = b.toHashSet()
            if (a.any { (it + 1) in bSet }) bonus++
        }
        return bonus
    }

    // ==================== 写：未同步区 ====================

    /**
     * 新建/更新一条**本地**商品，并在同一个事务里重建它的倒排索引。
     *
     * ⚠ 索引必须与商品同事务写入，否则进程在两步之间被杀就会留下
     * "商品在、索引不在"（搜不出来）或反之——两种都不报错。
     */
    suspend fun insertProduct(product: Product) {
        val now = System.currentTimeMillis()
        val existing = productDao.getLocal(storeKey, product.barcode)
        productDao.putLocalWithTokens(
            ProductLocal(
                storeKey = storeKey,
                barcode = product.barcode,
                name = product.name,
                price = product.price,
                boxPrice = product.boxPrice,
                boxSize = product.boxSize,
                stock = product.stock,
                orderable = product.orderable,
                pushState = "PENDING",
                createdAtLocal = existing?.createdAtLocal ?: now,
                updatedAtLocal = now,
                lastPushError = null,
            ),
            tokensFor(product.barcode, product.name),
        )
        saveExtra(product)
    }

    /** 与 [insertProduct] 同一套逻辑，保留 v3 的方法名。 */
    suspend fun updateProduct(product: Product) {
        if (product.origin == Product.ORIGIN_SYNCED) {
            updateSyncedProduct(product)
        } else {
            insertProduct(product)
        }
    }

    /**
     * 删一条商品。已同步的排一笔 `op='DELETE'`，未推送的直接从本地区拿掉。
     *
     * ⚠ **员工删不掉已同步的商品**：`DELETE /api/products/{barcode}` 在
     * `ProductController:53` 上是 `isOwner(ma)` 门控，不看权限位。
     * 员工排队的那笔 DELETE 推上去必然 403，会一直重试到 `FAILED`。
     * UI（P7）必须按 `level` 把删除入口对员工禁用并说明原因，
     * 否则他看到的是"删了又回来"，而原因写在一条他永远不会去看的错误里。
     */
    suspend fun deleteProduct(barcode: String) {
        // 墓碑先记：商品删掉之后历史订单还要靠它显示名字
        productDao.getProductByBarcode(storeKey, barcode)?.let {
            productDao.insertTombstone(
                com.example.myapplication.db.ProductTombstone(storeKey, barcode, it.name, System.currentTimeMillis())
            )
        }
        productDao.removeLocalWithTokens(storeKey, barcode)
        if (productDao.getProductByBarcode(storeKey, barcode) != null) {
            // 还在 synced 表里 —— 排队一个 DELETE，等联网了推给服务器。
            // 拿掉镜像与排队必须在同一个事务里，见 ProductDao.queueSyncedDelete。
            productDao.queueSyncedDelete(storeKey, barcode, pendingDelete(barcode))
        }
    }

    /** 批量导入（`DatabaseImporter` 与导入文件走这里）。 */
    suspend fun insertProducts(products: List<Product>) {
        if (products.isEmpty()) return
        val now = System.currentTimeMillis()
        val locals = products.map {
            ProductLocal(
                storeKey = storeKey, it.barcode, it.name, it.price, it.boxPrice, it.boxSize,
                it.stock, it.orderable, "PENDING", now, now, null,
            )
        }
        productDao.putLocalsWithTokens(locals, locals.flatMap { tokensFor(it.barcode, it.name) })
    }

    suspend fun setCategory(barcode: String, category: String, note: String) {
        productDao.upsertExtra(ProductExtra(storeKey, barcode, category, note))
    }

    // ==================== 写：已同步商品的离线改动 ====================

    /**
     * 改一条已同步的商品：**排队 + 乐观更新**，两步在 DAO 的同一个事务里。
     *
     * 排队是为了联网后推上去；乐观更新是为了 UI 立刻反映改动。
     * 服务器是权威，下一次同步会用服务器的值纠正乐观更新——
     * 只要 pending 队列还在，就知道"这里有一笔我没推上去的改动"。
     *
     * ⚠ 两步不同事务的话，进程在中间被杀会留下"UI 显示改了、队列里没有" ——
     * 那笔改动永远不会被推上去，而下次 FULL 又把它悄悄抹回去。见 [ProductDao.queueSyncedEdit]。
     */
    private suspend fun updateSyncedProduct(product: Product) {
        productDao.upsertExtra(
            ProductExtra(storeKey, product.barcode, product.category, product.note)
        )
        val queuedAt = System.currentTimeMillis()
        productDao.queueSyncedEdit(
            synced = ProductSynced(
                storeKey = storeKey,
                barcode = product.barcode,
                name = product.name,
                price = product.price,
                boxPrice = product.boxPrice,
                boxSize = product.boxSize,
                stock = product.stock,
                orderable = product.orderable,
                updatedAt = queuedAt,
            ),
            tokens = tokensFor(product.barcode, product.name),
            change = ProductPendingChange(
                storeKey = storeKey,
                barcode = product.barcode,
                op = ProductPendingChange.OP_UPSERT,
                name = product.name,
                price = product.price,
                boxPrice = product.boxPrice,
                boxSize = product.boxSize,
                stock = product.stock,
                orderable = product.orderable,
                queuedAt = queuedAt,
                attemptCount = 0,
                lastError = null,
            ),
        )
    }

    /**
     * 排一笔 `op='DELETE'`。
     *
     * 除 barcode 之外的字段都是占位：服务器按 barcode 删，请求体里也不需要它们。
     * `name` 留空是因为**名字已经进墓碑表了**（[deleteProduct] 先写墓碑再删商品），
     * 历史订单靠墓碑显示，不靠这张队列表。
     */
    private fun pendingDelete(barcode: String) = ProductPendingChange(
        storeKey = storeKey, barcode = barcode, op = ProductPendingChange.OP_DELETE,
        name = "", price = java.math.BigDecimal.ZERO, boxPrice = null, boxSize = 0,
        stock = 0, orderable = true,
        queuedAt = System.currentTimeMillis(), attemptCount = 0, lastError = null,
    )

    private suspend fun saveExtra(product: Product) {
        if (product.category.isNotEmpty() || product.note.isNotEmpty()) {
            productDao.upsertExtra(ProductExtra(storeKey, product.barcode, product.category, product.note))
        }
    }

    private fun tokensFor(barcode: String, name: String): List<ProductToken> =
        ProductTokenizer.tokenizeWithPositions(name).map { (tok, pos) ->
            ProductToken(storeKey, tok, barcode, pos)
        }

    companion object {
        /** 离线模式的 storeKey，导出给 UI 判断"当前是否已登录"。 */
        const val OFFLINE_STORE_KEY: String = LOCAL_STORE_KEY
    }
}
