package com.example.myapplication.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.myapplication.data.SessionRepository.LoginOutcome
import com.example.myapplication.data.SessionRepository.SessionState

/**
 * 登录页。
 *
 * ## ⚠ 它**不是**一道闸门
 *
 * 这个 App 的设计前提是"无账号也能完整使用"（离线几个月再买服务是正常路径），
 * 所以登录页任何时候都能被绕过，登录中也照样能开单。
 * 点「登录」之后立刻 [onDismiss]，**不等结果** —— 结果通过导航栏颜色反映
 * （成功=白，其余=灰），失败的文案在「我的」页里看。
 *
 * 把登录做成模态阻塞的代价很具体：店里正在排队结账，而网络恰好不通，
 * 用户就被卡在一个转圈的对话框后面，什么也干不了。
 *
 * @param onDismiss 点了登录或"继续离线"之后立刻调，导航回主界面。
 */
@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var merchantCode by remember { mutableStateOf("") }

    // 「继续离线使用」只在**还没选过**的时候是大按钮。
    // 选过之后（OFFLINE / AUTO）它退成一行小字链接 —— 每次都摆一个大按钮，
    // 用户会以为不点它就不能用这个 App。
    val needsChoice = state is SessionState.AskChoice

    // 登录中：显示进度，但**不禁用返回**。用户随时可以走开去开单。
    val signingIn = state is SessionState.SigningIn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("登录", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "不登录也能完整使用：开单、库存、统计都在本地。登录之后才会与店里其它设备同步。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名") },
            singleLine = true,
            enabled = !signingIn,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            enabled = !signingIn,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = merchantCode,
            onValueChange = { merchantCode = it },
            label = { Text("店铺编号（员工登录才填）") },
            supportingText = { Text("店主本人登录请留空") },
            singleLine = true,
            enabled = !signingIn,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                viewModel.submit(username, password, merchantCode)
                // ⚠ 立刻走，不等结果。loginAsync 跑在 appScope 上，
                // 离开这个页面不会取消它（见 LoginViewModel 的注释）。
                onDismiss()
            },
            enabled = !signingIn && username.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (signingIn) {
                CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
            }
            Text(if (signingIn) "登录中…" else "登录")
        }

        Spacer(Modifier.height(12.dp))

        if (needsChoice) {
            OutlinedButton(onClick = { viewModel.chooseOffline(); onDismiss() }, Modifier.fillMaxWidth()) {
                Text("继续离线使用（之后可在「我的」里登录）")
            }
        } else {
            OutlinedButton(onClick = onDismiss, Modifier.fillMaxWidth()) {
                Text("先不登录，回去开单")
            }
        }

        // 上一次失败的原因。放在这里而不是弹对话框：用户可能是在别处点了登录之后
        // 自己走过来的，弹框会打断他。
        FailureCard(state)

        // 登录成功但有件事要说（目前是"离线期间有别的设备登录过"）
        (state as? SessionState.Online)?.warning?.let { w ->
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("注意", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(w, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    // AUTO 模式下冷启动会静默自动登录；成功就自己走掉，别把用户留在登录页上。
    LaunchedEffect(state) {
        if (state is SessionState.Online && !needsChoice) onDismiss()
    }
}

@Composable
private fun FailureCard(state: SessionState) {
    val failed = state as? SessionState.Failed ?: return
    Spacer(Modifier.height(16.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                when (failed.outcome) {
                    is LoginOutcome.OwnerUnknown -> "服务端版本过旧"
                    is LoginOutcome.AdoptionConflicted -> "本机已有这家店的数据"
                    is LoginOutcome.Kicked -> "需要手动重新登录"
                    else -> "登录没成功"
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(LoginViewModel.messageOf(failed.outcome), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "离线期间开单、库存、统计都照常可用，数据都在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
