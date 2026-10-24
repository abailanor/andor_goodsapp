package com.example.myapplication.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.DriftedOrder
import com.example.myapplication.data.OrderCalendar
import com.example.myapplication.data.OrderDetail
import com.example.myapplication.data.OrderRepository
import com.example.myapplication.data.OrderRow
import com.example.myapplication.data.PriceDrift
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.outbox.OutboxUploader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.TimeZone

/**
 * 订单页的时间范围。
 *
 * ## 为什么是 sealed class 而不是「今天 / 本月 / 近一年」三个枚举值
 *
 * 用户要的是"点开只列出**数据库里实际存在**的年份/月份"，还要一个月历能直接点某一天。
 * 固定的三个枚举值做不到这两件事：`YEAR` 是"最近 12 个月"（含一堆没单的月份），
 * 而"2025 年 3 月"这种具体区间根本没有对应的枚举值。
 *
 * 所以区间本身成为值：[Year] / [Month] / [Day] 各自带具体的年月日，
 * 选择器只从 [OrderCalendar.years] / `monthsOf()` / `daysOf()` 里取**真的有单的**那些。
 */
sealed class OrderPeriod {

    /** 半开区间 `[from, to)`。 */
    abstract fun bounds(now: Long, tz: TimeZone): Pair<Long, Long>

    /** 按钮上显示的文字。 */
    abstract fun label(now: Long, tz: TimeZone): String

    /**
     * 今天。
     *
     * 单独留一个值（而不是每次构造 [Day]）是因为它要**跟着时钟走**：
     * 收银台可能连着开一整天，界面停在昨天会很莫名其妙。
     */
    data object Today : OrderPeriod() {
        override fun bounds(now: Long, tz: TimeZone): Pair<Long, Long> =
            startOfDay(now, tz) to upperBound(now)

        override fun label(now: Long, tz: TimeZone) = "今天"
    }

    data class Day(val year: Int, val month: Int, val day: Int) : OrderPeriod() {
        override fun bounds(now: Long, tz: TimeZone): Pair<Long, Long> {
            val cal = Calendar.getInstance(tz)
            cal.clear()
            cal.set(year, month - 1, day, 0, 0, 0)
            val from = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 1)
            return from to cal.timeInMillis
        }

        override fun label(now: Long, tz: TimeZone): String {
            val (from, _) = bounds(now, tz)
            return if (from == startOfDay(now, tz)) "今天" else "${year}年${month}月${day}日"
        }
    }

    data class Month(val year: Int, val month: Int) : OrderPeriod() {
        override fun bounds(now: Long, tz: TimeZone): Pair<Long, Long> {
            val cal = Calendar.getInstance(tz)
            cal.clear()
            cal.set(year, month - 1, 1, 0, 0, 0)
            val from = cal.timeInMillis
            cal.add(Calendar.MONTH, 1)
            return from to cal.timeInMillis
        }

        override fun label(now: Long, tz: TimeZone): String {
            val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
            return if (year == cal.get(Calendar.YEAR) && month == cal.get(Calendar.MONTH) + 1) {
                "本月"
            } else {
                "${year}年${month}月"
            }
        }
    }

    data class Year(val year: Int) : OrderPeriod() {
        override fun bounds(now: Long, tz: TimeZone): Pair<Long, Long> {
            val cal = Calendar.getInstance(tz).apply { clear(); set(year, 0, 1, 0, 0, 0) }
            val from = cal.timeInMillis
            cal.add(Calendar.YEAR, 1)
            return from to cal.timeInMillis
        }

        override fun label(now: Long, tz: TimeZone): String {
            val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
            return if (year == cal.get(Calendar.YEAR)) "今年" else "${year}年"
        }
    }

    /**
     * 全部。
     *
     * ⚠ 下界是 **1** 而不是 0：`orderTs == 0` 表示"别的客户端没写 `content.ts`，时间是猜的"
     * （`StatsProjection.effectiveTs` 用文件月份兜底，但那一列本身仍然是 0）。
     * 把它们算进"全部"的话，列表最底下会堆着一批 1970-01-01 的单，
     * 而用户看不出那是"时间未知"还是"系统坏了"。日历那侧也排除它们，两边口径一致。
     */
    data object All : OrderPeriod() {
        override fun bounds(now: Long, tz: TimeZone): Pair<Long, Long> = 1L to upperBound(now)

        override fun label(now: Long, tz: TimeZone) = "全部"
    }

    companion object {
        /** 上界给"现在 + 1 天"而不是"明天 0 点"：补传的历史订单 `orderTs` 可能落在未来。 */
        fun upperBound(now: Long): Long = now + 86_400_000L

        fun startOfDay(now: Long, tz: TimeZone): Long =
            Calendar.getInstance(tz).apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        /** 冷启动的默认范围 = 本月。 */
        fun thisMonth(now: Long, tz: TimeZone): Month =
            Calendar.getInstance(tz).apply { timeInMillis = now }.let {
                Month(it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1)
            }
    }
}

/**
 * 订单页的 ViewModel：已同步与未上传的**合并视图** + 勾选上传 + 删除。
 *
 * ## ⚠ 上传被取消是安全的，这是设计出来的
 *
 * 用的是 `viewModelScope`，用户划走这一页就会取消正在跑的上传。看起来危险，其实不会：
 * [OutboxUploader.uploadOne] 是 write-ahead 的 —— 先把行置 `IN_FLIGHT` 并落盘，**然后**才发请求。
 * 所以无论在哪一步被取消，库里都留着"这一笔结果未知"的记录，
 * 下次启动或重连时 `recoverInFlight` 会先同步那个文件、在 `order_index` 里找它，
 * 找到了当成功、没找到才重推。
 *
 * 反过来，如果为了"不被取消"而挂到进程级 scope 上，用户就失去了中止一批错误上传的手段。
 */
class OrdersViewModel(
    private val repo: OrderRepository,
    private val priceDrift: PriceDrift,
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    /**
     * 有价格漂移的未上传订单。
     *
     * ⚠ **只提示，不自动改价**：顾客已经按旧价付过钱了，悄悄改成新价等于事后涨价，
     * 而营业额会与收银台里的现金对不上，且对不上的原因藏在一次自动重算里。
     * 改不改由用户决定（原价上传 / 回开单页用新价重开 / 删掉）。
     */
    private val _drift = MutableStateFlow<List<DriftedOrder>>(emptyList())
    val drift: StateFlow<List<DriftedOrder>> = _drift.asStateFlow()

    private val _period = MutableStateFlow<OrderPeriod>(OrderPeriod.thisMonth(now(), timeZone()))
    val period: StateFlow<OrderPeriod> = _period.asStateFlow()

    /**
     * 按天聚合的日历。期间选择器用它列出"真的有单的年/月"，月历用它点亮有单的格子。
     *
     * 初始值是一个**空日历**（不是 null）：选择器打开时它可能还没查完，
     * 空列表只是"暂时没有可选项"，而 null 会让整个对话框闪一下或者干脆不显示。
     */
    val calendar: StateFlow<OrderCalendar> = repo.calendar(timeZone())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrderCalendar.EMPTY)

    /** 只按店员筛。null = 全部人。 */
    private val _writer = MutableStateFlow<String?>(null)
    val writer: StateFlow<String?> = _writer.asStateFlow()

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** 上一次批量上传的结果汇总，给用户一句话交代。显示过后调 [consumeReport] 清掉。 */
    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    /**
     * 按开单模式筛选。
     *
     * 销售模式下不该看到进货单，反之亦然 —— 两者混在一个列表里，
     * 用户会把 A 类进货单当成一笔营收，而它既不计营收也不扣库存。
     * null = 不按模式筛（两种都看）。
     */
    private val _fileType = MutableStateFlow<String?>(OrderFileId.TYPE_B)
    val fileType: StateFlow<String?> = _fileType.asStateFlow()

    fun setFileType(type: String?) {
        _fileType.value = type
    }

    val rows: StateFlow<List<OrderRow>> =
        combine(_period, _writer, _fileType) { r, w, t -> Triple(r, w, t) }
            .flatMapLatest { (r, w, t) ->
                val (from, to) = r.bounds(now(), timeZone())
                repo.orders(from, to, w).map { rows ->
                    if (t == null) rows else rows.filter { it.fileType == t }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 点某一笔订单时加载的明细。null = 没打开详情。 */
    private val _detail = MutableStateFlow<OrderDetail?>(null)
    val detail: StateFlow<OrderDetail?> = _detail.asStateFlow()

    fun openDetail(row: OrderRow) {
        viewModelScope.launch { _detail.value = repo.detail(row) }
    }

    fun closeDetail() {
        _detail.value = null
    }

    fun setPeriod(p: OrderPeriod) {
        _period.value = p
        // 换了时间范围，之前勾的那些行大概率已经不在列表里了。
        // 留着的话"已选 N 笔"这个数字会指着一批用户看不见的订单，
        // 而"上传选中"照样会把它们发出去。
        _selected.value = emptySet()
    }

    /** 按钮上显示的那行字。 */
    fun periodLabel(): String = _period.value.label(now(), timeZone())

    /**
     * 选择器顶部的快捷项。
     *
     * 放在 ViewModel 里而不是界面里，是因为"本月"要知道**现在**是哪个月 ——
     * 而 `now()` / `timeZone()` 是这个类的构造参数（测试里被替换掉）。
     * 让界面自己 `Calendar.getInstance()` 的话，测试就测不到它，
     * 而且界面与列表区间会用上两个不同的"现在"。
     */
    fun quickPeriods(): List<OrderPeriod> = listOf(
        OrderPeriod.Today,
        OrderPeriod.thisMonth(now(), timeZone()),
        OrderPeriod.All,
    )

    fun periodLabelOf(p: OrderPeriod): String = p.label(now(), timeZone())

    fun setWriter(w: String?) {
        _writer.value = w
        _selected.value = emptySet()
    }

    fun toggle(uuid: String) {
        _selected.value = _selected.value.let { if (uuid in it) it - uuid else it + uuid }
    }

    /**
     * 全选**本机能上传的**那些。
     *
     * ⚠ 不是"全选列表里所有未上传的行"，有两个排除：
     *  - `BLOCKED`（缺箱规）、`IN_FLIGHT`（结果未知）、`FAILED_PERMANENT`（重推无意义）
     *    勾上了也发不出去，只会让"已选 N 笔"这个数字骗人；
     *  - **别的设备传来的单**（[OrderRow.isForeign]）：正常情况下创建者自己会上传，
     *    两边都传就是同一个 uuid 在服务端文件里两行 + 库存扣两次。
     *    接管必须是**一笔一笔手动勾**的明确动作，不能被"全选"顺手带走。
     */
    fun selectAllUploadable() {
        _selected.value = rows.value
            .filter { it.source == OrderRow.Source.PENDING_READY && !it.isForeign }
            .map { it.orderUuid }
            .toSet()
    }

    /** 手动接管一笔外部单（勾选它）。二次确认由 UI 负责。 */
    fun selectForeign(uuid: String) {
        _selected.value = _selected.value + uuid
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun uploadSelected() {
        val uuids = _selected.value.toList()
        if (uuids.isEmpty() || _busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val results = repo.uploadSelected(uuids)
            _report.value = summarize(results)
            _selected.value = emptySet()
            _busy.value = false
        }
    }

    /**
     * 重新扫描价格漂移。进页面时调一次；同步落地后也该再调一次
     * （别的设备改了价，是同步把新价带下来的）。
     */
    fun refreshDrift() {
        viewModelScope.launch { _drift.value = priceDrift.scan() }
    }

    /** 删掉一笔未上传的订单（连同它同组的其它未上传块）。 */
    fun discard(uuid: String) {
        viewModelScope.launch {
            val n = repo.discard(uuid)
            _selected.value = _selected.value - uuid
            if (n == 0) _report.value = "这一笔删不掉：它已经上传了，或者正在上传中。要撤销得走「取消订单」。"
        }
    }

    fun consumeReport() {
        _report.value = null
    }

    private fun summarize(results: List<OutboxUploader.UploadResult>): String {
        var promoted = 0
        var retry = 0
        var unknown = 0
        var failed = 0
        var skipped = 0
        for (r in results) when (r) {
            is OutboxUploader.UploadResult.Promoted -> promoted++
            is OutboxUploader.UploadResult.RetryLater -> retry++
            is OutboxUploader.UploadResult.Unknown -> unknown++
            is OutboxUploader.UploadResult.FailedPermanent -> failed++
            is OutboxUploader.UploadResult.Skipped -> skipped++
        }
        return buildString {
            append("已上传 $promoted 笔")
            if (retry > 0) append("，$retry 笔待重试")
            // "结果未知"必须说清楚，否则用户会以为失败了而重开一张单 ——
            // 而服务端不查重，重开就是两笔。
            if (unknown > 0) append("，$unknown 笔已发出但结果未知（重启或重连后会自动核对，**不要重开**）")
            if (failed > 0) append("，$failed 笔上传失败需要处理")
            if (skipped > 0) append("，$skipped 笔状态已变、跳过")
        }
    }
}
