package com.example.myapplication.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.core.money.Money
import com.example.myapplication.data.StatsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 统计的时间范围。 */
enum class StatsRange(val label: String) {
    TODAY("今天"),
    MONTH("本月"),
    YEAR("本年"),
}

class StatsViewModel(
    private val repo: StatsRepository,
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private val _range = MutableStateFlow(StatsRange.MONTH)
    val range: StateFlow<StatsRange> = _range.asStateFlow()

    val summary: StateFlow<StatsRepository.Summary?> = _range
        .flatMapLatest { r ->
            val (from, to) = bounds(r)
            repo.summary(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setRange(r: StatsRange) {
        _range.value = r
    }

    /** 闭区间 `[fromDate, toDate]`，`yyyy-MM-dd`。 */
    private fun bounds(r: StatsRange): Pair<String, String> {
        val cal = Calendar.getInstance(timeZone()).apply { timeInMillis = now() }
        when (r) {
            StatsRange.TODAY -> Unit                              // 就是今天，不用动
            StatsRange.MONTH -> cal.set(Calendar.DAY_OF_MONTH, 1)
            StatsRange.YEAR -> cal.set(Calendar.DAY_OF_YEAR, 1)
        }
        val from = DAY.format(cal.time)
        // ⚠ 上界给"今天"，不是"月底"：还没到的日子不可能有数据，
        // 而把它们算进 incompleteMonths 会让 `*` 永远亮着 —— 看多了用户就不信这个标记了。
        return from to DAY.format(Date(now()))
    }

    private companion object {
        /** ⚠ Locale 固定：默认 Locale 可能输出非 ASCII 数字，而这个字符串要拿去和 dimKey 比。 */
        val DAY = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

/**
 * 统计页。
 *
 * ## ⚠ 那个 `*` 不是装饰
 *
 * [StatsRepository.Summary.partial] 为真时，屏幕上每个数字都是**下界**：
 * 区间里有月份的本地数据不完整（从没同步过，或用户删过本地文件）。
 * 不标出来的话，店主会拿一个偏小的数字去对账，然后花一下午查一笔根本不存在的差额。
 *
 * 所以星号旁边必须写清"为什么不全"和"怎么补全"，不能只给一个符号。
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel) {
    val range by viewModel.range.collectAsState()
    val summary by viewModel.summary.collectAsState()

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (r in StatsRange.entries) {
                FilterChip(selected = range == r, onClick = { viewModel.setRange(r) }, label = { Text(r.label) })
            }
        }
        Spacer(Modifier.height(8.dp))

        val s = summary
        if (s == null) {
            Text("计算中…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Money.fromCents(s.amountCents),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    // ⚠ 星号紧贴数字，不能放到页面角落：用户截图发出去的时候，
                    // 角落里的说明大概率被裁掉，而那个数字会被当成准确值。
                    if (s.partial) {
                        Spacer(Modifier.width(4.dp))
                        Text("*", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${s.orderCount} 笔 · ${s.pieceCount} 件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.partial) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "* 这些数字是**下界**：${s.incompleteMonths.joinToString("、")} " +
                            "的本地数据还不完整（还没同步过，或本机文件被删过）。" +
                            "在「我的 → 同步」里跑一次完整同步后星号会消失。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            if (s.byWriter.isNotEmpty()) {
                item { SectionTitle("按店员") }
                items(s.byWriter, key = { "w:${it.writer}" }) { w ->
                    StatLine(
                        left = w.writer.ifEmpty { "（未署名）" },
                        amount = w.amountCents,
                        right = "${w.orderCount} 笔 · ${w.pieceCount} 件",
                    )
                    HorizontalDivider()
                }
            }
            if (s.byDay.isNotEmpty()) {
                item { SectionTitle("按天") }
                items(s.byDay, key = { "d:${it.date}" }) { d ->
                    StatLine(left = d.date, amount = d.amountCents, right = "${d.orderCount} 笔 · ${d.pieceCount} 件")
                    HorizontalDivider()
                }
            }
            if (s.byWriter.isEmpty() && s.byDay.isEmpty()) {
                item {
                    Text(
                        "这个时间段还没有统计。开单之后这里会实时更新，未上传的单也算在内。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun StatLine(left: String, amount: Long, right: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(left, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(Money.fromCents(amount), style = MaterialTheme.typography.bodyLarge)
            Text(right, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
