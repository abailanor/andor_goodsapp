package com.example.myapplication.log

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.CoroutineContext

/**
 * [ChangeLog] 的按月滚动与尾部读取（方案 §5.3）。
 *
 * ## 这个文件是**给人读的诊断材料**，所以测的是"不会骗人"
 *
 * 统计一律不许读它（无事务、无索引、进程被杀会留半行）。但正因为它唯一的用途是
 * "用户报障时贴出来 / 开发查 401、409、Diverged"，一旦它把两个年份的日志混在一个文件里、
 * 或者把该删的证据删掉了，它就变成了**主动误导**——比没有日志更糟。
 *
 * 所以用例集中在三类：
 *  1. **跨年**：`1月.log` 只按月份命名，2027-01 时那个文件是 2026-01 留下的（[跨年_同名文件属于上一年_重写而不是追加]）；
 *  2. **删除前读 tag**：1 月的"两个月前"是上一年 11 月，同名文件可能属于**未来**（时钟被回拨过），删掉就是毁证据；
 *  3. **行完整性**：多线程并发写不许交错（[并发写_行与行不交错]），读尾部不许返回残行。
 */
class ChangeLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private val auditLines = mutableListOf<String>()

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private var nowMillis = at(2026, 10, 5, 10, 30, 15, 250)

    @Before
    fun setUp() {
        dir = tmp.newFolder("logs")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        auditLines.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 一个**永不派发**的调度器：消费者协程起不来，于是缓冲行为完全确定（见 [缓冲满_丢最旧并计数]）。 */
    private val neverDispatch = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private fun at(y: Int, m: Int, d: Int, h: Int = 0, mi: Int = 0, s: Int = 0, ms: Int = 0): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, m - 1, d, h, mi, s)
            set(Calendar.MILLISECOND, ms)
        }.timeInMillis

    private fun newLog(
        maxBuffered: Int = 10_000,
        dirProvider: () -> File = { dir },
        io: CoroutineDispatcher = Dispatchers.IO,
    ) = ChangeLog(
        dirProvider = dirProvider,
        scope = scope,
        now = { nowMillis },
        timeZone = { utc },
        io = io,
        maxBuffered = maxBuffered,
        audit = { auditLines += it },
    )

    private fun File.monthFile(month: Int) = File(this, "${month}月.log")

    private fun File.lines(): List<String> = readText(Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }

    private fun writeFixture(month: Int, firstLine: String, body: String = "") {
        val f = dir.monthFile(month)
        f.writeText(firstLine + "\n" + body, Charsets.UTF_8)
    }

    // ==================== 基本写入 ====================

    @Test
    fun 首行写_tag_同月内追加不再写() = runBlocking {
        val cl = newLog()
        cl.log("SSE", "第一条")
        cl.log("HTTP429", "第二条")
        cl.drain()

        val f = dir.monthFile(10)
        val ls = f.lines()
        assertEquals(3, ls.size)
        assertEquals("# year=2026 month=10", ls[0])
        assertEquals("2026-10-05 10:30:15.250 SSE 第一条", ls[1])
        assertEquals("2026-10-05 10:30:15.250 HTTP429 第二条", ls[2])
        // 整行一次交出去：进程被杀最多丢一整行，不会留半行拼在下一条前面。
        assertTrue("文件必须以换行结尾", f.readText(Charsets.UTF_8).endsWith("\n"))
        cl.close()
    }

    @Test
    fun 时间戳按注入的时区渲染() = runBlocking {
        nowMillis = at(2026, 10, 5, 10, 30, 15, 250)
        val cl = ChangeLog(
            dirProvider = { dir },
            scope = scope,
            now = { nowMillis },
            timeZone = { TimeZone.getTimeZone("Asia/Shanghai") }, // UTC+8
            io = Dispatchers.IO,
            audit = { auditLines += it },
        )
        cl.log("SSE", "x")
        cl.drain()
        assertTrue(dir.monthFile(10).lines()[1].startsWith("2026-10-05 18:30:15.250 SSE x"))
        cl.close()
    }

    // ==================== 跨年（承重） ====================

    @Test
    fun 跨年_同名文件属于上一年_重写而不是追加() = runBlocking {
        // 2027-01 时 `1月.log` 已经在了（2026-01 留下的）。只判"文件在不在"的话：
        //  ① 2027 年的日志被追加到 2026 年文件尾部，而两边时间戳都是同一种格式，肉眼分不出来；
        //  ② 轮转只在"新建当月文件"时发生，那次 never happens → 旧文件永远不删。
        nowMillis = at(2026, 1, 15, 9, 0, 0)
        val cl = newLog()
        cl.log("SSE", "2026 年的一月")
        cl.drain()
        assertEquals(2026 to 1, cl.readTag(dir.monthFile(1)))

        nowMillis = at(2027, 1, 3, 8, 0, 0)
        cl.log("SSE", "2027 年的一月")
        cl.drain()

        val f = dir.monthFile(1)
        assertEquals("首行 tag 必须换成 2027", 2027 to 1, cl.readTag(f))
        val ls = f.lines()
        assertEquals("旧年的行必须被丢掉，不能追加在后面", 2, ls.size)
        assertEquals("# year=2027 month=1", ls[0])
        assertEquals("2027-01-03 08:00:00.000 SSE 2027 年的一月", ls[1])
        cl.close()
    }

    @Test
    fun 首行被写坏_当作不存在并重写() = runBlocking {
        writeFixture(10, "半行没有 tag 的垃圾", "2026-10-01 00:00:00.000 SSE 旧\n")
        nowMillis = at(2026, 10, 5, 10, 30, 15, 250)
        val cl = newLog()
        cl.log("SSE", "新")
        cl.drain()

        val ls = dir.monthFile(10).lines()
        assertEquals(2, ls.size)
        assertEquals("# year=2026 month=10", ls[0])
        assertTrue(ls[1].endsWith("SSE 新"))
        cl.close()
    }

    @Test
    fun 当前月文件删不掉时_本次不写_免得把两个年份混在一起() = runBlocking {
        // 把 `10月.log` 做成一个**非空目录**：exists() 为真、readTag 抛、delete() 失败。
        val impostor = dir.monthFile(10).also { it.mkdirs() }
        File(impostor, "占位").writeText("x")

        val cl = newLog()
        cl.log("SSE", "写不进去")
        cl.drain()

        assertTrue(auditLines.any { it.contains("删不掉过期的") })
        assertTrue("占位目录必须原样留着当证据", impostor.isDirectory)
        assertEquals("一个字都不许写进去", listOf("x"), File(impostor, "占位").lines())
        cl.close()
    }

    // ==================== 轮转 ====================

    @Test
    fun 轮转_删掉两个月前的文件() = runBlocking {
        writeFixture(8, "# year=2026 month=8", "2026-08-01 00:00:00.000 SSE 旧\n")
        writeFixture(9, "# year=2026 month=9", "2026-09-01 00:00:00.000 SSE 上月\n")

        val cl = newLog()
        cl.log("SSE", "新建当月")
        cl.drain()

        assertFalse("8 月（两个月前）应被删掉", dir.monthFile(8).exists())
        assertTrue("9 月（上月）必须保留", dir.monthFile(9).exists())
        assertTrue(dir.monthFile(10).exists())
        assertTrue(auditLines.any { it.contains("日志轮转") && it.contains("8月.log") })
        cl.close()
    }

    @Test
    fun 轮转_一月时删的是上一年的十一月() = runBlocking {
        writeFixture(11, "# year=2026 month=11", "2026-11-01 00:00:00.000 SSE 旧\n")
        nowMillis = at(2027, 1, 5, 8, 0, 0)

        val cl = newLog()
        cl.log("SSE", "新年第一条")
        cl.drain()

        assertFalse(dir.monthFile(11).exists())
        assertEquals(2027 to 1, cl.readTag(dir.monthFile(1)))
        cl.close()
    }

    @Test
    fun 轮转_目标文件_tag_不早于当前月_则不删() = runBlocking {
        // 时钟被往回拨过（或上次跑的时候系统时间是错的）。同名文件属于**未来**，
        // 删掉就是毁掉更新的证据 —— 而这件事不会报错，只会让用户少一个月的日志。
        writeFixture(8, "# year=2027 month=8", "2027-08-01 00:00:00.000 SSE 未来的\n")

        val cl = newLog() // now = 2026-10
        cl.log("SSE", "x")
        cl.drain()

        assertTrue("tag 更新的同名文件不许删", dir.monthFile(8).exists())
        assertTrue(auditLines.any { it.contains("时钟被改过") })
        cl.close()
    }

    @Test
    fun 轮转_目标文件_tag_读不出来_当垃圾删掉() = runBlocking {
        writeFixture(8, "这不是 tag，是半行残骸")

        val cl = newLog()
        cl.log("SSE", "x")
        cl.drain()

        assertFalse("tag 读不出来的文件是垃圾，删掉", dir.monthFile(8).exists())
        assertTrue(auditLines.any { it.contains("读不出来") })
        cl.close()
    }

    @Test
    fun 轮转_只在需要新建文件时发生() = runBlocking {
        // 每次写都轮转的话，写一行日志要扫一遍目录 —— 异常风暴时每秒几十行会很难看。
        writeFixture(8, "# year=2026 month=8")
        val cl = newLog()
        cl.log("SSE", "第一条") // 新建 10月.log → 轮转，删掉 8月
        cl.drain()
        assertFalse(dir.monthFile(8).exists())

        writeFixture(8, "# year=2026 month=8") // 又出现一个
        cl.log("SSE", "第二条") // 10月.log 已在且 tag 相符 → 不轮转
        cl.drain()
        assertTrue("同月内的后续写入不该触发轮转", dir.monthFile(8).exists())
        cl.close()
    }

    // ==================== 不阻塞、不抛、不无限占内存 ====================

    @Test
    fun 缓冲满_丢最旧并计数() {
        // 为什么丢最旧而不是阻塞调用方：调用方是 SSE 回调与同步引擎，
        // 为了写一行日志把它们挂住，等于让"记录故障"这件事本身制造故障。
        val cl = ChangeLog(
            dirProvider = { dir },
            scope = CoroutineScope(neverDispatch), // 消费者起不来，缓冲行为完全确定
            now = { nowMillis },
            timeZone = { utc },
            io = neverDispatch,
            maxBuffered = 2,
            audit = { auditLines += it },
        )
        repeat(5) { cl.log("T", "m$it") }
        assertEquals(3L, cl.droppedCount)
        cl.close()
    }

    @Test
    fun 落盘失败_计数且_log_绝不抛() = runBlocking {
        val cl = newLog(dirProvider = { throw RuntimeException("存储卷不见了") })
        cl.log("SSE", "x") // 不抛
        cl.drain()
        assertEquals(1L, cl.failureCount)
        assertEquals(0L, cl.droppedCount)
        cl.close()
    }

    @Test
    fun close_之后迟到的_log_被丢掉_不抛也不污染计数() = runBlocking {
        // close() 之后还会有迟到的调用：SSE 回调跑在 OkHttp 的线程上，退登时不一定已经停干净。
        // 这时抛出去会打到调用方（也就是同步引擎）——"记录故障"这件事本身绝不能制造故障。
        val cl = newLog()
        cl.log("SSE", "x")
        cl.drain()
        cl.close()

        repeat(3) { cl.log("SSE", "迟到$it") }

        assertEquals("迟到条目不是「日志量异常」，不该进 dropped", 0L, cl.droppedCount)
        assertEquals(0L, cl.failureCount)
        assertEquals("一个字都不该再写进去", 2, dir.monthFile(10).lines().size)
        // 记账闭合：submitted 没被迟到条目加过，所以 drain 必须立刻返回而不是永远转下去
        cl.drain()
    }

    @Test
    fun 并发写_行与行不交错() = runBlocking {
        // 多线程各自 appendText 的话两个半行会拼在一起，日志就废了 ——
        // 而且是**静默**废掉：文件看着还是那么多行，只是有几行读不出来。
        val threads = 8
        val perThread = 200
        val cl = newLog(maxBuffered = threads * perThread + 16)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) { tid ->
            Thread {
                try {
                    start.await()
                    repeat(perThread) { i -> cl.log("T$tid", "线程$tid 第${i}条 填充填充填充") }
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        done.await()
        cl.drain()

        val ls = dir.monthFile(10).lines()
        assertEquals(0L, cl.droppedCount)
        assertEquals("条数不能少", 1 + threads * perThread, ls.size)
        val lineRegex = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} T\d 线程\d 第\d+条 填充填充填充$""")
        ls.drop(1).forEachIndexed { idx, line ->
            assertTrue("第 $idx 行被切碎或交错了：<<$line>>", lineRegex.matches(line))
        }
        // FIFO：同一线程内部的相对顺序必须保留（乱序的日志会把因果讲反）
        val t0 = ls.drop(1).filter { it.contains(" T0 ") }
        val nums = t0.map { Regex("第(\\d+)条").find(it)!!.groupValues[1].toInt() }
        assertEquals((0 until perThread).toList(), nums)
        cl.close()
    }

    // ==================== 读 ====================

    @Test
    fun tail_时间升序_不含_tag_且截到_maxLines() = runBlocking {
        val cl = newLog()
        repeat(5) { i ->
            nowMillis = at(2026, 10, 5, 10, 0, i)
            cl.log("SSE", "第${i}条")
        }
        cl.drain()

        assertEquals(
            listOf(
                "2026-10-05 10:00:02.000 SSE 第2条",
                "2026-10-05 10:00:03.000 SSE 第3条",
                "2026-10-05 10:00:04.000 SSE 第4条",
            ),
            cl.tail(3),
        )
        cl.close()
    }

    @Test
    fun tail_当月不存在时退回上月() = runBlocking {
        // 月初刚过、还没写任何日志的时候，用户想看的正是上个月末尾那几条。
        nowMillis = at(2026, 9, 28, 23, 0, 0)
        val cl = newLog()
        cl.log("SSE", "九月末")
        cl.drain()

        nowMillis = at(2026, 10, 1, 0, 5, 0)
        assertEquals(listOf("2026-09-28 23:00:00.000 SSE 九月末"), cl.tail())
        cl.close()
    }

    @Test
    fun tail_两个月的文件都不存在时返回空() = runBlocking {
        val cl = newLog()
        assertEquals(emptyList<String>(), cl.tail())
        cl.close()
    }

    @Test
    fun tail_跨块读取时丢掉残行且不遗漏() = runBlocking {
        // 从文件尾部往前按块读，块起点几乎必然落在某一行中间，
        // 而且从中间截断会切碎一个 UTF-8 多字节字符 —— 所以块起点不在文件头时必须丢掉第一行。
        val cl = newLog()
        repeat(50) { i ->
            nowMillis = at(2026, 10, 5, 10, 0, 0) + i
            cl.log("SSE", "行${i.toString().padStart(2, '0')} " + "中".repeat(30))
        }
        cl.drain()

        val tail = cl.tail(10)
        assertEquals(10, tail.size)
        val nums = tail.map { Regex("""行(\d\d) """).find(it)?.groupValues?.get(1)?.toInt() }
        assertEquals("必须是**最后** 10 行，且升序", (40..49).toList(), nums)
        tail.forEach { assertTrue("不该返回残行：<<${it.take(30)}>>", it.startsWith("2026-10-05 ")) }

        // 一次读到头（行数少于 maxLines）时，tag 行必须被过滤掉
        val all = cl.tail(500)
        assertEquals(50, all.size)
        assertFalse(all.any { it.startsWith(ChangeLog.TAG_PREFIX) })
        cl.close()
    }

    @Test
    fun tail_maxLines_必须为正() = runBlocking {
        val cl = newLog()
        try {
            cl.tail(0)
            fail("maxLines=0 应当抛 IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // 静默返回空列表会让"变更消息页"看起来像"没有任何变更"
        }
        cl.close()
    }

    // ==================== tag 解析 ====================

    @Test
    fun parseTag_接受规范格式() {
        val cl = newLog()
        assertEquals(2026 to 10, cl.parseTag("# year=2026 month=10"))
        assertEquals(2026 to 1, cl.parseTag("# year=2026 month=1"))
        assertEquals(2026 to 10, cl.parseTag("# year=2026 month=10 "))
        assertEquals(2026 to 10, cl.parseTag("# year=2026 month=10\r"))
        cl.close()
    }

    @Test
    fun parseTag_拒绝一切读不准的输入() {
        val cl = newLog()
        assertNull(cl.parseTag(null))
        assertNull(cl.parseTag(""))
        assertNull(cl.parseTag("# year=2026 month=13")) // 月份越界
        assertNull(cl.parseTag("# year=2026 month=0"))
        assertNull(cl.parseTag("# year=26 month=1")) // 年份不是 4 位
        assertNull(cl.parseTag("# year=2026 month=1 尾巴"))
        assertNull(cl.parseTag("2026-10-05 10:30:15.250 SSE x"))
        assertNull(cl.parseTag("# year=2026 month=abc"))
        cl.close()
    }

    @Test
    fun readTag_文件不存在或为空时返回_null() {
        val cl = newLog()
        assertNull(cl.readTag(File(dir, "根本没有.log")))
        val empty = File(dir, "空.log").also { it.writeText("") }
        assertNull(cl.readTag(empty))
        cl.close()
    }

    @Test
    fun readTag_只读第一行_不把整个文件拉进堆() = runBlocking {
        // 异常风暴时一个月的日志能长到几十 MB。
        val f = dir.monthFile(10)
        f.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("# year=2026 month=10\n")
            repeat(20_000) { w.write("2026-10-05 10:00:00.000 SSE " + "长".repeat(80) + "\n") }
        }
        val cl = newLog()
        assertEquals(2026 to 10, cl.readTag(f))
        cl.close()
    }
}
