package com.example.myapplication.net.dto

/**
 * 商品/库存同步载荷（服务端 `model/dto/SyncDataResponse`）。
 *
 * 它出现在**两个地方**，形状完全一样：
 *  1. 登录响应的 `syncData` 字段（前提是登录请求带了 `priceVersion`/`stockVersion`）；
 *  2. SSE 建连后的一次性补发（`reconcileNow`）。
 *
 * ⚠ **没有 `GET /api/sync/data` 这个接口**（`SyncController` 只有 `/subscribe`）。
 * 想要一份"当前全量商品"只能走 `GET /api/products/list`，那是 [ProductDataDto] 的数组，
 * **不是**这个信封。别把两者混为一谈。
 */
class SyncDataResponseDto(
    /** `FULL` / `INCREMENTAL` / `LATEST` / `SERVER_BEHIND`，见 [SyncKind]。 */
    val type: String?,
    /**
     * 服务端**当前**的价格版本号。
     *
     * ⚠ 它是"服务端此刻的版本"，**不是**"我返回给你的这些条目里最大的版本"。
     * 所以客户端存下游标前必须确认返回的条目真的覆盖到了这个版本 ——
     * 服务端曾经在这里出过静默丢条目的 bug（`XRANGE COUNT` 返回最旧的 N 条，
     * 而 `XADD MAXLEN ~` 保留的条目数可以多于 N），现已修掉。
     * 客户端仍然要做自我保护：INCREMENTAL 落地后若发现条目版本 < priceVersion，
     * 说明中间有空洞，**必须强制 FULL**，不能把游标推到 priceVersion。
     */
    val priceVersion: Int?,
    val stockVersion: Int?,
    /** 仅 INCREMENTAL。值是字符串，字段名见 [StreamFields]。 */
    val stockStreamEntries: List<Map<String, String>>?,
    val priceStreamEntries: List<Map<String, String>>?,
    /** 仅 FULL。键是 barcode。 */
    val products: Map<String, ProductSnapshotDto>?,
) {
    val kind: SyncKind get() = SyncKind.of(type)
}

/**
 * 同步类型。商品同步、订单文件同步、目录同步三个接口**大致**共用一组取值，
 * 所以提成一个枚举 —— 但注意 [SERVER_BEHIND] 的两处例外，见它自己的注释。
 *
 * ⚠ [UNKNOWN] 必须有：服务端将来加一种类型时，客户端不能崩、也不能默认当成 FULL
 * （FULL 会**覆盖**本地数据，误判一次就是不可逆的丢失）。
 * UNKNOWN 的正确处理是：记录 + 不动本地数据 + 提示升级客户端。
 */
enum class SyncKind {
    LATEST,
    INCREMENTAL,
    FULL,

    /**
     * **服务器落后于客户端**（服务器丢过数据，典型是 Redis 被清空后重建）。
     *
     * 触发条件是 `RedisSyncService.buildSyncResponse:41` 的
     * `clientPriceVersion > serverPriceVersion || clientStockVersion > serverStockVersion`
     * —— 任一条成立就返回它，此时响应里**只有两个版本号**，
     * `products` 与两个 entry 列表全是 null。
     *
     * ## ⚠ 绝不能顺手把响应的版本号写进游标
     *
     * 那是这个分支唯一的陷阱，而且触发条件是"照抄 INCREMENTAL 的收尾代码"：
     * 响应的 `priceVersion` 是**服务器那个更小的值**，写进 `store_meta` 就等于把游标
     * **往回拨** → 下一次同步必然走 FULL → 用服务器那份更短的数据覆盖客户端唯一那份
     * 更完整的副本。而这正是服务端注释里明确写了"**不能直接 FULL**"要防的事。
     *
     * ## 正确处理：交给用户二选一
     *
     *  | 选择 | 动作 |
     *  |---|---|
     *  | 接受服务器版本 | 把游标**降**到响应值（于是下次 FULL），并告知用户本地超前的改动会丢 |
     *  | 把本地推上去 | `POST /api/products/batch-upsert`（每批 ≤100），推完再对游标 |
     *
     * 两个动作都必须**显式**发生，不能自动挑一个 —— 挑错的代价是不可逆的商品丢失。
     *
     * ## ⚠ 两处例外：不是所有接口都用这个字符串
     *
     * 订单文件同步里同名状态（`OrderSyncService.SyncOutcome.Kind.SERVER_BEHIND`）
     * **不走 type 字段，走 HTTP 409**（`OrderFileController:111-115`），
     * 由 `OrderFileSyncEngine.resolve409()` 处理，与商品这边完全两套逻辑。
     * 目录同步（`admin-catalog/sync`）目前只有 `LATEST`/`INCREMENTAL`/`FULL`。
     */
    SERVER_BEHIND,

    UNKNOWN;

    companion object {
        fun of(type: String?): SyncKind = when (type) {
            "LATEST" -> LATEST
            "INCREMENTAL" -> INCREMENTAL
            "FULL" -> FULL
            "SERVER_BEHIND" -> SERVER_BEHIND
            else -> UNKNOWN
        }
    }
}

// 服务端还有个 `SyncInitRequest{priceVersion, stockVersion}`，但**全项目没有任何端点用它**
// （已 grep 核实），是死代码。所以客户端不建模它 —— 想带游标只能塞进登录请求体。
