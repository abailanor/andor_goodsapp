package com.example.myapplication.data

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.myapplication.app.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 离线数据**收养**：把挂在 [ServiceLocator.LOCAL_STORE_KEY] 下的全部数据改写成真实 merchantCode。
 *
 * ## 为什么需要它
 *
 * App 必须**无账号也能完整使用**（方案 R2：用户可能先离线用几个月才买服务）。
 * 那段时间的数据挂在保留键 `__local__` 下。首次登录成功后要一次性搬进真实店铺，
 * 否则用户一登录，自己几个月的商品与订单会**从界面上全部消失** ——
 * 数据还在库里，只是查不到了，而且没有任何报错。
 *
 * ## ⚠ 漏一张表的后果是静默的
 *
 * 22 张表**全部**以 `storeKey` 为主键第一列。收养时漏掉任何一张，
 * 那一类数据就永远留在 `__local__` 下：商品还在但订单没了、统计对得上但补货清单是空的……
 * 每一种都看起来像"同步出问题了"，而真正的原因是搬漏了一张表。
 *
 * 所以 [TABLES] 是**唯一的清单**，并且 `AdoptionTxTest` 会拿它与 Room 导出的
 * `app/schemas/…/4.json` 做集合比对 —— 以后谁加了第 23 张表而忘了登记，测试会红。
 *
 * ## ⚠ 不做合并，撞了就拒绝
 *
 * `UPDATE … SET storeKey = 新 WHERE storeKey = 旧` 在目标键**已有同主键的行**时会违反主键约束，
 * 整个事务失败。此时有三种选择：`UPDATE OR IGNORE`（旧数据留在 `__local__`，看不见但不报错）、
 * `UPDATE OR REPLACE`（悄悄删掉目标店铺已有的行）、或者**拒绝并告诉用户**。
 *
 * 前两种都是静默丢数据，所以这里选第三种：先逐表数一遍目标键有没有数据，
 * 有就**一条都不改**，把占用的表名列出来交给上层决定。
 *
 * 按现在的语义（登出后作用域**停在原店铺**，`__local__` 只用于"从没登录过"），
 * 收养一生只发生一次、且那一刻目标键必然是空的，所以这条拒绝路径正常情况下走不到 ——
 * 但"正常情况走不到"恰恰是它必须存在的原因：走到了就说明前面的假设坏了。
 */
class AdoptionTx(
    /** 单值查询（`SELECT COUNT(*)`）。 */
    private val query: suspend (sql: String, args: List<Any?>) -> Long,
    /** 执行 UPDATE，返回受影响行数。 */
    private val exec: suspend (sql: String, args: List<Any?>) -> Int,
    /** "在一个数据库事务里跑这段"。生产实现是 `db.withTransaction { … }`（见 [of]）。 */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val audit: (String) -> Unit = {},
) {

    /** 收养结果。 */
    sealed interface Result {

        /** `oldKey == newKey`，什么都没做（例如登出后又登录同一家店）。 */
        data object NoOp : Result

        /**
         * 成功。[rows] 是逐表的受影响行数，用来给用户看一句"已导入 N 个商品、M 笔订单"，
         * 也用来事后核对——某张表是 0 而用户明确记得有数据，就是搬漏了。
         */
        data class Adopted(val rows: Map<String, Int>) : Result {
            val totalRows: Int get() = rows.values.sum()
        }

        /**
         * 目标店铺**已经有本地数据**，拒绝收养（一条都没改）。
         *
         * [occupied] 是查出有数据的表名。这不是错误状态，数据都还在，
         * 但"把两份数据合成一份"需要用户参与决定，不能由这里猜。
         */
        data class Refused(val occupied: List<String>) : Result
    }

    /**
     * 把 [oldKey] 下的数据搬到 [newKey]。
     *
     * **先检查、后改写**，两步不在同一个事务里是有意的：
     * 检查是只读的，放在事务外可以让"拒绝"这条路径完全不持有写锁。
     */
    suspend fun adopt(oldKey: String, newKey: String): Result {
        require(oldKey.isNotBlank() && newKey.isNotBlank()) { "storeKey 不能为空" }
        if (oldKey == newKey) return Result.NoOp

        val occupied = TABLES.filter { table ->
            query("SELECT COUNT(*) FROM $table WHERE storeKey = ?", listOf(newKey)) > 0L
        }
        if (occupied.isNotEmpty()) {
            audit("收养被拒绝：$newKey 下已有数据（${occupied.joinToString()}），$oldKey 的数据原样保留")
            return Result.Refused(occupied)
        }

        val at = now()
        val rows = LinkedHashMap<String, Int>()
        inTransaction {
            for (table in TABLES) {
                // ⚠ store_meta 额外盖 adoptedAt：它是"何时被收养"的唯一记录，
                // 之后排查"这个店铺的数据是搬来的还是原生成的"只看这一列。
                val (sql, args) = if (table == TABLE_STORE_META) {
                    "UPDATE $table SET storeKey = ?, adoptedAt = ? WHERE storeKey = ?" to
                        listOf<Any?>(newKey, at, oldKey)
                } else {
                    "UPDATE $table SET storeKey = ? WHERE storeKey = ?" to listOf<Any?>(newKey, oldKey)
                }
                rows[table] = exec(sql, args)
            }
        }
        audit("收养完成：$oldKey → $newKey，共 ${rows.values.sum()} 行")
        return Result.Adopted(rows)
    }

    companion object {

        /** `store_meta` 的 SET 子句与其它表不同（多一列 adoptedAt），所以单独点名。 */
        private const val TABLE_STORE_META = "store_meta"

        /**
         * **全部 22 张表**，与 `app/schemas/com.example.myapplication.data.AppDatabase/4.json`
         * 的实体清单逐一对应。加表时必须同步加这里 —— `AdoptionTxTest` 会盯着。
         *
         * 顺序按"商品 → 订单 → 统计 → 库存 → 补货/偏好 → 总部"分组，纯粹为了读日志时好找。
         * UPDATE 之间没有依赖（各表独立），所以顺序不影响结果。
         */
        val TABLES: List<String> = listOf(
            // 身份与作用域
            "store_meta",
            // 商品
            "product_synced",
            "product_local",
            "product_pending_change",
            "product_extra",
            "product_tombstone",
            "product_token",
            // 订单与镜像
            "file_state",
            "order_index",
            "order_item_index",
            "order_group",
            "order_outbox",
            // 统计
            "stats_contrib",
            "stats_agg",
            // 库存
            "stock_absorb_boundary",
            "stock_apply_log",
            // 补货与界面偏好
            "restock_item",
            "restock_rule",
            "restock_filter",
            "sort_pref",
            // 总部版 flavor
            "admin_catalog",
            "admin_catalog_cursor",
        )

        /**
         * 表名会被拼进 SQL（值一律用 `?` 绑定，但表名不能参数化）。
         *
         * [TABLES] 是硬编码常量、不含任何外部输入，所以这里其实拦不到真实攻击；
         * 加这道校验是为了**万一**将来有人把表名改成从配置或用户输入读，
         * 拼 SQL 这条路不会静默变成一个注入口子。
         */
        private val SAFE_NAME = Regex("^[a-z][a-z0-9_]*$")

        init {
            for (t in TABLES) {
                check(SAFE_NAME.matches(t)) { "非法表名进了 AdoptionTx.TABLES: $t" }
            }
            check(TABLES.distinct().size == TABLES.size) { "AdoptionTx.TABLES 有重复项" }
        }

        fun of(
            db: RoomDatabase,
            now: () -> Long = { System.currentTimeMillis() },
            audit: (String) -> Unit = {},
        ) = AdoptionTx(
            // ⚠ 都切到 IO：事务外那 22 次 COUNT 预检也是真的磁盘读，
            // 而 `adopt` 的调用方（登录流程）在主线程上挂着。
            query = { sql, args ->
                withContext(Dispatchers.IO) {
                    db.openHelper.readableDatabase.query(sql, args.toTypedArray()).use { c ->
                        if (c.moveToFirst()) c.getLong(0) else 0L
                    }
                }
            },
            exec = { sql, args ->
                withContext(Dispatchers.IO) {
                    // compileStatement 而不是 execSQL：后者不返回受影响行数，
                    // 而逐表行数正是"搬漏了没有"的唯一可观测证据。
                    db.openHelper.writableDatabase.compileStatement(sql).use { s ->
                        args.forEachIndexed { i, v -> s.bind(i + 1, v) }
                        s.executeUpdateDelete()
                    }
                }
            },
            inTransaction = { body -> db.withTransaction { body() } },
            now = now,
            audit = audit,
        )

        /** `SupportSQLiteProgram` 只认 null / Long / Double / String / ByteArray / Boolean。 */
        private fun androidx.sqlite.db.SupportSQLiteProgram.bind(index: Int, value: Any?) {
            when (value) {
                null -> bindNull(index)
                is Long -> bindLong(index, value)
                is Int -> bindLong(index, value.toLong())
                is Double -> bindDouble(index, value)
                is Float -> bindDouble(index, value.toDouble())
                is Boolean -> bindLong(index, if (value) 1L else 0L)
                is String -> bindString(index, value)
                is ByteArray -> bindBlob(index, value)
                else -> throw IllegalArgumentException(
                    "无法绑定的参数类型 ${value.javaClass.name}（第 $index 个）"
                )
            }
        }
    }
}
