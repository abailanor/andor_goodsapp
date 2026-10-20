package com.example.myapplication.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.myapplication.db.LOCAL_STORE_KEY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * `MIGRATION_3_4` 的 instrumented 测试。
 *
 * ## ⚠ 跑这个测试需要一个 AVD 或真机
 *
 * `MigrationTestHelper` 依赖真实的 Android SQLite 与 assets，**JVM 单测跑不了**。
 * 本机当前没有配置 AVD（`emulator -list-avds` 为空），所以这个文件是"写好待跑"的状态。
 *
 * ## ⚠ `3.json` 是手写的，不是 Room 导出的
 *
 * v3 的 `@Database` 是 `exportSchema = false`，所以没有生成过 `3.json`。
 * 这里的那份是按 v3 的实体定义（`products(barcode TEXT NOT NULL PK, name TEXT NOT NULL,
 * price TEXT NOT NULL)`，`price` 经 `BigDecimalTypeConverter` 落成 TEXT）反推的。
 * `identityHash` 是占位值 `000...003`——`MigrationTestHelper` 只把它写进
 * `room_master_table`，**不校验**，所以占位值不影响测试有效性。
 *
 * 代价要说清楚：这份手写 schema 万一与真实的 v3 库有出入，测试就会"通过"但真实升级仍会崩。
 * 所以**上线前必须在装过旧版 APK 的真机上覆盖安装一次**，那是唯一能证明迁移有效的验证。
 * 从 4→5 开始有导出的 JSON，就不再有这个问题。
 */
@RunWith(AndroidJUnit4::class)
class Migration3To4Test {

    private val dbName = "migration-test-3-to-4"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /** 造一个 v3 库并塞进覆盖各种形态的旧数据。 */
    @Throws(IOException::class)
    private fun createV3WithLegacyData() {
        helper.createDatabase(dbName, 3).apply {
            // 正常配对：整件行 + 单行
            execSQL("INSERT INTO products VALUES ('06901234567890', '可口可乐330ml整件', '80.00')")
            execSQL("INSERT INTO products VALUES ('6901234567890', '可口可乐330ml', '3.50')")
            // 只有单行，没有整件行
            execSQL("INSERT INTO products VALUES ('6901234567891', '雪碧330ml', '3.50')")
            // 孤儿整件行（没有对应的单行）—— 迁移**不许丢**它
            execSQL("INSERT INTO products VALUES ('06909999999999', '某个只有整件的东西', '120.00')")
            // 整件行名字不规范（不以"整件"结尾）
            execSQL("INSERT INTO products VALUES ('06901234567892', '云南白药箱装', '540.00')")
            execSQL("INSERT INTO products VALUES ('6901234567892', '云南白药气雾剂', '68.00')")
            // price 被 Excel 吃掉尾零的形态
            execSQL("INSERT INTO products VALUES ('6901234567893', '红牛250ml', '6.5')")
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_schemaIsValid() {
        createV3WithLegacyData()
        // validateDroppedTables = true：断言旧 `products` 表确实被删掉了
        helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_dataIsPreserved() {
        createV3WithLegacyData()
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        // ---- 商品数量不能减少（这是"首次 FULL 删光 400 条手录商品"那个坑的近亲）----
        db.query("SELECT COUNT(*) FROM product_local WHERE storeKey = '$LOCAL_STORE_KEY'").use {
            assertTrue(it.moveToFirst())
            // 旧库 7 行 → 4 条 merged（6901234567890 配对、6901234567891 单、
            // 6901234567892 配对、6901234567893 单）+ 1 条孤儿（06909999999999，按原条码保留）= 5
            assertEquals("旧库 7 行应合并成 5 条商品", 5, it.getInt(0))
        }

        // ---- 0 前缀行已被合并，不再单独存在（孤儿除外）----
        db.query(
            "SELECT barcode FROM product_local WHERE storeKey = '$LOCAL_STORE_KEY' " +
                "AND substr(barcode,1,1) = '0'"
        ).use {
            val prefixed = mutableListOf<String>()
            while (it.moveToNext()) prefixed.add(it.getString(0))
            assertEquals(
                "只剩孤儿整件行允许保留 0 前缀，实际: $prefixed",
                listOf("06909999999999"), prefixed
            )
        }

        // ---- 整件行的 price 变成了 boxPrice ----
        db.query(
            "SELECT name, price, boxPrice, boxSize FROM product_local " +
                "WHERE storeKey = '$LOCAL_STORE_KEY' AND barcode = '6901234567890'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("可口可乐330ml", it.getString(0))   // 尾部的"整件"被去掉了
            assertEquals("3.50", it.getString(1))
            assertEquals("80.00", it.getString(2))          // ← 来自 0 前缀行
            assertEquals(0, it.getInt(3))                   // 旧模型没有箱规，一律 0
        }

        // ---- 没有整件行的商品，boxPrice 必须是 NULL 而不是 '0' ----
        // NULL = 不按整箱卖；0 = 整箱免费。语义不同，混了就会给出错误的价格。
        db.query(
            "SELECT boxPrice FROM product_local " +
                "WHERE storeKey = '$LOCAL_STORE_KEY' AND barcode = '6901234567891'"
        ).use {
            assertTrue(it.moveToFirst())
            assertTrue("boxPrice 应当是 NULL", it.isNull(0))
        }

        // ---- 不规范命名的整件行也被正确吸收 ----
        db.query(
            "SELECT name, price, boxPrice FROM product_local " +
                "WHERE storeKey = '$LOCAL_STORE_KEY' AND barcode = '6901234567892'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("云南白药气雾剂", it.getString(0))
            assertEquals("68.00", it.getString(1))
            assertEquals("540.00", it.getString(2))
        }

        // ---- 尾零形态原样保留（不做规范化，那是导入报告的事，不是迁移的事）----
        db.query(
            "SELECT price FROM product_local " +
                "WHERE storeKey = '$LOCAL_STORE_KEY' AND barcode = '6901234567893'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("6.5", it.getString(0))
        }

        // ---- 全部落在未同步区，storeKey 是离线作用域 ----
        db.query("SELECT COUNT(*) FROM product_synced").use {
            assertTrue(it.moveToFirst())
            assertEquals("迁移不能把商品放进已同步区，否则首次 FULL 会把它们删光", 0, it.getInt(0))
        }
        db.query("SELECT DISTINCT pushState FROM product_local").use {
            assertTrue(it.moveToFirst())
            assertEquals("PENDING", it.getString(0))
            assertFalse(it.moveToNext())
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_invertedIndexIsBuilt() {
        createV3WithLegacyData()
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        // 「可口可乐330ml」→ 可(0),口(1),可(2),乐(3),330(4),m(7),l(8) → 去重后 6 个不同 token
        db.query(
            "SELECT DISTINCT token FROM product_token " +
                "WHERE storeKey = '$LOCAL_STORE_KEY' AND barcode = '6901234567890' ORDER BY token"
        ).use {
            val tokens = mutableListOf<String>()
            while (it.moveToNext()) tokens.add(it.getString(0))
            assertEquals(listOf("330", "m", "l", "可", "口", "乐").sorted(), tokens.sorted())
            assertTrue("数字串必须作为一个完整 token 存在", "330" in tokens)
            assertFalse("不该把 330 拆成单个数字", "3" in tokens)
        }

        // 相邻加分依赖 pos：「可口可乐」的 可 在 0 和 2，乐 在 3
        db.query(
            "SELECT token, pos FROM product_token " +
                "WHERE storeKey = '$LOCAL_STORE_KEY' AND barcode = '6901234567890' " +
                "AND token IN ('可','乐') ORDER BY pos"
        ).use {
            val pairs = mutableListOf<Pair<String, Int>>()
            while (it.moveToNext()) pairs.add(it.getString(0) to it.getInt(1))
            assertEquals(listOf("可" to 0, "可" to 2, "乐" to 3), pairs)
        }

        // 每条商品都要有索引，否则"商品在、索引不在"就搜不出来
        db.query(
            "SELECT COUNT(DISTINCT barcode) FROM product_token WHERE storeKey = '$LOCAL_STORE_KEY'"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(5, it.getInt(0))
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_storeMetaIsCreated() {
        createV3WithLegacyData()
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)

        db.query("SELECT * FROM store_meta WHERE storeKey = '$LOCAL_STORE_KEY'").use {
            assertTrue("必须为离线作用域建一条 store_meta", it.moveToFirst())
            // enumFloor 有硬下界，否则 createdAt=0 时后台补同步会从 1970 年一路枚举，打爆服务端
            assertEquals(ENUM_FLOOR_HARD_MIN_YEAR, it.getInt(it.getColumnIndexOrThrow("enumFloorYear")))
            assertEquals(ENUM_FLOOR_HARD_MIN_MONTH, it.getInt(it.getColumnIndexOrThrow("enumFloorMonth")))
            assertEquals(0L, it.getLong(it.getColumnIndexOrThrow("createdAt")))
            assertEquals(0L, it.getLong(it.getColumnIndexOrThrow("adoptedAt")))
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_legacyProductsTableIsDropped() {
        createV3WithLegacyData()
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='products'").use {
            assertFalse("旧 products 表必须被删掉，否则它会一直占着磁盘且没人维护", it.moveToNext())
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_emptyLegacyTableDoesNotCrash() {
        // 全新装机后立刻升级、或旧库是空的
        helper.createDatabase(dbName, 3).close()
        val db = helper.runMigrationsAndValidate(dbName, 4, true, MIGRATION_3_4)
        db.query("SELECT COUNT(*) FROM product_local").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        // store_meta 仍然要建（离线模式也要有一个作用域行）
        db.query("SELECT COUNT(*) FROM store_meta").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        assertNotNull(db)
    }
}
