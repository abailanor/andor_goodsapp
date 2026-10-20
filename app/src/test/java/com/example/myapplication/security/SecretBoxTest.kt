package com.example.myapplication.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * [SecretBox] 帧格式的测试。
 *
 * 密钥来源（AndroidKeyStore）在 JVM 上跑不了，所以这里用 `SecretKeySpec` 造可导出的密钥，
 * 专测**帧格式与失败归一**——那才是最容易写错、错了又最不显眼的部分。
 */
class SecretBoxTest {

    private fun box(seed: Byte) = SecretBox(
        SecretKeySpec(ByteArray(32) { seed }, "AES")
    )

    private val b = box(7)

    // ==================== 往返 ====================

    @Test
    fun `往返_普通字节`() {
        val plain = "hunter2".toByteArray()
        assertArrayEquals(plain, b.decrypt(b.encrypt(plain)))
    }

    @Test
    fun `往返_空明文`() {
        // 空密码不是合法输入，但加解密不该因此崩——崩了会掩盖真正的调用方 bug
        assertArrayEquals(ByteArray(0), b.decrypt(b.encrypt(ByteArray(0))))
    }

    @Test
    fun `往返_中文与 emoji`() {
        val plain = "密码：可乐🥤".toByteArray(Charsets.UTF_8)
        assertArrayEquals(plain, b.decrypt(b.encrypt(plain)))
    }

    @Test
    fun `往返_几 KB 的 JSON`() {
        val plain = ("x".repeat(4096) + "尾").toByteArray(Charsets.UTF_8)
        assertArrayEquals(plain, b.decrypt(b.encrypt(plain)))
    }

    @Test
    fun `帧长等于 1 加 12 加密文`() {
        val plain = "abc".toByteArray()
        val blob = b.encrypt(plain)
        assertEquals(1, blob[0].toInt())                       // FORMAT_VERSION
        assertEquals(1 + SecretBox.IV_LEN + plain.size + SecretBox.TAG_LEN, blob.size)
    }

    // ==================== IV 必须每次不同 ====================

    @Test
    fun `同一明文两次加密的密文不同`() {
        // GCM 在同一把密钥下重用 IV 是灾难性的：两条密文异或即可消掉密钥流，还能伪造标签。
        // 所以必须验证 IV 真的是随机的，而不是"看起来能解密就行"。
        val plain = "same-plaintext".toByteArray()
        val e1 = b.encrypt(plain)
        val e2 = b.encrypt(plain)
        assertNotEquals("两次加密必须产生不同密文（IV 随机）", e1.toList(), e2.toList())
        // IV 段本身不同
        assertNotEquals(
            e1.copyOfRange(1, 1 + SecretBox.IV_LEN).toList(),
            e2.copyOfRange(1, 1 + SecretBox.IV_LEN).toList(),
        )
        // 但都能解回同一个明文
        assertArrayEquals(plain, b.decrypt(e1))
        assertArrayEquals(plain, b.decrypt(e2))
    }

    // ==================== 篡改必须被发现（GCM 的认证标签） ====================

    @Test
    fun `改密文一个 bit 就解不开`() {
        val blob = b.encrypt("payload".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull("密文被改过必须解密失败，不能给出一个错误的明文", b.decrypt(blob))
    }

    @Test
    fun `改 IV 一个 bit 就解不开`() {
        val blob = b.encrypt("payload".toByteArray())
        blob[1] = (blob[1].toInt() xor 0x80).toByte()
        assertNull(b.decrypt(blob))
    }

    @Test
    fun `换一把密钥解不开`() {
        val blob = b.encrypt("payload".toByteArray())
        assertNull(box(9).decrypt(blob))
    }

    // ==================== 失败一律归一成 null，绝不抛 ====================

    @Test
    fun `各种畸形输入都返回 null 而不是抛异常`() {
        val bad = listOf(
            null,
            ByteArray(0),
            ByteArray(1),
            ByteArray(1 + SecretBox.IV_LEN),                    // 没有密文段
            ByteArray(1 + SecretBox.IV_LEN + SecretBox.TAG_LEN), // 长度刚好但内容全 0
            byteArrayOf(2) + ByteArray(60),                      // 版本字节不认识
            byteArrayOf(0) + ByteArray(60),
        )
        for (blob in bad) {
            // 解密路径上抛异常等于把"凭据丢了"升级成"App 打不开"
            assertNull("blob=${blob?.size} 应当返回 null", b.decrypt(blob))
        }
    }

    @Test
    fun `版本字节不认识时不解密`() {
        // 将来换格式（比如改成 AES-GCM-SIV 或加盐）时，老数据必须被识别成"不认识"，
        // 而不是拿新格式的逻辑去解旧帧、得到一个看似合法实则错误的明文。
        val blob = b.encrypt("payload".toByteArray())
        blob[0] = (SecretBox.FORMAT_VERSION + 1).toByte()
        assertNull(b.decrypt(blob))
    }

    @Test
    fun `常量值是协议的一部分_改了就等于换格式`() {
        // 这三个值一旦落到用户设备上就改不得了（改了老密文全部解不开）。
        // 把它们钉住，让任何改动都必须是有意识的、并且同时升级 FORMAT_VERSION。
        assertEquals(12, SecretBox.IV_LEN)
        assertEquals(16, SecretBox.TAG_LEN)
        assertEquals(1.toByte(), SecretBox.FORMAT_VERSION)
    }

    @Test
    fun `临时密钥每次生成都不同`() {
        val k1 = SecretBox.newEphemeralKey()
        val k2 = SecretBox.newEphemeralKey()
        assertEquals("AES", k1.algorithm)
        assertFalse("临时密钥必须可导出（JVM 测试用）", k1.encoded.contentEquals(k2.encoded))
        assertTrue(SecretBox(k1).decrypt(SecretBox(k1).encrypt("x".toByteArray())) != null)
    }
}
