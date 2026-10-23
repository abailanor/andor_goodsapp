package com.example.myapplication.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.settings.AppSettings
import com.example.myapplication.settings.NotifyEvent
import com.example.myapplication.settings.SettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.TimeZone

class SettingsViewModel(private val settings: SettingsStore) : ViewModel() {

    val current: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.current())

    /**
     * 改服务器地址。
     *
     * ⚠ 走 [AppSettings.withHost]，不要自己 `copy(serverHost = …)`：
     * 那个 helper 会剥掉用户顺手粘进来的 `https://` 前缀与 `:端口` 后缀。
     * 不剥的话 `baseUrl` 会变成 `https://https://192.168.1.5:8443`，
     * 用户看到的是"连接失败"，而他明明填对了地址。
     */
    fun setHost(raw: String) = mutate { it.withHost(raw) }

    fun setPort(raw: String) = mutate { it.withPort(raw.trim().toIntOrNull() ?: it.serverPort) }

    fun setUseTls(on: Boolean) = mutate { it.copy(useTls = on) }

    fun setNotify(event: NotifyEvent, on: Boolean) = mutate { it.withNotify(event, on) }

    private fun mutate(block: (AppSettings) -> AppSettings) {
        // mutate 是 suspend 的（DataStore 每次写都重写整个文件），不能在主线程直接调
        viewModelScope.launch { settings.mutate(block) }
    }
}

/**
 * 设置页。**刻意只做最小集**：服务器地址、通知开关、以及两条"不知道就会吃亏"的说明。
 *
 * 开单相关的设置（箱规录入、定价模式、载入待开单时重扫价）属于 P7 —— 那一页改造完
 * 再放进来，否则用户改了也看不出效果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, shopName: String?, onSync: () -> Unit) {
    val s by viewModel.current.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Section("账号")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(shopName ?: "未登录", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (shopName == null) "离线模式：数据只在本机，不与其它设备同步。"
                    else "店铺就是用店主用户名标识的 —— 订单文件是 B:<店主名>:<年>-<月>。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Section("服务器")
        ServerFields(s, viewModel)

        Spacer(Modifier.height(8.dp))
        Text(
            "改动立刻生效：`Service1Api` 的 baseUrl 是每次请求现取的，已经构造好的引用也会用新地址。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Section("同步")
        Card(
            onClick = onSync,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同步状态", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "手动同步、逐个文件的版本与状态、校验镜像、变更日志",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(16.dp))
        Section("通知")
        for (e in NotifyEvent.entries) {
            SwitchRow(
                label = labelOf(e),
                checked = s.isNotifyEnabled(e),
                onCheckedChange = { viewModel.setNotify(e, it) },
            )
        }

        Spacer(Modifier.height(16.dp))
        Section("两件必须知道的事")
        WarningCard(
            "改系统时区会让营业额算错",
            "统计的分组键里含日期，而日期是按当时的设备时区算的。" +
                "在「订单进未上传区」与「上传成功」之间改了时区，同一笔钱会被加在一个日期上、" +
                "减在另一个日期上 —— 旧的那条留下一笔搬不走的钱，新的那条多出一笔负数。" +
                "改完时区必须重新投影统计，目前只能靠重装或等 P8 的重投影工具。",
        )
        WarningCard(
            "当前时区：" + TimeZone.getDefault().id,
            "上面那段不是吓唬人：这个 App 的统计是按天分组的，而「一天」的边界由时区决定。",
        )
    }
}

@Composable
private fun ServerFields(s: AppSettings, vm: SettingsViewModel) {
    var host by remember { mutableStateOf(s.serverHost) }
    var port by remember { mutableStateOf(s.serverPort.toString()) }

    // 设置从磁盘加载完之后要把输入框同步过来，否则用户看到的是默认值而实际生效的是别的
    androidx.compose.runtime.LaunchedEffect(s.serverHost, s.serverPort) {
        host = s.serverHost
        port = s.serverPort.toString()
    }

    OutlinedTextField(
        value = host,
        onValueChange = { host = it },
        label = { Text("服务器地址") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = port,
        onValueChange = { port = it.filter(Char::isDigit) },
        label = { Text("端口") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    SwitchRow("使用 HTTPS（自签证书，只信任内置 CA）", s.useTls) { vm.setUseTls(it) }
    Spacer(Modifier.height(4.dp))
    Text(
        "实际请求地址：${s.copy(serverHost = host, serverPort = port.toIntOrNull() ?: s.serverPort).baseUrl}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    // 失焦/回车才落盘：每敲一个字符就 mutate 一次会让 DataStore 重写整个文件几十遍
    androidx.compose.material3.OutlinedButton(
        onClick = { vm.setHost(host); vm.setPort(port) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("保存服务器地址") }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider()
}

@Composable
private fun WarningCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * 通知事件的中文名。
 *
 * ⚠ 直接照抄 [NotifyEvent] 的语义，**不要**自己发挥：
 * 这些事件里 `KICKED` 与 `SERVER_BEHIND` 是"不知道就会造成损失"的，
 * 文案必须让用户看懂严重性，否则他会把默认打开的通知关掉。
 */
private fun labelOf(e: NotifyEvent): String = when (e) {
    NotifyEvent.PRICE_CHANGED -> "员工改了商品价格"
    NotifyEvent.STOCK_CHANGED -> "员工改了库存"
    NotifyEvent.NEW_ORDER -> "有人下了新单"
    NotifyEvent.ORDER_CANCELLED -> "有订单被取消"
    NotifyEvent.PRODUCT_DELETED -> "商品被删除"
    NotifyEvent.MY_PERMISSIONS_CHANGED -> "我的权限被改了"
    NotifyEvent.KICKED -> "被踢下线（账号在别处登录）"
    NotifyEvent.SERVER_BEHIND -> "订单文件版本冲突（数据有分歧风险）"
    NotifyEvent.STOCK_NEGATIVE -> "库存变成负数"
}
