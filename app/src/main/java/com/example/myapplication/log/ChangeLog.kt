package com.example.myapplication.log

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/**
 * 按月滚动的变更日志（方案 §5.3）。**给人读的诊断材料，不是数据源。**
 *
 * ## ⚠ 统计与同步一律不许读这个文件
 *
 * txt 没有事务、没有索引，进程被杀会留半行。统计数据全部建立在 Room（`stats_contrib` /
 * `stats_agg`）与镜像文件之上；这里只记"发生过什么"，供用户报障时贴出来、
 * 供开发看 401/403/409/429、500 的追踪号、`Diverged`、`UNKNOWN patch` 这些异常。
 *
 * 把它当数据源的话，一次半行就会让统计**静默**偏掉，而且没有任何一处会报错。
 *
 * ## 记什么
 *
 * SSE 的 `UPDATE`/`ORDER_FILE`/`PERMISSIONS`/`KICKED`、自己发起的上传与补丁、
 * 401/403/409/429、500 的追踪号+时间、`Diverged` / `UNKNOWN patch` 等异常。
 *
 * ## 文件布局
 *
 * ```
 * logs/10月.log      ← 首行 `# year=2026 month=10`
 * logs/9月.log
 * ```
 *
 * 文件名**只有月份没有年份**，所以最多 12 个文件，靠首行的 tag 区分属于哪一年。
 * 保留两个月：新建当月文件时删掉"两个月前"那个。
 *
 * ## ⚠ 跨年：判据必须是"这个文件属不属于当月"，不是"这个文件在不在"
 *
 * 2027 年 1 月时 `1月.log` 已经在了（2026 年 1 月留下的）。只判存在的话：
 *  - 2027 年的日志被**追加到 2026 年的文件尾部** —— 一年的日志混在一个文件里，
 *    而两个年份的时间戳都是 `MM-dd HH:mm`，肉眼根本分不出来；
 *  - `11月.log`（2026-11）永远不会被删 —— 因为轮转只在"新建当月文件"时发生，
 *    而那次 never happens。
 *
 * 所以每次写之前都读首行 tag，与当前 (年, 月) 比对；不符就当作"不存在"，走一次轮转。
 *
 * ## ⚠ 删除前也要读 tag
 *
 * 1 月的"两个月前"是**上一年** 11 月。同名文件 `11月.log` 可能属于 2026-11（该删），
 * 也可能属于 2027-11（时钟被往回拨过、或者上次跑的时候系统时间是错的 —— **不该删**）。
 * 只删 tag 严格早于当前 (年, 月) 的那个；tag 读不出来的（文件损坏）也删，那是垃圾。
 *
 * ## 写入是**单消费者串行**的
 *
 * 调用方（SSE 回调、OkHttp 线程、协程）用非阻塞的 [log]，条目进 [Channel]，
 * 由一个协程顺序落盘。这样行与行之间不会交错，也保证了 FIFO 顺序 ——
 * 多线程各自 `appendText` 的话两个半行会拼在一起，日志就废了。
 */
class ChangeLog(
    /** 现取：`File(context.filesDir, "logs")`。测试传临时目录。 */
    private val dirProvider: () -> File,
    private val scope: CoroutineScope,
    /** **墙钟**。这里记的是"给人看的几点几分"，与 [com.example.myapplication.core.time.MonotonicClock] 的用途相反。 */
    private val now: () -> Long = { System.currentTimeMillis() },
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 缓冲上限。满了丢**最旧**的一条并计入 [dropped]。
     *
     * 为什么丢最旧而不是阻塞调用方：调用方是 SSE 回调与同步引擎，
     * 为了写一行日志把它们挂住，等于让"记录故障"这件事本身制造故障。
     */
    private val maxBuffered: Int = DEFAULT_MAX_BUFFERED,
    private val audit: (String) -> Unit = {},
) {

    companion object {
        const val DEFAULT_MAX_BUFFERED = 1024

        /** 首行 tag 的格式。**改它就得同时改 [parseTag]，并且旧文件会全部被判成"不属于当月"而重写。** */
        const val TAG_PREFIX = "# year="

        /** 保留几个月。2 = 当月 + 上月。 */
        const val RETENTION_MONTHS = 2

        /**
         * 估一行日志多少字节，用来定 [readTailLines] 的首个读取块。
         *
         * `yyyy-MM-dd HH:mm:ss.SSS` = 23，tag 约 10，消息按 150 估。估小的代价是多翻一倍块重读
         * （一次 `seek`+`readFully`，很便宜）；估大的代价是一次性把不需要的字节拉进堆。
         * 所以宁可**估小**：正确性由"不够就翻倍"兜住，与这个常数无关。
         */
        private const val AVG_LINE_BYTES = 200L
    }

    private data class Entry(val atMillis: Long, val tag: String, val message: String)

    // ⚠ 计数器必须声明在 [entries] **之前**：`entries` 的 `onUndeliveredElement` 回调
    // 会读 `dropped`，而属性按声明顺序初始化。回调只在运行时触发所以不会真的读到 null，
    // 但把被捕获的字段声明在前面，读代码的人不必去论证这件事。
    private val dropped = AtomicLong()
    private val writeFailures = AtomicLong()
    private val written = AtomicLong()
    private val submitted = AtomicLong()

    private val entries = Channel<Entry>(maxBuffered, BufferOverflow.DROP_OLDEST) { dropped.incrementAndGet() }

    private val tagRegex = Regex("""^# year=(\d{4}) month=(\d{1,2})\s*$""")

    /**
     * 因为缓冲满而被丢掉的条数。**非 0 说明日志量异常**（例如陷入了重连循环），值得在诊断页显示。
     *
     * [close] 之后仍在飞行中的那一两条也会计进来（见 [log]），所以**非 0 不必然**是异常；
     * 但它上到几十上百就一定是。
     */
    val droppedCount: Long get() = dropped.get()

    /** 落盘失败的条数。非 0 通常是磁盘满或 `logs/` 被别的进程占了。 */
    val failureCount: Long get() = writeFailures.get()

    private var consumer: Job? = null

    // ==================== 写 ====================

    /**
     * 记一条。**非阻塞、不抛**：日志失败绝不能影响同步。
     *
     * @param tag 短标签，例如 `SSE` / `ORDER_FILE` / `HTTP429` / `DIVERGED`。
     *            ⚠ **不要**把用户输入或响应体原文当 tag：它进的是固定宽度的列。
     */
    fun log(tag: String, message: String) {
        // [close] 之后还会有迟到的调用：SSE 回调跑在 OkHttp 的线程上，退登时不一定已经停干净。
        // 这时直接丢掉，并且**不计数** —— [dropped] 的含义是"日志量异常"，退登后的迟到条目不是。
        // 顺带避免每次迟到调用都新起一个立即结束的消费者协程。
        if (entries.isClosedForSend) return
        submitted.incrementAndGet()
        // ⚠ 记账必须闭合：`submitted` 加了 1 就必须有 `written`/`writeFailures`/`dropped`
        // 三者之一也加 1，否则 [drain] 会永远转下去。DROP_OLDEST 的缓冲满**不算失败**
        // （trySend 返回成功，最旧那条走 onUndeliveredElement 计数），所以这里唯一会失败
        // 的就是"上面那次检查之后、trySend 之前，通道被关了"这个窗口。
        if (entries.trySend(Entry(now(), tag, message)).isFailure) dropped.incrementAndGet()
        startConsumerIfNeeded()
    }

    private fun startConsumerIfNeeded() {
        if (consumer?.isActive == true) return
        consumer = scope.launch(io) {
            for (e in entries) {
                val ok = runCatching { append(e) }.isSuccess
                if (ok) written.incrementAndGet() else writeFailures.incrementAndGet()
            }
        }
    }

    /** 等队列排空。测试用；生产代码不需要（[log] 本来就是发后即忘）。 */
    suspend fun drain() {
        while (written.get() + writeFailures.get() + dropped.get() < submitted.get()) yield()
    }

    // ==================== 落盘 ====================

    private fun append(entry: Entry) {
        val tz = timeZone()
        val cal = Calendar.getInstance(tz).apply { timeInMillis = entry.atMillis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1

        val dir = dirProvider()
        if (!dir.exists() && !dir.mkdirs()) {
            audit("日志目录建不出来：${dir.absolutePath}")
            return
        }
        val file = File(dir, fileName(month))

        // 只在"要新建文件"时轮转：每次写都轮转的话，写一行日志要扫一遍目录。
        var needHeader = false
        if (file.exists()) {
            val tag = readTag(file)
            if (tag == null || tag != year to month) {
                // 上一年的同名文件、或者首行被写坏了 → 都当"不存在"
                rotate(dir, tz, entry.atMillis)
                if (!file.delete()) {
                    audit("删不掉过期的 ${file.name}（tag=$tag），本次不写，免得把两个年份混在一起")
                    return
                }
                needHeader = true
            }
        } else {
            rotate(dir, tz, entry.atMillis)
            needHeader = true
        }

        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            .apply { timeZone = tz }
            .format(Date(entry.atMillis))
        val text = buildString {
            if (needHeader) appendLine("$TAG_PREFIX$year month=$month")
            append(stamp)
            append(' ')
            append(entry.tag)
            append(' ')
            appendLine(entry.message)
        }
        // 一次 write 把整行（含换行）交出去：进程被杀最多丢一整行，
        // 不会留半行拼在下一条前面 —— 那样两行都读不出来，而看着又像"日志错乱了"。
        file.appendText(text, Charsets.UTF_8)
    }

    /**
     * 删掉 [RETENTION_MONTHS] 个月之前的那个文件。见类注释里"删除前也要读 tag"。
     *
     * @param atMillis 当前时刻。**每次现算**，不复用调用方的 Calendar ——
     *                 `Calendar` 是可变的，`add()` 之后原引用跟着变，
     *                 跨月轮转时踩一次就会把"两个月前"算成"四个月前"。
     */
    private fun rotate(dir: File, tz: TimeZone, atMillis: Long) {
        val cur = Calendar.getInstance(tz).apply { timeInMillis = atMillis }
        val curYear = cur.get(Calendar.YEAR)
        val curMonth = cur.get(Calendar.MONTH) + 1

        val old = Calendar.getInstance(tz).apply {
            timeInMillis = atMillis
            add(Calendar.MONTH, -RETENTION_MONTHS)
        }
        val victim = File(dir, fileName(old.get(Calendar.MONTH) + 1))
        if (!victim.exists()) return

        val tag = readTag(victim)
        if (tag != null && (tag.first > curYear || (tag.first == curYear && tag.second >= curMonth))) {
            // 比当前月还新 —— 时钟被往回拨过。删掉就是毁掉更新的证据。
            audit("轮转时跳过 ${victim.name}：tag=$tag 不早于当前 $curYear-$curMonth（时钟被改过？）")
            return
        }
        if (victim.delete()) {
            audit("日志轮转：删掉 ${victim.name}（tag=${tag ?: "读不出来"}）")
        } else {
            audit("⚠ 删不掉过期日志 ${victim.name}")
        }
    }

    private fun fileName(month: Int) = "${month}月.log"

    /**
     * 读首行 tag → `(year, month)`。读不出来（文件空、首行被截断、不是 tag）返回 null。
     *
     * 只读**第一行**：日志文件可能几 MB，`readLines()` 会把整个文件拉进堆。
     */
    internal fun readTag(file: File): Pair<Int, Int>? {
        if (!file.exists()) return null
        val first = runCatching {
            file.bufferedReader(Charsets.UTF_8).use { it.readLine() }
        }.getOrNull() ?: return null
        return parseTag(first)
    }

    internal fun parseTag(line: String?): Pair<Int, Int>? {
        if (line == null || !line.startsWith(TAG_PREFIX)) return null
        // `# year=2026 month=10`
        val m = tagRegex.find(line) ?: return null
        val year = m.groupValues[1].toIntOrNull() ?: return null
        val month = m.groupValues[2].toIntOrNull() ?: return null
        if (month !in 1..12) return null
        return year to month
    }

    // ==================== 读 ====================

    /**
     * 读最近的日志（变更消息页用）。
     *
     * 当月文件不存在时退回上一个月 —— 月初刚过、还没写任何日志的时候，
     * 用户想看的正是上个月末尾那几条。
     *
     * @return 最多 [maxLines] 行，**时间升序**（旧在上）。不含首行的 tag。
     */
    suspend fun tail(maxLines: Int = 400): List<String> = withContext(io) {
        require(maxLines > 0) { "maxLines 必须为正" }
        val dir = dirProvider()
        val base = now()
        val tz = timeZone()
        val curMonth = Calendar.getInstance(tz).apply { timeInMillis = base }.get(Calendar.MONTH) + 1
        val prevMonth = Calendar.getInstance(tz).apply {
            timeInMillis = base
            add(Calendar.MONTH, -1)
        }.get(Calendar.MONTH) + 1

        val file = listOf(fileName(curMonth), fileName(prevMonth))
            .map { File(dir, it) }
            .firstOrNull { it.exists() }
            ?: return@withContext emptyList()

        runCatching { readTailLines(file, maxLines) }
            .getOrElse { e ->
                audit("读日志失败 ${file.name}: ${e.message}")
                emptyList()
            }
    }

    /**
     * 从**文件尾部**往前读，只把需要的部分拉进堆。
     *
     * 一个月的日志在异常风暴时（例如陷入重连循环）能长到几十 MB，
     * `readLines()` 会整个读进来再 `takeLast` —— 恰好是在最不该占内存的时候占最多。
     *
     * 按 [AVG_LINE_BYTES] 估一个初始块大小，行数不够就翻倍重读，直到够或者读到文件头。
     *
     * ⚠ 从中间截断会切碎一个 UTF-8 多字节字符，所以**块起点不在文件头时必须丢掉第一行**
     * （它本来就是残行）。`'\n'` 是 0x0A，不会出现在任何多字节序列内部，所以按 `\n` 切是安全的。
     */
    private fun readTailLines(file: File, maxLines: Int): List<String> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len == 0L) return emptyList()
            var chunk = minOf(maxLines.toLong() * AVG_LINE_BYTES, len)
            while (true) {
                val start = len - chunk
                val buf = ByteArray(chunk.toInt())
                raf.seek(start)
                raf.readFully(buf)
                val lines = String(buf, Charsets.UTF_8).split('\n')
                val usable = if (start > 0) lines.drop(1) else lines
                val kept = usable.filter { it.isNotEmpty() && !it.startsWith(TAG_PREFIX) }
                if (start == 0L || kept.size > maxLines) return kept.takeLast(maxLines)
                chunk = minOf(chunk * 2, len)
            }
        }
    }

    /** 停掉消费者协程。进程退出或换账户时用；已入队的条目会丢。 */
    fun close() {
        consumer?.cancel()
        consumer = null
        entries.close()
    }

    init {
        // 提前起消费者，免得第一条日志要等 startConsumerIfNeeded 才被落盘
        startConsumerIfNeeded()
    }
}
