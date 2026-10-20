package com.example.myapplication.sync

import com.example.myapplication.db.BarcodeStock
import com.example.myapplication.db.FileVersion
import com.example.myapplication.db.StockAbsorbBoundaryEntity
import com.example.myapplication.db.StockApplyLogEntity
import com.example.myapplication.db.StockDao
import com.example.myapplication.db.StockDelta

/**
 * [StockDao] 的内存实现，给 JVM 上的同步测试共用
 * （`StockLedgerTest` 测四则运算与吸收边界，`ProductSyncEngineTest` 测"商品行与库存观测的先后顺序"）。
 *
 * ## 它照抄了什么、没照抄什么
 *
 * `baseline` / `allBaselines` / `deltaAfterBoundary` / `allDeltasAfterBoundary` 四个查询
 * **逐条对齐** `StockDao` 里那两段 SQL 常量（`DELTA_SELECT` / `DELTA_TAIL`）与合并视图语义。
 * `applyAbsolute` / `applyAbsoluteBatch` 用的是接口里的**真实默认实现**（Kotlin 接口默认方法体，
 * Room 生成的实现类也是继承它的），所以编排逻辑是被真测到的。
 *
 * **没有**被测到的是 SQL 本身（`CASE` / `IFNULL` / `GROUP BY` / `LIKE 'B:%'` / `UNION ALL` + `NOT IN`）。
 * 那部分由 `StockDaoTest`（androidTest，跑真 Room）覆盖，两边**刻意断言同一批场景**：
 * 谁改错了另一边会红。这是"抄两遍"唯一能接受的场合 —— 抄的是测试，不是产品代码。
 *
 * ## 为什么用 fake 而不是 Robolectric
 *
 * 项目里没有 Robolectric 依赖，真 SQLite 只能上 instrumented 测试，
 * 而 instrumented 测试在 CI 里要设备/模拟器。把"编排逻辑"与"SQL 语义"切开，
 * 前者就能在 JVM 上跑、秒级反馈。
 *
 * ## ⚠ 键一律用 `Pair`/`Triple`
 *
 * 不用 `"a|b"` 这种拼字符串的写法 —— 拼出来的键在值本身含分隔符时会撞，
 * 而撞了之后测试**照样通过**（只是断言的东西悄悄变了）。
 */
internal class FakeStockDao(
    /**
     * `product_synced` 的 `stock` 列。
     *
     * 做成构造参数是为了能与 [FakeProductDao] **共享同一份**：真实世界里这一列就长在
     * `product_synced` 表上，`ProductSyncEngine` 写整行、`StockLedger` 只写这一列。
     * 两个 fake 各存一份的话，`effectiveStock` 的断言就没有意义了 ——
     * 它会读到一份引擎从没写过的表。见 [fakeWorld]。
     */
    val synced: MutableMap<Pair<String, String>, Int> = LinkedHashMap(),

    /** `product_local` 的 `stock` 列。同 [synced] 的理由。 */
    val local: MutableMap<Pair<String, String>, Int> = LinkedHashMap(),

    /**
     * 调用顺序日志，同样可与 [FakeProductDao] 共享。
     *
     * 存在的全部理由是钉住「商品行必须在库存观测**之前**写好」这条顺序不变量。
     * 我试着构造过一个能靠**数值**区分正反两种顺序的场景，没成功：
     * `writeFromPriceEntry` 会把 `existing.stock` 原样带到新行上，而 `fetchUnknown`
     * 用的是服务端**当前**的值 —— 两条路径都会顺手把反序写坏的值修回来。
     * 也就是说这个 bug 现在被别的代码**掩盖**着，一旦那些代码被重构就会露出来，
     * 而届时没有任何数值断言会变红。所以只能断言顺序本身。
     */
    val ops: MutableList<String> = mutableListOf(),
) : StockDao {

    /** `file_state`：storeKey -> fileId -> version。 */
    val files = LinkedHashMap<String, LinkedHashMap<String, Int>>()

    /**
     * `stock_absorb_boundary`：(storeKey, fileId, **barcode**) -> boundaryVersion。
     *
     * ⚠ 键里必须有 barcode：边界是**每个条码各自的**（"这个条码的基线观测发生在哪个文件版本上"）。
     * 少了这一维，X 的一次观测会把 Y 的边界一起推过去 → Y 偏高，见
     * `StockAbsorbBoundaryEntity` 的类注释与 `X_的观测不许抬高_Y_的库存` 那条测试。
     */
    val boundary = LinkedHashMap<Triple<String, String, String>, Int>()

    /** `stock_apply_log` 的行（按插入顺序），主键 (storeKey, dedupKey)。 */
    val applyLog = mutableListOf<StockApplyLogEntity>()

    /** `order_index` JOIN `order_item_index`。 */
    val orders = LinkedHashMap<String, MutableList<FakeOrder>>()

    private var rowId = 0L

    var baselineCalls = 0; private set
    var allBaselinesCalls = 0; private set
    var deltaCalls = 0; private set
    var allDeltasCalls = 0; private set

    fun resetCounters() {
        baselineCalls = 0; allBaselinesCalls = 0; deltaCalls = 0; allDeltasCalls = 0
    }

    fun putSynced(storeKey: String, barcode: String, stock: Int) { synced[storeKey to barcode] = stock }
    fun putLocal(storeKey: String, barcode: String, stock: Int) { local[storeKey to barcode] = stock }

    /** 测试辅助：直接塞一行边界（对应真 SQL 测试里的 `setBoundary`）。 */
    fun setBoundary(storeKey: String, fileId: String, version: Int, barcode: String) {
        boundary[Triple(storeKey, fileId, barcode)] = version
    }

    /** 测试辅助：读一行边界，null = 没有这一行（语义上等于 0）。 */
    fun boundaryAt(storeKey: String, fileId: String, barcode: String): Int? =
        boundary[Triple(storeKey, fileId, barcode)]

    fun putFileVersion(storeKey: String, fileId: String, version: Int) {
        files.getOrPut(storeKey) { LinkedHashMap() }[fileId] = version
    }

    /** 模拟一次订单文件提交：版本 +1，返回新版本。 */
    fun bumpFileVersion(storeKey: String, fileId: String): Int {
        val m = files.getOrPut(storeKey) { LinkedHashMap() }
        val next = (m[fileId] ?: 0) + 1
        m[fileId] = next
        return next
    }

    fun addOrder(storeKey: String, o: FakeOrder) {
        orders.getOrPut(storeKey) { mutableListOf() }.add(o)
    }

    fun cancelOrder(storeKey: String, orderUuid: String, cancelAppliedVersion: Int) {
        val list = orders[storeKey]!!
        val i = list.indexOfFirst { it.orderUuid == orderUuid }
        list[i] = list[i].copy(cancelAppliedVersion = cancelAppliedVersion)
    }

    // ==================== StockDao 实现 ====================

    override suspend fun baseline(storeKey: String, barcode: String): Int? {
        baselineCalls++
        // UNION ALL + `NOT IN (SELECT barcode FROM product_synced WHERE storeKey=?)` 的语义：
        // 已同步区赢，本地区只补已同步区没有的条码。
        return synced[storeKey to barcode] ?: local[storeKey to barcode]
    }

    override suspend fun allBaselines(storeKey: String): List<BarcodeStock> {
        allBaselinesCalls++
        val syncedHere = synced.filterKeys { it.first == storeKey }
        val syncedBarcodes = syncedHere.keys.map { it.second }.toSet()
        val out = syncedHere.map { (k, v) -> BarcodeStock(k.second, v) }.toMutableList()
        for ((k, v) in local.filterKeys { it.first == storeKey }) {
            if (k.second !in syncedBarcodes) out.add(BarcodeStock(k.second, v))
        }
        return out
    }

    /**
     * 照抄 `DELTA_SELECT` + `DELTA_TAIL`。
     *
     * 三个必须对齐的细节：
     *  - `LEFT JOIN ... AND b.barcode = i.barcode`：边界**按条码**连，所以查表要在 item 循环里
     *    （同一笔订单的不同行属于不同条码，各自的边界可能不同）；
     *  - `IFNULL(b.boundaryVersion, 0)`：没有边界行时按 0 比，于是**所有**订单都算"边界之后"；
     *  - `GROUP BY i.barcode`：没有任何 B 类行的条码**不产生结果行**（单条查询返回 null）。
     */
    private fun deltaRows(storeKey: String, barcodeFilter: String?): Map<String, IntArray> {
        val acc = LinkedHashMap<String, IntArray>()
        for (o in orders[storeKey].orEmpty()) {
            if (!o.fileId.startsWith("B:")) continue                 // AND i.fileId LIKE 'B:%'
            for (item in o.items) {
                if (barcodeFilter != null && item.barcode != barcodeFilter) continue
                val b = boundaryAt(storeKey, o.fileId, item.barcode) ?: 0   // IFNULL(b.boundaryVersion, 0)
                val a = acc.getOrPut(item.barcode) { intArrayOf(0, 0) }
                // ⚠ 两项各自独立地跟边界比，不能用 status 代替（见 StockDelta 的 KDoc）。
                if (o.appliedFileVersion > b) a[0] += item.quantity
                val cv = o.cancelAppliedVersion
                if (cv != null && cv > b) a[1] += item.quantity
            }
        }
        return acc
    }

    override suspend fun deltaAfterBoundary(storeKey: String, barcode: String): StockDelta? {
        deltaCalls++
        return deltaRows(storeKey, barcode)[barcode]?.let { StockDelta(barcode, it[0], it[1]) }
    }

    override suspend fun allDeltasAfterBoundary(storeKey: String): List<StockDelta> {
        allDeltasCalls++
        return deltaRows(storeKey, null).map { (bc, a) -> StockDelta(bc, a[0], a[1]) }
    }

    override suspend fun writeSyncedStock(storeKey: String, barcode: String, stock: Int): Int {
        val key = storeKey to barcode
        if (key !in synced) return 0            // UPDATE 命中 0 行
        synced[key] = stock
        return 1
    }

    override suspend fun writeLocalStock(storeKey: String, barcode: String, stock: Int): Int {
        val key = storeKey to barcode
        if (key !in local) return 0
        local[key] = stock
        return 1
    }

    override suspend fun fileVersions(storeKey: String): List<FileVersion> =
        (files[storeKey] ?: emptyMap()).map { (fileId, version) -> FileVersion(fileId, version) }

    override suspend fun upsertBoundaries(rows: List<StockAbsorbBoundaryEntity>) {
        for (e in rows) boundary[Triple(e.storeKey, e.fileId, e.barcode)] = e.boundaryVersion
    }

    override suspend fun insertApplyLog(row: StockApplyLogEntity): Long {
        // 记在这里而不是 `writeSyncedStock`：这是**每次观测尝试**的唯一入口，
        // 写基线那两个方法在命中 0 行时也会被调（退回去写本地表），会记重。
        ops += "obs:${row.barcode}"
        val key = row.storeKey to row.dedupKey
        if (applyLog.any { it.storeKey to it.dedupKey == key }) return -1L   // IGNORE 冲突
        applyLog.add(row)
        return ++rowId
    }

    override suspend fun boundaryOf(storeKey: String, fileId: String, barcode: String): Int? =
        boundaryAt(storeKey, fileId, barcode)
}

internal data class FakeItem(val barcode: String, val quantity: Int)

internal data class FakeOrder(
    val fileId: String,
    val orderUuid: String,
    val appliedFileVersion: Int,
    val cancelAppliedVersion: Int? = null,
    val items: List<FakeItem> = emptyList(),
)
