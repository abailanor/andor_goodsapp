package com.example.myapplication.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.SessionRepository.SessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * 「我的」：账号状态 + 所有非高频入口。
 *
 * ## 为什么要有这一页
 *
 * 底部导航只有五个位置，而入口远不止五个。把低频的（登录、设置、待开单、AI、
 * 校验镜像、变更日志、旧数据）收进这里，高频的（开单、订单、商品、统计）留在导航栏上。
 *
 * ## ⚠ 店铺名显示的是 `ownerName`
 *
 * 本系统里店铺就是用**店主用户名**标识的（fileId 是 `B:<ownerName>:<年>-<月>`），
 * 服务端也没有单独的店铺显示名字段。员工登录时这里显示的是**店主名**而不是他自己的名字 ——
 * 这是对的：他操作的是这家店的账。他自己的名字在下面的"当前登录"那一行。
 */
@Composable
fun MoreScreen(
    state: StateFlow<SessionState>,
    onLogin: () -> Unit,
    onSettings: () -> Unit,
    onPendingOrders: () -> Unit,
    onLegacyHistory: () -> Unit,
    onAi: () -> Unit,
    onLogout: () -> Unit,
) {
    val session by state.collectAsState()
    val online = session as? SessionState.Online

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // ---- 账号卡片 ----
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    online?.shopName ?: "未登录",
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    when (session) {
                        is SessionState.Online ->
                            "已登录 · 当前用户 ${online!!.username}" + employeeHint(online!!.level)
                        is SessionState.SigningIn -> "登录中…（不影响开单）"
                        is SessionState.Failed -> "上次登录没成功 · 离线可用"
                        is SessionState.AskChoice -> "还没选过：登录或离线都可以"
                        is SessionState.Offline -> "离线 · 数据在本机"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 「离线期间有别的设备登录过」这类警告必须一直看得见，
                // 不能只在登录那一刻闪一下 —— 用户当时可能正在结账。
                online?.warning?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Entry(if (online == null) "登录 / 切换店铺" else "切换店铺") { onLogin() }
        Entry("设置") { onSettings() }

        Spacer(Modifier.height(16.dp))
        SectionTitle("订单")
        Entry("待开单") { onPendingOrders() }
        // ⚠ 临时入口。旧历史页读的是 filesDir/Orders/*.json，与新架构（order_index）
        // 是两套平行存储。P8 的数据迁移向导做完之后这一行要摘掉 ——
        // 在那之前不能删，否则用户过去的订单在界面上就再也打不开了。
        Entry("旧历史订单（本机文件）") { onLegacyHistory() }

        Spacer(Modifier.height(16.dp))
        SectionTitle("工具")
        Entry("AI 辅助开单") { onAi() }

        if (online != null) {
            Spacer(Modifier.height(16.dp))
            Entry("登出（数据保留在本机）") { onLogout() }
        }
    }
}

/** `A` / `B` 是员工的两档；`OWNER` 是店主本人。显示成"员工"就够了，档位对用户没有意义。 */
private fun employeeHint(level: String): String = when (level) {
    "A", "B" -> "（员工账号）"
    "ADMIN" -> "（管理员）"
    else -> ""
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun Entry(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
