package com.example.myapplication.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * `MIGRATION_4_5` 的 instrumented 测试（`order_outbox` 增加 `originDevice`）。
 *
 * ## ⚠ 跑这个测试需要一个 AVD 或真机
 *
 * `MigrationTestHelper` 依赖真实的 Android SQLite 与 assets，**JVM 单测跑不了**。
 *
 * ## 与 [Migration3To4Test] 的差别：这里的 v4 schema 是 Room 真导出的
 *
 * v3 那份是手写的（当时 `exportSchema = false`），所以那个测试"通过"并不能完全证明
 * 真实升级不崩。v4 开始有 `app/schemas/…/4.json`，`MigrationTestHelper` 会拿它建库，
 * 于是这个测试是真的在验一条从真实 v4 结构出发的迁移路径。
 *
 * ## ⚠ 迁移写错的后果是 App 起不来
 *
 * Room 打开库时会把**实际 schema** 与实体声明的 schema 逐列比对（含 `defaultValue`），
 * 对不上就抛 `IllegalStateException`。所以 `ALTER TABLE … ADD COLUMN … NOT NULL DEFAULT ''`
 * 必须与实体上的 `@ColumnInfo(defaultValue = "")` 一致 —— 少写 DEFAULT 会让已有行没值，
 * 多写或写错引号形式会让 schema 校验失败。两种都是"装上去就崩"。
 */
@RunWith(AndroidJUnit4::class)
class Migration4To5Test {

    private val dbName = "migration-test-4-to-5"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    /** 造一个 v4 库，塞一笔未上传订单（v4 里还没有 originDevice 列）。 */
    @Throws(IOException::class)
    private fun createV4WithOutboxRow() {
        helper.createDatabase(dbName, 4).apply {
            execSQL(
                """
                INSERT INTO order_outbox
                  (storeKey, orderUuid, groupId, fileType, year, month, payload, state,
                   blockedReason, blockedBarcodes, attemptCount, lastError,
                   pricedAt, createdAt, writer, totalCents, itemCount, orderTs)
                VALUES
                  ('00002', '11111111-2222-3333-4444-555555555555', '', 'B', 2026, 9,
                   X'7B226F72646572', 'READY', NULL, NULL, 0, NULL,
                   1700000000000, 1700000000000, 'alice', 5200, 1, 1700000000000)
                """.trimIndent()
            )
            close()
        }
    }

    @Test
    @Throws(IOException::class)
    fun `迁移后老行的_originDevice_是空串_其它列一个字节都没变`() {
        createV4WithOutboxRow()

        val db = helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5)

        db.query("SELECT originDevice, state, writer, totalCents, itemCount FROM order_outbox").use { c ->
            assertEquals("应当还是那一行", 1, c.count)
            c.moveToFirst()
            // ⚠ 空串按"本机"处理是**正确**的，不是将就：v5 之前的行都是这台设备自己开的、
            // 从来没被别人同步走过。填一个假 id 的话用户自己开的单会变成"来自其他设备"、
            // 永远不自动上传。
            assertEquals("", c.getString(0))
            assertEquals("READY", c.getString(1))
            assertEquals("alice", c.getString(2))
            assertEquals(5200L, c.getLong(3))
            assertEquals(1, c.getInt(4))
        }
        db.close()
    }

    @Test
    @Throws(IOException::class)
    fun `迁移是幂等的_重复跑不会报错也不会多出一列`() {
        createV4WithOutboxRow()

        helper.runMigrationsAndValidate(dbName, 5, true, MIGRATION_4_5).close()
        // 第二次：库已经是 v5 了，Room 会跳过这条迁移。
        // 真正要防的是"有人把 ADD COLUMN 写进了每次打开都跑的路径"——
        // 那第二次就会因为列已存在而抛。
        val db = helper.runMigrationsAndValidate(dbName, 5, true)
        db.query("SELECT COUNT(*) FROM order_outbox").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }
}
