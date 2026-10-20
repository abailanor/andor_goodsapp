package com.example.myapplication.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.example.myapplication.db.AdminCatalogCursorEntity
import com.example.myapplication.db.AdminCatalogItemEntity
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderGroupEntity
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.OrderIndexEntity
import com.example.myapplication.db.OrderItemIndexEntity
import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.db.ProductDao
import com.example.myapplication.db.ProductExtra
import com.example.myapplication.db.ProductLocal
import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.db.ProductSynced
import com.example.myapplication.db.ProductTombstone
import com.example.myapplication.db.ProductToken
import com.example.myapplication.db.RestockFilterEntity
import com.example.myapplication.db.RestockItemEntity
import com.example.myapplication.db.RestockRuleEntity
import com.example.myapplication.db.SortPrefEntity
import com.example.myapplication.db.StatsAggEntity
import com.example.myapplication.db.StatsContribEntity
import com.example.myapplication.db.StockAbsorbBoundaryEntity
import com.example.myapplication.db.StockApplyLogEntity
import com.example.myapplication.db.StockDao
import com.example.myapplication.db.StoreMeta
import com.example.myapplication.db.StoreMetaDao

/**
 * v4：从"离线单机"改成"离线优先 + 可选同步"。
 *
 * ## 相对 v3 的变化
 *
 * v3 只有一张 `products(barcode, name, price)`，"整件"靠在条码前加 `0` 再插一行表示。
 * v4 把它拆成 `product_synced`（服务器权威，FULL 整表替换）与 `product_local`
 * （未推送），字段对齐服务端 `ProductData`（price / boxPrice / boxSize / stock / orderable）。
 *
 * **分区而不是加标记列**是刻意的：FULL 只写 `product_synced`，所以"首次登录的商品
 * FULL 把 400 条手录商品删光"在结构上不可能发生，不依赖代码记得先检查标记。
 * 详见 [ProductSynced] / [ProductLocal] 的 KDoc。
 *
 * ## 关于 exportSchema
 *
 * v3 是 `exportSchema = false`，所以没有 v3 的 schema JSON，`MigrationTestHelper`
 * 无法造出"真实的 v3 库"来验证迁移。v4 起打开导出，`app/schemas/` 要进 git。
 *
 * ⚠ 这意味着 **3→4 的迁移测试只能用手工写的 v3 DDL**（见 `MIGRATION_3_4` 的注释与
 * `Migration3To4Test`）。从 4→5 开始就能用导出的 JSON 了。
 *
 * ## 关于 JournalMode.TRUNCATE
 *
 * 保留 v3 的选择，**不要顺手改成 WAL**。理由：现有的"导出数据库文件"功能走
 * `context.getDatabasePath("product_database")` 直接拷主文件，WAL 模式下未 checkpoint
 * 的数据在 `-wal` 侧文件里，拷出来的库是残缺的——而且**不报错**。
 * 真要改 WAL，必须同时改成"先 checkpoint 再拷"或用 `VACUUM INTO`。
 *
 * ## 不加 fallbackToDestructiveMigration
 *
 * 加了之后迁移写错会**静默清库**，用户数据一声不响地没了。宁可启动崩溃。
 */
@Database(
    entities = [
        StoreMeta::class,
        ProductSynced::class,
        ProductLocal::class,
        ProductPendingChange::class,
        ProductExtra::class,
        ProductTombstone::class,
        ProductToken::class,
        FileStateEntity::class,
        OrderIndexEntity::class,
        OrderItemIndexEntity::class,
        OrderGroupEntity::class,
        OrderOutboxEntity::class,
        StatsContribEntity::class,
        StatsAggEntity::class,
        StockAbsorbBoundaryEntity::class,
        StockApplyLogEntity::class,
        RestockItemEntity::class,
        RestockRuleEntity::class,
        RestockFilterEntity::class,
        SortPrefEntity::class,
        AdminCatalogItemEntity::class,
        AdminCatalogCursorEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(BigDecimalTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun storeMetaDao(): StoreMetaDao

    /**
     * 库存。**这是唯一能改 `stock` 基线的入口**，理由见 [StockDao] 的类注释。
     * 业务代码不要直接拿它，走 `StockLedger`（那层负责吸收边界与对账规则）。
     */
    abstract fun stockDao(): StockDao

    /**
     * 订单文件镜像（`file_state` / `order_index` / `order_item_index` / `stats_contrib`）。
     *
     * ⚠ 业务代码**不要直接用它**，走 `RoomMirrorStateStore`（它实现 `MirrorStateStore`）。
     * 直接用就会绕过"游标必须在文件落盘之后写"与"索引和贡献必须同事务"这两条不变量，
     * 而两者被破坏时都不会报错。
     */
    abstract fun orderFileDao(): OrderFileDao

    companion object {
        const val DATABASE_NAME = "product_database"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME,
                )
                    .setJournalMode(JournalMode.TRUNCATE)
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** 关闭数据库连接。 */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
