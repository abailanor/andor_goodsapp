package com.example.myapplication.mirror

import com.example.myapplication.core.bytes.ByteOps
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 一份订单文件的本地镜像：**与服务器逐字节一致**。
 *
 * 布局（README §2.2）：
 * ```
 * 偏移 0..3 ：4 字节大端 int = 文件版本号（每次修改 +1）
 * 偏移 4 起 ：订单 JSON，每行一个，行尾单个 \n（0x0A，不是 \r\n）
 * ```
 * UTF-8，无 BOM。服务器把客户端上传的请求体**原样追加**（不重新格式化、不调整字段顺序），
 * 所以本地也必须原样存——任何"顺手美化一下 JSON"都会让偏移全部错位。
 *
 * ## 这个类故意不提供的方法
 *
 * **没有 `readAll()` / `bytes()`。** 一个月的 B 类镜像在"一天上百单、一单上百行"的量级下
 * 可以有 5~26 MB，全量读进内存会 OOM 或 ANR。需要内容时只能用：
 *  - [readAt] 读固定长度的一段（补丁前读旧字节、校验 statusOffset）
 *  - [readLine] 从某个行起点读到行尾（解析单条订单）
 *  - [streamContent] 流式遍历（重建索引、算校验和）
 *
 * 参照实现是 `C:\Users\hhl\Desktop\python\s1client.py` 的 `Mirror` 类（L335-393），
 * 那是跑通 161 条契约断言的版本。本类与它语义一致，但**去掉了 `bytes()`**，
 * 并补上了它没有的 fsync 与流式替换。
 *
 * ## 并发
 *
 * 本类**不加锁**。调用方必须持有 [com.example.myapplication.core.lock.StripedMutex]
 * 对应 `storeKey|fileId` 的锁。理由见 [MirrorCommitter]。
 */
class Mirror(val file: File) {

    /**
     * 确保文件存在且至少有 4 字节头。缺失或过短时用版本 0 的空头创建。
     *
     * 与 `s1client.py` 的 `Mirror.__init__` 一致。对应服务端 `ensureFileBlocking`
     * 写 `new byte[]{0,0,0,0}` 的行为。
     */
    fun ensureCreated() {
        file.parentFile?.mkdirs()
        if (!file.exists() || file.length() < ByteOps.HEADER_LEN.toLong()) {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(ByteOps.encodeHeader(0))
                raf.fd.sync()
            }
        }
    }

    fun exists(): Boolean = file.exists()

    /** 文件总长度（含 4 字节头）。 */
    fun length(): Long = if (file.exists()) file.length() else 0L

    /** 读 4 字节大端版本头。文件不存在或过短返回 0（与服务端 `readHeaderVersionBlocking` 一致）。 */
    fun readHeaderVersion(): Int {
        if (!file.exists()) return 0
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < ByteOps.HEADER_LEN) return 0
            val b = ByteArray(ByteOps.HEADER_LEN)
            raf.readFully(b)
            return ByteOps.decodeHeader(b)
        }
    }

    /**
     * 读 [len] 字节。越界抛异常——**绝不静默截断**。
     *
     * 静默截断会让"补丁前读旧字节确认 1→0"这类校验得到错误结论，
     * 进而把一次备注修改误判成取消、错误回退库存与统计。
     */
    fun readAt(offset: Long, len: Int): ByteArray {
        require(offset >= 0) { "偏移不能为负: $offset" }
        require(len >= 0) { "长度不能为负: $len" }
        val size = length()
        if (offset + len > size) {
            throw IOException("读取超出文件范围: offset=$offset len=$len size=$size (${file.name})")
        }
        val b = ByteArray(len)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(b)
        }
        return b
    }

    /**
     * 从 [lineStart] 读到该行末尾（不含行尾 `\n`）。
     *
     * 用于解析单条订单（拿到 uuid / writer / items / total）。分块读，不整文件载入。
     */
    fun readLine(lineStart: Long): String {
        val size = length()
        if (lineStart < ByteOps.HEADER_LEN || lineStart >= size) {
            throw IOException("行起点越界: lineStart=$lineStart size=$size (${file.name})")
        }
        val out = java.io.ByteArrayOutputStream()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(lineStart)
            val buf = ByteArray(4096)
            var remaining = size - lineStart
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                for (i in 0 until n) {
                    if (buf[i] == ByteOps.LF) {
                        return String(out.toByteArray(), Charsets.UTF_8)
                    }
                    out.write(buf[i].toInt())
                }
                remaining -= n
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 追加一行订单：原文 + 单个 `\n`，然后 fsync。
     *
     * @return 追加的**起始偏移**（= 写入前的文件长度）。调用方要用它做幂等守卫的判定。
     *
     * ⚠ 绝不能用 `PrintWriter.println()` / `BufferedWriter.newLine()`——它们用**平台分隔符**，
     * 在 Windows 上会写成 `\r\n`，镜像立刻与服务器分歧。这里直接写字节。
     */
    fun appendLine(orderJson: String): Long {
        val offset = length()
        val payload = ByteArray(ByteOps.utf8Len(orderJson) + 1)
        val body = ByteOps.utf8(orderJson)
        body.copyInto(payload, 0)
        payload[body.size] = ByteOps.LF
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(payload)
            raf.fd.sync()
        }
        return offset
    }

    /** 追加已经序列化好的字节（含末尾 LF），然后 fsync。返回追加起始偏移。 */
    fun appendBytes(payload: ByteArray): Long {
        val offset = length()
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(payload)
            raf.fd.sync()
        }
        return offset
    }

    /**
     * 等长就地覆写，然后 fsync。**文件长度不变**，所以所有绝对偏移保持有效。
     *
     * 越界抛异常。注意这**不是**变长修改的通道——变长修改无法用本机制实现
     * （README §2.4），这也是 A 类备注必须 padding 到固定 150 字节的原因。
     */
    fun patchAt(offset: Long, bytes: ByteArray) {
        val size = length()
        if (offset + bytes.size > size) {
            throw IOException("补丁超出文件范围: offset=$offset len=${bytes.size} size=$size (${file.name})")
        }
        if (offset < ByteOps.HEADER_LEN) {
            throw IOException("补丁不得落在 4 字节版本头内: offset=$offset")
        }
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(offset)
            raf.write(bytes)
            raf.fd.sync()
        }
    }

    /**
     * 写 4 字节版本头，然后 fsync。
     *
     * ⚠ **顺序：永远先写内容、后写版本头**，与服务器一致（README §8 描述的顺序）。
     * 反过来的话，进程在两步之间被杀会留下"头部说已是新版本、内容还是旧的"——
     * 下次同步会命中 LATEST，那段内容就**永久丢失且无法自愈**。
     * 这个顺序由 [MirrorCommitter] 强制，本方法只是原语。
     */
    fun writeHeaderVersion(version: Int) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(ByteOps.encodeHeader(version))
            raf.fd.sync()
        }
    }

    /**
     * 流式算内容校验和（偏移 4 起，无符号字节求和，Int 回绕）。分块读，不整文件载入。
     *
     * 与服务端 `OrderFileService.computeContentChecksum` 同构。
     * ⚠ 只能自比，不能与服务器对账——见 [ByteOps.sumBytes] 的说明。
     */
    fun checksumContent(): Int {
        if (!file.exists()) return 0
        val size = length()
        if (size <= ByteOps.HEADER_LEN) return 0
        var sum = 0
        streamContent { buf, n ->
            for (i in 0 until n) sum += (buf[i].toInt() and 0xFF)
        }
        return sum
    }

    /**
     * 从偏移 4 起分块遍历内容。用于算校验和、重建订单索引。
     *
     * @param onChunk 收到 (缓冲区, 本次有效字节数)。**缓冲区会被复用，不要在回调外持有它。**
     */
    fun streamContent(onChunk: (ByteArray, Int) -> Unit) {
        if (!file.exists()) return
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size <= ByteOps.HEADER_LEN) return
            raf.seek(ByteOps.HEADER_LEN.toLong())
            val buf = ByteArray(64 * 1024)
            var remaining = size - ByteOps.HEADER_LEN
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n <= 0) break
                onChunk(buf, n)
                remaining -= n
            }
        }
    }

    /**
     * FULL 同步：用服务器给的字节流**整体替换**本地镜像。
     *
     * 流程：流式写入同目录的临时文件 → fsync → 原子 rename 到目标路径。
     * 中途失败只会留下一个临时文件，**活动镜像不受影响**——这很重要，
     * 因为 FULL 期间进程被杀是常态，而半写的镜像会让所有偏移失效。
     *
     * 临时文件放在**同一目录**（不是 `mirror-tmp/`）：`Files.move(ATOMIC_MOVE)`
     * 要求源与目标在同一个文件系统上，跨目录可能跨挂载点。
     *
     * @return (内容校验和, 文件总字节数)，供调用方在同一个 Room 事务里写入 `file_state`。
     */
    fun replaceAllStreaming(source: InputStream): Pair<Int, Long> {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.full.tmp")
        var sum = 0
        var total = 0L
        try {
            // 显式持有 FileOutputStream 才能拿到 fd 做 fsync。
            // ⚠ 缓冲流**不能**用 `use{}`：关闭 BufferedOutputStream 会连带关闭底层的
            // FileOutputStream，之后再 `fos.fd.sync()` 就是 SyncFailedException。
            // 这里只 flush，让外层的 `fos.use{}` 统一关闭。
            java.io.FileOutputStream(tmp).use { fos ->
                val out = fos.buffered()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    // 校验和只算偏移 4 起的内容
                    val from = if (total < ByteOps.HEADER_LEN) ByteOps.HEADER_LEN - total.toInt() else 0
                    if (from < n) sum += ByteOps.sumBytes(buf, from, n)
                    total += n
                }
                out.flush()
                fos.fd.sync()
            }
            if (total < ByteOps.HEADER_LEN) {
                throw IOException("FULL 响应体不足 4 字节版本头: $total 字节")
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        // rename 之后再 fsync 父目录，确保目录项本身落盘。
        // ⚠ 这是 POSIX 语义，**Windows 上打不开目录**（FileChannel.open 会抛
        // AccessDeniedException / FileSystemException）。JVM 单测跑在 Windows 上，
        // 所以必须容错：失败只静默跳过，不影响正确性（NTFS 的元数据语义不同）。
        val parent = file.parentFile
        if (parent != null) {
            runCatching {
                FileChannel.open(parent.toPath(), java.nio.file.StandardOpenOption.READ)
                    .use { it.force(true) }
            }
        }
        return sum to total
    }

    /** 把镜像移走（Diverged 时隔离到 quarantine、ARCHIVED 时移入 archive）。保留证据，不删。 */
    fun moveTo(target: File) {
        target.parentFile?.mkdirs()
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /** 镜像相对路径：`<merchantCode>/<ownerName>/A/<yyyy>.mirror` 或 `.../B/<yyyy>-<MM>.mirror`。 */
        fun relativePath(merchantCode: String, ownerName: String, fileId: String): String {
            // fileId 形如 "A:alice:2026" 或 "B:alice:2026-09"
            val parts = fileId.split(':')
            require(parts.size == 3) { "非法 fileId: $fileId" }
            return when (parts[0]) {
                "A" -> "$merchantCode/$ownerName/A/${parts[2]}.mirror"
                "B" -> "$merchantCode/$ownerName/B/${parts[2]}.mirror"
                else -> throw IllegalArgumentException("未知订单类型: ${parts[0]}（fileId=$fileId）")
            }
        }
    }
}
