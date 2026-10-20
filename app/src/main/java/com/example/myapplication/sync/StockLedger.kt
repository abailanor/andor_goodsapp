package com.example.myapplication.sync

import com.example.myapplication.db.StockDao
import com.example.myapplication.db.StockObservation

/**
 * 库存的**唯一**计算入口。
 *
 * ## 为什么需要这么个东西：服务端把库存算了两遍，而协议不提供交叉参照
 *
 * `order_write.lua` 第 5 节对 B 类订单**直接** `HSET product:<code>:<barcode> stock <newStock>`，
 * 注释里明确写了"**不再写 stream:stock，也不再推进 version_stock**"。
 *
 * 推论：库存流里的 `new_stock` 是那个 hash 的绝对值，**已经吸收了此前所有 B 类订单的扣减**。
 * README §2.3 只警告了正向（"别去库存流找 B 类订单的变化"），没警告**反向**：
 * 一旦收到一个新的绝对值，你此前已应用的那些订单增量有一部分已被它吸收，
 * 再叠加就是**双算**。而 `version_stock` 与 per-fileId 的订单版本是**两个独立空间，
 * 协议不提供任何交叉参照** —— 所以没有任何办法"按时间对齐"。
 *
 * ## 解法：不维护运行中的库存数字
 *
 * ```
 * effectiveStock(barcode)
 *   = 基线（最后一次绝对观测值）
 *   - Σ qty  WHERE 卖出发生在 absorbBoundary[fileId] 之后
 *   + Σ qty  WHERE 取消回退发生在 absorbBoundary[fileId] 之后
 * ```
 *
 * 订单侧的贡献**不存累加值**，每次从 `order_item_index JOIN order_index` 现算。
 * 于是：
 *  - **天然幂等**：同一笔订单查两次不会双扣；
 *  - **天然自愈**：任何一次绝对观测把边界推到当前，之前的订单自动被基线吸收；
 *  - **完全不依赖时钟**：切分依据是版本号，不是 timestamp。
 *    （用时间切分是退化方案：FULL 回来的 `products` 不带 `updatedAt`，只能猜。）
 *
 * 结构上不可能双算 —— 这是"约定性防护换成结构性防护"的又一处。
 *
 * ## 四道防线
 *
 *  1. 本类是**单独改 stock** 的唯一入口（[StockDao] 里只有 `applyAbsolute*` 能动这一列）。
 *     `ProductDao` 的整行 upsert 也会带上 `stock`，但那只发生在"收到服务器权威数据"的路径上，
 *     不是旁路 —— 详见 [StockDao] 的类注释；
 *  2. `stock_apply_log` 主键 `(storeKey, dedupKey)` UNIQUE，重复应用直接被忽略；
 *  3. 订单侧只做集合求和，结构上无法双算；
 *  4. `/add` 的 B 类响应带 `stockChanges[].newStock`，promote 时用 [reconcile] 对账。
 *
 * ## ⚠ [now] 只用于诊断字段
 *
 * `appliedAt` 是给人看的时间戳，走墙钟（见 `MonotonicClock` 的类注释：
 * "订单里的 `content.ts`、日志行的时间——那些是给人看的墙钟"）。
 * **任何判定都不许读它**。做成构造参数是为了让测试能钉住时间、结果可重现。
 */
class StockLedger(
    private val dao: StockDao,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 某个条码当前的有效库存。
     *
     * @return null = 这个条码两张商品表里都没有（已删或从未存在）。
     *         **不要用 0 代替 null**：补货筛选的条件是"库存 < 阈值"，
     *         把"查不到"当成 0 会让每个已删商品都进补货清单。
     */
    suspend fun effectiveStock(storeKey: String, barcode: String): Int? {
        val base = dao.baseline(storeKey, barcode) ?: return null
        return base + (dao.deltaAfterBoundary(storeKey, barcode)?.net ?: 0)
    }

    /**
     * 全部商品的有效库存，两次聚合查询搞定（补货清单用）。
     *
     * 只包含**商品表里存在**的条码：订单里引用了已删商品时，那些行会被丢掉 ——
     * 这是对的，已删商品不该出现在补货清单里；它的名字仍能从 `product_tombstone` 读到，
     * 所以历史订单照样能显示。
     */
    suspend fun effectiveStockAll(storeKey: String): Map<String, Int> {
        val deltas: Map<String, Int> = dao.allDeltasAfterBoundary(storeKey)
            .associate { it.barcode to it.net }
        return dao.allBaselines(storeKey).associate { it.barcode to (it.stock + (deltas[it.barcode] ?: 0)) }
    }

    /**
     * 应用一次绝对库存观测。
     *
     * @param dedupKey 见 [DedupKeys]。**同一次观测被重投递必须产生同一个 key，
     *                 不同的观测必须产生不同的 key** —— 这两条任一写错都会静默算错库存：
     *                 前者写错 → SSE 重连后重投递被重复应用（边界被推得太远 → 库存偏高）；
     *                 后者写错 → 第二次商品 FULL 被当成重复而跳过（基线永不更新 → 库存停在旧值）。
     * @param source `STOCK_STREAM` / `STOCK_API` / `PRODUCT_FULL` / `BATCH_UPSERT` / `RECONCILE`。
     * @return false = 这条之前已经应用过，本次什么都没改。
     */
    suspend fun applyAbsolute(
        storeKey: String,
        dedupKey: String,
        barcode: String,
        newStock: Int,
        source: String,
    ): Boolean = dao.applyAbsolute(storeKey, dedupKey, barcode, newStock, source, now())

    /**
     * 批量应用一组绝对观测：商品 FULL（每条商品一次）与 `batch-upsert` 回推走这里。
     *
     * 逐条调 [applyAbsolute] 在 400 条商品上是 400 个事务、约 800 次 fsync，
     * 卡顿点恰好在"同步刚落地、UI 正要刷新"的时候。语义完全一致，见
     * [StockDao.applyAbsoluteBatch]（尤其是"整批都撞去重时**不推边界**"那条）。
     *
     * @param observations 应按版本号升序；同一 barcode 出现多次时后者覆盖前者。
     * @return 实际应用的条数。0 = 整批都是重复的，什么都没改。
     */
    suspend fun applyAbsoluteBatch(
        storeKey: String,
        observations: List<StockObservation>,
        source: String,
    ): Int = dao.applyAbsoluteBatch(storeKey, observations, source, now())

    /**
     * 与服务器返回的绝对值对账（`/add` 的 `stockChanges[].newStock`）。
     *
     * ## ⚠ 规则必须**不对称**
     *
     * `newStock` 是服务器**那一刻**的绝对值，而本地可能还含有此后别的设备的单没同步过来。
     * 所以：
     *
     * | 关系 | 含义 | 处理 |
     * |---|---|---|
     * | `effective > server` | 本地还没同步到别的设备的销售 | **正常**，不告警 |
     * | `effective < server` | 本地扣得比服务器还多 = 双扣了，或漏吸收了一次回退 | **告警 + 自愈** |
     *
     * 对称地写（`!=` 就告警）会让每次正常的多设备使用都报一次假警，
     * 用户三次之后就再也不看告警了 —— 那比没有告警更糟。
     *
     * ## 自愈为什么是安全的
     *
     * 服务器给的是**绝对值**，按定义就是权威的。把它当一次新的绝对观测应用
     * （重置基线 + 把边界推到当前）必然收敛，不需要知道本地是哪里算错的。
     * 这也正是"不维护运行中的数字"这个设计换来的好处：错了就重新对齐，没有累积误差要修。
     *
     * @param dedupKey 必须标识**这一次服务器响应事件**，不是标识那个数值：
     *                 `/add` 的响应用 `orderUuid`（+barcode）、库存流消息用 entry id、
     *                 显式改库存用 [DedupKeys.stockApi]。理由见 [DedupKeys.stockApi] 的注释
     *                 —— 用数值做 key 会让"同一个值出现两次"被误判成重投递。
     * @return 对账结果。[Result.LOCAL_TOO_LOW] 表示已经自愈；`healed` 为 false 的含义见该分支的注释。
     */
    suspend fun reconcile(
        storeKey: String,
        dedupKey: String,
        barcode: String,
        serverNewStock: Int,
    ): Result {
        val effective = effectiveStock(storeKey, barcode)
            ?: return Result.UNKNOWN_PRODUCT
        return when {
            effective > serverNewStock -> Result.LOCAL_AHEAD
            effective == serverNewStock -> Result.MATCH
            else -> {
                val healed = applyAbsolute(storeKey, dedupKey, barcode, serverNewStock, SOURCE_RECONCILE)
                Result.LOCAL_TOO_LOW(healed)
            }
        }
    }

    /** [reconcile] 的结果。 */
    sealed interface Result {
        /** 本地与服务器一致。 */
        data object MATCH : Result

        /** 本地比服务器多（还没同步到别的设备的销售）。正常。 */
        data object LOCAL_AHEAD : Result

        /**
         * 本地比服务器少 = 算错了。已尝试用服务器的绝对值自愈。
         *
         * @param healed false 表示这条观测（同一个 `dedupKey`）**之前已经用过了**，本次什么都没改。
         *               此时本地会保持偏低，直到下一次**新的**绝对观测
         *               （下一条库存流消息、下一次商品 FULL、或下一次响应值不同的 `/add` 对账）把它拉回来。
         *
         *               ⚠ **不要**为了"修好它"而现编一个新的 `dedupKey` 强行自愈 ——
         *               那会把边界推到当前，把这条观测**之后**才落地的订单增量也吸收进基线，
         *               库存于是变成偏**高**。偏低会被补货清单看见（顶多多订一次货），
         *               偏高不会（等到卖光了才发现），所以撞去重时保持偏低是安全的那一侧。
         */
        data class LOCAL_TOO_LOW(val healed: Boolean) : Result

        /** 这个条码本地没有（已删商品）。服务器仍然会回它的库存，忽略即可。 */
        data object UNKNOWN_PRODUCT : Result
    }

    /**
     * `dedupKey` 的构造规则。集中在这里是因为**写错的后果是静默的库存错误**，
     * 让每个调用点自己想一个字符串迟早会想出两个一样的。
     */
    object DedupKeys {
        /**
         * 库存/属性流消息。用条目里的 `version` 字段。
         *
         * ## ⚠ 不是 Redis 的 entry id —— 客户端根本收不到它
         *
         * `RedisSyncService.recordToFieldsMap`(:109) 只映射 `record.getValue()`，
         * 也就是**条目字段**，`record.getId()` 被丢掉了。所以 SSE 的 `UPDATE` 里
         * 每条 entry 就是一个 `Map<String,String>`，没有任何 id。
         *
         * 能用的只有 `product_stream_entry.lua` 第 3 步注入的 `version`：
         * 它是 `version_stock` / `version_price` 自增后的值，与 XADD 在**同一个脚本里**
         * 原子完成，所以在同一条流内唯一、单调、且**重投递时不变** —— 正是需要的性质。
         * （脚本注释 §(2)(3) 说明了为什么必须在脚本内分配：Java 侧读改写会撞号，
         * 而撞号会让客户端按 `version > 本地版本` 过滤时永久漏掉一条变更。）
         *
         * ⚠ 必须带上 `kind`：属性流与库存流是两个不同的 stream、两个不同的版本计数器，
         * `version=7` 在两边都可能出现。少了它，属性流的第 7 条会把库存流的第 7 条顶掉。
         */
        fun stream(kind: String, version: Int) = "S:$kind:$version"

        /**
         * 商品 FULL。用这次同步的 `priceVersion` **与** `stockVersion`。
         *
         * ## ⚠ 必须两个版本号都带上
         *
         * `RedisSyncService.buildSyncResponse`(:57) 的条件是 `needFullStock || needFullPrice`
         * —— 只由**库存**过时触发的 FULL 完全可能带着一个与上次相同的 `priceVersion`。
         * 只按 `priceVersion` 做 key 的话，那次 FULL 会被当成重复而跳过，
         * 库存基线永远停在旧值上，而且没有任何报错。
         *
         * 两个版本号都没变才说明数据真的没变（所有商品改动都会推进其中之一），跳过是无害的。
         *
         * ⚠ 同理**绝不能**用 `"FULL:$barcode"` —— 每一次 FULL 都会被当成重复。
         */
        fun productFull(priceVersion: Int, stockVersion: Int, barcode: String) =
            "F:$priceVersion:$stockVersion:$barcode"

        /**
         * `batch-upsert` 回推。回推的 `stock` 是客户端自己算出来的绝对值
         * （方案 R2：`用户填的当前真实库存 + 该商品历史总销量`），
         * 服务器按绝对值落地，所以推完立刻拿它当一次观测吸收掉。
         *
         * @param batchId 本次推送的批次标识（调用方生成，重试同一批时必须复用）。
         */
        fun batchUpsert(batchId: String, barcode: String) = "U:$batchId:$barcode"

        /**
         * 显式的库存接口响应（`PUT /api/products/stock*`）。
         *
         * ## ⚠ key 里**不能**放 `newStock`
         *
         * 直觉写法是 `"A:$barcode:$newStock"`。用户把库存两次都设成 100 时，
         * 第二次响应会被当成重复而跳过 → 基线本来就是 100（看不出问题），
         * 但**边界没有推** → 两次之间落地的订单仍被扣一遍 → 库存偏低，无报错。
         *
         * ## ⚠ 也**不能**用随机 nonce
         *
         * 那样 HTTP 重试会产生两个 nonce，同一次观测被吸收两次 → 边界推过头 →
         * 把此后别的设备的扣减也"吸收"进基线 → 库存偏高，同样无报错。
         *
         * ## 所以：调用方按"一次用户动作"生成，重试时复用
         *
         * 与 [batchUpsert] 的 `batchId` 完全同一套规则。判定"这是同一次动作的重试"
         * 只能由发起请求的那一层做 —— 它知道自己是不是在重发。
         */
        fun stockApi(requestId: String, barcode: String) = "A:$requestId:$barcode"
    }

    /**
     * `source` 的取值。只写进 `stock_apply_log` 供诊断（"这个基线是哪来的"），
     * **不参与任何判定**。集中定义是因为散在各处的字符串字面量迟早会拼错一个，
     * 而拼错的 source 只是让日志难读 —— 但读日志的人那时正在查一个库存错误。
     */
    companion object {
        /** 库存流 `stream:stock` 的 `new_stock`（INCREMENTAL / SSE 推送）。 */
        const val SOURCE_STOCK_STREAM = "STOCK_STREAM"

        /** `PUT /api/products/stock*` 响应里的 `newStock` / `correctedStock`。 */
        const val SOURCE_STOCK_API = "STOCK_API"

        /** 商品 FULL 的 `products[].stock`。 */
        const val SOURCE_PRODUCT_FULL = "PRODUCT_FULL"

        /**
         * 属性流 `action=ADD` 但本地没有这个条码时，补拉 `GET /api/products/{barcode}`
         * 拿到的整行（两条流都不带 `name`/`orderable`，见 `StreamFields` 的陷阱三）。
         *
         * 整行写入本身就是一次绝对观测，所以必须配套推边界 —— 与 [SOURCE_PRODUCT_FULL] 同理。
         */
        const val SOURCE_PRODUCT_FETCH = "PRODUCT_FETCH"

        /** `POST /api/products/batch-upsert` 回推后，把推上去的值当一次观测吸收。 */
        const val SOURCE_BATCH_UPSERT = "BATCH_UPSERT"

        /** [reconcile] 发现本地偏低时的自愈。 */
        const val SOURCE_RECONCILE = "RECONCILE"
    }
}
