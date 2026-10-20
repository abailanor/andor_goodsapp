package com.example.myapplication.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * [CredentialStore] 的测试。
 *
 * 重点不是"存了能读回来"（那是显然的），而是三条**做错了会造成真实损害**的性质：
 *  1. 凭据不可用时，`KICKED` 闩必须**还在**（安全闸不能和被它保护的数据同生共死）；
 *  2. 存失败必须返回 false，而不是静默当成功（否则用户以为能自动登录，实际每次手输）；
 *  3. 一个 storeKey 的密文不能被另一个 storeKey 的密钥解开。
 */
class CredentialStoreTest {

    /** 内存版 BlobStore：真机上是 DataStore，这里不需要 Android。 */
    private class MemStore : BlobStore {
        val map = LinkedHashMap<String, ByteArray>()
        override suspend fun read(name: String) = map[name]?.copyOf()
        override suspend fun write(name: String, blob: ByteArray?) {
            if (blob == null) map.remove(name) else map[name] = blob.copyOf()
        }
    }

    private fun box(seed: Int) = SecretBox(SecretKeySpec(ByteArray(32) { seed.toByte() }, "AES"))

    private val store = MemStore()
    private val destroyed = mutableListOf<String>()

    /** 默认每个 storeKey 一把稳定的密钥（按 seed 派生，模拟 Keystore 的 alias→key）。 */
    private fun newStore(seedOf: (String) -> Int = { it.hashCode() and 0xFF }) =
        CredentialStore(store) { box(seedOf(it)) }

    private fun creds(
        code: String = "99901",
        user: String = "alice",
        pwd: String = "s3cret",
        token: String = "jwt-token-1",
        perms: Set<String> = setOf("ORDER_WRITE", "SYNC_SUB"),
    ) = Credentials(
        username = user, password = pwd, token = token,
        merchantCode = code, ownerName = "alice", level = "OWNER",
        perms = perms, loginAt = 1_758_000_000_000L, savedAt = 1_758_000_001_000L,
    )

    // ==================== 往返 ====================

    @Test
    fun `存取往返_所有字段都回来`() = runTest {
        val cs = newStore()
        val c = creds()
        assertTrue(cs.save(c))
        assertEquals(c, cs.load("99901"))
    }

    @Test
    fun `权限集合按名字往返_顺序无关`() = runTest {
        val cs = newStore()
        val perms = setOf("SYNC_SUB", "ORDER_WRITE", "PRICE_CHANGE", "STOCK_CHANGE")
        cs.save(creds(perms = perms))
        val loaded = cs.load("99901")!!
        assertEquals(perms, loaded.perms)
        // 按名字判断，不是按序号
        assertTrue(loaded.hasPerm("PRICE_CHANGE"))
        assertFalse(loaded.hasPerm("ADMIN"))
    }

    @Test
    fun `空权限集合也能往返`() = runTest {
        val cs = newStore()
        cs.save(creds(perms = emptySet()))
        assertEquals(emptySet<String>(), cs.load("99901")!!.perms)
    }

    @Test
    fun `没存过就是 null`() = runTest {
        assertNull(newStore().load("99901"))
    }

    // ==================== 落盘的是密文，不是明文 ====================

    @Test
    fun `落盘的字节里找不到密码与 token 的明文`() = runTest {
        val cs = newStore()
        cs.save(creds(pwd = "Sup3rSecretPwd", token = "eyJhbGciOiJIUzI1NiJ9.payload.sig"))
        val blob = store.map["cred_99901"]!!
        val asText = String(blob, Charsets.ISO_8859_1)
        assertFalse("密码不能明文落盘", asText.contains("Sup3rSecretPwd"))
        assertFalse("token 不能明文落盘", asText.contains("eyJhbGciOi"))
        assertFalse("用户名也不能明文落盘", asText.contains("alice"))
        // 帧头仍然是可识别的（版本字节 + 12 字节 IV）
        assertEquals(1, blob[0].toInt())
    }

    // ==================== Keystore 不可用 ====================

    @Test
    fun `Keystore 不可用时 save 返回 false_且不写任何数据`() = runTest {
        // 少数机型/ROM 上 AndroidKeyStore 会抛异常。这时必须明确告知"没存上"，
        // 否则用户以为下次能自动登录，实际每次手输且毫无提示。
        val cs = CredentialStore(store) { null }
        assertFalse(cs.save(creds()))
        assertTrue(store.map.isEmpty())
        assertNull(cs.load("99901"))
    }

    @Test
    fun `Keystore 不可用时 load 返回 null_即使密文还在`() = runTest {
        newStore().save(creds())
        val cs = CredentialStore(store) { null }
        assertNull(cs.load("99901"))
    }

    // ==================== 密钥换了 / 密文坏了 ====================

    @Test
    fun `换了密钥就读不出来_归一成没有凭据`() = runTest {
        // 真机对应的情形：改锁屏、OTA、从备份恢复到另一台设备。
        // 正确反应是引导重新登录，不是崩、不是重试。
        CredentialStore(store) { box(1) }.save(creds())
        assertNull(CredentialStore(store) { box(2) }.load("99901"))
    }

    @Test
    fun `密文被篡改就读不出来`() = runTest {
        val cs = newStore()
        cs.save(creds())
        val blob = store.map["cred_99901"]!!
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 1).toByte()
        assertNull(cs.load("99901"))
    }

    @Test
    fun `密文里塞进非法 JSON 也读不出来`() = runTest {
        val cs = newStore()
        val box = box("99901".hashCode() and 0xFF)
        store.map["cred_99901"] = box.encrypt("这不是 JSON".toByteArray())
        assertNull(cs.load("99901"))
    }

    @Test
    fun `老版本信封不会被当成当前版本读出来`() = runTest {
        val cs = newStore()
        cs.save(creds())
        // 把解密后的 JSON 改成 v=999 再加密回去：load 必须拒绝，
        // 否则将来改字段语义时会解出一个字段全 null 的对象。
        val box = box("99901".hashCode() and 0xFF)
        val json = String(box.decrypt(store.map["cred_99901"])!!, Charsets.UTF_8)
        store.map["cred_99901"] = box.encrypt(json.replaceFirst("\"v\":1", "\"v\":999").toByteArray())
        assertNull(cs.load("99901"))
    }

    // ==================== 每个 storeKey 独立 ====================

    @Test
    fun `两个店铺各自一份_互不覆盖`() = runTest {
        val cs = newStore()
        cs.save(creds(code = "99901", user = "alice", pwd = "pw-A"))
        cs.save(creds(code = "99902", user = "bob", pwd = "pw-B"))
        assertEquals("alice", cs.load("99901")!!.username)
        assertEquals("bob", cs.load("99902")!!.username)
        assertEquals("pw-A", cs.load("99901")!!.password)
        assertEquals("pw-B", cs.load("99902")!!.password)
    }

    @Test
    fun `A 店的密文用 B 店的密钥解不开`() = runTest {
        // 一台设备先后登录两个店铺是真实场景。密钥按 storeKey 分开，
        // 所以即使读到别人的密文文件也没用。
        val cs = newStore()
        cs.save(creds(code = "99901"))
        val blobOfA = store.map["cred_99901"]!!.copyOf()
        store.map["cred_99902"] = blobOfA          // 把 A 的密文放到 B 的位置
        assertNull("B 的密钥不该能解开 A 的密文", cs.load("99902"))
    }

    // ==================== updateSession ====================

    @Test
    fun `updateSession 只换 token 与权限_密码保持不变`() = runTest {
        val cs = newStore()
        cs.save(creds(pwd = "keep-me", token = "old-jwt"))
        assertTrue(cs.updateSession("99901", "new-jwt", setOf("SYNC_SUB"), 123L))
        val c = cs.load("99901")!!
        assertEquals("new-jwt", c.token)
        assertEquals(setOf("SYNC_SUB"), c.perms)
        assertEquals(123L, c.loginAt)
        assertEquals("密码不能被 updateSession 弄丢，否则自动登录静默失效", "keep-me", c.password)
        assertEquals("alice", c.username)
    }

    @Test
    fun `updateSession 在没有凭据时返回 false_不凭空造一条`() = runTest {
        val cs = newStore()
        assertFalse(cs.updateSession("99901", "jwt", emptySet(), 1L))
        assertNull(cs.load("99901"))
    }

    @Test
    fun `loginAt 存下来是为了下次比对 previousLoginAt`() = runTest {
        // 单会话策略：重登后若服务端给的 previousLoginAt ≠ 本地存的 loginAt，
        // 说明离线期间有别的设备登录过，必须警告。所以这个字段不能丢、不能被自动刷新。
        val cs = newStore()
        cs.save(creds().copy(loginAt = 111L))
        assertEquals(111L, cs.load("99901")!!.loginAt)
        cs.updateSession("99901", "jwt", emptySet(), 222L)
        assertEquals(222L, cs.load("99901")!!.loginAt)
    }

    // ==================== KICKED 闩 ====================

    @Test
    fun `KICKED 闩默认关闭_标记后打开`() = runTest {
        val cs = newStore()
        assertFalse(cs.isKicked("99901"))
        cs.markKicked("99901")
        assertTrue(cs.isKicked("99901"))
        cs.clearKicked("99901")
        assertFalse(cs.isKicked("99901"))
    }

    @Test
    fun `KICKED 闩是明文_不在加密块里`() = runTest {
        // 这是刻意的：安全闸不能和被它保护的数据同生共死。
        val cs = newStore()
        cs.markKicked("99901")
        assertEquals("1", String(store.map["kick_99901"]!!, Charsets.UTF_8))
        // 而且不需要 Keystore 就能读到
        assertTrue(CredentialStore(store) { null }.isKicked("99901"))
    }

    @Test
    fun `凭据解不开时 KICKED 闩仍然生效`() = runTest {
        // ⚠ 这是整个类里最重要的一条。
        // 场景：设备被踢下线 → 闩上 → 之后用户改了锁屏，Keystore 密钥作废 →
        // 加密块解不开被当成"没有凭据"丢弃。如果闩也在加密块里，它会跟着一起丢，
        // App 又开始自动登录 → 和把它踢下线的那台设备形成互踢风暴。
        CredentialStore(store) { box(1) }.save(creds())
        val broken = CredentialStore(store) { box(2) }     // 密钥已失效
        broken.markKicked("99901")
        assertNull("凭据确实读不出来了", broken.load("99901"))
        assertTrue("但闩必须还在", broken.isKicked("99901"))
    }

    @Test
    fun `闩是按 storeKey 分的`() = runTest {
        val cs = newStore()
        cs.markKicked("99901")
        assertFalse(cs.isKicked("99902"))
    }

    // ==================== clear ====================

    @Test
    fun `clear 删掉密文与闩_并请求销毁密钥`() = runTest {
        val cs = newStore()
        cs.save(creds())
        cs.markKicked("99901")
        cs.clear("99901") { destroyed.add(it) }
        assertNull(cs.load("99901"))
        assertFalse(cs.isKicked("99901"))
        assertEquals(listOf("99901"), destroyed)
        assertFalse("密文必须真的从存储里删掉", store.map.containsKey("cred_99901"))
    }

    @Test
    fun `clear 不存在的店铺也不崩`() = runTest {
        val cs = newStore()
        cs.clear("nope") { destroyed.add(it) }
        assertEquals(listOf("nope"), destroyed)   // 密钥仍然尝试销毁，幂等
    }

    @Test
    fun `clear 之后重新登录可以正常保存`() = runTest {
        val cs = newStore()
        cs.save(creds())
        cs.clear("99901") { }
        assertNull(cs.load("99901"))
        assertTrue(cs.save(creds(token = "jwt-after-relogin")))
        assertEquals("jwt-after-relogin", cs.load("99901")!!.token)
    }

    // ==================== 覆盖写 ====================

    @Test
    fun `重复保存同一店铺是覆盖不是追加`() = runTest {
        val cs = newStore()
        cs.save(creds(token = "t1"))
        cs.save(creds(token = "t2"))
        assertEquals("t2", cs.load("99901")!!.token)
        assertEquals(1, store.map.keys.count { it.startsWith("cred_") })
    }

    @Test
    fun `同一明文两次保存的密文不同_但都能读回来`() = runTest {
        // IV 随机 → 密文不同。这条保证"覆盖写"不会留下可比的密文差异。
        val cs = newStore()
        cs.save(creds())
        val first = store.map["cred_99901"]!!.copyOf()
        cs.save(creds())
        assertNotEquals(first.toList(), store.map["cred_99901"]!!.toList())
        assertEquals(creds(), cs.load("99901"))
    }
}
