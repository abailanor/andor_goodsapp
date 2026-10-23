package com.example.myapplication.data

import com.example.myapplication.security.CredentialStore
import com.example.myapplication.security.Credentials
import com.example.myapplication.settings.AppSettings
import com.example.myapplication.settings.LoginMode
import com.example.myapplication.settings.SettingsStore

/**
 * [SessionRepository] 的持久化侧：凭据 + 登录策略。
 *
 * ## 为什么要抽这个接口
 *
 * 与 [com.example.myapplication.net.AuthApi] 同一个理由，但方向相反：
 * `AuthApi` 抽的是**网络**（`Service1Api` 是 final），这里抽的是**存储**
 * （`CredentialStore` 碰 AndroidKeyStore、`SettingsStore` 碰 DataStore 文件）。
 *
 * 登录这一段的分支密度是全项目最高的之一，而每条分支错了都很贵：
 *  - `ownerName` 退错 → 订单落进只有本机知道的私有文件；
 *  - 收养时机错了 → 离线数据搬不到、或者搬进错误的店铺；
 *  - `store_meta` 用 REPLACE 直写 → `enumFloor` 被冲掉 → 补传的历史月份永不同步；
 *  - 被踢下线后还自动重登 → 单会话策略下两台设备无限互踢。
 *
 * 这些都必须能在 JVM 上逐条钉住，所以存储侧要可替换。
 *
 * 生产实现是 [of]，转发给真的 [CredentialStore] 与 [SettingsStore]。
 */
interface SessionStore {

    /** 等设置从磁盘加载完。冷启动恢复作用域之前必须等，否则读到的是默认值。 */
    suspend fun awaitReady()

    /** 当前设置的快照。[awaitReady] 之前调用拿到的是默认值。 */
    fun settings(): AppSettings

    /** 读出这个店铺上次存的凭据。null = 没有（从没登录过、被清掉了、或 Keystore 不可用）。 */
    suspend fun loadCredentials(storeKey: String): Credentials?

    /**
     * 存凭据供下次自动登录。
     *
     * @return false 表示**存不进去**（AndroidKeyStore 在少数 ROM / OTA 之后不可用）。
     *         这不是登录失败——本次会话照常可用，只是下次要重新输密码。
     *         必须记审计，否则用户会以为"自动登录坏了"而查不出原因。
     */
    suspend fun saveCredentials(creds: Credentials): Boolean

    /**
     * 这个店铺是否被永久闩死（收到过 SSE `KICKED`）。
     *
     * ⚠ 是的话**绝不自动重登**：单会话策略下会和新登录方互相踢，形成风暴。
     * 见 `ApiErrorClassifier.mayAutoRelogin` 的注释。
     */
    suspend fun isKicked(storeKey: String): Boolean

    /**
     * 记住"登录成功过"与"最后用的是哪家店"。
     *
     * 两者必须一起写：冷启动时先按 [lastStoreKey] 恢复作用域，再按 [mode] 决定要不要自动登录。
     * 只写一个的话，要么作用域落在 `__local__`（界面是空的），要么自动登录登进错误的店。
     */
    suspend fun rememberLogin(storeKey: String, mode: LoginMode)

    /** 用户在启动页选了"继续离线使用"。之后启动**不再问**。 */
    suspend fun rememberOfflineChoice()

    companion object {

        fun of(
            credentials: CredentialStore,
            settings: SettingsStore,
        ): SessionStore = object : SessionStore {

            override suspend fun awaitReady() = settings.awaitLoaded()

            override fun settings(): AppSettings = settings.current()

            override suspend fun loadCredentials(storeKey: String): Credentials? =
                credentials.load(storeKey)

            override suspend fun saveCredentials(creds: Credentials): Boolean = credentials.save(creds)

            override suspend fun isKicked(storeKey: String): Boolean = credentials.isKicked(storeKey)

            override suspend fun rememberLogin(storeKey: String, mode: LoginMode) {
                settings.mutate { it.copy(loginMode = mode, lastStoreKey = storeKey) }
            }

            override suspend fun rememberOfflineChoice() {
                settings.mutate { it.copy(loginMode = LoginMode.OFFLINE) }
            }
        }
    }
}
