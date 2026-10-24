package com.example.myapplication.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.DriftedOrder
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

/** 列表的时间范围。 */
enum class OrderRange(val label: String) {
    TODAY("今天"),
    MONTH("本月"),
    YEAR("近一年"),
    ;

    /** 半开区间 `[from, to)`。 */
    fun bounds(now: Long, tz: TimeZone): Pair<Long, Long> {
        val cal = Calendar.getInstance(tz).apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val from = when (this) {
            TODAY -> cal.timeInMillis
            MONTH -> cal.apply { set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
            // 近一年：往前推 11 个月再取当月 1 号，一共 12 个月
            YEAR -> cal.apply { add(Calendar.MONTH, -11); set(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
        }
        // 上界给"现在 + 1 天"而不是"明天 0 点"：补传的历史订单 orderTs 可能落在未来
        // （设备时钟被调过），卡太紧会让它们从列表里消失，而用户看不出为什么少了一笔。
        return from to now + 86_400_000L
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

    private val _range = MutableStateFlow(OrderRange.MONTH)
    val range: StateFlow<OrderRange> = _range.asStateFlow()

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
        combine(_range, _writer, _fileType) { r, w, t -> Triple(r, w, t) }
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

    fun setRange(r: OrderRange) {
        _range.value = r
    }

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
