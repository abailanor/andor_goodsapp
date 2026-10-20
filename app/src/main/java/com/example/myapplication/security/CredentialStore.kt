package com.example.myapplication.security

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个店铺的登录凭据（**每个 storeKey 一份**，各自用自己的 Keystore 密钥加密）。
 *
 * @param username 登录名。店主/管理员用 merchantCode，员工用员工名 —— 由 [level] 区分。
 * @param password 明文密码（只在内存里明文存在，落盘前被 [SecretBox] 加密）。
 *                 留着它是为了自动登录：token 20 小时到期、每月 1 号 3:40 密钥轮换之后，
 *                 唯一能恢复会话的办法就是拿密码重新登录一次。
 * @param token 当前 JWT。**它和密码一样是 bearer 凭据**，所以也在同一个加密块里。
 * @param merchantCode 店铺代码，= Room 里所有表的 storeKey。
 * @param ownerName 文件归属者（店主用户名）。镜像路径 `mirror/<merchantCode>/<ownerName>/...` 要用它，
 *                  而它**不等于** merchantCode —— 这个混淆在 P0 期间真实造成过一次路径错误。
 * @param level OWNER / EMPLOYEE / ADMIN。
 * @param perms 权限**名字**集合。按名字判断而不是按序号，因为服务端可能插入新权限。
 * @param loginAt 服务端返回的本次登录时间。下次登录后若服务端给的 `previousLoginAt` ≠ 这里存的值，
 *                说明**离线期间有别的设备登录过**，必须警告用户（单会话策略下这很关键）。
 * @param savedAt 本地写入时间，仅用于诊断与"多久没同步过凭据"的判断，不参与任何协议逻辑。
 */
data class Credentials(
    val username: String,
    val password: String,
    val token: String,
    val merchantCode: String,
    val ownerName: String,
    val level: String,
    val perms: Set<String>,
    val loginAt: Long,
    val savedAt: Long,
) {
    fun hasPerm(name: String) = name in perms
}

/**
 * 密文/明文的持久化后端。抽象出来是为了让 [CredentialStore] 的逻辑能在 JVM 上测：
 * 真机用 DataStore 实现，测试用内存实现。
 */
interface BlobStore {
    /** 读不到返回 null。**不许抛**——文件损坏等于"没有凭据"。 */
    suspend fun read(name: String): ByteArray?
    /** 传 null 表示删除。 */
    suspend fun write(name: String, blob: ByteArray?)
}

/**
 * 凭据的读写。
 *
 * ## 两样东西故意**分开**存
 *
 * | 数据 | 存法 | 为什么 |
 * |---|---|---|
 * | 密码 / token / 权限 | 加密（[SecretBox]） | bearer 凭据，泄露即账户失守 |
 * | `KICKED` 闩 | **明文** | 见下 |
 *
 * `KICKED` 闩必须明文：它是"收到过被踢事件 → 永久停止自动重连与自动登录，直到用户手动确认"
 * 的那道闸。如果它躺在加密块里，那么 Keystore 密钥一旦失效（改锁屏、OTA、换机恢复），
 * 解密失败 → 整个块被当成"没有凭据"丢弃 → **闩也跟着丢了** → App 又开始自动登录 →
 * 和把它踢下线的那台设备形成互踢风暴。安全闸不能和被它保护的数据同生共死。
 *
 * ## 解密失败一律当"没有凭据"
 *
 * [SecretBox.decrypt] 已经把篡改、密钥失效、格式不认识全部归一成 null。
 * 这里再叠一层：JSON 解析失败也归一成 null。调用方拿到 null 的唯一正确反应是
 * 引导用户重新登录 —— 不重试、不崩、不猜。
 */
class CredentialStore(
    private val store: BlobStore,
    private val boxProvider: (storeKey: String) -> SecretBox?,
) {

    private val gson = Gson()

    /**
     * 读取某店铺的凭据。没有 / 解不开 / 解析失败都返回 null。
     *
     * ## ⚠ 为什么这几个方法自己 `withContext(Dispatchers.IO)`
     *
     * 里面有两样主线程碰不得的东西：`boxProvider` 要访问 **AndroidKeyStore**（TEE 往返，
     * 少数 ROM 上能到几十毫秒），`store` 要做**文件读写**。
     *
     * 靠"调用方记得切 IO"是约定性防护，一定会被漏掉 —— 而漏掉的后果是冷启动时
     * 自动登录卡在主线程上，表现为**启动白屏**，且 StrictMode 之外没有任何日志。
     * 所以切在实现里，结构性地保证。`runTest` 下这样切也是安全的（不涉及虚拟时间断言）。
     */
    suspend fun load(storeKey: String): Credentials? = withContext(Dispatchers.IO) {
        val blob = store.read(blobName(storeKey)) ?: return@withContext null
        val box = boxProvider(storeKey) ?: return@withContext null
        val json = box.decrypt(blob)?.toString(Charsets.UTF_8) ?: return@withContext null
        try {
            gson.fromJson(json, Envelope::class.java)
                ?.takeIf { it.v == FORMAT_VERSION }
                ?.toCredentials()
        } catch (e: JsonSyntaxException) {
            null
        } catch (e: RuntimeException) {
            // Gson 对缺字段会给出 null，Kotlin 的非空参数在构造时抛 NPE/IllegalArgumentException
            null
        }
    }

    /**
     * 保存凭据。
     *
     * @return false 表示 Keystore 不可用，**没存成功**。调用方必须把它当成
     *         "这台设备不支持自动登录"，而不是当成成功 —— 否则用户会以为下次能自动登录，
     *         实际每次都要手输，而且没有任何提示。
     */
    suspend fun save(creds: Credentials): Boolean = withContext(Dispatchers.IO) {
        val box = boxProvider(creds.merchantCode) ?: return@withContext false
        val envelope = Envelope.from(creds)
        val blob = box.encrypt(gson.toJson(envelope).toByteArray(Charsets.UTF_8))
        store.write(blobName(creds.merchantCode), blob)
        true
    }

    /**
     * 只更新 token / 权限 / 登录时间（密码不变）。凭据不存在时不做任何事。
     *
     * 不需要再包一层 `withContext`：[load] 与 [save] 各自已经切了 IO。
     */
    suspend fun updateSession(
        storeKey: String,
        token: String,
        perms: Set<String>,
        loginAt: Long,
    ): Boolean {
        val cur = load(storeKey) ?: return false
        return save(cur.copy(token = token, perms = perms, loginAt = loginAt))
    }

    /** 登出：先删密文，再删密钥（顺序反了会留下解不开的垃圾）。 */
    suspend fun clear(storeKey: String, destroyKey: (String) -> Unit) = withContext(Dispatchers.IO) {
        store.write(blobName(storeKey), null)
        destroyKey(storeKey)
        store.write(KICK_LATCH_PREFIX + storeKey, null)
    }

    // ==================== KICKED 闩（明文） ====================

    suspend fun isKicked(storeKey: String): Boolean = withContext(Dispatchers.IO) {
        store.read(KICK_LATCH_PREFIX + storeKey)?.let { String(it, Charsets.UTF_8) } == "1"
    }

    /** 收到 SSE `KICKED` 或 401=别处登录 时调用。**不可自动解除**，只有用户明确操作才能清。 */
    suspend fun markKicked(storeKey: String) = withContext(Dispatchers.IO) {
        store.write(KICK_LATCH_PREFIX + storeKey, "1".toByteArray(Charsets.UTF_8))
    }

    /** 用户在登录页明确点了"重新登录"之后才调用。 */
    suspend fun clearKicked(storeKey: String) = withContext(Dispatchers.IO) {
        store.write(KICK_LATCH_PREFIX + storeKey, null)
    }

    private fun blobName(storeKey: String) = "cred_$storeKey"

    /** 带版本号的外壳：将来加字段/改语义时能识别出老数据，而不是解出一个字段全 null 的对象。 */
    private data class Envelope(
        val v: Int,
        val username: String,
        val password: String,
        val token: String,
        val merchantCode: String,
        val ownerName: String,
        val level: String,
        val perms: List<String>,
        val loginAt: Long,
        val savedAt: Long,
    ) {
        fun toCredentials() = Credentials(
            username, password, token, merchantCode, ownerName, level,
            perms.toSet(), loginAt, savedAt,
        )

        companion object {
            fun from(c: Credentials) = Envelope(
                FORMAT_VERSION, c.username, c.password, c.token, c.merchantCode,
                c.ownerName, c.level, c.perms.toList(), c.loginAt, c.savedAt,
            )
        }
    }

    companion object {
        private const val FORMAT_VERSION = 1
        private const val KICK_LATCH_PREFIX = "kick_"
    }
}
