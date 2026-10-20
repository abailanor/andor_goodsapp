package com.example.myapplication.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.myapplication.data.AppDatabase
import com.example.myapplication.sync.StockLedger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `StockDao` 的**真 SQL** 测试（instrumented，跑真 Room + 真 SQLite）。
 *
 * ## 与 `StockLedgerTest`（JVM）的分工
 *
 * 那边用 `FakeStockDao` 照抄 SQL 语义、测编排；这边测**那两段 SQL 本身**：
 * `CASE` / `IFNULL` / `GROUP BY` / `LIKE 'B:%'` / `UNION ALL` + `NOT IN` 的合并视图。
 * 两边断言同一批场景 —— fake 抄错了这边会红，SQL 改错了那边会红。
 *
 * 这些查询在 JVM 上跑不了（Room 要真实的 Android SQLite），而写错的后果是
 * **库存算错且不报错**，所以只能真机验证。
 *
 * ## 事务性不在这里测
 *
 * `applyAbsolute` 上的 `@Transaction` 由 Room 生成的实现类落地：
 * `StockDao_Impl.java:138` 把默认方法体包进了 `RoomDatabaseKt.withTransaction(...)`
 * （2026-10-19 读生成代码确认）。JVM 的 fake 继承同一个默认方法体，
 * 但**没有**那层包装 —— 所以"写基线与读 file_state 之间不会插入别的提交"
 * 这条性质靠 Room 保证，不靠测试。这里测的是它做的三件事的**结果**。
 */
@RunWith(AndroidJUnit4::class)
class StockDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: StockDao
    private val store = "00002"
    private val other = "00003"
    private val cola = "6901234567890"
    private val water = "6901234567891"
    private val oct = "B:alice:2026-10"
    private val sep = "B:alice:2026-09"
    private val aFile = "A:alice:2026"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.stockDao()
    }

    @After
    fun tearDown() = db.close()

    // ==================== 写入辅助（绕过 DAO，直接 SQL） ====================
    //
    // `order_index` / `order_item_index` / `file_state` 的写入 DAO 是 P4 的事，
    // 现在还没有。用裸 SQL 插入的额外好处是：列名写错时 SQLite 会**立刻报错**，
    // 而不是像 ContentValues 那样静默少一列。

    private fun sql(statement: String, vararg args: Any?) =
        db.openHelper.writableDatabase.execSQL(statement, args)

    private fun putSynced(barcode: String, stock: Int, storeKey: String = store) = sql(
        """INSERT INTO product_synced
           (storeKey, barcode, name, price, boxPrice, boxSize, stock, orderable, updatedAt)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        storeKey, barcode, barcode, "3.50", null, 0, stock, 1, 0L,
    )

    private fun putLocal(barcode: String, stock: Int, storeKey: String = store) = sql(
        """INSERT INTO product_local
           (storeKey, barcode, name, price, boxPrice, boxSize, stock, orderable,
            pushState, createdAtLocal, updatedAtLocal, lastPushError)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        storeKey, barcode, barcode, "3.50", null, 0, stock, 1, "PENDING", 0L, 0L, null,
    )

    private fun putFile(fileId: String, version: Int, storeKey: String = store) = sql(
        """INSERT INTO file_state
           (storeKey, fileId, fileType, year, month, version, lastStreamId, checksum,
            mirrorBytes, firstSyncedAt, syncState, lastSyncAttemptAt)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        storeKey, fileId, if (fileId.startsWith("B:")) "B" else "A",
        2026, if (fileId.startsWith("B:")) 10 else 0,
        version, null, 0, 0L, null, "LIVE", 0L,
    )

    private fun putOrder(
        fileId: String,
        orderUuid: String,
        appliedFileVersion: Int,
        cancelAppliedVersion: Int? = null,
        status: Int = 1,
        storeKey: String = store,
        vararg items: Pair<String, Int>,
    ) {
        sql(
            """INSERT INTO order_index
               (storeKey, fileId, orderUuid, writer, status, orderTs, totalCents, manualTotal,
                itemCount, lineStart, uuidOffset, statusOffset, remarkOffset, remark,
                appliedFileVersion, cancelAppliedVersion, groupId)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            storeKey, fileId, orderUuid, "alice", status, 0L, 0L, 0,
            items.size, 0L, 0L, 0L, null, null,
            appliedFileVersion, cancelAppliedVersion, null,
        )
        items.forEachIndexed { lineNo, (barcode, qty) ->
            sql(
                """INSERT INTO order_item_index
                   (storeKey, fileId, orderUuid, lineNo, barcode, name, quantity, amountCents)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                storeKey, fileId, orderUuid, lineNo, barcode, barcode, qty, 0L,
            )
        }
    }

    /**
     * 直接插一行边界。默认给 [cola] —— 边界是 **per-barcode** 的，
     * 涉及第二个条码的测试必须显式再插一行，否则会落进 `IFNULL(...,0)` 的兜底
     * （语义是"从没吸收过"，于是所有增量都算边界之后）。
     */
    private fun setBoundary(fileId: String, version: Int, barcode: String = cola) = sql(
        "INSERT INTO stock_absorb_boundary (storeKey, fileId, barcode, boundaryVersion, updatedAt) VALUES (?, ?, ?, ?, ?)",
        store, fileId, barcode, version, 0L,
    )

    // ==================== 合并视图（baseline / allBaselines） ====================

    @Test
    fun `基线_已同步区优先_本地区补空_都没有则_null`() = runBlocking {
        assertNull("两张表都没有时必须 null，不能是 0", dao.baseline(store, cola))

        putLocal(cola, 7)
        assertEquals(7, dao.baseline(store, cola))

        putSynced(cola, 100)
        assertEquals("同一条码两边都有 → 服务器权威", 100, dao.baseline(store, cola))
    }

    @Test
    fun `基线不跨店铺串味`() = runBlocking {
        putSynced(cola, 100)
        putSynced(cola, 5, storeKey = other)
        assertEquals(100, dao.baseline(store, cola))
        assertEquals(5, dao.baseline(other, cola))
    }

    @Test
    fun `全量基线与单条同一套语义_且同一条码只出一行`() = runBlocking {
        // ⚠ `UNION ALL` 不去重。全量那条查询靠 `NOT IN` 子查询排除本地区里
        // 已同步区已有的条码；漏了那个条件，同一条码会出现两行，
        // `associate {}` 静默留下后一个 —— 补货清单里的库存就成了本地表的旧值。
        putSynced(cola, 100)
        putLocal(cola, 7)
        putLocal(water, 50)

        val all = dao.allBaselines(store).associate { it.barcode to it.stock }
        assertEquals(mapOf(cola to 100, water to 50), all)
        assertEquals("必须恰好两行", 2, dao.allBaselines(store).size)
        assertEquals(all[cola], dao.baseline(store, cola))
        assertEquals(all[water], dao.baseline(store, water))
    }

    // ==================== 增量聚合（DELTA_SELECT / DELTA_TAIL） ====================

    @Test
    fun `没有订单时增量为_null_不是全零对象`() = runBlocking {
        putSynced(cola, 100)
        putFile(oct, 0)
        assertNull(dao.deltaAfterBoundary(store, cola))
        assertTrue(dao.allDeltasAfterBoundary(store).isEmpty())
    }

    @Test
    fun `边界之后卖出扣_边界之内不扣`() = runBlocking {
        putFile(oct, 9)
        setBoundary(oct, 5)
        putOrder(oct, "u-in", appliedFileVersion = 5, items = arrayOf(cola to 2))
        putOrder(oct, "u-after", appliedFileVersion = 6, items = arrayOf(cola to 3))

        val d = dao.deltaAfterBoundary(store, cola)!!
        assertEquals(3, d.soldAfter)
        assertEquals(0, d.refundedAfter)
        assertEquals(-3, d.net)
    }

    @Test
    fun `边界落在下单与取消之间时回退不丢`() = runBlocking {
        // ⚠ 这条是 `StockDelta` KDoc 里那个"错了不报错"陷阱的**真 SQL** 版本。
        //
        // 下单 v10（已被边界 15 之前那次绝对观测吸收）、取消 v20（观测之后才发生）。
        // 订单此刻 status=0，所以"只统计 status=1"的直觉写法会**整行跳过**，
        // +3 的回退永久丢失，库存偏低 3，没有任何日志。
        //
        // 也顺便证明 `WHERE` 里不能按 appliedFileVersion 过滤行 ——
        // 那会把这一行滤掉，效果与按 status 过滤一样。
        putFile(oct, 20)
        setBoundary(oct, 15)
        putOrder(oct, "u-split", appliedFileVersion = 10, cancelAppliedVersion = 20,
            status = 0, items = arrayOf(cola to 3))

        val d = dao.deltaAfterBoundary(store, cola)!!
        assertEquals("卖出不该重复扣", 0, d.soldAfter)
        assertEquals("回退必须加回来", 3, d.refundedAfter)
        assertEquals(3, d.net)
    }

    @Test
    fun `下单与取消都在边界之内时净贡献为零`() = runBlocking {
        putFile(oct, 20)
        setBoundary(oct, 15)
        putOrder(oct, "u-old", appliedFileVersion = 10, cancelAppliedVersion = 12,
            status = 0, items = arrayOf(cola to 3))
        assertEquals(0, dao.deltaAfterBoundary(store, cola)!!.net)
    }

    @Test
    fun `没有边界行时按零比_所有订单都算边界之后`() = runBlocking {
        // `LEFT JOIN` + `IFNULL(b.boundaryVersion, 0)`。
        // 漏掉 IFNULL 的话比较结果是 NULL → CASE 落 ELSE 0 → **所有扣减静默消失**，库存偏高。
        putFile(oct, 9)
        putOrder(oct, "u1", appliedFileVersion = 1, items = arrayOf(cola to 4))
        assertEquals(-4, dao.deltaAfterBoundary(store, cola)!!.net)
    }

    @Test
    fun `A_类文件不参与销售扣减`() = runBlocking {
        // A 类是向丽华金龙进货，服务端 `order_write.lua` 只对 B 类 HSET 库存。
        putFile(aFile, 9)
        setBoundary(aFile, 0)
        putOrder(aFile, "u-a", appliedFileVersion = 5, items = arrayOf(cola to 40))
        assertNull("A 类被 LIKE 'B:%' 滤掉后一行都不剩 → null", dao.deltaAfterBoundary(store, cola))
        assertTrue(dao.allDeltasAfterBoundary(store).isEmpty())
    }

    @Test
    fun `每个_fileId_的边界各自独立`() = runBlocking {
        putFile(sep, 8)
        putFile(oct, 8)
        setBoundary(sep, 8)     // 已吸收
        setBoundary(oct, 3)     // 未吸收
        putOrder(sep, "u-sep", appliedFileVersion = 5, items = arrayOf(cola to 4))
        putOrder(oct, "u-oct", appliedFileVersion = 5, items = arrayOf(cola to 6))
        assertEquals("只有十月那笔该被扣", -6, dao.deltaAfterBoundary(store, cola)!!.net)
    }

    @Test
    fun `同一订单多行与跨订单同条码都归并到一条结果`() = runBlocking {
        putFile(oct, 9)
        setBoundary(oct, 0)
        putOrder(oct, "u1", appliedFileVersion = 5, items = arrayOf(cola to 2, water to 7))
        putOrder(oct, "u2", appliedFileVersion = 6, items = arrayOf(cola to 3))

        assertEquals(-5, dao.deltaAfterBoundary(store, cola)!!.net)
        assertEquals(-7, dao.deltaAfterBoundary(store, water)!!.net)
        val all = dao.allDeltasAfterBoundary(store).associate { it.barcode to it.net }
        assertEquals(mapOf(cola to -5, water to -7), all)
    }

    @Test
    fun `增量不跨店铺串味`() = runBlocking {
        putFile(oct, 9)
        putFile(oct, 9, storeKey = other)
        setBoundary(oct, 0)
        putOrder(oct, "u-mine", appliedFileVersion = 5, items = arrayOf(cola to 2))
        putOrder(oct, "u-theirs", appliedFileVersion = 5, storeKey = other, items = arrayOf(cola to 99))

        assertEquals(-2, dao.deltaAfterBoundary(store, cola)!!.net)
        assertEquals(mapOf(cola to -2), dao.allDeltasAfterBoundary(store).associate { it.barcode to it.net })
        assertEquals(-99, dao.deltaAfterBoundary(other, cola)!!.net)
    }

    // ==================== applyAbsolute ====================

    @Test
    fun `重复应用返回_false_且基线与边界都不动`() = runBlocking {
        putSynced(cola, 100)
        putFile(oct, 5)

        assertTrue(dao.applyAbsolute(store, "K1", cola, 40, "STOCK_API", 1_000))
        assertEquals(40, dao.baseline(store, cola))
        assertEquals(5, dao.boundaryOf(store, oct, cola))

        // 期间订单文件又推进了几个版本。
        sql("UPDATE file_state SET version = 8 WHERE storeKey = ? AND fileId = ?", store, oct)

        // ⚠ 第二次带的是**不同的值**。`@Insert(IGNORE)` 返回 -1 时整个方法必须立刻返回，
        // 否则基线被改、边界被推到 8，v6..v8 之间的订单增量凭空消失。
        assertFalse(dao.applyAbsolute(store, "K1", cola, 999, "STOCK_API", 2_000))
        assertEquals(40, dao.baseline(store, cola))
        assertEquals("重投递不许动边界", 5, dao.boundaryOf(store, oct, cola))
    }

    @Test
    fun `一次观测把该条码在所有_fileId_上的边界推到各自当前版本`() = runBlocking {
        // 服务器给的 new_stock 是 product:<code>:<barcode> 这个 hash 的绝对值，
        // 它吸收了**该店铺所有 B 类文件**到目前为止的扣减，不只是某一个月的。
        putSynced(cola, 100)
        putSynced(water, 100)
        putFile(sep, 3)
        putFile(oct, 7)
        putFile(aFile, 11)

        dao.applyAbsolute(store, "K1", cola, 100, "PRODUCT_FULL", 1_000)

        assertEquals(3, dao.boundaryOf(store, sep, cola))
        assertEquals(7, dao.boundaryOf(store, oct, cola))
        assertEquals(11, dao.boundaryOf(store, aFile, cola))
        assertNull("别的店铺不受影响", dao.boundaryOf(other, oct, cola))

        // ⚠ "所有 fileId"是**文件**维度，不是商品维度。water 的基线还是它自己上一次观测时的值，
        // 把它的边界也推过去等于宣布"它那些扣减已被吸收"（并没有）→ 库存偏高。
        assertNull("别的条码一行都不许写", dao.boundaryOf(store, oct, water))
        assertEquals("边界行只属于被观测的那个条码",
            3, countOf("SELECT COUNT(*) FROM stock_absorb_boundary WHERE storeKey = ?", store))
    }

    @Test
    fun `X_的观测不许抬高_Y_的库存`() = runBlocking {
        // `StockAbsorbBoundaryEntity` 类注释里那个反例的**真 SQL** 版本。
        //
        // 边界若只按 fileId（不带 barcode），第 2 步 X 的观测会把边界推到 v1，
        // 于是 Y 那笔 appliedFileVersion=1 的扣减不再满足 `> 边界` →
        // effective(Y) 从 97 跳回 100。**偏高 3，而且没有任何报错。**
        val ledger = StockLedger(dao) { 1_000L }
        putSynced(cola, 100)     // X
        putSynced(water, 100)    // Y
        putFile(oct, 0)

        // 1) 一笔 3×Y 的卖出。B 类不写库存流，本地只能靠"基线 + 边界之后的增量"算。
        sql("UPDATE file_state SET version = 1 WHERE storeKey = ? AND fileId = ?", store, oct)
        putOrder(oct, "u1", appliedFileVersion = 1, items = arrayOf(water to 3))
        assertEquals(97, ledger.effectiveStock(store, water))
        assertEquals(100, ledger.effectiveStock(store, cola))

        // 2) 有人显式把 X 改成 50 —— 一次与 Y 毫无关系的观测。
        assertTrue(ledger.applyAbsolute(store, "K-X", cola, 50, "STOCK_API"))
        assertEquals(50, ledger.effectiveStock(store, cola))

        // 3) Y 必须纹丝不动。
        assertEquals("X 的观测把 Y 的边界也推过去了 → Y 偏高 3",
            97, ledger.effectiveStock(store, water))
        assertNull("Y 根本没有边界行", dao.boundaryOf(store, oct, water))
        assertEquals(1, dao.boundaryOf(store, oct, cola))
    }

    @Test
    fun `边界已有记录时被推到新版本`() = runBlocking {
        putSynced(cola, 100)
        putFile(oct, 7)
        setBoundary(oct, 2)
        dao.applyAbsolute(store, "K1", cola, 100, "STOCK_STREAM", 1_000)
        assertEquals(7, dao.boundaryOf(store, oct, cola))
    }

    @Test
    fun `一个文件都没同步过时也能落地基线`() = runBlocking {
        putLocal(cola, 0)
        assertTrue(dao.applyAbsolute(store, "K1", cola, 25, "BATCH_UPSERT", 1_000))
        assertEquals(25, dao.baseline(store, cola))
        assertNull(dao.boundaryOf(store, oct, cola))
    }

    @Test
    fun `只在本地表里的条码走回退分支_且不往已同步区插行`() = runBlocking {
        putLocal(cola, 3)
        dao.applyAbsolute(store, "K1", cola, 42, "BATCH_UPSERT", 1_000)
        assertEquals(42, dao.baseline(store, cola))
        assertEquals(0, countOf("SELECT COUNT(*) FROM product_synced"))
    }

    @Test
    fun `条码两张表都没有时不会凭空造一行`() = runBlocking {
        // UPDATE 命中 0 行就是 0 行。库存观测无权创造商品 ——
        // 否则一个已删条码会从库存流里"复活"进补货清单，而且没有名字。
        putFile(oct, 5)
        dao.applyAbsolute(store, "K1", cola, 25, "STOCK_STREAM", 1_000)
        assertNull(dao.baseline(store, cola))
        assertEquals(0, countOf("SELECT COUNT(*) FROM product_synced"))
        assertEquals(0, countOf("SELECT COUNT(*) FROM product_local"))
        assertEquals("去重日志照记，这样重投递时也不会突然生效",
            1, countOf("SELECT COUNT(*) FROM stock_apply_log"))
    }

    @Test
    fun `去重日志按_店铺_加_key_去重_不跨店`() = runBlocking {
        putSynced(cola, 100)
        putSynced(cola, 100, storeKey = other)
        putFile(oct, 5)
        putFile(oct, 5, storeKey = other)

        assertTrue(dao.applyAbsolute(store, "K1", cola, 40, "STOCK_STREAM", 1_000))
        assertTrue("同 key 不同店铺是两条不同的观测",
            dao.applyAbsolute(other, "K1", cola, 60, "STOCK_STREAM", 1_000))
        assertEquals(40, dao.baseline(store, cola))
        assertEquals(60, dao.baseline(other, cola))
    }

    @Test
    fun `appliedAt_只落诊断字段_不参与判定`() = runBlocking {
        // 切分依据是版本号。第二条观测的墙钟时间**更早**，但它必须生效。
        putSynced(cola, 100)
        putFile(oct, 5)
        dao.applyAbsolute(store, "K1", cola, 40, "STOCK_STREAM", 5_000)
        dao.applyAbsolute(store, "K2", cola, 25, "PRODUCT_FULL", 100)
        assertEquals(25, dao.baseline(store, cola))
    }

    private fun countOf(query: String, vararg args: Any?): Int {
        val c = db.openHelper.readableDatabase.query(query, args)
        c.use { it.moveToFirst(); return it.getInt(0) }
    }

    // ==================== 端到端：真 Room 上的 StockLedger ====================

    @Test
    fun `交错场景_三笔订单_一次入库_一次_FULL_一次取消_最终等于服务器绝对值`() = runBlocking {
        // 方案 §7.3(d) 的验收场景，跑在**真 SQL** 上（JVM 那边用 fake 跑了一遍同样的剧本）。
        // 服务器那侧的数字是手算的：`order_write.lua` 第 5 节对 B 类订单直接 HSET 绝对值，
        // 不写 stream:stock、不推进 version_stock。
        val ledger = StockLedger(dao) { 1_000L }
        var server = 100
        putSynced(cola, server)
        putFile(oct, 0)

        fun fileVersion(): Int {
            // ⚠ 用绑定参数，不把值拼进 SQL 字符串。这里是测试常量、拼了也不会出事，
            // 但产品代码里同样的写法就是注入面 —— 测试是被人抄的，别留坏榜样。
            val c = db.openHelper.readableDatabase.query(
                "SELECT version FROM file_state WHERE storeKey = ? AND fileId = ?",
                arrayOf(store, oct),
            )
            c.use { it.moveToFirst(); return it.getInt(0) }
        }

        fun bump(): Int {
            sql("UPDATE file_state SET version = version + 1 WHERE storeKey = ? AND fileId = ?", store, oct)
            return fileVersion()
        }

        suspend fun step(what: String) {
            assertEquals("$what：本地必须等于服务器", server, ledger.effectiveStock(store, cola))
        }

        // 1) 两笔卖出。B 类不写库存流，本地只能靠"基线 + 边界之后的增量"算出来。
        putOrder(oct, "u1", appliedFileVersion = bump(), items = arrayOf(cola to 2)); server -= 2
        step("卖出 u1 之后")
        putOrder(oct, "u2", appliedFileVersion = bump(), items = arrayOf(cola to 3)); server -= 3
        step("卖出 u2 之后")

        // 2) 显式入库到 200（PUT 的响应带 newStock，是一次绝对观测）。
        server = 200
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.stockApi("put-1", cola), cola, 200, "STOCK_API"))
        step("显式入库之后")
        // u1/u2 的扣减此刻被基线吸收：边界已推到 v2，它们的 appliedFileVersion 都 <= 2。
        assertEquals(2, dao.boundaryOf(store, oct, cola))

        // 3) 第三笔卖出，落在新边界之后。
        putOrder(oct, "u3", appliedFileVersion = bump(), items = arrayOf(cola to 5)); server -= 5
        step("卖出 u3 之后")

        // 4) 取消 u3。服务器在取消那一刻把货退回去。
        sql(
            "UPDATE order_index SET status = 0, cancelAppliedVersion = ? WHERE storeKey = ? AND orderUuid = ?",
            bump(), store, "u3",
        )
        server += 5
        step("取消 u3 之后")

        // 5) 商品 FULL（priceVersion=7），带回服务器当前的绝对值。
        assertTrue(ledger.applyAbsolute(store, StockLedger.DedupKeys.productFull(7, 7, cola), cola, server, "PRODUCT_FULL"))
        step("商品 FULL 之后")
        assertEquals("FULL 把边界推到当前", 4, dao.boundaryOf(store, oct, cola))

        // 6) FULL 之后又来一笔。
        putOrder(oct, "u4", appliedFileVersion = bump(), items = arrayOf(cola to 1)); server -= 1
        step("FULL 之后再卖一笔")

        // 7) 重投递：把第 2 步那次入库的响应再投一遍（SSE 重连最常见的事）。
        assertFalse(ledger.applyAbsolute(store, StockLedger.DedupKeys.stockApi("put-1", cola), cola, 200, "STOCK_API"))
        step("重投递一次旧的绝对观测之后")

        // 8) /add 的对账：服务器说 199，本地也应该是 199。
        assertEquals(199, server)
        assertEquals(StockLedger.Result.MATCH, ledger.reconcile(store, "R1", cola, server))
    }

    @Test
    fun `对账自愈在真_SQL_上收敛`() = runBlocking {
        val ledger = StockLedger(dao) { 1_000L }
        putSynced(cola, 100)
        putFile(oct, 4)
        setBoundary(oct, 0)
        putOrder(oct, "u1", appliedFileVersion = 3, items = arrayOf(cola to 9))

        assertEquals(91, ledger.effectiveStock(store, cola))
        assertEquals(StockLedger.Result.LOCAL_TOO_LOW(true), ledger.reconcile(store, "R1", cola, 100))
        assertEquals(100, ledger.effectiveStock(store, cola))
        assertEquals(4, dao.boundaryOf(store, oct, cola))
        // 自愈留下的痕迹要能看出来是"对账"而不是"库存流" —— 排查库存问题时这是唯一的线索。
        val c = db.openHelper.readableDatabase.query(
            "SELECT source, newStock FROM stock_apply_log WHERE storeKey = ? AND dedupKey = ?",
            arrayOf(store, "R1"),
        )
        c.use {
            it.moveToFirst()
            assertEquals(StockLedger.SOURCE_RECONCILE, it.getString(0))
            assertEquals(100, it.getInt(1))
        }
    }

    @Test
    fun `批量与逐条在真_SQL_上完全一致`() = runBlocking {
        // 补货清单走 `effectiveStockAll`（两次聚合查询），开单页走 `effectiveStock`（逐条）。
        // 两条路径的合并视图语义或增量语义只要有一处不同，两个页面显示的库存就会对不上，
        // 而两边各自看都"是对的"。
        val ledger = StockLedger(dao) { 1_000L }
        putSynced(cola, 100)
        putLocal(water, 50)
        putFile(oct, 9)
        putFile(sep, 4)
        setBoundary(oct, 2)
        setBoundary(sep, 4)
        // 边界是 per-barcode 的：water 也要各插一行，否则它落进 `IFNULL(...,0)` 的兜底，
        // 于是 sep 那笔 v4 的卖出会被算成"边界之后" → 42 而不是 43。
        setBoundary(oct, 2, water)
        setBoundary(sep, 4, water)
        putOrder(oct, "u1", appliedFileVersion = 3, items = arrayOf(cola to 2, water to 7))
        putOrder(oct, "u2", appliedFileVersion = 1, items = arrayOf(cola to 100))   // 边界之内，不该扣
        putOrder(sep, "u3", appliedFileVersion = 4, items = arrayOf(water to 1))     // 边界之内，不该扣
        putOrder(aFile, "u4", appliedFileVersion = 9, items = arrayOf(cola to 50))   // A 类，不算

        val all = ledger.effectiveStockAll(store)
        assertEquals(mapOf(cola to 98, water to 43), all)
        for ((barcode, stock) in all) {
            assertEquals("barcode=$barcode 两条路径必须一致", stock, ledger.effectiveStock(store, barcode))
        }
    }
}
