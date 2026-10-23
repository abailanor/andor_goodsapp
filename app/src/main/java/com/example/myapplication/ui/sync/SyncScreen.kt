package com.example.myapplication.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.log.ChangeLog
import com.example.myapplication.mirror.MirrorVerifier
import com.example.myapplication.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 同步状态页。**这是诊断页，不是给普通用户日常用的**，所以信息密度优先于好看。
 *
 * 存在的理由：这个 App 最危险的故障全都是静默的（镜像与服务端逐字节不一致、统计桶没搬、
 * enumFloor 被冲掉），而它们唯一的线索就是审计日志与三重自检。
 * 没有这一页，用户遇到"营业额对不上"就只能重装。
 */
class SyncViewModel(
    private val scheduler: SyncScheduler,
    private val verifier: MirrorVerifier,
    private val changeLog: ChangeLog,
    dao: OrderFileDao,
    private val storeKey: () -> String,
    /**
     * **必须是 appScope**，不能是 `viewModelScope`。
     *
     * 「校验镜像」会把每个文件重新 FULL 下载到 `mirror-tmp/` 再逐字节比对，
     * 几十 MB 的量级要跑很久。挂在 `viewModelScope` 上的话用户划走这一页就被取消，
     * 而取消会留下半个临时文件 —— 下一次校验可能拿它当"服务器副本"去比对，
     * 于是报一个根本不存在的分歧。
     */
    private val scope: CoroutineScope,
    private val audit: (String) -> Unit = {},
) : ViewModel() {

    /** 正在做什么。非 null 时按钮全部禁用（并发跑两次校验会互相踩 mirror-tmp）。 */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    /** 屏幕上那几行输出。 */
    private val _output = MutableStateFlow<List<String>>(emptyList())
    val output: StateFlow<List<String>> = _output.asStateFlow()

    val files: StateFlow<List<FileStateEntity>> = dao.observeFileStates(storeKey())
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun urgentSync() = run("同步当月与上月（急通道）") {
        scheduler.urgentSync(storeKey()).toString()
    }

    fun catchUp() = run("补齐历史月份（后台通道，一次最多 12 个文件）") {
        scheduler.backgroundCatchUp(storeKey()).toString()
    }

    fun selfCheck() = run("三重自检（版本 / 长度 / 校验和）") {
        val bad = scheduler.selfCheckAll(storeKey())
        if (bad.isEmpty()) "全部文件自洽。"
        else "⚠ 这些文件被判分歧，已隔离证据并归零游标，下次同步会走 FULL 自愈：\n" + bad.joinToString("\n")
    }

    /**
     * 端到端逐字节对账。
     *
     * ⚠ 这是**唯一**能发现"本地自洽但与服务器不同"的手段：三重自检比的是
     * 磁盘 vs `file_state`，两边都错的话它照样通过。
     */
    fun verify() = run("校验镜像（重新下载全部文件逐字节比对，可能几十 MB）") {
        val ids = files.value.map { it.fileId }
        if (ids.isEmpty()) return@run "本地还没有任何订单文件。"
        verifier.verifyAll(storeKey(), ids).joinToString("\n") { (fileId, v) -> "$fileId → ${describe(v)}" }
    }

    fun loadLog() = run("读取变更日志") {
        changeLog.tail().joinToString("\n").ifEmpty { "（日志是空的）" }
    }

    private fun run(label: String, block: suspend () -> String) {
        if (_busy.value != null) return
        _busy.value = label
        _output.value = emptyList()
        scope.launch {
            val text = try {
                block()
            } catch (e: Exception) {
                // 诊断页绝不能自己崩：它是在用户已经遇到问题时打开的，
                // 这时候再抛一个异常，他就彻底没有线索了。
                audit("诊断动作失败（$label）：${e.javaClass.simpleName}: ${e.message}")
                "失败：${e.javaClass.simpleName}: ${e.message}"
            }
            _output.value = text.lines()
            _busy.value = null
        }
    }

    private fun describe(v: MirrorVerifier.Verdict): String = when (v) {
        is MirrorVerifier.Verdict.Identical -> "✓ 逐字节一致（v${v.version}, ${v.bytes} 字节）"
        is MirrorVerifier.Verdict.Behind -> "落后于服务器：下一次同步会补上"
        is MirrorVerifier.Verdict.Diverged -> "⚠ 分歧：本地与服务器内容不同，需要人工决定"
        is MirrorVerifier.Verdict.LocalAhead -> "⚠ 本地比服务器多：服务器丢过数据，**绝不能**用服务器的内容覆盖本地"
        is MirrorVerifier.Verdict.ServerMissing -> "服务器上已经没有这个文件（运维归档删除？）"
        is MirrorVerifier.Verdict.LocalMissing -> "本地没有这个文件，下一次同步会 FULL 拉下来"
        is MirrorVerifier.Verdict.Failed -> "校验失败：${v.msg}"
    }
}

@Composable
fun SyncScreen(viewModel: SyncViewModel) {
    val busy by viewModel.busy.collectAsState()
    val output by viewModel.output.collectAsState()
    val files by viewModel.files.collectAsState()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("同步", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "这一页是给排查问题用的。日常使用不需要打开它 —— 同步是自动的。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (busy != null) {
                CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
                Text(busy!!, style = MaterialTheme.typography.bodySmall)
            }
        }

        ActionRow("同步当月与上月", "最常用的那一个：把最近两个月的销售单与今年的进货单追平。", busy != null) {
            viewModel.urgentSync()
        }
        ActionRow("补齐历史月份", "从 enumFloor 一直补到当月，一次最多 12 个文件（新手机刚登录时用这个）。", busy != null) {
            viewModel.catchUp()
        }
        ActionRow("三重自检", "只比本地：磁盘头部版本 vs file_state.version、文件长度、校验和。很快，不联网下载内容。", busy != null) {
            viewModel.selfCheck()
        }
        ActionRow("校验镜像", "重新下载每个文件逐字节比对。**慢且费流量**，但它是唯一能发现「本地自洽却与服务器不同」的手段。", busy != null) {
            viewModel.verify()
        }
        ActionRow("变更日志", "所有审计记录：分歧、隔离、库存对账偏低、偏移交叉校验失败……静默故障的唯一线索都在这里。", busy != null) {
            viewModel.loadLog()
        }

        if (output.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("结果", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    // 输出可能几百行，放进 LazyColumn 而不是一次性摆出来
                    LazyColumn(Modifier.height(240.dp)) {
                        items(output) { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("本地文件（${files.size}）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        if (files.isEmpty()) {
            Text(
                "还没有任何订单文件。离线开的单会先进「未上传」，登录并同步之后才会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(files, key = { it.fileId }) { f -> FileRow(f) }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FileRow(f: FileStateEntity) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.fileId, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                // ⚠ DIVERGED 必须显眼：它意味着本地镜像已经被隔离、游标归零，
                // 统计贡献也被冻结了，用户看到的数字从这一刻起是不全的。
                f.syncState,
                style = MaterialTheme.typography.labelMedium,
                color = if (f.syncState == "DIVERGED") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            buildString {
                append("v").append(f.version)
                append(" · ").append(f.mirrorBytes).append(" 字节")
                // firstSyncedAt == null 是"从没同步过"的判据（不是 version == 0：
                // 空文件同步完 version 也是 0）。这就是统计页那个 `*` 的来源之一。
                append(if (f.firstSyncedAt == null) " · **从没同步过**" else " · 已同步")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}
