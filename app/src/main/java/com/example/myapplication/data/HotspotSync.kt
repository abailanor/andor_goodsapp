package com.example.myapplication.data

import com.example.myapplication.db.OrderOutboxEntity
import com.example.myapplication.db.ProductPendingChange
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.math.BigDecimal

/**
 * 热点互同步的**行级**传输格式。
 *
 * ## 为什么不用现有的 `DataShareService`
 *
 * 那个是"把整个 db 文件拷给对方再导入"。对商品目录还凑合，对 `order_outbox` 是
 * **丢数据**而不是同步：两台设备各自有几十笔未上传的订单，整库覆盖会让一方的
 * 全部消失，而且消失得无声无息（导入报告只会说"成功导入 N 条商品"）。
 *
 * 行级合并才是对的：两边各自保留自己的行，缺的补上，重合的按规则裁决。
 *
 * ## ⚠ 只传**未上传**的订单，不传 PROMOTED
 *
 * "已上传"这个状态**不能**通过热点同步来写。B 设备要知道 A 已经传了某笔单，
 * 正确的途径是同步那个订单文件：ADD 消息到达 → `applyAdded` → `promoteFromOutbox`，
 * 那条路径会把偏移、索引行、统计桶三件事**一起**做对。
 *
 * 让热点协议直接改状态就等于开了第二个搬桶入口，而两处的幂等判据不同 ——
 * 结果要么双算要么漏算，两种都静默。所以 [OrderDto] 里虽然带 `state` 字段
 * （为了保留 READY / BLOCKED / FAILED_PERMANENT 的区别），接收侧一律拒绝 PROMOTED。
 *
 * ## ⚠ payload 是**字节**，必须逐字节保真
 *
 * `order_outbox.payload` 是"将要原样发给服务端的那些字节"，一次写入不可变。
 * 传输过程中动一个字节，偏移就全错，而等长覆写不报错、文件长度不变、
 * 校验和自洽 —— 没有任何机制会发现。所以这里用 base64 而不是"把 JSON 再解析一遍"，
 * 并且往返有测试盯着。
 *
 * 用 okio 的 [decodeBase64] 而不是 `java.util.Base64`：后者要 API 26，
 * 而 minSdk 是 24。也不用 `android.util.Base64`：那个在 JVM 单测里跑不了，
 * 而这段编解码恰恰是最需要测的（字节保真）。
 */
object HotspotSync {

    /**
     * 协议头。**改格式必须改这个字符串**，不能只改 [VERSION]：
     * 旧版本 App 读到新版本的数据会解析出一堆默认值（Gson 对缺失字段不报错），
     * 而那些默认值看起来像合法数据 —— 比如 `payloadB64` 缺失会变成空字节数组，
     * 于是对方收到一笔"内容为空"的订单。magic 不匹配则直接拒收。
     */
    const val MAGIC = "S1_ROW_SYNC_V1"
    const val VERSION = 1

    /** 一笔未上传订单在线上的样子。 */
    data class OrderDto(
        val orderUuid: String,
        val groupId: String,
        val fileType: String,
        val year: Int,
        val month: Int,
        /** `payload` 的 base64。⚠ 必须逐字节还原，见类注释。 */
        val payloadB64: String,
        val state: String,
        val blockedReason: String?,
        val blockedBarcodes: String?,
        val attemptCount: Int,
        val lastError: String?,
        val pricedAt: Long,
        val createdAt: Long,
        val writer: String,
        val totalCents: Long,
        val itemCount: Int,
        val orderTs: Long,
        /** **创建者**的设备 id，不是发送者的。转发时要原样保留，见 [fromEntity]。 */
        val originDevice: String,
    )

    /** 一笔待推送的商品改动。金额一律**字符串**（价格过 double 有精度问题）。 */
    data class ProductDto(
        val barcode: String,
        val op: String,
        val name: String,
        val price: String,
        /** null = 不按整箱卖。⚠ 不能用 "0"，那是"整箱免费"。 */
        val boxPrice: String?,
        val boxSize: Int,
        val stock: Int,
        val orderable: Boolean,
        val queuedAt: Long,
    )

    /** 一次同步的全部内容。 */
    data class Bundle(
        val version: Int,
        /** 发送方的设备 id。接收方用它来判断"这些单是谁开的"。 */
        val deviceId: String,
        /**
         * 发送方当前的作用域键（merchantCode，或离线时的 `__local__`）。
         *
         * ⚠ 接收方**必须**校验：两家不同的店互相同步会把订单串店，
         * 而串店之后那些单会上传到错误的 fileId 下，只有那一台设备看得到。
         */
        val storeKey: String,
        val orders: List<OrderDto>,
        val products: List<ProductDto>,
    )

    private val gson = Gson()

    /** 线上格式：magic 一行 + 一个 JSON 文档。 */
    fun encode(bundle: Bundle): ByteArray {
        val json = gson.toJson(bundle)
        return (MAGIC + "\n" + json).toByteArray(Charsets.UTF_8)
    }

    /**
     * 解析。**绝不抛**：magic 不对、JSON 坏了、版本不认，一律返回 null。
     *
     * 热点对面是一台我们不控制版本的设备，收到不认识的数据是正常情况。
     * 抛异常会让接收线程死掉，而用户看到的只是"同步失败"，查不出原因。
     */
    fun decode(bytes: ByteArray): Bundle? {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        if (!text.startsWith(MAGIC)) return null
        val json = text.substringAfter('\n', "")
        if (json.isBlank()) return null
        val bundle = try {
            gson.fromJson(json, Bundle::class.java)
        } catch (e: JsonSyntaxException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        // version 不认识就拒收：将来加了必填字段的话，旧 App 用默认值解出来的是**假数据**，
        // 而假数据看起来完全合法（见 MAGIC 的注释）。
        if (bundle == null || bundle.version != VERSION) return null
        // Gson 对缺失的 List 字段会给 null，而声明是非空 —— 兜住，否则调用方 NPE
        return bundle.copy(
            orders = bundle.orders ?: emptyList(),
            products = bundle.products ?: emptyList(),
        )
    }

    // ==================== 实体 ↔ DTO ====================

    fun fromEntity(row: OrderOutboxEntity) = OrderDto(
        orderUuid = row.orderUuid,
        groupId = row.groupId,
        fileType = row.fileType,
        year = row.year,
        month = row.month,
        payloadB64 = row.payload.toByteString().base64(),
        state = row.state,
        blockedReason = row.blockedReason,
        blockedBarcodes = row.blockedBarcodes,
        attemptCount = row.attemptCount,
        lastError = row.lastError,
        pricedAt = row.pricedAt,
        createdAt = row.createdAt,
        writer = row.writer,
        totalCents = row.totalCents,
        itemCount = row.itemCount,
        orderTs = row.orderTs,
        // ⚠ 原样转发**创建者**的 id，不是本机 id。
        // A → B → C 这样转一手之后，C 仍然要知道这单原本是 A 开的：
        // 只有 A 该自动上传它，B 和 C 都只能手动接管。
        // 盖成转发者的 id 会让每一跳都以为是自己开的，于是三台设备都自动上传。
        originDevice = row.originDevice,
    )

    /**
     * @param fallbackDevice DTO 里 `originDevice` 为空时用它（对方是没升级的旧版本）。
     *        空串在本地语义里是"迁移前的老行 = 本机"，所以**不能**留空 ——
     *        那会让外来单被当成本机单自动上传，正是要防的那件事。
     * @return null 表示这一条不该收（payload 解不开、或状态是 PROMOTED / DRAFT）。
     */
    fun toEntity(dto: OrderDto, storeKey: String, fallbackDevice: String): OrderOutboxEntity? {
        val payload = dto.payloadB64.decodeBase64()?.toByteArray() ?: return null
        // 空 payload 说明编码那侧出了问题。收下来就是一笔"内容为空"的订单，
        // 上传时会被服务端 400，而用户看到的是"上传失败"却查不出原因。
        if (payload.isEmpty()) return null
        // PROMOTED：状态只能由同步路径写（见类注释）。
        // DRAFT：草稿连"成交了吗"都没确定，收下来会让对方算进营业额。
        if (dto.state == OrderOutboxEntity.PROMOTED || dto.state == OrderOutboxEntity.DRAFT) return null
        return OrderOutboxEntity(
            storeKey = storeKey,
            orderUuid = dto.orderUuid,
            groupId = dto.groupId,
            fileType = dto.fileType,
            year = dto.year,
            month = dto.month,
            payload = payload,
            state = dto.state,
            blockedReason = dto.blockedReason,
            blockedBarcodes = dto.blockedBarcodes,
            attemptCount = dto.attemptCount,
            lastError = dto.lastError,
            pricedAt = dto.pricedAt,
            createdAt = dto.createdAt,
            writer = dto.writer,
            totalCents = dto.totalCents,
            itemCount = dto.itemCount,
            orderTs = dto.orderTs,
            originDevice = dto.originDevice.ifEmpty { fallbackDevice },
        )
    }

    fun productFromEntity(c: ProductPendingChange) = ProductDto(
        barcode = c.barcode,
        op = c.op,
        name = c.name,
        // 金额一律走字符串：BigDecimal → double → BigDecimal 会丢尾零
        // （"85.00" 变成 "85.0"），而服务端要求两位小数。
        price = c.price.toPlainString(),
        boxPrice = c.boxPrice?.toPlainString(),
        boxSize = c.boxSize,
        stock = c.stock,
        orderable = c.orderable,
        queuedAt = c.queuedAt,
    )

    /**
     * @return null 表示这一条不该收（价格解析不出来）。
     *         ⚠ 解析失败**必须**丢掉整条，不能填 0 —— 0 元的商品会静默覆盖掉对方
     *         目录里的真实价格，而用户看到的是"价格莫名其妙变成 0 了"。
     */
    fun productToEntity(dto: ProductDto, storeKey: String): ProductPendingChange? {
        val price = parseDecimal(dto.price) ?: return null
        val boxPrice = dto.boxPrice?.let { parseDecimal(it) }
        // boxPrice 非空但解析失败 = 数据坏了。宁可当"不按整箱卖"（null）也不要 0，
        // 因为 0 的语义是"整箱免费"，那会让整箱优惠算出一个巨大的负数。
        if (dto.boxPrice != null && boxPrice == null) return null
        return ProductPendingChange(
            storeKey = storeKey,
            barcode = dto.barcode,
            op = dto.op,
            name = dto.name,
            price = price,
            boxPrice = boxPrice,
            boxSize = dto.boxSize,
            stock = dto.stock,
            orderable = dto.orderable,
            queuedAt = dto.queuedAt,
            // 尝试次数与错误**不跨设备传递**：那是本机推送失败的记录，
            // 带过去会让对方以为自己也失败过三次而放弃推送。
            attemptCount = 0,
            lastError = null,
        )
    }

    private fun parseDecimal(raw: String): BigDecimal? =
        runCatching { BigDecimal(raw.trim()) }.getOrNull()
}
