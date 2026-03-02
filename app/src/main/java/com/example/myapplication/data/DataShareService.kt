package com.example.myapplication.data

import android.content.Context
import kotlinx.coroutines.*
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 数据共享服务
 * 用于通过热点在两个设备间同步数据库文件
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
     * 启动数据共享服务（作为服务器）
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
            onStatusChangedListener?.invoke("同步数据功能已开启")
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
     */
    fun stopSharing() {
        if (!isSharing) return

        isSharing = false
        serverThread?.interrupt()
        serverThread = null
        onStatusChangedListener?.invoke("同步数据功能已关闭")
    }

    /**
     * 服务器线程主循环
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

                    // 在新线程中处理请求
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
     * 处理客户端请求
     */
    private fun handleClientRequest(socket: Socket) {
        try {
            socket.soTimeout = 10000 // 10秒超时
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            val magic = input.readUTF()
            val type = input.readUTF()

            if (magic == MAGIC_HEADER && type == "REQUEST_DB") {
                // 收到请求，发送数据库文件
                sendDatabaseFile(output)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // 忽略
            }
        }
    }

    /**
     * 发送数据库文件
     */
    private fun sendDatabaseFile(output: DataOutputStream) {
        try {
            val databaseFile = context.getDatabasePath("product_database")
            if (!databaseFile.exists()) {
                return
            }

            val fileName = "product_database.db"
            val fileData = databaseFile.readBytes()

            output.writeUTF(MAGIC_HEADER)
            output.writeUTF("DB_DATA")
            output.writeUTF(fileName)
            output.writeLong(fileData.size.toLong())
            output.write(fileData)
            output.flush()

            // 发送完成后关闭共享
            serviceScope.launch {
                stopSharing()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 发送数据请求（作为客户端）
     * @param targetAddress 目标设备IP地址
     */
    fun startRequesting(targetAddress: InetAddress) {
        if (isRequesting) return

        isRequesting = true
        onStatusChangedListener?.invoke("数据请求中...")

        // 开始发送请求
        requestJob = serviceScope.launch {
            sendDataRequests(targetAddress)
        }
    }

    /**
     * 停止请求数据
     */
    fun stopRequesting() {
        requestJob?.cancel()
        requestJob = null
        isRequesting = false
        onStatusChangedListener?.invoke("数据请求已取消")
    }

    /**
     * 循环发送数据请求，直到收到响应或被取消
     */
    private suspend fun sendDataRequests(address: InetAddress) {
        while (isRequesting && requestJob?.isActive == true) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(address, REQUEST_PORT), 3000)
                socket.soTimeout = 5000 // 5秒读取超时

                val output = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())

                // 发送请求头
                output.writeUTF(MAGIC_HEADER)
                output.writeUTF("REQUEST_DB")
                output.flush()

                // 读取响应
                val responseMagic = input.readUTF()
                val responseType = input.readUTF()

                if (responseMagic == MAGIC_HEADER && responseType == "DB_DATA") {
                    // 收到数据库文件
                    val fileName = input.readUTF()
                    val fileSize = input.readLong()

                    val bytes = ByteArray(fileSize.toInt())
                    var read = 0
                    while (read < fileSize) {
                        val chunk = input.readBytes()
                        System.arraycopy(chunk, 0, bytes, read, chunk.size)
                        read += chunk.size
                    }

                    // 保存文件并导入
                    val result = receiveAndImportDatabase(bytes, fileName)
                    onDataReceivedListener?.invoke(result)
                    break
                }
            } catch (e: Exception) {
                // 请求失败，5秒后重试
                if (isRequesting && requestJob?.isActive == true) {
                    delay(5000)
                }
            } finally {
                socket?.close()
            }
        }

        // 请求结束
        if (isRequesting) {
            isRequesting = false
        }
    }

    /**
     * 接收并导入数据库文件
     */
    private suspend fun receiveAndImportDatabase(data: ByteArray, fileName: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // 保存到临时文件
                val tempFile = File(context.cacheDir, "received_$fileName")
                tempFile.writeBytes(data)

                // 调用DatabaseImporter导入
                val importer = DatabaseImporter(context)
                val result = importer.importFromExternalDb(tempFile.absolutePath)

                // 删除临时文件
                tempFile.delete()

                when (result) {
                    is ImportResult.Success -> "数据同步成功！共导入 ${result.importedCount} 条记录"
                    is ImportResult.Error -> "导入失败: ${result.message}"
                }
            } catch (e: Exception) {
                "接收失败: ${e.message}"
            }
        }
    }

    /**
     * 获取本机IP地址
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
