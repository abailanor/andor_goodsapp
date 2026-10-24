package com.example.myapplication.data

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 热点互同步的传输层。**一次往返、双向交换。**
 *
 * ## 与 [DataShareService] 的区别
 *
 * 那个是"客户端把整个 db 文件推给服务端，服务端导入商品"。单向、整库覆盖。
 * 对 `order_outbox` 来说整库覆盖是**丢数据**：两台设备各有几十笔未上传的订单，
 * 覆盖会让一方的全部消失，而导入报告只会说"成功导入 N 条商品"。
 *
 * 这里是行级、双向的：两边各自把自己的未上传订单与待推送改价发过去，
 * 各自合并对方发来的（[HotspotMerger]），谁也不覆盖谁。
 *
 * ## 为什么是"一次往返"而不是各自单向推一次
 *
 * 单向推需要用户操作两次（A 推给 B，再 B 推给 A），而第二次很容易忘 ——
 * 忘了的后果是 B 的订单永远留在 B 上，A 看不到也不会替它上传。
 * 一次往返让"连上就两边都齐了"，没有半个状态。
 *
 * ## ⚠ 传输中断是安全的
 *
 * 合并是**幂等**的（已存在的 uuid 跳过、`queuedAt` 更晚才覆盖），
 * 所以传一半断掉、重连再来一次，结果与一次传完相同。
 * 唯一要避免的是"读到半个包就当整包解析"——[HotspotSync.decode] 对坏数据返回 null，
 * 而这里在读取长度与内容时用了 `readFully`，不会给出半截字节。
 */
class HotspotSyncService(
    private val context: Context,
    private val merger: HotspotMerger,
    private val deviceId: () -> String,
    private val storeKey: () -> String,
    private val audit: (String) -> Unit = {},
) {

    companion object {
        /**
         * ⚠ 与 [DataShareService.PORT] / `REQUEST_PORT` **不能相同**：
         * 两个服务可能同时开着（用户在做商品目录同步的同时又点了订单互同步），
         * 抢同一个端口会让后启动的那个静默失败。
         */
        const val PORT = 8767

        private const val MAGIC = "S1_HOTSPOT"
        private const val KIND_ROWS = "ROWS_V1"

        /** 单次传输上限。一个店的未上传订单撑死几千笔，8MB 远远够；
         *  设上限是为了防对面发来一个巨大的 length 把内存打爆。 */
        private const val MAX_BYTES = 8 * 1024 * 1024

        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val SO_TIMEOUT_MS = 60_000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var hostJob: Job? = null
    private var joinJob: Job? = null

    private var _status: ((String) -> Unit)? = null
    private var _result: ((HotspotMerger.Report?) -> Unit)? = null

    fun onStatus(listener: (String) -> Unit) {
        _status = listener
    }

    fun onResult(listener: (HotspotMerger.Report?) -> Unit) {
        _result = listener
    }

    private fun status(s: String) {
        _status?.invoke(s)
    }

    val isHosting: Boolean get() = serverSocket != null
    val isJoining: Boolean get() = joinJob?.isActive == true

    // ==================== 主机侧（开热点的那台）====================

    /**
     * 开始等待对方连过来。
     *
     * @return false 表示端口起不来（被占用、或权限问题）。
     */
    fun startHosting(): Boolean {
        if (isHosting) return true
        return try {
            val socket = ServerSocket(PORT).apply { reuseAddress = true }
            serverSocket = socket
            status("等待另一台设备连接…（对方请点「连接并同步」）")
            hostJob = scope.launch { acceptLoop(socket) }
            true
        } catch (e: Exception) {
            audit("热点同步主机启动失败：${e.message}")
            status("启动失败：${e.message}")
            false
        }
    }

    fun stopHosting() {
        hostJob?.cancel()
        hostJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        status("已停止等待")
    }

    /**
     * 接受连接并同步。**处理完一个就继续等下一个** —— 店里可能有好几台设备，
     * 用户不该每同步一台就重新点一次"开始等待"。
     */
    private suspend fun acceptLoop(server: ServerSocket) {
        while (true) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                // 关闭 socket 会让 accept 抛，那是正常的停止路径
                if (serverSocket == null) return
                audit("热点同步 accept 失败：${e.message}")
                continue
            }
            try {
                handleExchange(client, isHost = true)
            } catch (e: Exception) {
                audit("热点同步（主机侧）失败：${e.javaClass.simpleName}: ${e.message}")
                status("同步失败：${e.message}")
                _result?.invoke(null)
            } finally {
                runCatching { client.close() }
            }
        }
    }

    // ==================== 客户端侧（连热点的那台）====================

    /** 连接热点网关并同步。结果通过 [onResult] 回调。 */
    fun startJoining() {
        if (isJoining) return
        joinJob = scope.launch {
            val gateway = runCatching { hotspotGateway() }.getOrNull()
            if (gateway == null) {
                status("找不到热点网关：请确认已连上对方的热点")
                _result?.invoke(null)
                return@launch
            }
            status("正在连接 $gateway…")
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(gateway, PORT), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = SO_TIMEOUT_MS
                    handleExchange(socket, isHost = false)
                }
            } catch (e: Exception) {
                audit("热点同步（客户端侧）失败：${e.javaClass.simpleName}: ${e.message}")
                status("同步失败：${e.message}")
                _result?.invoke(null)
            }
        }
    }

    fun stopJoining() {
        joinJob?.cancel()
        joinJob = null
    }

    // ==================== 交换 ====================

    /**
     * 一次往返：先把自己那份发过去，再读对方那份并合并。
     *
     * ⚠ 两侧的读写顺序必须**完全一致**（都是先写后读），否则会死锁 ——
     * A 在等 B 先写、B 在等 A 先写，两边都卡在 read 上直到超时。
     */
    private suspend fun handleExchange(socket: Socket, isHost: Boolean) {
        val out = DataOutputStream(socket.getOutputStream())
        val input = DataInputStream(socket.getInputStream())

        val mine = merger.export(deviceId(), storeKey())
        val mineBytes = HotspotSync.encode(mine)

        out.writeUTF(MAGIC)
        out.writeUTF(KIND_ROWS)
        out.writeInt(mineBytes.size)
        out.write(mineBytes)
        out.flush()

        status(if (isHost) "已收到连接，正在交换…" else "已连接，正在交换…")

        val magic = input.readUTF()
        val kind = input.readUTF()
        if (magic != MAGIC || kind != KIND_ROWS) {
            // 对面可能是旧版 App（只支持整库推送），或者压根不是这个 App。
            // 直接拒收，不要试着"兼容"——猜错格式会解出一堆看起来合法的默认值。
            audit("热点同步协议不匹配：收到 magic=$magic kind=$kind")
            status("对方版本不兼容（收到 $magic/$kind）")
            _result?.invoke(null)
            return
        }
        val len = input.readInt()
        if (len <= 0 || len > MAX_BYTES) {
            audit("热点同步收到不合理的长度 $len")
            status("数据长度异常（$len 字节），已拒收")
            _result?.invoke(null)
            return
        }
        val theirs = ByteArray(len)
        // ⚠ readFully 而不是循环 read：read 可能只填一部分就返回，
        // 半截字节交给 decode 会得到 null，用户看到的是"同步失败"而原因是我们读少了。
        input.readFully(theirs)

        val bundle = HotspotSync.decode(theirs)
        if (bundle == null) {
            status("对方的数据格式不认识（可能版本不一致），已拒收")
            _result?.invoke(null)
            return
        }

        val report = merger.merge(bundle, storeKey())
        if (report == null) {
            status("店铺不一致：对方是 ${bundle.storeKey}，本机是 ${storeKey()}。已拒收全部数据")
            _result?.invoke(null)
            return
        }
        audit("热点同步完成（${if (isHost) "主机" else "客户端"}侧）：${report.summary}")
        status(report.summary)
        _result?.invoke(report)
    }

    /** 热点网关地址 = 开热点那台设备的 IP。 */
    private fun hotspotGateway(): InetAddress? {
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcp: DhcpInfo = wifi.dhcpInfo ?: return null
        val gw = dhcp.gateway
        if (gw == 0) return null
        return InetAddress.getByAddress(
            byteArrayOf(
                (gw and 0xFF).toByte(),
                (gw shr 8 and 0xFF).toByte(),
                (gw shr 16 and 0xFF).toByte(),
                (gw shr 24 and 0xFF).toByte(),
            )
        )
    }

    fun release() {
        stopHosting()
        stopJoining()
        scope.cancel()
    }
}
