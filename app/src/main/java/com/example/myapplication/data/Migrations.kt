package com.example.myapplication.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.myapplication.db.LOCAL_STORE_KEY
import com.example.myapplication.db.ProductToken
import com.example.myapplication.db.ProductTokenizer
import java.math.BigDecimal

/**
 * v3 → v4。
 *
 * ## 迁移动作
 *
 *  1. 建全部新表（DDL 见 [V4_DDL]，由 Room 导出的 `app/schemas/.../4.json` 生成，
 *     手写会漏掉 nullability / 默认值 / 索引，Room 启动时校验不过）；
 *  2. 读旧 `products` 表，用 [BoxRowMerger] 把 `0<barcode>` 整件行合并进 `<barcode>` 行，
 *     全部落到 `product_local`，`storeKey = '__local__'`（离线作用域，登录后由上线向导收养）；
 *  3. 建倒排索引；
 *  4. 写一条 `store_meta`（离线店铺）；
 *  5. 删掉旧 `products` 表。
 *
 * ## 为什么全部落到 product_local 而不是 product_synced
>
 * 升级时这台设备**还没登录过任何服务器**，那 400 条商品是 `DatabaseImporter` 从外部
 * SQLite 导进来的，服务器上一条都没有。若把它们放进 `product_synced`，
 * 首次登录拿到 `syncData.type == FULL`（服务器的空商品表）就会**整表替换掉它们**，
 * 不可逆。放进 `product_local` 之后 FULL 在结构上就碰不到（见 [com.example.myapplication.db.ProductLocal]）。
 *
 * ## 为什么合并逻辑在 Kotlin 里而不是纯 SQL
 *
 * `BoxRowMerger` 同时被迁移、旧版 SQLite 导入、v0 CSV 导入三条路径复用
 * （`docs/INTERCHANGE_FORMAT.md` §4.2 要求"同一套代码，不要写三遍"）。
 * 写成 SQL 就得再写一份 Kotlin 版，两份额外开销换不来任何东西——
 * 旧表只有几百行，读进内存合并的成本可以忽略。
 *
 * ## 孤儿整件行（只有 `0X` 没有 `X`）
>
 * **不丢**。`BoxRowMerger` 会把它们报成 `orphanBoxRows`，这里按原条码插进
 * `product_local`（`boxPrice = null`），让用户在商品列表里看见并自行处理。
 * 迁移里静默删数据是不可接受的——用户没有任何机会挽回。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        V4_DDL.forEach { db.execSQL(it) }

        val now = System.currentTimeMillis()

        // ---- 读旧表 ----
        val legacy = ArrayList<BoxRowMerger.LegacyRow>()
        db.query("SELECT barcode, name, price FROM products").use { c ->
            val iBarcode = c.getColumnIndex("barcode")
            val iName = c.getColumnIndex("name")
            val iPrice = c.getColumnIndex("price")
            while (c.moveToNext()) {
                val barcode = if (iBarcode >= 0 && !c.isNull(iBarcode)) c.getString(iBarcode) else ""
                val name = if (iName >= 0 && !c.isNull(iName)) c.getString(iName) else ""
                // price 可能是 TEXT（BigDecimalTypeConverter 用 toPlainString 写的）、
                // 也可能是 REAL/NUMERIC（外部导入的库）。一律按字符串读再转 BigDecimal。
                val priceText = if (iPrice >= 0 && !c.isNull(iPrice)) c.getString(iPrice) else null
                val price = parsePrice(priceText) ?: continue
                if (barcode.isNotBlank()) legacy.add(BoxRowMerger.LegacyRow(barcode, name, price))
            }
        }

        val result = BoxRowMerger.merge(legacy)
        val toInsert = ArrayList<Product>(result.merged.size + result.orphanBoxRows.size)
        result.merged.forEach { toInsert.add(it.product) }
        // 孤儿整件行按原条码保留，不丢数据
        result.orphanBoxRows.forEach { r ->
            toInsert.add(Product(barcode = r.barcode, name = r.name, price = r.price))
        }

        // ---- 写 product_local ----
        toInsert.forEach { p ->
            db.execSQL(
                """
                INSERT OR REPLACE INTO product_local
                  (storeKey, barcode, name, price, boxPrice, boxSize, stock, orderable,
                   pushState, createdAtLocal, updatedAtLocal, lastPushError)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    LOCAL_STORE_KEY, p.barcode, p.name, p.price.toPlainString(),
                    p.boxPrice?.toPlainString(), p.boxSize, p.stock, if (p.orderable) 1 else 0,
                    "PENDING", now, now, null,
                ),
            )
        }

        // ---- 建倒排索引 ----
        val tokens = ArrayList<ProductToken>(toInsert.size * 8)
        toInsert.forEach { p ->
            ProductTokenizer.tokenizeWithPositions(p.name).forEach { (tok, pos) ->
                tokens.add(ProductToken(LOCAL_STORE_KEY, tok, p.barcode, pos))
            }
        }
        tokens.forEach { t ->
            db.execSQL(
                "INSERT OR REPLACE INTO product_token (storeKey, token, barcode, pos) VALUES (?, ?, ?, ?)",
                arrayOf(t.storeKey, t.token, t.barcode, t.pos),
            )
        }

        // ---- 离线店铺的 store_meta ----
        // enumFloor 用一个保守的硬下界；登录后会用 min(createdAt, 本地最早订单月, 2020-01) 收紧。
        db.execSQL(
            """
            INSERT OR REPLACE INTO store_meta
              (storeKey, ownerName, username, level, createdAt, enumFloorYear, enumFloorMonth,
               priceVersion, stockVersion, lastLoginAt, previousLoginAt, adoptedAt, permsCsv, adminCode)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                LOCAL_STORE_KEY, "", "", "", 0L, ENUM_FLOOR_HARD_MIN_YEAR, ENUM_FLOOR_HARD_MIN_MONTH,
                0, 0, 0L, 0L, 0L, "", "",
            ),
        )

        // ---- 删旧表 ----
        db.execSQL("DROP TABLE IF EXISTS products")
    }

    private fun parsePrice(text: String?): BigDecimal? {
        if (text.isNullOrBlank()) return BigDecimal.ZERO
        return try {
            BigDecimal(text.trim())
        } catch (e: NumberFormatException) {
            // 外部导入的库可能有 "面议" 之类的值；跳过该行，不要整批失败
            null
        }
    }
}

/**
 * 月份枚举的硬下界。
 *
 * `createdAt` 可能是 `0`（未知，README §4.1 明确允许）。没有下界的话后台补同步会
 * 从 1970 年一路枚举到现在，把服务端打爆。
 */
const val ENUM_FLOOR_HARD_MIN_YEAR = 2020
const val ENUM_FLOOR_HARD_MIN_MONTH = 1

/**
 * v4 的建表 DDL。
 *
 * **由 Room 导出的 `app/schemas/com.example.myapplication.data.AppDatabase/4.json` 生成**，
 * 不要手写：Room 在迁移后会逐列校验（类型、nullability、默认值、主键、索引、外键），
 * 差一点就抛 `IllegalStateException: Migration didn't properly handle ...`。
 */
internal val V4_DDL: List<String> = listOf(
    // table: store_meta
    "CREATE TABLE IF NOT EXISTS `store_meta` (`storeKey` TEXT NOT NULL, `ownerName` TEXT NOT NULL, `username` TEXT NOT NULL, `level` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `enumFloorYear` INTEGER NOT NULL, `enumFloorMonth` INTEGER NOT NULL, `priceVersion` INTEGER NOT NULL, `stockVersion` INTEGER NOT NULL, `lastLoginAt` INTEGER NOT NULL, `previousLoginAt` INTEGER NOT NULL, `adoptedAt` INTEGER NOT NULL, `permsCsv` TEXT NOT NULL, `adminCode` TEXT NOT NULL, PRIMARY KEY(`storeKey`))",
    // table: product_synced
    "CREATE TABLE IF NOT EXISTS `product_synced` (`storeKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `name` TEXT NOT NULL, `price` TEXT NOT NULL, `boxPrice` TEXT, `boxSize` INTEGER NOT NULL, `stock` INTEGER NOT NULL, `orderable` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `barcode`))",
    // index: index_product_synced_storeKey_name
    "CREATE INDEX IF NOT EXISTS `index_product_synced_storeKey_name` ON `product_synced` (`storeKey`, `name`)",
    // table: product_local
    "CREATE TABLE IF NOT EXISTS `product_local` (`storeKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `name` TEXT NOT NULL, `price` TEXT NOT NULL, `boxPrice` TEXT, `boxSize` INTEGER NOT NULL, `stock` INTEGER NOT NULL, `orderable` INTEGER NOT NULL, `pushState` TEXT NOT NULL, `createdAtLocal` INTEGER NOT NULL, `updatedAtLocal` INTEGER NOT NULL, `lastPushError` TEXT, PRIMARY KEY(`storeKey`, `barcode`))",
    // index: index_product_local_storeKey_name
    "CREATE INDEX IF NOT EXISTS `index_product_local_storeKey_name` ON `product_local` (`storeKey`, `name`)",
    // table: product_pending_change
    "CREATE TABLE IF NOT EXISTS `product_pending_change` (`storeKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `op` TEXT NOT NULL, `name` TEXT NOT NULL, `price` TEXT NOT NULL, `boxPrice` TEXT, `boxSize` INTEGER NOT NULL, `stock` INTEGER NOT NULL, `orderable` INTEGER NOT NULL, `queuedAt` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `lastError` TEXT, PRIMARY KEY(`storeKey`, `barcode`))",
    // table: product_extra
    "CREATE TABLE IF NOT EXISTS `product_extra` (`storeKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, PRIMARY KEY(`storeKey`, `barcode`))",
    // table: product_tombstone
    "CREATE TABLE IF NOT EXISTS `product_tombstone` (`storeKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `name` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `barcode`))",
    // table: product_token
    "CREATE TABLE IF NOT EXISTS `product_token` (`storeKey` TEXT NOT NULL, `token` TEXT NOT NULL, `barcode` TEXT NOT NULL, `pos` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `token`, `barcode`, `pos`))",
    // index: index_product_token_storeKey_token
    "CREATE INDEX IF NOT EXISTS `index_product_token_storeKey_token` ON `product_token` (`storeKey`, `token`)",
    // table: file_state
    "CREATE TABLE IF NOT EXISTS `file_state` (`storeKey` TEXT NOT NULL, `fileId` TEXT NOT NULL, `fileType` TEXT NOT NULL, `year` INTEGER NOT NULL, `month` INTEGER NOT NULL, `version` INTEGER NOT NULL, `lastStreamId` TEXT, `checksum` INTEGER NOT NULL, `mirrorBytes` INTEGER NOT NULL, `firstSyncedAt` INTEGER, `syncState` TEXT NOT NULL, `lastSyncAttemptAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `fileId`))",
    // table: order_index
    "CREATE TABLE IF NOT EXISTS `order_index` (`storeKey` TEXT NOT NULL, `fileId` TEXT NOT NULL, `orderUuid` TEXT NOT NULL, `writer` TEXT NOT NULL, `status` INTEGER NOT NULL, `orderTs` INTEGER NOT NULL, `totalCents` INTEGER NOT NULL, `manualTotal` INTEGER NOT NULL, `itemCount` INTEGER NOT NULL, `lineStart` INTEGER NOT NULL, `uuidOffset` INTEGER NOT NULL, `statusOffset` INTEGER NOT NULL, `remarkOffset` INTEGER, `remark` TEXT, `appliedFileVersion` INTEGER NOT NULL, `cancelAppliedVersion` INTEGER, `groupId` TEXT, PRIMARY KEY(`storeKey`, `fileId`, `orderUuid`))",
    // index: index_order_index_storeKey_writer_orderTs
    "CREATE INDEX IF NOT EXISTS `index_order_index_storeKey_writer_orderTs` ON `order_index` (`storeKey`, `writer`, `orderTs`)",
    // index: index_order_index_storeKey_fileId_orderTs
    "CREATE INDEX IF NOT EXISTS `index_order_index_storeKey_fileId_orderTs` ON `order_index` (`storeKey`, `fileId`, `orderTs`)",
    // table: order_item_index
    "CREATE TABLE IF NOT EXISTS `order_item_index` (`storeKey` TEXT NOT NULL, `fileId` TEXT NOT NULL, `orderUuid` TEXT NOT NULL, `lineNo` INTEGER NOT NULL, `barcode` TEXT NOT NULL, `name` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `amountCents` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `fileId`, `orderUuid`, `lineNo`))",
    // index: index_order_item_index_storeKey_barcode
    "CREATE INDEX IF NOT EXISTS `index_order_item_index_storeKey_barcode` ON `order_item_index` (`storeKey`, `barcode`)",
    // table: order_group
    "CREATE TABLE IF NOT EXISTS `order_group` (`storeKey` TEXT NOT NULL, `groupId` TEXT NOT NULL, `writer` TEXT NOT NULL, `orderCount` INTEGER NOT NULL, `totalCents` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `cancelState` TEXT NOT NULL, PRIMARY KEY(`storeKey`, `groupId`))",
    // index: index_order_group_storeKey_createdAt
    "CREATE INDEX IF NOT EXISTS `index_order_group_storeKey_createdAt` ON `order_group` (`storeKey`, `createdAt`)",
    // table: order_outbox
    "CREATE TABLE IF NOT EXISTS `order_outbox` (`storeKey` TEXT NOT NULL, `orderUuid` TEXT NOT NULL, `groupId` TEXT NOT NULL, `fileType` TEXT NOT NULL, `year` INTEGER NOT NULL, `month` INTEGER NOT NULL, `payload` BLOB NOT NULL, `state` TEXT NOT NULL, `blockedReason` TEXT, `blockedBarcodes` TEXT, `attemptCount` INTEGER NOT NULL, `lastError` TEXT, `pricedAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `writer` TEXT NOT NULL, `totalCents` INTEGER NOT NULL, `itemCount` INTEGER NOT NULL, `orderTs` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `orderUuid`))",
    // index: index_order_outbox_storeKey_state_createdAt
    "CREATE INDEX IF NOT EXISTS `index_order_outbox_storeKey_state_createdAt` ON `order_outbox` (`storeKey`, `state`, `createdAt`)",
    // table: stats_contrib
    "CREATE TABLE IF NOT EXISTS `stats_contrib` (`storeKey` TEXT NOT NULL, `scope` TEXT NOT NULL, `dimKey` TEXT NOT NULL, `metric` TEXT NOT NULL, `value` INTEGER NOT NULL, `frozen` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `scope`, `dimKey`, `metric`))",
    // table: stats_agg
    "CREATE TABLE IF NOT EXISTS `stats_agg` (`storeKey` TEXT NOT NULL, `dimKey` TEXT NOT NULL, `metric` TEXT NOT NULL, `value` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `dimKey`, `metric`))",
    // table: stock_absorb_boundary
    "CREATE TABLE IF NOT EXISTS `stock_absorb_boundary` (`storeKey` TEXT NOT NULL, `fileId` TEXT NOT NULL, `barcode` TEXT NOT NULL, `boundaryVersion` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `fileId`, `barcode`))",
    // table: stock_apply_log
    "CREATE TABLE IF NOT EXISTS `stock_apply_log` (`storeKey` TEXT NOT NULL, `dedupKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `newStock` INTEGER NOT NULL, `source` TEXT NOT NULL, `appliedAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `dedupKey`))",
    // table: restock_item
    "CREATE TABLE IF NOT EXISTS `restock_item` (`storeKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, `checked` INTEGER NOT NULL, `note` TEXT, PRIMARY KEY(`storeKey`, `barcode`))",
    // table: restock_rule
    "CREATE TABLE IF NOT EXISTS `restock_rule` (`storeKey` TEXT NOT NULL, `ruleId` TEXT NOT NULL, `barcodesCsv` TEXT NOT NULL, `threshold` INTEGER NOT NULL, `enabled` INTEGER NOT NULL, `lastRunAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `ruleId`))",
    // table: restock_filter
    "CREATE TABLE IF NOT EXISTS `restock_filter` (`storeKey` TEXT NOT NULL, `filterId` TEXT NOT NULL, `name` TEXT NOT NULL, `treeJson` TEXT NOT NULL, `isLastUsed` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `filterId`))",
    // index: index_restock_filter_storeKey_isLastUsed
    "CREATE INDEX IF NOT EXISTS `index_restock_filter_storeKey_isLastUsed` ON `restock_filter` (`storeKey`, `isLastUsed`)",
    // table: sort_pref
    "CREATE TABLE IF NOT EXISTS `sort_pref` (`storeKey` TEXT NOT NULL, `screen` TEXT NOT NULL, `field` TEXT NOT NULL, `ascending` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `screen`))",
    // table: admin_catalog
    "CREATE TABLE IF NOT EXISTS `admin_catalog` (`storeKey` TEXT NOT NULL, `barcode` TEXT NOT NULL, `name` TEXT NOT NULL, `price` TEXT NOT NULL, `boxPrice` TEXT, `boxSize` INTEGER NOT NULL, `stock` INTEGER NOT NULL, `orderable` INTEGER NOT NULL, PRIMARY KEY(`storeKey`, `barcode`))",
    // table: admin_catalog_cursor
    "CREATE TABLE IF NOT EXISTS `admin_catalog_cursor` (`storeKey` TEXT NOT NULL, `adminCode` TEXT NOT NULL, `lastId` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`storeKey`))",
)
