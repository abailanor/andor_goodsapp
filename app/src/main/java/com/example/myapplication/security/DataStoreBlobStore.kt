package com.example.myapplication.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.io.IOException

/**
 * [BlobStore] 的真机实现（Preferences DataStore）。
 *
 * ## 为什么用 DataStore 而不是自己写文件
 *
 * 三条我们正好需要的性质：
 *  1. **原子写**：写临时文件再 rename，进程在写入中途被杀也不会留下半个文件。
 *     对凭据来说半个文件意味着 GCM 标签校验失败 → `SecretBox.decrypt` 返回 null →
 *     "凭据没了，请重新登录"。安全上不出事，但用户会莫名其妙被登出。
 *  2. **序列化访问**：所有读写排在一个 actor 上，不用自己加锁，也不会出现
 *     `updateSession` 与 `save` 并发互相覆盖。
 *  3. **损坏可恢复**：见下面的 corruptionHandler。
 *
 * ## 为什么和设置**分开**一个文件
 *
 * DataStore 每次写都**全量重写**整个文件。设置项（通知开关、频率阈值）写得频繁，
 * 凭据几乎不写。放一起的话每拨一次开关都要把密文重抄一遍；更要紧的是
 * **一次坏写会同时毁掉两样东西** —— 而 `CredentialStore` 明确要求
 * "`KICKED` 闩不能和它保护的数据同生共死"。分开文件让损坏的影响面各自独立。
 *
 * ## ⚠ corruptionHandler 必须挂
 *
 * 不挂的话 `.preferences_pb` 一旦损坏（进程被杀在写入中途、闪存坏块），
 * **此后每次读写都永久抛 `IOException`**，表现为"App 一启动就崩，清数据才能好"。
 * 挂上之后损坏被当成"空文件" → [read] 返回 null → 上层按"没有凭据"处理，
 * 正好是 [BlobStore.read] 注释里写的语义。
 *
 * ## ⚠ 存储位置与"别的程序读不到"
 *
 * 文件在 `/data/data/<pkg>/files/datastore/`，属主是本 App 的 UID，非 root 的其它 App 读不到。
 * 内容本身还是 AES-GCM 密文（密钥在 AndroidKeyStore 里、**不可导出**）。
 * 两道防线之外还要堵第三条路：**备份**。`AndroidManifest` 里 `allowBackup="false"`，
 * 且 `res/xml/data_extraction_rules.xml` 显式排除了 `datastore/` —— 后者是为了
 * 将来有人把 `allowBackup` 改回 true 时凭据仍然不会进云备份 / D2D 迁移。
 */
class DataStoreBlobStore(private val ds: DataStore<Preferences>) : BlobStore {

    /**
     * 读不到、文件损坏、被中断，一律返回 null，**不抛**。
     *
     * 这是 [BlobStore] 的接口契约：调用方（`CredentialStore.load`）拿到 null 的唯一正确反应
     * 是引导用户重新登录。在读取路径上抛异常会把"凭据丢了"升级成"App 打不开"。
     */
    override suspend fun read(name: String): ByteArray? = try {
        // `data.first()`：DataStore 的 flow 会先发一个当前快照再继续，first() 取到就取消订阅。
        // 这是官方推荐的"同步式读一次"写法，不需要自己拿 collect 当 break 用。
        ds.data.first()[byteArrayPreferencesKey(name)]
    } catch (e: IOException) {
        // 只兜 IOException。CancellationException 是 IllegalStateException 的子类，
        // 不会被这里吞掉 —— 吞掉取消会破坏结构化并发，比读失败严重得多。
        null
    }

    /**
     * 传入的 blob 为 null 时删除该键。
     *
     * ⚠ `edit {}` 会挂起直到写入落盘，**不要在主线程调**。
     */
    override suspend fun write(name: String, blob: ByteArray?) {
        val key = byteArrayPreferencesKey(name)
        ds.edit { prefs ->
            if (blob == null) prefs.remove(key) else prefs[key] = blob
        }
    }

    companion object {
        /**
         * 全进程唯一的实例。同一个文件开两个 DataStore 会抛
         * `IllegalStateException: There are multiple DataStores active for the same file`，
         * 而且是在**第一次访问**时才抛，不在创建时 —— 很容易在某个 ViewModel 里
         * "顺手 new 一个"就踩到。
         */
        private val Context.credDataStore by preferencesDataStore(
            name = "cred_blobs",
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        )

        fun forApp(context: Context): DataStoreBlobStore =
            DataStoreBlobStore(context.applicationContext.credDataStore)
    }
}
