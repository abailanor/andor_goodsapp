package com.example.myapplication.data

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * [AdoptionTx] 的两件事：**表清单的完整性**与**拒绝语义**。
 *
 * ## 为什么第一条值得单独一个测试
 *
 * 收养漏掉一张表的后果是**静默的数据消失**：用户登录之后，商品还在但订单没了、
 * 统计对得上但补货清单是空的。每一种都看起来像"同步出问题了"，
 * 而真正的原因是搬漏了一张表 —— 而且不会有任何日志。
 *
 * 所以 [tableListMatchesRoomSchema] 不拿一份手写的期望清单去比，
 * 而是直接读 Room 导出的 `app/schemas/…/<version>.json`（`exportSchema = true` 的产物，
 * 也就是数据库结构的**权威来源**）。这样"加了第 23 张表但忘了登记进 TABLES"会立刻红，
 * 而不是等到某个用户升级之后丢数据。
 *
 * 读**最高版本**的 schema 而不是写死 `4.json`：升到 v5 之后这个测试会自动改比 v5 的清单。
 */
class AdoptionTxTest {

    // ==================== 表清单 ====================

    @Test
    fun `TABLES_与_Room_schema_的实体清单完全一致`() {
        val (version, tables) = schemaTables()

        assertEquals(
            "Room schema v$version 的表清单与 AdoptionTx.TABLES 不一致 —— " +
                "加了新表就必须在 TABLES 里登记，否则收养时那张表的数据会永远留在 __local__ 下（静默消失）",
            tables.toSortedSet(),
            AdoptionTx.TABLES.toSortedSet(),
        )
    }

    @Test
    fun `schema_里每张表都以_storeKey_作为主键第一列`() {
        // 这是"收养 = 改写 storeKey"这个做法成立的前提。
        // 哪张表的主键不以 storeKey 打头，那张表就不是按店铺隔离的，
        // 用同一套 UPDATE 去搬它就是错的。
        val (version, _) = schemaTables()
        val bad = schemaPrimaryKeys().filter { (_, pk) -> pk.firstOrNull() != "storeKey" }

        assertTrue("Room schema v$version 里这些表的主键不是以 storeKey 打头：$bad", bad.isEmpty())
    }

    @Test
    fun `TABLES_没有重复项_且都是合法标识符`() {
        assertEquals(AdoptionTx.TABLES.size, AdoptionTx.TABLES.distinct().size)
        for (t in AdoptionTx.TABLES) {
            assertTrue("非法表名 $t", t.matches(Regex("^[a-z][a-z0-9_]*$")))
        }
    }

    // ==================== adopt ====================

    private val sqls = mutableListOf<Pair<String, List<Any?>>>()
    private val queries = mutableListOf<Pair<String, List<Any?>>>()
    private val occupied = HashMap<String, Long>()
    private var transactions = 0
    private var clock = 1_700_000_000_000L
    private val audits = mutableListOf<String>()

    private val tx = AdoptionTx(
        query = { sql, args ->
            queries += sql to args
            occupied[tableOf(sql)] ?: 0L
        },
        exec = { sql, args ->
            sqls += sql to args
            3
        },
        inTransaction = { body ->
            transactions++
            body()
        },
        now = { clock },
        audit = { audits += it },
    )

    /** 从 `SELECT COUNT(*) FROM t …` 或 `UPDATE t SET …` 里取出表名。 */
    private fun tableOf(sql: String): String =
        if (sql.startsWith("UPDATE ")) sql.removePrefix("UPDATE ").substringBefore(" ")
        else sql.substringAfter("FROM ").substringBefore(" ")

    @Test
    fun `old_等于_new_时什么都不做`() {
        val r = suspendRun { tx.adopt("00002", "00002") }

        assertEquals(AdoptionTx.Result.NoOp, r)
        assertTrue(queries.isEmpty())
        assertTrue(sqls.isEmpty())
        assertEquals(0, transactions)
    }

    @Test
    fun `空_storeKey_抛异常`() {
        try {
            suspendRun { tx.adopt("", "00002") }
            fail("空 storeKey 应当抛")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("storeKey"))
        }
        assertTrue(sqls.isEmpty())
    }

    @Test
    fun `目标店铺已有数据时拒绝_且一条都不改`() {
        occupied["product_local"] = 7L
        occupied["stats_contrib"] = 2L

        val r = suspendRun { tx.adopt("__local__", "00002") } as AdoptionTx.Result.Refused

        // 22 张表全查一遍，把占用的都列出来（不是查到第一个就停 —— 用户要一次看全）
        assertEquals(AdoptionTx.TABLES.size, queries.size)
        assertEquals(listOf("product_local", "stats_contrib"), r.occupied)
        assertTrue("拒绝时一条 UPDATE 都不该发", sqls.isEmpty())
        assertEquals(0, transactions)
        assertTrue(audits.any { "收养被拒绝" in it })
    }

    @Test
    fun `成功时逐表改写_并在一个事务里`() {
        val r = suspendRun { tx.adopt("__local__", "00002") } as AdoptionTx.Result.Adopted

        assertEquals(AdoptionTx.TABLES.size, sqls.size)
        assertEquals(1, transactions)
        assertEquals(AdoptionTx.TABLES.size * 3, r.totalRows)
        // 顺序与清单一致：日志里对得上
        assertEquals(AdoptionTx.TABLES, sqls.map { tableOf(it.first) })
        assertTrue(audits.any { "收养完成" in it && "__local__ → 00002" in it })
    }

    @Test
    fun `每条_UPDATE_都按_storeKey_过滤_不会动别的店铺的数据`() {
        suspendRun { tx.adopt("__local__", "00002") }

        for ((sql, args) in sqls) {
            // 多账号共用一个库：漏了 WHERE 就是把别的店铺的数据一起搬走
            assertTrue("缺 WHERE storeKey：$sql", sql.contains("WHERE storeKey = ?"))
            val expected = if (sql.contains("adoptedAt")) {
                listOf<Any?>("00002", clock, "__local__")
            } else {
                listOf<Any?>("00002", "__local__")
            }
            assertEquals("绑定的参数对不上：$sql", expected, args)
        }
    }

    @Test
    fun `只有_store_meta_额外盖_adoptedAt`() {
        suspendRun { tx.adopt("__local__", "00002") }

        val withAdoptedAt = sqls.filter { it.first.contains("adoptedAt") }
        assertEquals(1, withAdoptedAt.size)
        assertTrue(withAdoptedAt.single().first.startsWith("UPDATE store_meta "))
        // adoptedAt 是"何时被收养"的唯一记录，之后排查"这个店铺的数据是搬来的还是原生成的"只看它
        assertEquals(clock, withAdoptedAt.single().second[1])
    }

    @Test
    fun `值一律用占位符绑定_表名之外没有拼接进来的字面量`() {
        suspendRun { tx.adopt("__local__", "00002") }

        for ((sql, _) in sqls) {
            // storeKey 的值绝不能出现在 SQL 文本里：那既是注入口子，
            // 也会让"含引号的店铺编号"直接拼出语法错误的语句
            assertTrue("SQL 里出现了未绑定的字面量：$sql", !sql.contains("__local__") && !sql.contains("00002"))
            assertTrue(sql.contains("SET storeKey = ?"))
        }
    }

    // ==================== schema 读取 ====================

    /** 找到 `app/schemas` 下版本最高的那份 schema。 */
    private fun latestSchema(): File {
        // 单测的工作目录是模块目录（app/），但不同 IDE / Gradle 版本下也可能是仓库根，两处都试
        val candidates = listOf("schemas", "app/schemas").map { File(it) }.filter { it.isDirectory }
        assertTrue("找不到 Room 导出的 schema 目录（试过 schemas/ 与 app/schemas/）", candidates.isNotEmpty())
        val files = candidates.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        }
        assertTrue("schema 目录里没有 json", files.isNotEmpty())
        return files.maxByOrNull { schemaVersionOf(it) }!!
    }

    private fun schemaVersionOf(f: File): Int =
        JsonParser.parseString(f.readText()).asJsonObject
            .getAsJsonObject("database").get("version").asInt

    private fun entities(): List<Pair<String, String>> {
        val db = JsonParser.parseString(latestSchema().readText()).asJsonObject.getAsJsonObject("database")
        return db.getAsJsonArray("entities").map { e ->
            val o = e.asJsonObject
            o.get("tableName").asString to o.get("createSql").asString
        }
    }

    private fun schemaTables(): Pair<Int, List<String>> =
        schemaVersionOf(latestSchema()) to entities().map { it.first }

    /** 从 `createSql` 里抠出 `PRIMARY KEY(...)` 的列名（按声明顺序）。 */
    private fun schemaPrimaryKeys(): List<Pair<String, List<String>>> = entities().map { (name, createSql) ->
        val m = Regex("PRIMARY KEY\\(([^)]*)\\)").find(createSql)
        name to (m?.groupValues?.get(1)?.split(",")?.map { it.trim().trim('`') }?.filter { it.isNotEmpty() }
            ?: emptyList())
    }

    /** 这些替身全是同步的，不需要真的协程调度器。 */
    private fun <T> suspendRun(block: suspend () -> T): T = kotlinx.coroutines.runBlocking { block() }
}
