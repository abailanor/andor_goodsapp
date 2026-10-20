package com.example.myapplication.data

import android.content.Context
import android.net.DhcpInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 数据共享服务
 * 用于通过热点在两个设备间同步数据库文件
 * 【修改后】：客户端（连热点）发送数据 → 服务端（开热点，默认网关）接收并保存数据
 */
class DataShareService(private val context: Context) {

    companion object {
        const val PORT = 8765
        const val REQUEST_PORT = 8766
        const val MAGIC_HEADER = "PRODUCT_DB_SYNC_V1"
    }

    private var serverThread: Thread? = null
    private var isSharing = false
    private var isRequesting = false
    private var requestJob: Job? = null
    private var onDataReceivedListener: ((String) -> Unit)? = null
    private var onStatusChangedListener: ((String) -> Unit)? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 启动数据共享服务（作为服务端：开热点，接收数据）
     * 【函数名/接口完全不变】
     */
    fun startSharing(): Boolean {
        if (isSharing) return true

        return try {
            isSharing = true
            serverThread = Thread {
                runServer()
            }.apply {
                isDaemon = true
                start()
            }
            onStatusChangedListener?.invoke("等待接收数据...")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            isSharing = false
            onStatusChangedListener?.invoke("启动失败: ${e.message}")
            false
        }
    }

    /**
     * 停止数据共享服务
     * 【函数名/接口完全不变】
     */
    fun stopSharing() {
        if (!isSharing) return

        isSharing = false
        serverThread?.interrupt()
        serverThread = null
        onStatusChangedListener?.invoke("数据接收已关闭")
    }

    /**
     * 服务端线程主循环（接收客户端数据）
     * 【函数名不变，内部逻辑改为：接收文件+导入数据库】
     */
    private fun runServer() {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(REQUEST_PORT).apply {
                reuseAddress = true
            }

            while (isSharing) {
                try {
                    val client = serverSocket.accept()
                    if (!isSharing) {
                        client.close()
                        break
                    }

                    Thread {
                        handleClientRequest(client)
                    }.start()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isSharing) e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            serverSocket?.close()
        }
    }

    /**
     * 处理客户端请求（服务端：接收数据库文件并导入）
     * 【函数名不变，核心逻辑反转】
     */
    private fun handleClientRequest(socket: Socket) {
        try {
            socket.soTimeout = 30000 // 30秒超时（传输大文件）
            val input = DataInputStream(socket.getInputStream())

            val magic = input.readUTF()
            val type = input.readUTF()

            if (magic == MAGIC_HEADER && type == "SEND_DB") {
                // 服务端：接收客户端发送的数据库文件
                val fileName = input.readUTF()
                val fileSize = input.readLong()
                val fileData = ByteArray(fileSize.toInt())

                var bytesRead = 0
                var offset = 0
                while (offset < fileSize) {
                    bytesRead = input.read(fileData, offset, fileData.size - offset)
                    if (bytesRead == -1) break
                    offset += bytesRead
                }

                // 异步导入数据（调用现有外部接口，不实现）
                serviceScope.launch {
                    val result = receiveAndImportDatabase(fileData, fileName)
                    onDataReceivedListener?.invoke(result)
                    stopSharing() // 接收完成自动关闭
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
            }
        }
    }

    /**
     * 发送数据库文件（客户端：读取本地DB发送给服务端）
     * 【函数名不变，仅被客户端调用】
     */
    private fun sendDatabaseFile(output: DataOutputStream) {
        try {
            val databaseFile = context.getDatabasePath("product_database")
            if (!databaseFile.exists()) {
                onStatusChangedListener?.invoke("数据库文件不存在")
                return
            }

            val fileName = "product_database.db"
            val fileData = databaseFile.readBytes()

            // 发送协议头
            output.writeUTF(MAGIC_HEADER)
            output.writeUTF("SEND_DB")
            output.writeUTF(fileName)
            output.writeLong(fileData.size.toLong())
            output.write(fileData)
            output.flush()

            onStatusChangedListener?.invoke("数据发送完成")

        } catch (e: Exception) {
            e.printStackTrace()
            onStatusChangedListener?.invoke("发送失败: ${e.message}")
        }
    }

    /**
     * 发送数据请求（作为客户端：连热点，主动发送数据）
     * 【函数名/接口完全不变】
     */
    fun startRequesting() {
        if (isRequesting) return

        isRequesting = true
        onStatusChangedListener?.invoke("准备发送数据...")

        requestJob = serviceScope.launch {
            sendDataRequests(getHotspotGatewayAddress()) // 自动获取热点网关（服务端IP）

        }
    }

    /**
     * 停止请求数据
     * 【函数名/接口完全不变】
     */
    fun stopRequesting() {
        requestJob?.cancel()
        requestJob = null
        isRequesting = false
        onStatusChangedListener?.invoke("数据发送已取消")
    }

    /**
     * 客户端：连接网关（服务端）并发送数据库文件
     * 【函数名不变，内部逻辑改为发送数据】
     */
    private suspend fun sendDataRequests(address: InetAddress) {
        var socket: Socket? = null
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(address, REQUEST_PORT), 5000)
            socket.soTimeout = 15000

            val output = DataOutputStream(socket.getOutputStream())
            // 客户端：直接发送本地数据库文件
            sendDatabaseFile(output)

        } catch (e: Exception) {
            onStatusChangedListener?.invoke("发送失败: ${e.message}")
            // 失败后5秒重试
            if (isRequesting) {
                delay(2000)
                if (isRequesting) sendDataRequests(address)
            }
        } finally {
            socket?.close()
            isRequesting = false
        }
    }

    /**
     * 接收并导入数据库文件（服务端专用）
     * 【函数名/接口/外部调用完全不变】
     */
    private suspend fun receiveAndImportDatabase(data: ByteArray, fileName: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // 保存到临时文件（调用现有导入接口）
                val tempFile = File(context.cacheDir, "received_$fileName")
                tempFile.writeBytes(data)

                // 调用现有导入器，不实现内部逻辑
                val importer = DatabaseImporter(context)
                val result = importer.importFromExternalDb(tempFile.absolutePath)

                tempFile.delete()

                when (result) {
                    is ImportResult.Success -> "数据接收成功！共导入 ${result.importedCount} 条记录"
                    is ImportResult.Error -> "导入失败: ${result.message}"
                }
            } catch (e: Exception) {
                "接收失败: ${e.message}"
            }
        }
    }

    /**
     * 【新增工具方法】获取手机热点的默认网关IP（服务端IP）
     * 不影响现有接口，仅内部调用
     */
    private fun getHotspotGatewayAddress(): InetAddress {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val dhcpInfo: DhcpInfo = wifiManager.dhcpInfo
        val gatewayIp = dhcpInfo.gateway
        return InetAddress.getByAddress(
            byteArrayOf(
                (gatewayIp and 0xFF).toByte(),
                (gatewayIp shr 8 and 0xFF).toByte(),
                (gatewayIp shr 16 and 0xFF).toByte(),
                (gatewayIp shr 24 and 0xFF).toByte()
            )
        )
    }

    /**
     * 获取本机IP地址
     * 【函数名/接口完全不变】
     */
    fun getLocalIpAddress(): InetAddress? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 以下所有接口、函数、监听器 完全无修改
    fun setOnDataReceivedListener(listener: (String) -> Unit) {
        onDataReceivedListener = listener
    }

    fun setOnStatusChangedListener(listener: (String) -> Unit) {
        onStatusChangedListener = listener
    }

    fun isSharing(): Boolean = isSharing
    fun isRequesting(): Boolean = isRequesting

    fun release() {
        stopSharing()
        stopRequesting()
        serviceScope.cancel()
    }
}