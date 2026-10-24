package com.example.myapplication.data

import com.example.myapplication.db.ProductDao
import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.sync.outbox.OutboxStore

/**
 * 热点同步的**合并**逻辑。与 socket 分开，因为它错了是静默的，必须能在 JVM 上测。
 *
 * ## 合并规则
 *
 * **订单**：只插入本地没有的 uuid，已有的原样保留（见 [OutboxStore.adoptForeign]）。
 * 不做状态裁决 —— "谁已经上传了"这件事只能由**同步订单文件**来回答，
 * 那条路径会同时把偏移、索引行、统计桶三件事一起做对。
 *
 * **商品改动**：按 (storeKey, barcode) 合并，`queuedAt` **更晚的赢**。
 *
 * ⚠ 用 `queuedAt` 而不是"外来的总是赢"：两台设备可能都改了同一个商品的价格，
 * 后改的那个才是用户的最新意图。而"外来的总是赢"会让先发起同步的那台设备
 * 永远覆盖后一台 —— 谁先点按钮谁说了算，那不是合并是抢。
 *
 * ⚠ 但 `queuedAt` 是**设备本地时钟**。两台设备的时钟可能差几分钟甚至几小时，
 * 于是"更晚"未必是"更新"。这个不精确是可接受的：商品改价冲突本来就少，
 * 而且两边都会把结果推给服务端，服务端那边以最后收到的为准 ——
 * 最终一致，只是中间态可能不是用户想要的那一个。要彻底解决得用逻辑时钟，
 * 而那要改服务端协议。
 */
class HotspotMerger(
    private val outbox: OutboxStore,
    private val productDao: ProductDao,
    /**
     * 店主名。做成参数而不是在这里读 `ServiceLocator`：这一层要能在 JVM 上测，
     * 而 ServiceLocator 需要 Android Context。
     *
     * ⚠ 它决定 fileId，而 fileId 决定统计桶落在哪个文件下。必须是**店主**名，
     * 不是登录名 —— 员工登录时两者不同（见 `OutboxContribution.partsOf` 的警告）。
     */
    private val ownerName: () -> String,
    private val audit: (String) -> Unit = {},
) {

    /** 一次合并的结果，用来给用户一句交代。 */
    data class Report(
        val ordersInserted: Int,
        val ordersSkipped: Int,
        val payloadConflicts: List<String>,
        val productsUpdated: Int,
        val productsSkipped: Int,
        val rejected: Int,
    ) {
        val summary: String
            get() = buildString {
                append("订单：收到 ${ordersInserted + ordersSkipped} 笔，新增 $ordersInserted 笔")
                if (payloadConflicts.isNotEmpty()) append("，⚠ ${payloadConflicts.size} 笔内容不一致")
                append("；商品改动：更新 $productsUpdated 条，跳过 $productsSkipped 条")
                if (rejected > 0) append("；另有 $rejected 条数据格式不对被拒收")
            }
    }

    /**
     * @param bundle 对方发来的内容。
     * @param myStoreKey 本机当前作用域。**与 [HotspotSync.Bundle.storeKey] 不符就整包拒收**：
     *        两家不同的店互相同步会把订单串店，而串店之后那些单会上传到错误的
     *        fileId 下，只有那一台设备看得到，且两边都不报错。
     */
    suspend fun merge(bundle: HotspotSync.Bundle, myStoreKey: String): Report? {
        if (bundle.storeKey != myStoreKey) {
            audit("⚠ 热点同步被拒：对方是店铺 ${bundle.storeKey}，本机是 $myStoreKey。" +
                "不同店铺之间不能互相同步订单，否则那些单会上传到错误的文件下")
            return null
        }

        // ---- 订单 ----
        val rows = ArrayList<com.example.myapplication.db.OrderOutboxEntity>(bundle.orders.size)
        var rejected = 0
        for (dto in bundle.orders) {
            // fallbackDevice 用**发送方**的 deviceId：DTO 里 originDevice 为空说明对方是
            // 没升级的旧版本，那时至少要把它归到"不是本机开的"，否则会走自动上传。
            val row = HotspotSync.toEntity(dto, myStoreKey, bundle.deviceId)
            if (row == null) rejected++ else rows += row
        }
        val adopted = if (rows.isEmpty()) {
            OutboxStore.AdoptReport(0, 0, emptyList())
        } else {
            // ownerName 用本机当前的：它决定 fileId，而 fileId 决定统计桶落在哪个文件下。
            // ⚠ 对方的 ownerName 没传过来也不需要 —— 同一家店的店主名是同一个。
            outbox.adoptForeign(myStoreKey, ownerName(), rows)
        }

        // ---- 商品改动 ----
        var productsUpdated = 0
        var productsSkipped = 0
        for (dto in bundle.products) {
            val incoming = HotspotSync.productToEntity(dto, myStoreKey)
            if (incoming == null) {
                rejected++
                continue
            }
            val local = productDao.pendingChange(myStoreKey, incoming.barcode)
            if (local != null && local.queuedAt >= incoming.queuedAt) {
                // 本地更新（或一样新）→ 保留本地。
                // 一样新时也保留本地：两个相等的 queuedAt 说明是同一笔改动被转了一圈回来，
                // 覆盖它没有意义，而覆盖会让 attemptCount 归零、重新烧三次退避。
                productsSkipped++
                continue
            }
            productDao.upsertPendingChange(incoming)
            productsUpdated++
        }

        if (adopted.payloadConflicts.isNotEmpty()) {
            audit("⚠ 热点同步发现 ${adopted.payloadConflicts.size} 笔订单两边字节不一致：" +
                adopted.payloadConflicts.joinToString() + " —— 已保留本地那份")
        }
        return Report(
            ordersInserted = adopted.inserted,
            ordersSkipped = adopted.skipped,
            payloadConflicts = adopted.payloadConflicts,
            productsUpdated = productsUpdated,
            productsSkipped = productsSkipped,
            rejected = rejected,
        )
    }

    /**
     * 本机要发出去的内容。
     *
     * ⚠ 商品改动发的是 `product_pending_change`（**待推送**的改动），不是整个商品目录。
     * 整目录同步已经有 `DataShareService` 那条路（拷 db 文件 + `DatabaseImporter`），
     * 两条路混在一起会互相覆盖。这里只补"离线改了价、还没推上去"那一小块。
     */
    suspend fun export(deviceId: String, storeKey: String): HotspotSync.Bundle {
        val orders = outbox.exportForPeer(storeKey).map { HotspotSync.fromEntity(it) }
        val products = productDao.pendingChanges(storeKey).map { HotspotSync.productFromEntity(it) }
        return HotspotSync.Bundle(HotspotSync.VERSION, deviceId, storeKey, orders, products)
    }

}
