package com.example.myapplication.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.myapplication.data.Product
import kotlinx.coroutines.flow.Flow

/**
 * 商品合并视图（`product_synced` 优先）的 SQL 片段。
 *
 * 抽成常量是因为它被 5 个查询共用，而**语义必须完全一致**：
 * 任何一处漏了 `NOT IN` 子查询，同一个 barcode 就会在列表里出现两次
 * （一次 SYNCED 一次 LOCAL），开单时选中哪一条取决于排序，结果不可预测。
 *
 * `synced 优先` 的理由：服务器是权威。本地行只在"服务器还不知道这个条码"时才透出。
 * 推送成功后服务器会写属性流 `ADD`，增量同步回来落进 `product_synced`，
 * 本地行随即被这个视图遮住（之后再删掉），中间态因此是自洽的。
 *
 * `category`/`note` 走 LEFT JOIN `product_extra` —— 那张表 FULL 永远不碰，
 * 所以本地扩展字段能在同步之间活下来。
 *
 * ⚠ **这个片段以 LEFT JOIN 结尾，所以调用方接的必须是 `WHERE`，不能是 `AND`。**
 * 写成 `AND` 的话条件会被拼进 `ON` 子句——LEFT JOIN 的 ON 只决定"哪一行 extra 参与连接"，
 * **不过滤左表的行**。结果是查询返回全部商品，而且不报错。
 * 这个 bug 是 `ProductSearchTest.条码前缀搜索能搜出来` 抓出来的。
 */
private const val EFFECTIVE_PRODUCT_JOIN = """
    FROM (
        SELECT storeKey, barcode, name, price, boxPrice, boxSize, stock, orderable,
               updatedAt, 'SYNCED' AS origin
        FROM product_synced WHERE storeKey = :storeKey
        UNION ALL
        SELECT storeKey, barcode, name, price, boxPrice, boxSize, stock, orderable,
               updatedAtLocal AS updatedAt, 'LOCAL' AS origin
        FROM product_local
        WHERE storeKey = :storeKey
          AND barcode NOT IN (SELECT barcode FROM product_synced WHERE storeKey = :storeKey)
    ) p
    LEFT JOIN product_extra e ON e.storeKey = p.storeKey AND e.barcode = p.barcode
"""

private const val EFFECTIVE_PRODUCT_COLUMNS = """
    SELECT p.storeKey AS storeKey, p.barcode AS barcode, p.name AS name, p.price AS price,
           p.boxPrice AS boxPrice, p.boxSize AS boxSize, p.stock AS stock,
           p.orderable AS orderable, p.updatedAt AS updatedAt, p.origin AS origin,
           IFNULL(e.category, '') AS category, IFNULL(e.note, '') AS note
"""

@Dao
interface ProductDao {

    // ==================== 读：合并视图 ====================

    @Query("$EFFECTIVE_PRODUCT_COLUMNS $EFFECTIVE_PRODUCT_JOIN ORDER BY p.name COLLATE NOCASE")
    fun getAllProducts(storeKey: String): Flow<List<Product>>

    @Query("$EFFECTIVE_PRODUCT_COLUMNS $EFFECTIVE_PRODUCT_JOIN")
    suspend fun getAllProductsList(storeKey: String): List<Product>

    @Query("$EFFECTIVE_PRODUCT_COLUMNS $EFFECTIVE_PRODUCT_JOIN WHERE p.barcode = :barcode")
    suspend fun getProductByBarcode(storeKey: String, barcode: String): Product?

    /**
     * 名字模糊搜索（LIKE 兜底路径）。
     *
     * 正常的搜索走倒排索引（[searchByTokens]），LIKE 只在"查询串是一个连续子串"时
     * 作为补充——例如用户粘了一整段商品名。
     */
    @Query("$EFFECTIVE_PRODUCT_COLUMNS $EFFECTIVE_PRODUCT_JOIN WHERE p.name LIKE '%' || :query || '%' ORDER BY p.name COLLATE NOCASE")
    suspend fun searchByName(storeKey: String, query: String): List<Product>

    /**
     * 条码**前缀**搜索。
     *
     * 修的是一个现存 bug：`ProductRepository.matchesNum(productName, token, barcode)`
     * 收了 `barcode` 参数却**从未使用**，而 `OrderViewModel.searchProducts` 又对纯数字
     * 输入直接返回空 —— 结果是条码根本搜不出来，只能靠名字找。
     *
     * `barcode` 是主键的第一列（在 storeKey 之后），`LIKE 'xxx%'` 前缀匹配能用上索引；
     * `LIKE '%xxx%'` 不能，所以只支持前缀。
     */
    @Query("$EFFECTIVE_PRODUCT_COLUMNS $EFFECTIVE_PRODUCT_JOIN WHERE p.barcode LIKE :prefix || '%' ORDER BY p.barcode LIMIT :limit")
    suspend fun searchByBarcodePrefix(storeKey: String, prefix: String, limit: Int): List<Product>

    /**
     * 倒排索引搜索的候选集。
     *
     * `token IN (...)` 是等值查询，**不需要正则**——数字边界规则已经由分词规则免费实现
     * （见 [ProductTokenizer] 的说明）。
     *
     * 返回 `hits`（命中的不同 token 数）与 `firstPos`（最小位置），
     * "相邻位置加分"在外层 Kotlin 里对这个小候选集算（SQL 里做窗口函数不值得）。
     *
     * ⚠ Room 的 `IN (:tokens)` 需要一个 `List<String>` 参数，展开由 Room 生成。
     */
    @Query(
        """
        SELECT t.barcode AS barcode, COUNT(DISTINCT t.token) AS hits, MIN(t.pos) AS firstPos
        FROM product_token t
        WHERE t.storeKey = :storeKey AND t.token IN (:tokens)
        GROUP BY t.barcode
        HAVING hits >= :minHits
        ORDER BY hits DESC, firstPos ASC
        LIMIT :limit
        """
    )
    suspend fun searchTokenHits(
        storeKey: String,
        tokens: List<String>,
        minHits: Int,
        limit: Int,
    ): List<TokenHit>

    /** 命中某组 token 的条码，以及该条码在这些 token 上的全部位置（算相邻加分用）。 */
    @Query(
        """
        SELECT barcode, token, pos FROM product_token
        WHERE storeKey = :storeKey AND token IN (:tokens)
        ORDER BY barcode, pos
        """
    )
    suspend fun tokenPositions(storeKey: String, tokens: List<String>): List<TokenPosition>

    @Query("$EFFECTIVE_PRODUCT_COLUMNS $EFFECTIVE_PRODUCT_JOIN WHERE p.barcode IN (:barcodes)")
    suspend fun getProductsByBarcodes(storeKey: String, barcodes: List<String>): List<Product>

    // ==================== 未同步区：product_local ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocal(product: ProductLocal)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLocals(products: List<ProductLocal>)

    @Query("SELECT * FROM product_local WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun getLocal(storeKey: String, barcode: String): ProductLocal?

    @Query("SELECT * FROM product_local WHERE storeKey = :storeKey ORDER BY name COLLATE NOCASE")
    suspend fun getAllLocal(storeKey: String): List<ProductLocal>

    @Query("DELETE FROM product_local WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun deleteLocal(storeKey: String, barcode: String)

    @Query("SELECT COUNT(*) FROM product_local WHERE storeKey = :storeKey")
    suspend fun countLocal(storeKey: String): Int

    /**
     * 首次商品 FULL 之前的闸门。
     *
     * 计数 > 0 说明本地有服务器不知道的商品，**必须拦下来走引导**
     * （batch-upsert 推上去 / 用户明确确认覆盖），不能直接整表替换。
     * 分区设计已经让 FULL 碰不到这张表，这个计数是给"上线向导"用的进度依据。
     */
    @Query("SELECT COUNT(*) FROM product_local WHERE storeKey = :storeKey AND pushState != 'PUSHED'")
    suspend fun countPendingPush(storeKey: String): Int

    @Query("UPDATE product_local SET pushState = :state, lastPushError = :error WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun setLocalPushState(storeKey: String, barcode: String, state: String, error: String?)

    // ==================== 已同步区：product_synced ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSynced(product: ProductSynced)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSynceds(products: List<ProductSynced>)

    @Query("SELECT * FROM product_synced WHERE storeKey = :storeKey")
    suspend fun getAllSynced(storeKey: String): List<ProductSynced>

    @Query("DELETE FROM product_synced WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun deleteSynced(storeKey: String, barcode: String)

    @Query("DELETE FROM product_synced WHERE storeKey = :storeKey")
    suspend fun clearSynced(storeKey: String)

    @Query("SELECT COUNT(*) FROM product_synced WHERE storeKey = :storeKey")
    suspend fun countSynced(storeKey: String): Int

    // ==================== 已同步商品的离线改动：product_pending_change ====================

    /**
     * 排一笔离线改动。`op` = `UPSERT` | `DELETE`。
     *
     * ⚠ 主键是 `(storeKey, barcode)` 且用 REPLACE —— **同一个条码只保留最后一笔**。
     * 这是对的：推给服务器的是绝对值，中间那些改动推上去也只会被最后一笔覆盖，
     * 排队留着反而会在弱网下产生"改了三次、推了三次、服务端属性流回三条"的噪音。
     *
     * ⚠ 但 `attemptCount` 因此会被 REPLACE 清零。重试计数只在"这一笔改动没被新的编辑
     * 覆盖过"时才有意义，覆盖过就意味着这是一笔新改动、从头数是对的。
     *
     * **不要**直接调这个方法改已同步商品 —— 走 [queueSyncedEdit] / [queueSyncedDelete]，
     * 那两个把"改镜像"与"排队"绑在一个事务里。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingChange(change: ProductPendingChange)

    /**
     * 待推送的改动，按入队时间排序。
     *
     * 排序是为了让 `batch-upsert` 的分批**稳定**：重试同一批时拿到同样的切分，
     * 于是 `StockLedger.DedupKeys.batchUpsert(batchId, barcode)` 的 `batchId` 能复用
     * （见那里的注释 —— 重试必须复用，否则同一次观测会被吸收两次）。
     */
    @Query(
        """
        SELECT * FROM product_pending_change
        WHERE storeKey = :storeKey
        ORDER BY queuedAt ASC, barcode ASC
        """
    )
    suspend fun pendingChanges(storeKey: String): List<ProductPendingChange>

    @Query("SELECT * FROM product_pending_change WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun pendingChange(storeKey: String, barcode: String): ProductPendingChange?

    @Query("SELECT COUNT(*) FROM product_pending_change WHERE storeKey = :storeKey")
    suspend fun countPendingChanges(storeKey: String): Int

    /** 设置页/未上传列表要显示"N 条商品改动待推送"。 */
    @Query("SELECT COUNT(*) FROM product_pending_change WHERE storeKey = :storeKey AND op = 'DELETE'")
    suspend fun countPendingDeletes(storeKey: String): Int

    @Query("DELETE FROM product_pending_change WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun deletePendingChange(storeKey: String, barcode: String)

    @Query("UPDATE product_pending_change SET attemptCount = attemptCount + 1, lastError = :error WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun markPendingChangeAttempt(storeKey: String, barcode: String, error: String?)

    /**
     * 推送成功后清掉整批。
     *
     * 逐条 DELETE 而不是 `IN (:barcodes)`：Room 对空列表会生成 `IN ()`，
     * 那是 SQLite 语法错误 —— 而这个方法恰好在"这一批是空的"时最容易被调到。
     */
    @Transaction
    suspend fun deletePendingChanges(storeKey: String, barcodes: List<String>) {
        barcodes.forEach { deletePendingChange(storeKey, it) }
    }

    /** 推送失败后整批记一次尝试。一次 HTTP 调用打一批，失败天然是整批的。 */
    @Transaction
    suspend fun markPendingChangeAttempts(storeKey: String, barcodes: List<String>, error: String?) {
        barcodes.forEach { markPendingChangeAttempt(storeKey, it, error) }
    }

    /** 上线向导里"放弃本地改动、接受服务器版本"的出口。 */
    @Query("DELETE FROM product_pending_change WHERE storeKey = :storeKey")
    suspend fun clearPendingChanges(storeKey: String)

    // ==================== 本地扩展字段 ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExtra(extra: ProductExtra)

    @Query("SELECT * FROM product_extra WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun getExtra(storeKey: String, barcode: String): ProductExtra?

    // ==================== 墓碑 ====================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(tombstone: ProductTombstone)

    /** 历史订单页取商品名：先查活的，查不到回落墓碑。 */
    @Query("SELECT name FROM product_tombstone WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun tombstoneName(storeKey: String, barcode: String): String?

    // ==================== 倒排索引 ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTokens(tokens: List<ProductToken>)

    @Query("DELETE FROM product_token WHERE storeKey = :storeKey AND barcode = :barcode")
    suspend fun deleteTokens(storeKey: String, barcode: String)

    @Query("DELETE FROM product_token WHERE storeKey = :storeKey")
    suspend fun deleteAllTokens(storeKey: String)

    // ==================== 事务：索引必须与商品写入同生共死 ====================

    /**
     * 写一条本地商品并同步重建它的倒排索引。
     *
     * ⚠ **必须在同一个事务里**。索引与商品分开写的话，进程在两步之间被杀就会留下
     * "商品在、索引不在"（搜不出来）或"索引在、商品不在"（搜出来是空的）——
     * 两种都不报错。
     */
    @Transaction
    suspend fun putLocalWithTokens(product: ProductLocal, tokens: List<ProductToken>) {
        deleteTokens(product.storeKey, product.barcode)
        upsertLocal(product)
        if (tokens.isNotEmpty()) insertTokens(tokens)
    }

    @Transaction
    suspend fun putLocalsWithTokens(products: List<ProductLocal>, tokens: List<ProductToken>) {
        products.forEach { deleteTokens(it.storeKey, it.barcode) }
        upsertLocals(products)
        if (tokens.isNotEmpty()) insertTokens(tokens)
    }

    @Transaction
    suspend fun removeLocalWithTokens(storeKey: String, barcode: String) {
        deleteTokens(storeKey, barcode)
        deleteLocal(storeKey, barcode)
    }

    @Transaction
    suspend fun putSyncedWithTokens(product: ProductSynced, tokens: List<ProductToken>) {
        deleteTokens(product.storeKey, product.barcode)
        upsertSynced(product)
        if (tokens.isNotEmpty()) insertTokens(tokens)
    }

    @Transaction
    suspend fun removeSyncedWithTokens(storeKey: String, barcode: String) {
        deleteTokens(storeKey, barcode)
        deleteSynced(storeKey, barcode)
    }

    /**
     * 离线改一条**已同步**的商品：乐观写镜像 + 排一笔待推送改动，一个事务。
     *
     * ## ⚠ 为什么这两步必须同事务
     *
     * 分开写的话，进程在两步之间被杀会留下"镜像是用户改后的值、队列里没有这笔改动" ——
     * UI 显示改动生效了，而它永远不会被推给服务器，下一次 FULL 又把它悄悄抹回去。
     * 用户看到的是"我改的价格过两天自己变回去了"，日志里什么都没有。
     * 反过来的顺序（先排队后写镜像）失败则留下一笔推上去但本地没生效的改动，同样糟。
     *
     * ## 乐观写的语义
     *
     * `product_synced` 因此**暂时**不是服务器的纯镜像：它带着本地还没推上去的值。
     * 这是有意的取舍 —— 另一条路是让合并视图再叠一层 `product_pending_change`，
     * 但那要给 `EFFECTIVE_PRODUCT_JOIN` 与 `StockDao.baseline` **两处**同时加分支
     * （两个 DAO 各抄了一份合并语义，漏一处就出现"开单页的库存与补货页的库存对不上"）。
     * 现在这版的自愈路径是：下一次 FULL / 属性流条目会把服务器值写回来，
     * 而队列还在 → 再推一次 → 收敛。
     */
    @Transaction
    suspend fun queueSyncedEdit(synced: ProductSynced, tokens: List<ProductToken>, change: ProductPendingChange) {
        putSyncedWithTokens(synced, tokens)
        upsertPendingChange(change)
    }

    /**
     * 离线删一条**已同步**的商品：从镜像与索引里拿掉 + 排一笔 `op='DELETE'`，一个事务。
     *
     * 同 [queueSyncedEdit] 的理由。
     *
     * ⚠ **FULL 会把它复活**：服务器还留着这个商品，整表替换之后它又出现在列表里。
     * 这是可接受的 —— 队列里那笔 DELETE 还在，联网后推上去就真删了。
     * 复活窗口只在"离线删了、还没推上去、却先收到一次 FULL"时出现，
     * UI 上表现为"删掉的商品又回来了"，而不是数据丢失。
     *
     * 墓碑由调用方另写（[insertTombstone]）：它记的是**名字**，
     * 而这里排队的 DELETE 行不需要名字（服务器按 barcode 删）。
     */
    @Transaction
    suspend fun queueSyncedDelete(storeKey: String, barcode: String, change: ProductPendingChange) {
        removeSyncedWithTokens(storeKey, barcode)
        upsertPendingChange(change)
    }

    /**
     * FULL 同步：整表替换 `product_synced` 并重建该店铺的倒排索引。
     *
     * ⚠ **只碰 `product_synced`**。`product_local` 与 `product_extra` 一个字都不动 ——
     * 这正是分区设计的全部意义。
     *
     * 索引要**全量重建**而不是增量修补：FULL 之后本地行可能从"透出"变成"被 synced 遮住"
     * （或反过来），受影响的是哪些条码无法从新列表推出来。
     * 400 商品 × ~10 token ≈ 4000 行，全量重建的成本可以忽略。
     */
    @Transaction
    suspend fun replaceSyncedWithTokens(products: List<ProductSynced>, tokens: List<ProductToken>, storeKey: String) {
        clearSynced(storeKey)
        deleteAllTokens(storeKey)
        if (products.isNotEmpty()) upsertSynceds(products)
        if (tokens.isNotEmpty()) insertTokens(tokens)
        // 本地行也要重建索引：它们此前可能因 barcode 撞车而被遮住，现在不一定了
        val locals = getAllLocal(storeKey)
        val localTokens = locals.flatMap { l ->
            ProductTokenizer.tokenizeWithPositions(l.name).map { (tok, pos) ->
                ProductToken(storeKey, tok, l.barcode, pos)
            }
        }
        if (localTokens.isNotEmpty()) insertTokens(localTokens)
    }
}

/** [ProductDao.searchTokenHits] 的投影。 */
data class TokenHit(val barcode: String, val hits: Int, val firstPos: Int)

/** [ProductDao.tokenPositions] 的投影。 */
data class TokenPosition(val barcode: String, val token: String, val pos: Int)
