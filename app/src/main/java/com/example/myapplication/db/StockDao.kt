package com.example.myapplication.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * 某个 barcode 在**吸收边界之后**的订单侧增量。
 *
 * @param soldAfter 边界之后被应用的卖出数量合计（要从基线里**减掉**）
 * @param refundedAfter 边界之后被取消而回退的数量合计（要**加回**基线）
 *
 * ## ⚠ 这两项必须各自独立地跟边界比，不能用 `status` 代替
 *
 * 直觉写法是"只统计 `status=1`（未取消）的订单"，那是错的。设想边界落在
 * 下单版本与取消版本**之间**：下单在 v10（已被基线吸收）、取消在 v20（还没被吸收）。
 * 此时订单 `status=0`，按直觉写法它整行被跳过 → **那笔回退永久丢失**，库存偏低，
 * 而且没有任何报错。
 *
 * 正确的模型是把库存看成一串带版本的事件：`appliedFileVersion` 处 `-qty`、
 * `cancelAppliedVersion` 处 `+qty`。每个事件**各自**判断"是否发生在边界之后"。
 * 两个 CASE 因此都不能省略，`WHERE` 里也**不能**按 `appliedFileVersion` 过滤行
 * （那会把上面那种行整行滤掉）。
 */
data class StockDelta(
    val barcode: String,
    val soldAfter: Int,
    val refundedAfter: Int,
) {
    /** 加到基线上的净增量（通常是负数）。 */
    val net: Int get() = refundedAfter - soldAfter
}

/** `file_state` 的 (fileId, version)，推吸收边界时用。 */
data class FileVersion(val fileId: String, val version: Int)

/** 某条码的绝对基线。见 [StockDao.allBaselines]。 */
data class BarcodeStock(val barcode: String, val stock: Int)

/**
 * 一次绝对库存观测，给 [StockDao.applyAbsoluteBatch] 用。
 *
 * `dedupKey` 的构造规则见 [com.example.myapplication.sync.StockLedger.DedupKeys]。
 */
data class StockObservation(
    val dedupKey: String,
    val barcode: String,
    val newStock: Int,
)

/**
 * 增量聚合的 SELECT 头。抽成常量是因为单条与全量两个查询**必须共用同一套 CASE** ——
 * 抄两遍就一定有一处漏改，而漏改的那处不会报错，只会算错。
 *
 * ## ⚠ 边界要按 **barcode** 连，不能只按 fileId
 *
 * `b.barcode = i.barcode` 这一条是整个模型的正确性所在：每个条码的基线来自它自己
 * 那次观测，边界因此也是它自己的。只按 fileId 连的话，X 的一次观测会把 Y 的边界一起
 * 推过去，Y 那笔尚未被基线吸收的扣减就此消失 → 库存偏**高**（不安全的那一侧）。
 * 完整反例见 [StockAbsorbBoundaryEntity] 的类注释。
 *
 * `IFNULL(...,0)`：这个 (barcode,fileId) 还没有边界行时按 0 比，所有增量都算"边界之后"
 * → 偏低 → 安全的一侧，且下一次观测就会补上。
 */
private const val DELTA_SELECT = """
    SELECT i.barcode AS barcode,
           SUM(CASE WHEN o.appliedFileVersion > IFNULL(b.boundaryVersion, 0)
                    THEN i.quantity ELSE 0 END) AS soldAfter,
           SUM(CASE WHEN o.cancelAppliedVersion IS NOT NULL
                     AND o.cancelAppliedVersion > IFNULL(b.boundaryVersion, 0)
                    THEN i.quantity ELSE 0 END) AS refundedAfter
    FROM order_item_index i
    JOIN order_index o
      ON o.storeKey = i.storeKey AND o.fileId = i.fileId AND o.orderUuid = i.orderUuid
    LEFT JOIN stock_absorb_boundary b
      ON b.storeKey = i.storeKey AND b.fileId = i.fileId AND b.barcode = i.barcode
"""

/**
 * 增量聚合的 WHERE 尾 + GROUP BY。
 *
 * ⚠ `fileId LIKE 'B:%'` 只算**卖出**（B 类）。A 类是向丽华金龙进货，
 * 它的库存变化由总部那边管，不进本店的销售扣减。
 * ⚠ 这里**不能**再按 `appliedFileVersion` 过滤行，理由见 [StockDelta] 的注释。
 */
private const val DELTA_TAIL = """
    AND i.fileId LIKE 'B:%'
    GROUP BY i.barcode
"""

/**
 * 库存的存储层。
 *
 * ## ⚠ 这里是"改库存"的唯一入口，但**不是** `stock` 列的唯一写者
 *
 * [com.example.myapplication.sync.StockLedger] 是"库存不双算"的第一道防线，
 * 而防线成立的前提是：**除了 [applyAbsolute]，没有任何代码单独改 stock**。
 * 这个 DAO 里只有 [applyAbsolute] / [applyAbsoluteBatch] 会动它（经 [writeSyncedStock] /
 * [writeLocalStock]）。将来谁想加一个"直接改库存"的入口，先想清楚它会绕过吸收边界。
 *
 * ⚠ 严格说 `ProductDao.upsertSynced` / `replaceSyncedWithTokens` 也会写 `stock` ——
 * 它们是**整行** upsert，带着服务器那次观测的全部字段。这不破坏防线，
 * 因为整行写只发生在"收到服务器权威数据"的路径上（FULL / 属性流落地），
 * 而那些路径本来就该把 stock 一起写掉。防线要拦的是**只改 stock 一个列**的旁路：
 * 那种写入没有配套的边界推进，于是订单侧的同一笔扣减会被再算一次。
 */
@Dao
interface StockDao {

    // ==================== 读 ====================

    /**
     * 当前的绝对基线。**合并视图语义**：`product_synced` 优先，其次 `product_local`。
     *
     * 与 `ProductDao` 的 `EFFECTIVE_PRODUCT_JOIN` 保持一致 —— 两处语义不同的话，
     * 开单页显示的库存与补货筛选算出来的库存会对不上，而两边都"看起来是对的"。
     *
     * @return null 表示这个条码两张表里都没有（商品已删或从未存在）。
     */
    @Query(
        """
        SELECT stock FROM product_synced WHERE storeKey = :storeKey AND barcode = :barcode
        UNION ALL
        SELECT stock FROM product_local
        WHERE storeKey = :storeKey AND barcode = :barcode
          AND barcode NOT IN (SELECT barcode FROM product_synced WHERE storeKey = :storeKey)
        LIMIT 1
        """
    )
    suspend fun baseline(storeKey: String, barcode: String): Int?

    /**
     * 全部商品的基线，与 [baseline] 同一套合并视图语义。
     *
     * 给 [StockDao.allDeltasAfterBoundary] 配对用：补货清单要一次算出 400 条的
     * `effectiveStock`，两边各查一次再在 Kotlin 里合并，比逐条查 400 次快一个数量级。
     */
    @Query(
        """
        SELECT barcode AS barcode, stock AS stock FROM product_synced WHERE storeKey = :storeKey
        UNION ALL
        SELECT barcode AS barcode, stock AS stock FROM product_local
        WHERE storeKey = :storeKey
          AND barcode NOT IN (SELECT barcode FROM product_synced WHERE storeKey = :storeKey)
        """
    )
    suspend fun allBaselines(storeKey: String): List<BarcodeStock>

    /**
     * 边界之后的订单侧增量。**没有订单时返回 null**（不是全 0 的对象）——
     * 调用方按"没有增量"处理，结果与"增量恰好为 0"等价。
     */
    @Query("$DELTA_SELECT WHERE i.storeKey = :storeKey AND i.barcode = :barcode $DELTA_TAIL")
    suspend fun deltaAfterBoundary(storeKey: String, barcode: String): StockDelta?

    /**
     * 全部商品的增量，一次聚合。补货清单（P9）要一次算 400 条，逐条查会慢一个数量级。
     *
     * ⚠ 这是对 `order_item_index` 的**全表聚合**（只按 storeKey 与 `B:` 前缀过滤）。
     * 用户规模（一天上百单、每单上百行）下一个月能到十万行量级，
     * 所以只在补货页打开时调，**不要**放进任何逐帧 recompose 的路径。
     */
    @Query("$DELTA_SELECT WHERE i.storeKey = :storeKey $DELTA_TAIL")
    suspend fun allDeltasAfterBoundary(storeKey: String): List<StockDelta>

    // ==================== 写：绝对库存观测 ====================

    /**
     * 应用一次**绝对库存观测**，一个事务里做完三件事：
     *
     *  1. 往 `stock_apply_log` 插去重记录 —— 插不进去说明**这一条已经应用过了**，
     *     直接返回 false，基线与边界都不动（重复应用会把边界推得太远）；
     *  2. 把基线写成 [newStock]（`product_synced` 没这个条码时退回 `product_local`）；
     *  3. 把**这个条码**在所有 fileId 上的吸收边界推到它们当前的 `file_state.version`。
     *
     * ## ⚠ 第 3 步为什么必须在同一个事务里读 `file_state`
     *
     * 边界要推到"观测发生那一刻各文件的版本"。如果在事务外先查一遍版本再传进来，
     * 中间恰好有订单文件同步提交，写进去的就是**过期的小版本** → 那笔订单的扣减
     * 既在基线里、又落在边界之后 → **双算**，库存偏低。
     *
     * 事务里读则保证"写基线"与"读版本"之间不会插入任何提交。
     *
     * ## ⚠ 为什么推的是**所有** fileId 而不是一个
     *
     * 服务器给的 `new_stock` 是 `product:<code>:<barcode>` 这个 hash 的绝对值，
     * 它吸收了**该店铺所有 B 类订单文件**到目前为止的扣减，而不只是某一个月的。
     * 只推当月的边界，其它月份的订单增量就会被再算一遍。
     *
     * ## ⚠ 但推的只是**这一个 barcode** 的边界
     *
     * "所有 fileId"是文件维度，**不是**商品维度。别的条码的基线还是它们各自上一次观测时的值，
     * 把它们也推过去等于宣布"那些扣减已被吸收"，而并没有 → 库存偏高。
     * 见 [StockAbsorbBoundaryEntity] 的类注释里那个 X/Y 反例。
     *
     * 单条观测。批量场景（商品 FULL、`batch-upsert` 回推）走 [applyAbsoluteBatch] ——
     * 语义相同，只是边界只推一次、且整批在一个事务里。
     *
     * @param at 观测时间，只用于诊断，**不参与任何判定**（判定一律用版本号，不依赖时钟 ——
     *           FULL 回来的 `products` 不带 `updatedAt`，用时间切分会退化成猜）。
     * @return false = 这条 [dedupKey] 之前已经应用过，本次什么都没改。
     */
    @Transaction
    suspend fun applyAbsolute(
        storeKey: String,
        dedupKey: String,
        barcode: String,
        newStock: Int,
        source: String,
        at: Long,
    ): Boolean = applyAbsoluteBatch(
        storeKey, listOf(StockObservation(dedupKey, barcode, newStock)), source, at,
    ) == 1

    /**
     * 一个事务里应用**一批**绝对观测。
     *
     * ## 为什么需要它
     *
     * 商品 FULL 会对每条商品各产生一次观测（400 条商品 = 400 次）。
     * 逐条调 [applyAbsolute] 就是 400 个事务，`JournalMode.TRUNCATE` 下每个约 2 次 fsync
     * —— 低端机上这是秒级卡顿，而卡顿点恰好在"同步刚落地、UI 正要刷新"的时候。
     *
     * ## ⚠ 语义与 [applyAbsolute] 一致，只有两点不同
     *
     * **一、`file_state` 只读一次，边界一次性写完。** 一批观测来自同一个时刻
     * （同一次 FULL / 同一次 `batch-upsert` 响应），所以"各 fileId 当时的 version"
     * 对整批是同一个向量；只是每个**实际被应用的条码**都要复制一份
     * （边界带 barcode 维度，理由见 [StockAbsorbBoundaryEntity]）。
     * 同一条码在批内出现两次只写一份（`appliedBarcodes` 是集合）。
     *
     * **二、`applied == 0` 时一次都不推。** 整批都撞去重 = 这批观测之前已经应用过了，
     * 此时再推边界会把边界推过"那之后才落地的订单增量"，而那些增量并没有被这批旧基线吸收
     * → 库存偏**高**。偏高比偏低危险：偏低会在补货清单上被看见，偏高要等到卖断货才发现。
     * （单条的 [applyAbsolute] 里这条规则表现为"去重命中就 `return false`"。）
     *
     * ## ⚠ 逐条 IGNORE，不做 `WHERE dedupKey IN (:keys)` 预筛
     *
     * 预筛看着更省，但 Android 上 `SQLITE_MAX_VARIABLE_NUMBER` 在旧版本是 **999**：
     * 400 条商品勉强够，一次 FULL 带 1000 条就抛 SQLiteException。
     * 而且"哪些 key 已存在"这个信息本来就由 IGNORE 的返回值给出，预筛是多余的一次往返。
     *
     * @param observations 允许为空（返回 0）。允许同一个 barcode 出现多次 ——
     *        两条不同的观测（不同 `dedupKey`）会**按列表顺序**依次写基线，后者覆盖前者，
     *        这与"逐条调用"的结果一致。调用方应当按版本号升序传入。
     * @return 实际应用的条数。0 = 整批都是重复的，什么都没改。
     */
    @Transaction
    suspend fun applyAbsoluteBatch(
        storeKey: String,
        observations: List<StockObservation>,
        source: String,
        at: Long,
    ): Int {
        if (observations.isEmpty()) return 0
        var applied = 0
        // 只有**真正写了基线**的条码才推边界。同一条码在批内出现两次只需一份。
        val appliedBarcodes = LinkedHashSet<String>(observations.size)
        for (o in observations) {
            // IGNORE + 返回 rowId：插不进去时 Room 返回 -1。
            // ⚠ 不要用 OnConflictStrategy.REPLACE —— 那样重复应用会**静默成功**并继续推边界，
            // 去重日志形同虚设，而这正是"不双算"的第二道防线。
            if (insertApplyLog(
                    StockApplyLogEntity(
                        storeKey = storeKey,
                        dedupKey = o.dedupKey,
                        barcode = o.barcode,
                        newStock = o.newStock,
                        source = source,
                        appliedAt = at,
                    )
                ) == -1L
            ) continue

            // 先写已同步区；0 行说明这个条码只在本地（离线期手录、还没推上去）。
            if (writeSyncedStock(storeKey, o.barcode, o.newStock) == 0) {
                writeLocalStock(storeKey, o.barcode, o.newStock)
            }
            applied++
            appliedBarcodes += o.barcode
        }
        if (applied == 0) return 0

        val files = fileVersions(storeKey)
        // 一个文件都还没同步过时 files 为空：没有订单增量需要吸收，边界行也就不必存在
        // （缺行的语义是 `IFNULL(boundaryVersion,0)`，与"边界=0"完全等价）。
        if (files.isNotEmpty()) {
            val boundaries = ArrayList<StockAbsorbBoundaryEntity>(appliedBarcodes.size * files.size)
            for (barcode in appliedBarcodes) {
                for (f in files) {
                    boundaries += StockAbsorbBoundaryEntity(
                        storeKey = storeKey,
                        fileId = f.fileId,
                        barcode = barcode,
                        boundaryVersion = f.version,
                        updatedAt = at,
                    )
                }
            }
            upsertBoundaries(boundaries)
        }
        return applied
    }

    /**
     * 只写 `product_synced` 的库存。@return 受影响的行数（0 = 这个条码不在已同步区）。
     *
     * 公开是因为 Room 生成的实现类要能覆盖它；**不要**在 DAO 之外直接调，
     * 那会绕过 [applyAbsolute] 的去重与边界推进。
     */
    @Query("UPDATE product_synced SET stock = :stock WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun writeSyncedStock(storeKey: String, barcode: String, stock: Int): Int

    /** 只写 `product_local` 的库存。@return 受影响的行数。同 [writeSyncedStock] 的告诫。 */
    @Query("UPDATE product_local SET stock = :stock WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun writeLocalStock(storeKey: String, barcode: String, stock: Int): Int

    @Query("SELECT fileId AS fileId, version AS version FROM file_state WHERE storeKey = :storeKey")
    suspend fun fileVersions(storeKey: String): List<FileVersion>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBoundaries(rows: List<StockAbsorbBoundaryEntity>)

    /** @return 新行的 rowId，**冲突被忽略时返回 -1**。见 [applyAbsolute]。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertApplyLog(row: StockApplyLogEntity): Long

    // ==================== 诊断 ====================

    /**
     * 某个 (barcode, fileId) 当前的吸收边界。没有记录返回 null（语义上等于 0：
     * 从没吸收过任何东西）。调试页与 `StockDaoTest` 用。
     */
    @Query(
        """
        SELECT boundaryVersion FROM stock_absorb_boundary
        WHERE storeKey = :storeKey AND fileId = :fileId AND barcode = :barcode
        """
    )
    suspend fun boundaryOf(storeKey: String, fileId: String, barcode: String): Int?
}
