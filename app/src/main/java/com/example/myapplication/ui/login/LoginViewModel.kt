package com.example.myapplication.ui.login

import androidx.lifecycle.ViewModel
import com.example.myapplication.data.SessionRepository
import com.example.myapplication.data.SessionRepository.LoginOutcome
import com.example.myapplication.data.SessionRepository.SessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * 登录页的 ViewModel。
 *
 * ## 它为什么这么薄
 *
 * 状态机、错误分类、收养、`store_meta` 合并全在 [SessionRepository] 里，而且**在那里被测**
 * （39 条 JVM 单测）。这一层只做两件事：把表单的三个字符串递过去、把 [SessionState] 翻译成文案。
 *
 * 把逻辑挪进 ViewModel 的代价是它变成不可测的（ViewModel 要 Android 运行时），
 * 而登录恰恰是全项目分支最密的一段 —— `ownerName` 退错一次，订单就永久落进
 * 一个只有本机知道的文件里。所以这里刻意什么都不判断。
 *
 * ## ⚠ 不用 `viewModelScope`
 *
 * 登录是后台行为：用户点完就该回到主界面继续开单。而 `viewModelScope` 会随着离开登录页被取消，
 * 于是"点了登录 → 跳回主界面 → 登录被悄悄取消"，导航栏永远停在灰色，且没有任何日志。
 * [SessionRepository.loginAsync] 跑在 `ServiceLocator.appScope` 上，与进程同长。
 */
class LoginViewModel(private val sessions: SessionRepository) : ViewModel() {

    val state: StateFlow<SessionState> = sessions.state

    /** 是不是"还没选过登录/离线"。只有这时才显示"继续离线使用"那个大按钮。 */
    val needsChoice: Boolean get() = state.value is SessionState.AskChoice

    /**
     * @param merchantCode 员工登录才填（店主的编号）。留空 = 店主登录。
     *        两条路径走的是**不同端点与不同限速桶**，所以这个字段不能省。
     */
    fun submit(username: String, password: String, merchantCode: String) {
        sessions.loginAsync(username, password, merchantCode.trim().ifEmpty { null })
    }

    fun chooseOffline() = sessions.chooseOffline()

    fun logout() = sessions.logout()

    companion object {
        /**
         * 把 [LoginOutcome] 翻译成一句给用户看的话。
         *
         * ⚠ [LoginOutcome.OwnerUnknown] 与 [LoginOutcome.AdoptionConflicted] 的文案
         * 必须说清"数据没丢、该怎么办"，不能只报错 —— 这两条都不是用户的错，
         * 而且它们的处理动作完全不同（一个是升级服务端/换店主账号，一个是决定怎么合并）。
         */
        fun messageOf(o: LoginOutcome): String = when (o) {
            is LoginOutcome.BadCredentials -> o.msg
            is LoginOutcome.Unreachable ->
                if (o.retryable) "${o.msg}（可以稍后再试，期间照常离线开单）"
                else "${o.msg}（请稍后再试，不要连续重试）"
            is LoginOutcome.OwnerUnknown -> o.msg
            is LoginOutcome.Malformed -> "服务端响应不完整：${o.msg}"
            is LoginOutcome.Kicked -> o.msg
            is LoginOutcome.AdoptionConflicted ->
                "这家店在本机已经有数据了（${o.occupied.joinToString("、")}），" +
                    "离线期间积累的数据无法自动并入。两份数据都完整保留着，没有丢失。"
            is LoginOutcome.Success -> ""
        }
    }
}
