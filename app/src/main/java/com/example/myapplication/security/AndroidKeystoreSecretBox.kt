package com.example.myapplication.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 从 `AndroidKeyStore` 取一把**导不出来**的 AES 密钥，交给 [SecretBox]。
 *
 * ## 为什么必须用 Keystore，而不是自己派生一个密钥存文件
 *
 * 用户的诉求是"密码存在别的程序一般读不到的地方"。三个层次的对比：
 *  - 明文 SharedPreferences：root / adb backup / 同 UID 的调试工具都能读。**不可接受**。
 *  - 自己派生密钥（比如 PBKDF2(ANDROID_ID)）加密后存文件：密钥和密文在同一台机器上，
 *    ANDROID_ID 对同签名应用可读，等于把锁和钥匙放一起。只是提高了门槛，不是防线。
 *  - **AndroidKeyStore**：私钥在 TEE/StrongBox 里生成，`getEncoded()` 返回 null，
 *    任何应用层代码（包括我们自己）都拿不到密钥字节，只能请求它做加解密。
 *    其它应用即使读到了密文文件也无法解密。
 *
 * 顺带：`androidx.security-crypto`（EncryptedSharedPreferences）**已被 Google 标记废弃**，
 * 且它内部也是这套 Keystore 主密钥方案，多一层依赖少一层控制，所以不用它。
 *
 * ## setUserAuthenticationRequired 必须是 false
 *
 * 设成 true 的话，密钥会绑定到锁屏凭据：**改锁屏/去掉锁屏会直接作废密钥**（凭据全丢），
 * 且每次使用都要求用户刚认证过。而自动登录发生在 App 冷启动、SSE 重连、401 之后 ——
 * 那些时刻弹不出"请验证指纹"，只会让自动登录静默失效。
 *
 * 代价要说清楚：false 意味着**设备解锁状态下，任何拿到这份密文并能调用我们代码的人都能解密**。
 * 但这本来就不是威胁模型 —— 能调用我们代码就等于已经拿到了 App 的 UID 与权限，
 * 那时候它能直接以用户身份发请求，解不解密密码已经无所谓了。
 * 真正防的是"别的应用/root 用户偷走数据文件"，Keystore 挡得住这个。
 *
 * ## 不用 StrongBox
 *
 * `setIsStrongBoxBacked(true)` 安全性更高（独立安全芯片），但：① 只有部分机型有；
 * ② 已知的实现质量参差，GCM 在某些 StrongBox 上会返回错误的 tag 长度。
 * 凭据存储用软件 Keystore（TEE 支撑）已经足够，不值得为此引入"换台手机就登不上"的风险。
 */
object AndroidKeystoreSecretBox {

    private const val TAG = "KeystoreSecretBox"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** 每个 storeKey 一把独立密钥：A 店的密文不能被 B 店的密钥解开。 */
    fun aliasFor(storeKey: String) = "s1_cred_${storeKey}"

    /**
     * 取（或首次生成）该 alias 的密钥。
     *
     * **失败返回 null 而不是抛**：Keystore 在少数机型/ROM 上会抛
     * `ProviderException`、`UnsupportedOperationException`，或者在系统 OTA 之后
     * 报告密钥已失效。凭据存不了只意味着"不能自动登录"，不该让整个 App 起不来。
     */
    fun getOrCreate(storeKey: String): SecretBox? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = aliasFor(storeKey)
        val existing = ks.getKey(alias, null) as? SecretKey
        SecretBox(existing ?: generate(ks, alias))
    }.onFailure {
        Log.w(TAG, "Keystore 不可用（alias=${aliasFor(storeKey)}），本次禁用自动登录", it)
    }.getOrNull()

    /**
     * 作废某个 storeKey 的密钥（登出 / 收养 `__local__` 时调用）。
     *
     * ⚠ 删掉密钥之后，用它加密的所有密文**永久不可解**——这正是登出想要的效果，
     * 但顺序必须是"先删密文、再删密钥"，否则中间崩了会留下一堆解不开的垃圾数据。
     */
    fun destroy(storeKey: String) {
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.deleteEntry(aliasFor(storeKey))
        }.onFailure { Log.w(TAG, "删除 Keystore 条目失败: $it") }
    }

    private fun generate(ks: KeyStore, alias: String): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)      // 见类注释
            .setRandomizedEncryptionRequired(true)      // 每次加密用新 IV，GCM 重用 IV 是灾难
            .build()
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(spec)
        return kg.generateKey()
    }
}
