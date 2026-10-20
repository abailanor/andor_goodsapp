package com.example.myapplication.net.dto

/**
 * 商品相关 DTO。
 *
 * ## 金额在线上一律是**字符串**
 *
 * `ProductData` 的 `price`/`boxPrice` 虽然是 `BigDecimal`，但 getter 上有
 * `@JsonSerialize(using = ToStringSerializer.class)`（源码注释写明是为了不丢尾零：
 * `85.00` 直接序列化成数字会变成 `85.0`）。`ProductDataResponse` 本来就声明成 `String`。
 * 所以**两个接口的 price 都是字符串**，客户端只有一种解析路径。
 *
 * ⚠ 因此本地**绝不能用 Double 中转**（`"3.50".toDouble()` 之后 0.1+0.2 那套问题全回来了）。
 * 一律 `String` → `BigDecimal`，或直接按"分"存 `Long`。
 */

/**
 * `GET /api/products/list`、`GET /api/products/{barcode}`、以及目录 FULL 的元素
 * （服务端 `model/entity/ProductData`）。
 *
 * `updatedAt` **只有这个类有**，商品 FULL 用的 [ProductSnapshotDto] 没有它 ——
 * 这正是 `StockLedger` 不能拿 timestamp 切分吸收边界的原因之一。
 */
class ProductDataDto(
    val barcode: String?,
    val name: String?,
    val price: String?,
    /** null 或空串 = 该商品**不按整箱卖**（不是"整箱价为 0"）。 */
    val boxPrice: String?,
    /** 一箱多少个。**0 = 未设置箱规**，此时整箱输入框必须禁用。 */
    val boxSize: Int?,
    val stock: Int?,
    val orderable: Boolean?,
    val updatedAt: Long?,
)

/**
 * 商品 FULL / INCREMENTAL 里的商品快照（`SyncDataResponse.ProductDataResponse`）。
 *
 * 与 [ProductDataDto] 的差别**只有** `updatedAt`（这里没有）。分成两个类而不是共用一个，
 * 是为了让"这个字段在 FULL 里拿不到"这件事在类型上可见 —— 共用一个可空的 `updatedAt`
 * 会让调用方以为 FULL 之后也有时间戳可用。
 */
class ProductSnapshotDto(
    val barcode: String?,
    val name: String?,
    val price: String?,
    val boxPrice: String?,
    val boxSize: Int?,
    val stock: Int?,
    val orderable: Boolean?,
)

/**
 * `PUT /api/products/price`。
 *
 * `newBoxSize` 是开单页"缺箱规表单"的出口之一：填完并记住 → 走这个接口 →
 * 进价格流 → **全店同步**。需要 `PRICE_CHANGE` 权限，而 B 级员工默认只有
 * `SYNC_SUB + STOCK_CHANGE`（`Permission.defaultsForLevel`），所以他填不了。
 *
 * 金额按字符串发（Jackson 会把 `"3.50"` 正常转成 `BigDecimal`），
 * 避免 Gson 序列化 `BigDecimal` 时可能写出科学计数法。
 */
class PriceChangeRequestDto(
    val barcode: String,
    val newPrice: String?,
    val newBoxPrice: String?,
    val newBoxSize: Int?,
)

/** `PUT /api/products/stock`。`change` **带符号**：正数入库、负数出库。 */
class StockChangeRequestDto(
    val barcode: String,
    val change: Int,
)

/** `PUT /api/products/stock/batch`。注意外层包一层 `changes`。 */
class BatchStockChangeRequestDto(val changes: List<StockChangeRequestDto>)

/**
 * `PUT /api/products/stock/adjust`（**绝对值**盘点校正，不是增量）。
 *
 * ⚠ 这个端点服务端收的是裸 `Map<String,Object>`（`ProductController:82`），字段名是
 * **`correctedStock`** 而不是 `stock`，且校验是 `instanceof Number` ——
 * **必须发 JSON 数字，发字符串会直接 400**。
 * 权限也不同：要 `PRODUCT_MANAGE`，而普通改库存只要 `STOCK_CHANGE`。
 */
class StockAdjustRequestDto(
    val barcode: String,
    val correctedStock: Int,
)

// ==================== 响应里的 `Map<String,Object>`（服务端没有 DTO 类，键名逐个核过源码） ====================

/**
 * `PUT /api/products/stock` 与 `/stock/batch` 的单条结果
 * （`ProductManagementService:102-109`）。
 *
 * [newStock] 是服务器那一刻的**绝对值** → `StockLedger` 用它把该 barcode 的吸收边界
 * 推到当前 `stockVersion`，之前的订单增量自动被基线吸收。
 *
 * [warning] 只在库存为负时出现（`"商品数目存疑（负数）"`）——服务端**容忍**负库存，
 * 所以这不是错误，但要提示用户。
 */
class StockChangeResultDto(
    val barcode: String?,
    val oldStock: Int?,
    val change: Int?,
    val newStock: Int?,
    val stockVersion: Int?,
    val warning: String?,
)

/**
 * `PUT /api/products/stock/adjust` 的结果（`ProductManagementService:237-240`）。
 *
 * ⚠ 绝对值字段名叫 **`correctedStock`**，而 [StockChangeResultDto] 里叫 `newStock`。
 * 同一个概念两个名字，漏掉一个就会把盘点校正当成"没有绝对观测值"，
 * 吸收边界不推进 → 库存双算。
 */
class StockAdjustResultDto(
    val barcode: String?,
    val oldStock: Int?,
    val correctedStock: Int?,
    val stockVersion: Int?,
)

/**
 * `PUT /api/products/price` 的结果（`ProductManagementService:214-221`）。
 *
 * 金额已经是 `toPlainString()` 出来的字符串（不会有科学计数法），可直接落库。
 * [newBoxPrice] 为 null = 该商品不按整箱卖。
 */
class PriceChangeResultDto(
    val barcode: String?,
    val oldPrice: String?,
    val newPrice: String?,
    val newBoxPrice: String?,
    val newBoxSize: Int?,
    val priceVersion: Int?,
)

/** `POST /api/products/batch-upsert` 的单条结果（`ProductManagementService:169-170`）。 */
class BatchUpsertResultDto(
    val barcode: String?,
    val created: Boolean?,
)

/**
 * `POST /api/products/add` 与 `POST /api/products/batch-upsert` 的元素。
 *
 * ## ⚠ `orderable` 必须显式传
 *
 * 服务端 `ProductAddRequest.orderable` 是**原始 `boolean`**（不是 `Boolean`），
 * `stock` 是原始 `int`。JSON 里漏掉 `orderable` → Jackson 留默认值 `false` →
 * **商品被静默改成不可订**，没有任何报错。
 *
 * 所以这个 DTO 把 `orderable` 声明成**非空** `Boolean`，编译期就不可能漏。
 * `batch-upsert` 回推本地商品时尤其危险：那条路径是"把本地数据原样推上去"，
 * 最容易漏字段。
 */
class ProductAddRequestDto(
    val barcode: String,
    val name: String,
    val price: String?,
    val boxPrice: String?,
    val boxSize: Int?,
    val stock: Int,
    val orderable: Boolean,
)

/** `POST /api/products/batch-upsert`。**一次最多 100 条**（`@Size(max=100)`）。 */
class ProductBatchUpsertRequestDto(val products: List<ProductAddRequestDto>) {
    companion object {
        /** 服务端硬上限。回推 400 条本地商品必须自己分批，否则 400。 */
        const val MAX_PER_BATCH = 100
    }
}

/**
 * `POST /api/products/admin-catalog/sync`（向"丽华金龙"目录拉可进货商品）。
 *
 * `adminCode` 由客户端指定，服务端会先 `isAdminShop(adminCode)` 校验，
 * 不是管理员店铺 → **403**。
 */
class AdminCatalogSyncRequestDto(
    val adminCode: String,
    val lastId: String?,
)

/**
 * 目录同步响应。三种 `type` 与订单文件同步**取值一致**（`LATEST`/`INCREMENTAL`/`FULL`）。
 *
 * 服务端返回的是 `Map<String,Object>`，所以 `products` 与 `messages` 是互斥出现的：
 * `FULL` 才有 `products`（`Map<barcode, ProductData>`），`INCREMENTAL` 才有 `messages`。
 * **三种都带 `lastId`**，一律覆盖保存为新游标。
 *
 * ⚠ `messages` 里的值是 `Map<String,Object>`，实际来自 Redis hash 反序列化 →
 * 全是字符串（`action`/`barcode`/`timestamp` + 变更字段）。
 */
class AdminCatalogSyncResponseDto(
    val type: String?,
    val lastId: String?,
    val products: Map<String, ProductDataDto>?,
    val messages: List<Map<String, String>>?,
)

/**
 * 属性流（价格流 / 库存流）条目的**字段名常量**。
 *
 * 值全是字符串（`RedisStreamService` 用 `List<List<String>>` 写、
 * `SyncDataResponse` 声明成 `List<Map<String,String>>`）。
 *
 * ## ⚠ 一律**绝对值覆盖**，绝不做加减
 *
 * `new_stock` / `new_price` / `new_box_price` / `new_box_size` 都是**该商品当前的绝对值**。
 * 拿 `change` 做增量累加会在"漏收一条"之后永久错下去，而且不报错。
 *
 * ## ⚠ 库存流的 `new_stock` 已吸收 B 类订单的扣减
 *
 * `order_write.lua` 第 5 节直接 `HSET product:<code>:<barcode> stock <newStock>`，
 * **不写库存流、不推进 `version_stock`**。所以收到一个新的绝对值时，
 * 此前已应用的那些订单增量有一部分已经被它吸收了 —— 再叠加就是双算。
 * 见 `StockLedger` 的吸收边界模型。
 *
 * ## 两条流的完整字段清单（逐个核过服务端写入点）
 *
 * 一次商品改动通常**同时**产生两条流里各一条（版本号各自独立自增），客户端两条都要应用。
 *
 * | 字段 | 价格流 `stream:price` | 库存流 `stream:stock` |
 * |---|---|---|
 * | `version` / `timestamp` | ✅ Lua 注入 | ✅ Lua 注入 |
 * | `barcode` / `action` | ✅ | ✅ |
 * | `old_price` | ✅ | — |
 * | `new_price` / `new_box_price` / `new_box_size` | ⚠ **仅 `action != DELETE`** | — |
 * | `change` / `new_stock` | — | ✅（DELETE 时由 Java 显式给 `"0"`） |
 *
 * `action` 的取值：价格流 `ADD` / `UPDATE` / `DELETE`（`addPriceEntry` 的三个调用点）；
 * 库存流 `ADD`（新增商品的快照）/ `CORRECT`（绝对值校正、batch-upsert）/ `UPDATE`（增量改库存）
 * / `DELETE`。
 *
 * ## ⚠ 陷阱一：DELETE 的价格条目**没有** `new_price` 三兄弟
 *
 * `addPriceEntry(..., updated = null)`（`RedisStreamService:145`）。
 * 所以处理价格条目必须**先按 `action == DELETE` 分支**，再去读 `new_price`；
 * 反过来的话会把 null 写进一个还活着的商品，价格是"静默变空"而不是报错。
 *
 * ## ⚠ 陷阱二：`new_box_price` 用**空串**表示 null
 *
 * `updated.getBoxPrice() != null ? toPlainString() : ""`（`RedisStreamService:147`）。
 * 空串 = **该商品不按整箱卖**，不是"整箱价 0 元"。`"".toBigDecimal()` 会抛异常，
 * 而 `?: 0` 会把商品变成"整箱白送"。
 *
 * ## ⚠ 陷阱三（协议缺口）：`action=ADD` 的条目里**没有商品名**
 *
 * 两条流都不带 `name`，也不带 `orderable` —— 带这两样的是**目录流**
 * （`AdminCatalogService.recordProductAdded`），而目录流只有管理员店铺才写、
 * 且只有总部版去消费。所以商户端收到"别的设备新增了一个商品"时，
 * 手上只有 barcode / price / boxPrice / boxSize / stock，**建不出一个能显示的商品行**。
 *
 * 补法：`action=ADD` 且本地没有这个 barcode → 拉一次 `GET /api/products/{barcode}`
 * （返回 [ProductDataDto]，带 `name`/`orderable`/`updatedAt`）。
 * 待补的 ADD 太多时（阈值见 `ProductSyncEngine`）改成**强制一次 FULL** ——
 * 400 条商品逐个 GET 不如一个 40KB 的全量。
 *
 * `action=UPDATE` 不补拉：那条路径改的只有 price/boxPrice/boxSize
 * （`updateProductSaleAttrs`），本地已有的 name/orderable 不会被服务端改到。
 * **唯一例外**是 batch-upsert 打在已存在商品上（发 `UPDATE` 但整体覆写，含 orderable），
 * 那条路径只在 SERVER_BEHIND 恢复与首次上线时走，见 `ProductSyncEngine` 的说明。
 *
 * ## 关于引号
 *
 * Lua 侧写的是 `cjson.encode(tostring(v))`，落进 Redis 的字节**带引号**；
 * 读出来时 `ReactiveRedisTemplate` 的 hashValue 用的是 `RedisSerializer.json()`
 * （`RedisConfig:31-37`），Jackson 把引号脱掉 → `recordToFieldsMap` 的 `v.toString()`
 * 得到的是干净字符串。所以客户端**不要**自己再剥引号（剥了反而会在服务端改序列化方式时出错）。
 */
object StreamFields {
    const val BARCODE = "barcode"
    /** 见类注释里的取值表。属性流与目录流共用 `ADD`/`UPDATE`/`DELETE`。 */
    const val ACTION = "action"
    const val ACTION_ADD = "ADD"
    const val ACTION_UPDATE = "UPDATE"
    const val ACTION_DELETE = "DELETE"

    /** 价格流。⚠ `action=DELETE` 时这三个键**不存在**，见类注释陷阱一。 */
    const val NEW_PRICE = "new_price"
    /** 空串 = 不按整箱卖。见类注释陷阱二。 */
    const val NEW_BOX_PRICE = "new_box_price"
    /** `"0"` = 未设箱规，整箱输入框必须禁用。 */
    const val NEW_BOX_SIZE = "new_box_size"
    /** 变更**前**的售价。只用于审计与"张三把可乐从 3.50 改成 4.00"这类界面显示，不参与任何计算。 */
    const val OLD_PRICE = "old_price"

    /** 库存流。绝对值，一律走 `StockLedger.applyAbsolute`。 */
    const val NEW_STOCK = "new_stock"

    /**
     * 库存流。本次变化的差值，**只用于诊断与日志显示**。
     *
     * ⚠ **绝不能拿它算库存**。三个理由：
     *  1. 漏收一条就永久错下去，而且不报错（`new_stock` 是绝对值，漏收会被下一条自动纠正）；
     *  2. 它的语义随 `action` 变（增量时 = delta、校正时 = new-old、快照时 = new），
     *     客户端得先判断 action 才知道该怎么用 —— 而绝对值不需要；
     *  3. B 类订单的扣减**根本不进这条流**（`order_write.lua` 第 5 节直接 HSET），
     *     所以"把 change 累加起来"从第一天起就少算了所有销售。
     */
    const val CHANGE = "change"

    /**
     * 版本号。**注意它是商品版本空间，与订单文件的 per-fileId 版本是两个独立空间，
     * 协议不提供交叉参照。**
     *
     * 这是 [com.example.myapplication.sync.StockLedger.DedupKeys.stream] 唯一能用的去重键：
     * 客户端收不到 Redis 的 entry id（`recordToFieldsMap:109` 只映射 `record.getValue()`），
     * 而 `version` 与 XADD 在同一个 Lua 脚本里原子分配，同一条流内唯一、单调、重投递不变。
     */
    const val VERSION = "version"

    const val TIMESTAMP = "timestamp"
}
