package com.example.myapplication.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM 的加解密封装（**帧格式**在这里，密钥来源不在这里）。
 *
 * ## 为什么要拆成 SecretBox + 密钥来源两层
 *
 * 密钥来自 `AndroidKeyStore` 时，这段逻辑在 JVM 上跑不了 —— 而帧格式（版本字节、IV 位置、
 * 篡改检测）恰恰是最容易写错、错了又最不容易被发现的部分。拆开后：
 *  - [SecretBox] 接受任意 [SecretKey]，JVM 单测用 `SecretKeySpec` 就能覆盖；
 *  - [AndroidKeystoreSecretBox] 只负责"从 Keystore 拿一把出不来的密钥"。
 *
 * ## 为什么是 AES-GCM 而不是 AES-CBC + HMAC
 *
 * GCM 自带认证标签，密文被改一个 bit 就会解密失败（`AEADBadTagException`）。
 * 对凭据存储来说这个属性是刚需：能被发现"被改过"比"改不动"更重要 ——
 * 攻击者把密文改成能解出**另一个**明文的概率为零，而我们能据此判定"本地凭据不可信，重新登录"。
 *
 * ## 为什么每次加密都用新的随机 IV
 *
 * GCM 在同一把密钥下**重用 IV 是灾难性的**（两条密文异或就能消掉密钥流，
 * 并且能伪造标签）。所以 IV 由 [Cipher] 在 `setRandomizedEncryptionRequired(true)` 下
 * 自己生成，我们只在加密后把它读出来存进帧头，绝不自己维护计数器。
 *
 * @param key AES 密钥。来自 AndroidKeyStore 时它**无法被导出**，只有句柄。
 */
class SecretBox(private val key: SecretKey) {

    /**
     * 加密。返回的帧格式：
     * ```
     * [0]      = FORMAT_VERSION (1 字节)
     * [1..12]  = IV (12 字节，GCM 标准长度)
     * [13..]   = 密文 + 16 字节认证标签（JCE 把 tag 追加在密文尾部）
     * ```
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_LEN) { "GCM IV 应当是 $IV_LEN 字节，实际 ${iv.size}" }
        return ByteArray(1 + IV_LEN + ct.size).also { out ->
            out[0] = FORMAT_VERSION
            iv.copyInto(out, 1)
            ct.copyInto(out, 1 + IV_LEN)
        }
    }

    /**
     * 解密。
     *
     * **任何失败都返回 null，不抛**：帧头不对、IV 长度不对、版本不认识、标签校验失败
     * （`AEADBadTagException`）、密钥已被系统作废（部分机型改锁屏/OTA 之后会发生）
     * —— 全部归一成"没有可用凭据"。
     *
     * 调用方拿到 null 的正确反应是**引导用户重新登录**，而不是崩，也不是重试。
     * 在解密路径上抛异常等于把"凭据丢了"升级成"App 打不开"。
     */
    fun decrypt(blob: ByteArray?): ByteArray? {
        if (blob == null || blob.size < 1 + IV_LEN + TAG_LEN) return null
        if (blob[0] != FORMAT_VERSION) return null      // 将来换格式时老数据不会被误解
        return try {
            val iv = blob.copyOfRange(1, 1 + IV_LEN)
            val ct = blob.copyOfRange(1 + IV_LEN, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LEN * 8, iv))
            cipher.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalStateException) {
            // AndroidKeyStore 在密钥失效时抛的是 IllegalStateException（"Key user not authenticated" /
            // "Keystore operation is not supported"），不是 GeneralSecurityException，必须单独兜住。
            null
        }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_LEN = 16
        const val FORMAT_VERSION: Byte = 1

        /** 生成一把**可导出**的 AES 密钥。只给 JVM 单测用；真机走 AndroidKeyStore。 */
        fun newEphemeralKey(): SecretKey {
            val k = javax.crypto.KeyGenerator.getInstance("AES")
            k.init(256, SecureRandom())
            return k.generateKey()
        }
    }
}
