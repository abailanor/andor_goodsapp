package com.example.myapplication.sync

import com.example.myapplication.data.Product
import com.example.myapplication.db.EnumFloor
import com.example.myapplication.db.ProductDao
import com.example.myapplication.db.ProductExtra
import com.example.myapplication.db.ProductLocal
import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.db.ProductSynced
import com.example.myapplication.db.ProductToken
import com.example.myapplication.db.ProductTokenizer
import com.example.myapplication.db.ProductTombstone
import com.example.myapplication.db.StoreMeta
import com.example.myapplication.db.StoreMetaDao
import com.example.myapplication.db.TokenHit
import com.example.myapplication.db.TokenPosition
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.ProductApi
import com.example.myapplication.net.dto.BatchUpsertResultDto
import com.example.myapplication.net.dto.ProductAddRequestDto
import com.example.myapplication.net.dto.ProductDataDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.math.BigDecimal

/**
 * [ProductSyncEngine] 的三个 fake，加上把它们接起来的 [FakeWorld]。
 *
 * ## 为什么 `FakeProductDao` 只实现抽象方法
 *
 * `ProductDao` 里那 10 个 `@Transaction` 方法都有**默认实现体**（`putSyncedWithTokens`、
 * `replaceSyncedWithTokens`、`queueSyncedEdit` …），Kotlin 接口的默认方法体会被实现类继承，
 * Room 生成的实现类也是这么干的。所以 fake 不重写它们 —— **编排逻辑本身是被真测到的**，
 * 包括「FULL 整表替换之后要把 `product_local` 的倒排索引一起重建」这一步
 * （漏了它，本地商品会还在列表里却搜不出来，见 `FULL_之后本地行仍然搜得出来`）。
 *
 * 与 [FakeStockDao] 同理：`applyAbsolute*` 走的也是真默认实现体。
 *
 * ## ⚠ 两个 fake 必须共享 `stock` 列
 *
 * 真实世界里 `stock` 就长在 `product_synced` / `product_local` 表上：
 * 引擎写整行、`StockLedger` 只写这一列。两个 fake 各存一份的话，
 * `effectiveStock` 会去读一张引擎从没写过的表 —— 断言看着通过了，测的却是另一个世界。
 * [FakeWorld] 负责把同一份 Map 传给两边。
 */
internal class FakeProductDao(
    /** `product_synced.stock`，与 [FakeStockDao.synced] 是同一个对象。 */
    private val stockSynced: MutableMap<Pair<String, String>, Int>,
    /** `product_local.stock`，与 [FakeStockDao.local] 是同一个对象。 */
    private val stockLocal: MutableMap<Pair<String, String>, Int>,
    /** 调用顺序日志，与 [FakeStockDao.ops] 是同一个对象。 */
    private val ops: MutableList<String>,
) : ProductDao {

    val synced = LinkedHashMap<Pair<String, String>, ProductSynced>()
    val local = LinkedHashMap<Pair<String, String>, ProductLocal>()
    val extra = LinkedHashMap<Pair<String, String>, ProductExtra>()
    val tombstones = LinkedHashMap<Pair<String, String>, ProductTombstone>()

    /** 键就是主键 (storeKey, token, barcode, pos)，而 `ProductToken` 是 data class → Set 即 REPLACE。 */
    val tokens = linkedSetOf<ProductToken>()

    val pending = LinkedHashMap<Pair<String, String>, ProductPendingChange>()

    // ---------- 测试辅助（非 suspend，@Before 里也能用） ----------

    fun putSyncedRow(row: ProductSynced) {
        synced[row.storeKey to row.barcode] = row
        stockSynced[row.storeKey to row.barcode] = row.stock
    }

    fun putLocalRow(row: ProductLocal) {
        local[row.storeKey to row.barcode] = row
        stockLocal[row.storeKey to row.barcode] = row.stock
    }

    fun syncedStock(storeKey: String, barcode: String): Int? = stockSynced[storeKey to barcode]
    fun localStock(storeKey: String, barcode: String): Int? = stockLocal[storeKey to barcode]

    fun tokensOf(storeKey: String, barcode: String): List<ProductToken> =
        tokens.filter { it.storeKey == storeKey && it.barcode == barcode }

    // ==================== 合并视图 ====================

    /**
     * 照抄 `EFFECTIVE_PRODUCT_JOIN`：synced 优先，local 只补 synced 没有的条码，
     * `category`/`note` 走 LEFT JOIN `product_extra`（`IFNULL(...,'')`）。
     *
     * ⚠ `stock` 读的是**共享的那份 Map**，不是行里存的值 ——
     * `StockLedger.writeSyncedStock` 只改这一列，改完之后合并视图必须看得见，
     * 否则 `writeFromPriceEntry` 会把过期的 stock 又抄回新行里。
     */
    private fun project(row: ProductSynced): Product {
        val key = row.storeKey to row.barcode
        val e = extra[key]
        return Product(
            barcode = row.barcode,
            name = row.name,
            price = row.price,
            boxPrice = row.boxPrice,
            boxSize = row.boxSize,
            stock = stockSynced[key] ?: row.stock,
            orderable = row.orderable,
            updatedAt = row.updatedAt,
            category = e?.category ?: "",
            note = e?.note ?: "",
            origin = Product.ORIGIN_SYNCED,
            storeKey = row.storeKey,
        )
    }

    private fun project(row: ProductLocal): Product {
        val key = row.storeKey to row.barcode
        val e = extra[key]
        return Product(
            barcode = row.barcode,
            name = row.name,
            price = row.price,
            boxPrice = row.boxPrice,
            boxSize = row.boxSize,
            stock = stockLocal[key] ?: row.stock,
            orderable = row.orderable,
            updatedAt = row.updatedAtLocal,
            category = e?.category ?: "",
            note = e?.note ?: "",
            origin = Product.ORIGIN_LOCAL,
            storeKey = row.storeKey,
        )
    }

    private fun merged(storeKey: String): List<Product> {
        val syncedHere = synced.filterKeys { it.first == storeKey }
        val syncedBarcodes = syncedHere.keys.map { it.second }.toSet()
        val out = syncedHere.values.map { project(it) }.toMutableList()
        for ((k, v) in local.filterKeys { it.first == storeKey }) {
            if (k.second !in syncedBarcodes) out += project(v)
        }
        // `ORDER BY p.name COLLATE NOCASE`。SQLite 的 NOCASE 只折叠 ASCII，fake 不必更精确。
        return out.sortedBy { it.name.lowercase() }
    }

    override fun getAllProducts(storeKey: String): Flow<List<Product>> = flowOf(merged(storeKey))

    override suspend fun getAllProductsList(storeKey: String): List<Product> = merged(storeKey)

    override suspend fun getProductByBarcode(storeKey: String, barcode: String): Product? =
        merged(storeKey).firstOrNull { it.barcode == barcode }

    override suspend fun searchByName(storeKey: String, query: String): List<Product> =
        merged(storeKey).filter { it.name.contains(query) }.sortedBy { it.name.lowercase() }

    override suspend fun searchByBarcodePrefix(storeKey: String, prefix: String, limit: Int): List<Product> =
        merged(storeKey).filter { it.barcode.startsWith(prefix) }.sortedBy { it.barcode }.take(limit)

    override suspend fun searchTokenHits(
        storeKey: String,
        tokens: List<String>,
        minHits: Int,
        limit: Int,
    ): List<TokenHit> {
        val wanted = tokens.toSet()
        return this.tokens.asSequence()
            .filter { it.storeKey == storeKey && it.token in wanted }
            .groupBy { it.barcode }
            .map { (barcode, rows) -> TokenHit(barcode, rows.map { it.token }.distinct().size, rows.minOf { it.pos }) }
            .filter { it.hits >= minHits }
            .sortedWith(compareByDescending<TokenHit> { it.hits }.thenBy { it.firstPos })
            .take(limit)
            .toList()
    }

    override suspend fun tokenPositions(storeKey: String, tokens: List<String>): List<TokenPosition> {
        val wanted = tokens.toSet()
        return this.tokens.asSequence()
            .filter { it.storeKey == storeKey && it.token in wanted }
            .sortedWith(compareBy<ProductToken> { it.barcode }.thenBy { it.pos })
            .map { TokenPosition(it.barcode, it.token, it.pos) }
            .toList()
    }

    override suspend fun getProductsByBarcodes(storeKey: String, barcodes: List<String>): List<Product> {
        val wanted = barcodes.toSet()
        return merged(storeKey).filter { it.barcode in wanted }
    }

    // ==================== product_local ====================

    override suspend fun upsertLocal(product: ProductLocal) {
        ops += "row:${product.barcode}"
        putLocalRow(product)
    }

    override suspend fun upsertLocals(products: List<ProductLocal>) = products.forEach { upsertLocal(it) }

    override suspend fun getLocal(storeKey: String, barcode: String): ProductLocal? = local[storeKey to barcode]

    override suspend fun getAllLocal(storeKey: String): List<ProductLocal> =
        local.filterKeys { it.first == storeKey }.values.sortedBy { it.name.lowercase() }

    override suspend fun deleteLocal(storeKey: String, barcode: String) {
        local.remove(storeKey to barcode)
        stockLocal.remove(storeKey to barcode)
    }

    override suspend fun countLocal(storeKey: String): Int = local.count { it.key.first == storeKey }

    override suspend fun countPendingPush(storeKey: String): Int =
        local.count { it.key.first == storeKey && it.value.pushState != ProductLocal.PUSH_PUSHED }

    override suspend fun setLocalPushState(storeKey: String, barcode: String, state: String, error: String?) {
        val k = storeKey to barcode
        // ⚠ 真 SQL 的 UPDATE 命中 0 行时**不报错**。fake 照抄这一点：
        // `pushOutbox` 会给"只在改动队列里、`product_local` 没有对应行"的条码调这个方法，
        // 那时它就该静默什么都不做。
        local[k]?.let { local[k] = it.copy(pushState = state, lastPushError = error) }
    }

    // ==================== product_synced ====================

    override suspend fun upsertSynced(product: ProductSynced) {
        // 记 `row:` 是为了钉住「商品行先于库存观测」这条顺序（见 [FakeStockDao.ops]）。
        ops += "row:${product.barcode}"
        putSyncedRow(product)
    }

    override suspend fun upsertSynceds(products: List<ProductSynced>) = products.forEach { upsertSynced(it) }

    override suspend fun getAllSynced(storeKey: String): List<ProductSynced> =
        synced.filterKeys { it.first == storeKey }.values.toList()

    override suspend fun deleteSynced(storeKey: String, barcode: String) {
        synced.remove(storeKey to barcode)
        stockSynced.remove(storeKey to barcode)
    }

    override suspend fun clearSynced(storeKey: String) {
        synced.keys.filter { it.first == storeKey }.forEach {
            synced.remove(it)
            stockSynced.remove(it)
        }
    }

    override suspend fun countSynced(storeKey: String): Int = synced.count { it.key.first == storeKey }

    // ==================== product_pending_change ====================

    override suspend fun upsertPendingChange(change: ProductPendingChange) {
        pending[change.storeKey to change.barcode] = change
    }

    override suspend fun pendingChanges(storeKey: String): List<ProductPendingChange> =
        pending.filterKeys { it.first == storeKey }.values
            .sortedWith(compareBy<ProductPendingChange> { it.queuedAt }.thenBy { it.barcode })

    override suspend fun pendingChange(storeKey: String, barcode: String): ProductPendingChange? =
        pending[storeKey to barcode]

    override suspend fun countPendingChanges(storeKey: String): Int = pending.count { it.key.first == storeKey }

    override suspend fun countPendingDeletes(storeKey: String): Int =
        pending.count { it.key.first == storeKey && it.value.op == ProductPendingChange.OP_DELETE }

    override suspend fun deletePendingChange(storeKey: String, barcode: String) {
        pending.remove(storeKey to barcode)
    }

    override suspend fun markPendingChangeAttempt(storeKey: String, barcode: String, error: String?) {
        val k = storeKey to barcode
        pending[k]?.let { pending[k] = it.copy(attemptCount = it.attemptCount + 1, lastError = error) }
    }

    override suspend fun clearPendingChanges(storeKey: String) {
        pending.keys.filter { it.first == storeKey }.forEach { pending.remove(it) }
    }

    // ==================== extra / 墓碑 / 倒排索引 ====================

    override suspend fun upsertExtra(extra: ProductExtra) {
        this.extra[extra.storeKey to extra.barcode] = extra
    }

    override suspend fun getExtra(storeKey: String, barcode: String): ProductExtra? = extra[storeKey to barcode]

    override suspend fun insertTombstone(tombstone: ProductTombstone) {
        val k = tombstone.storeKey to tombstone.barcode
        // IGNORE：**保留第一次**的名字。商品删→重建→再删时墓碑里是旧名，
        // 这是有意的（历史订单显示旧名比显示不出名字好）。
        if (k !in tombstones) tombstones[k] = tombstone
    }

    override suspend fun tombstoneName(storeKey: String, barcode: String): String? =
        tombstones[storeKey to barcode]?.name

    override suspend fun insertTokens(tokens: List<ProductToken>) {
        this.tokens.addAll(tokens)
    }

    override suspend fun deleteTokens(storeKey: String, barcode: String) {
        tokens.removeAll { it.storeKey == storeKey && it.barcode == barcode }
    }

    override suspend fun deleteAllTokens(storeKey: String) {
        tokens.removeAll { it.storeKey == storeKey }
    }
}

// ==================== store_meta ====================

internal class FakeStoreMetaDao : StoreMetaDao {

    val rows = LinkedHashMap<String, StoreMeta>()

    /** 每次游标写入记一笔，用来断言「SERVER_BEHIND 一个字都不写」。 */
    val cursorWrites = mutableListOf<Pair<String, Int>>()

    /** 测试辅助：直接塞一行（`upsert` 是 suspend，`@Before` 里调不了）。 */
    fun put(meta: StoreMeta) {
        rows[meta.storeKey] = meta
    }

    override suspend fun upsert(meta: StoreMeta) = put(meta)

    override suspend fun get(storeKey: String): StoreMeta? = rows[storeKey]

    override fun observe(storeKey: String): Flow<StoreMeta?> = flowOf(rows[storeKey])

    override suspend fun getAll(): List<StoreMeta> = rows.values.sortedByDescending { it.adoptedAt }

    override suspend fun getAllStoreKeys(): List<String> = rows.keys.toList()

    override suspend fun setPriceVersion(storeKey: String, v: Int) {
        // ⚠ 照抄真 SQL 的 UPDATE：没有这一行时命中 0 行、**不报错**。
        // 这正是 `ProductSyncEngine.apply` 开头必须先 `get` 一次的原因 ——
        // 少了那次检查，游标会永远停在 0，于是每次同步都 FULL 一遍。
        cursorWrites += "price" to v
        rows[storeKey]?.let { rows[storeKey] = it.copy(priceVersion = v) }
    }

    override suspend fun setStockVersion(storeKey: String, v: Int) {
        cursorWrites += "stock" to v
        rows[storeKey]?.let { rows[storeKey] = it.copy(stockVersion = v) }
    }

    override suspend fun lowerEnumFloor(storeKey: String, year: Int, month: Int): Int {
        val cur = rows[storeKey] ?: return 0
        val lower = cur.enumFloorYear > year || (cur.enumFloorYear == year && cur.enumFloorMonth > month)
        if (!lower) return 0
        rows[storeKey] = cur.copy(enumFloorYear = year, enumFloorMonth = month)
        return 1
    }

    override suspend fun getEnumFloor(storeKey: String): EnumFloor? =
        rows[storeKey]?.let { EnumFloor(it.enumFloorYear, it.enumFloorMonth) }

    override suspend fun setPerms(storeKey: String, permsCsv: String) {
        rows[storeKey]?.let { rows[storeKey] = it.copy(permsCsv = permsCsv) }
    }

    override suspend fun recordLogin(storeKey: String, previousLoginAt: Long, currentLoginAt: Long) {
        rows[storeKey]?.let {
            rows[storeKey] = it.copy(lastLoginAt = currentLoginAt, previousLoginAt = previousLoginAt)
        }
    }

    override suspend fun adoptStoreKey(oldKey: String, newKey: String, now: Long) {
        val m = rows.remove(oldKey) ?: return
        rows[newKey] = m.copy(storeKey = newKey, adoptedAt = now)
    }
}

// ==================== 商品接口 ====================

/**
 * [ProductApi] 的可编排 fake。
 *
 * 三个 `*Error` 字段是"这一类调用一律失败"的粗粒度开关 —— 引擎的分支只需要
 * "这个条码拉不到"与"这批推不上去"两种，按条码细分没有测试用得上。
 */
internal class FakeProductApi : ProductApi {

    /** 服务端那份商品表。`getProduct` 从这里取，取不到就是 [ApiError.NotFound]。 */
    val server = LinkedHashMap<String, ProductDataDto>()

    var getProductError: ApiError? = null
    var batchUpsertError: ApiError? = null
    var deleteError: ApiError? = null

    val getProductCalls = mutableListOf<String>()
    /** 每次 `batch-upsert` 收到的**那一整批**（断言"每批 ≤100"与"每条都带 orderable"用）。 */
    val upsertBatches = mutableListOf<List<ProductAddRequestDto>>()
    val deleteCalls = mutableListOf<String>()

    fun putServer(
        barcode: String,
        name: String,
        price: String = "1.00",
        boxPrice: String? = null,
        boxSize: Int? = 0,
        stock: Int? = 0,
        orderable: Boolean? = true,
        updatedAt: Long? = null,
    ) {
        server[barcode] = ProductDataDto(barcode, name, price, boxPrice, boxSize, stock, orderable, updatedAt)
    }

    override suspend fun getProduct(barcode: String): ProductDataDto {
        getProductCalls += barcode
        getProductError?.let { throw it }
        return server[barcode] ?: throw ApiError.NotFound("商品不存在: $barcode")
    }

    override suspend fun batchUpsertProducts(
        products: List<ProductAddRequestDto>,
    ): List<BatchUpsertResultDto> {
        // 先记后抛：失败的那一批也要能被断言到（"整批标记失败"依赖它)。
        upsertBatches += products
        batchUpsertError?.let { throw it }
        return products.map { BatchUpsertResultDto(it.barcode, it.barcode !in server) }
    }

    override suspend fun deleteProduct(barcode: String) {
        deleteCalls += barcode
        deleteError?.let { throw it }
        server.remove(barcode)
    }
}

// ==================== 把四个 fake 接成一个世界 ====================

/**
 * 一次测试要用的全部协作者。
 *
 * 存在的全部理由是**接线本身容易错**：两个 DAO fake 必须共享同一份 stock Map（见
 * [FakeProductDao] 的类注释），共享错了测试照样绿、只是测的是另一个世界。
 * 把接线收在一处，就不会每写一条测试都重犯一次。
 */
internal class FakeWorld(val storeKey: String = "kehu") {

    /** 钉住时钟：`updatedAt`/`deletedAt`/`appliedAt` 都是给人看的字段，但断言要可重现。 */
    val now = 1_700_000_000_000L

    val ops = mutableListOf<String>()
    private val stockSynced = LinkedHashMap<Pair<String, String>, Int>()
    private val stockLocal = LinkedHashMap<Pair<String, String>, Int>()

    val products = FakeProductDao(stockSynced, stockLocal, ops)
    val stock = FakeStockDao(stockSynced, stockLocal, ops)
    val meta = FakeStoreMetaDao()
    val api = FakeProductApi()

    val ledger = StockLedger(stock, now = { now })
    val engine = ProductSyncEngine(products, meta, ledger, api, now = { now })

    fun seedMeta(priceVersion: Int = 0, stockVersion: Int = 0) {
        meta.put(
            StoreMeta(
                storeKey = storeKey,
                ownerName = "alice",
                username = "alice",
                level = "OWNER",
                createdAt = 0L,
                enumFloorYear = 2020,
                enumFloorMonth = 1,
                priceVersion = priceVersion,
                stockVersion = stockVersion,
                lastLoginAt = 0L,
                previousLoginAt = 0L,
                adoptedAt = 0L,
                permsCsv = "",
                adminCode = "",
            )
        )
    }

    fun seedSynced(
        barcode: String,
        name: String,
        price: String = "1.00",
        stock: Int = 0,
        boxPrice: String? = null,
        boxSize: Int = 0,
        orderable: Boolean = true,
    ) {
        products.putSyncedRow(
            ProductSynced(
                storeKey = storeKey,
                barcode = barcode,
                name = name,
                price = price.toBigDecimal(),
                boxPrice = boxPrice?.let { BigDecimal(it) },
                boxSize = boxSize,
                stock = stock,
                orderable = orderable,
                updatedAt = now,
            )
        )
        products.tokens.addAll(
            ProductTokenizer.tokenizeWithPositions(name).map { (t, p) -> ProductToken(storeKey, t, barcode, p) }
        )
    }

    fun seedLocal(
        barcode: String,
        name: String,
        price: String = "1.00",
        stock: Int = 0,
        boxPrice: String? = null,
        boxSize: Int = 0,
        orderable: Boolean = true,
        pushState: String = ProductLocal.PUSH_PENDING,
    ) {
        products.putLocalRow(
            ProductLocal(
                storeKey = storeKey,
                barcode = barcode,
                name = name,
                price = price.toBigDecimal(),
                boxPrice = boxPrice?.let { BigDecimal(it) },
                boxSize = boxSize,
                stock = stock,
                orderable = orderable,
                pushState = pushState,
                createdAtLocal = now,
                updatedAtLocal = now,
                lastPushError = null,
            )
        )
        products.tokens.addAll(
            ProductTokenizer.tokenizeWithPositions(name).map { (t, p) -> ProductToken(storeKey, t, barcode, p) }
        )
    }

    fun seedExtra(barcode: String, category: String = "", note: String = "") {
        products.extra[storeKey to barcode] = ProductExtra(storeKey, barcode, category, note)
    }

    /**
     * 把一行退回"还没推过"，模拟**HTTP 超时**：请求其实到了服务端，
     * 但客户端没能记下 `PUSH_PUSHED`，重启后会重推同一批。
     */
    fun resetPushState(barcode: String) {
        val k = storeKey to barcode
        products.local[k]?.let { products.local[k] = it.copy(pushState = ProductLocal.PUSH_PENDING) }
    }

    fun seedPending(
        barcode: String,
        op: String = ProductPendingChange.OP_UPSERT,
        name: String = "",
        price: String = "1.00",
        stock: Int = 0,
        orderable: Boolean = true,
        attemptCount: Int = 0,
        queuedAt: Long = 0L,
    ) {
        products.pending[storeKey to barcode] = ProductPendingChange(
            storeKey = storeKey,
            barcode = barcode,
            op = op,
            name = name,
            price = price.toBigDecimal(),
            boxPrice = null,
            boxSize = 0,
            stock = stock,
            orderable = orderable,
            queuedAt = queuedAt,
            attemptCount = attemptCount,
            lastError = null,
        )
    }
}
