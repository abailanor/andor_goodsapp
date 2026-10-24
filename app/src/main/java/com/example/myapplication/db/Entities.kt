package com.example.myapplication.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import java.math.BigDecimal

/**
 * Room v4 的全部实体。
 *
 * ## 三条贯穿所有表的设计约束
 *
 * **1. 每张表的主键第一列都是 `storeKey`。**
 * 文件归属店主、员工操作的是店铺文件（README §4.2），一台设备先后登录两个店铺时，
 * 缺这一维会让 A 店的游标/统计/outbox 落到 B 店，镜像路径也会撞。
 * 离线模式用保留值 [LOCAL_STORE_KEY]。
 *
 * **2. 已同步区与未同步区物理分表，不是加一个标记列。**
 * `product_synced` / `product_local`、`order_index` / `order_outbox`。
 * 这样 FULL 覆盖在**结构上**碰不到未同步数据，不依赖代码记得先检查标记
 * （ANDROID.md §3.1 的硬性要求）。
 *
 * **3. 服务器没有的本地字段单独建表，绝不混进会被 FULL 整表替换的表里。**
 * 见 [ProductExtra]——否则一次 FULL 就会把用户手工填的分类和备注清空，
 * 而且不会报错。
 */

/** 离线模式（还没注册/登录）的保留 storeKey。上线向导会把它"收养"成真实 merchantCode。 */
const val LOCAL_STORE_KEY = "__local__"

// ==================== 店铺与游标 ====================

/**
 * 每个店铺一行。存登录身份、版本号、以及**月份枚举下界**。
 *
 * `enumFloor*` 是"离线先用、后购买服务"这个场景的必需品：
 * `createdAt` 是**审批通过时**服务端写的，而补传的历史订单可能早于它好几年。
 * README §3.2 要求"从 createdAt 所在月份枚举到当前月"，照做的话那些更早的月份
 * **永远不会被同步** —— 服务端会正常创建文件（`ensureFileBlocking` 按需建），
 * 但第二台设备登录时看不到这些订单，两台设备的数据永久分歧。
 *
 * 所以下界必须是 `min(createdAt 所在月, 本地写入过的最早月份, 2020-01)`，
 * 而且**必须持久化**（重装后 createdAt 还在，但"本地最早月份"就丢了）。
 */
@Entity(tableName = "store_meta", primaryKeys = ["storeKey"])
data class StoreMeta(
    val storeKey: String,
    /** 店主用户名。镜像路径 `mirror/<storeKey>/<ownerName>/...` 要用它。 */
    val ownerName: String,
    /** 当前登录用户名（员工登录时 != ownerName）。订单的 `writer` 用它。 */
    val username: String,
    /** `OWNER` / `A` / `B` / `ADMIN`；离线模式为空串。 */
    val level: String,
    /** 店铺成立时间（epoch millis），`0` = 未知（README §4.1 明确允许）。 */
    val createdAt: Long,
    val enumFloorYear: Int,
    val enumFloorMonth: Int,
    val priceVersion: Int,
    val stockVersion: Int,
    /** 上次登录响应的 `currentLoginAt`。下次登录拿 `previousLoginAt` 与它比对， */
    /** 就能发现自己离线期间有没有别人登录过（KICKED 推送覆盖不到的情况）。 */
    val lastLoginAt: Long,
    val previousLoginAt: Long,
    /** `LOCAL_STORE_KEY` 被收养成真实店铺的时刻；`0` = 未收养。 */
    val adoptedAt: Long,
    /** 逗号分隔的权限名。**按名字判断，不要按序号**（枚举将来可能增减）。 */
    val permsCsv: String,
    /** 管理员店铺编号（丽华金龙）。shop flavor 从 BuildConfig 来，可被设置覆盖。 */
    val adminCode: String,
)

// ==================== 商品：已同步区 / 未同步区 ====================

/**
 * 服务器权威的商品镜像。**只有同步协议能写这张表**（FULL 整表替换、INCREMENTAL 逐条覆盖）。
 *
 * `stock` 不是"真实库存"，而是**最后一次服务器绝对观测值**（基线）。
 * 真实库存由 StockLedger 派生：`基线 - 吸收边界之后的订单卖出 + 吸收边界之后的取消回退`。
 * 原因见 `order_write.lua` 第 5 节——B 类订单直接 HSET 商品 hash 且不写库存流，
 * 所以库存流里的 `new_stock` **已经吸收了**此前所有 B 类订单的扣减。
 * 直接维护一个运行中的数字必然双算，而"哪些已被吸收"无法用版本判定
 * （`version_stock` 与 per-fileId 的订单版本是两个独立空间，协议不提供交叉参照）。
 *
 * ⚠ 这张表**不放** `category` / `note`。服务器没有这两个字段，FULL 会整表替换，
 * 放这里等于每次 FULL 都清空用户手工填的分类。见 [ProductExtra]。
 *
 * ⚠ "只有同步协议能写"有一个例外：**离线改已同步商品**时的乐观更新
 * （`ProductDao.queueSyncedEdit`）。那一笔同时排进 [ProductPendingChange]，
 * 所以下一次 FULL 把它抹回服务器值时队列还在，会被再推一次 —— 收敛。
 * 除此之外任何直接写这张表的代码都是 bug。
 */
@Entity(
    tableName = "product_synced",
    primaryKeys = ["storeKey", "barcode"],
    indices = [Index(value = ["storeKey", "name"])],
)
data class ProductSynced(
    val storeKey: String,
    val barcode: String,
    val name: String,
    val price: BigDecimal,
    /** `null` = 不按整箱卖（对应服务端的空串）。**不要**用 0 表示，0 是"整箱免费"。 */
    val boxPrice: BigDecimal?,
    /** `0` = 未设置箱规。 */
    val boxSize: Int,
    val stock: Int,
    val orderable: Boolean,
    val updatedAt: Long,
)

/**
 * 从未推送过的本地商品（手录 / 导入 / 离线期间新建）。
 *
 * FULL **碰不到这张表**，所以"首次登录的商品 FULL 删光 400 条手录商品"在结构上不可能发生。
 *
 * 推送成功（`batch-upsert`）后，服务器会写属性流 `ADD`，增量同步回来落进
 * [ProductSynced]；那时这一行就冗余了，删掉。中间态由"synced 优先"的读语义兜住。
 */
@Entity(
    tableName = "product_local",
    primaryKeys = ["storeKey", "barcode"],
    indices = [Index(value = ["storeKey", "name"])],
)
data class ProductLocal(
    val storeKey: String,
    val barcode: String,
    val name: String,
    val price: BigDecimal,
    val boxPrice: BigDecimal?,
    val boxSize: Int,
    val stock: Int,
    val orderable: Boolean,
    val pushState: String,        // 取值见下面的 companion
    val createdAtLocal: Long,
    val updatedAtLocal: Long,
    val lastPushError: String?,
) {
    companion object {
        /** 还没推过（手录 / 导入 / 离线期间新建）。 */
        const val PUSH_PENDING = "PENDING"

        /** 正在推。**只在同一批推送的两次尝试之间短暂存在**——进程被杀会留在这里，
         *  重启后按"没推过"处理（`batch-upsert` 是幂等的绝对值覆盖，重推无害）。 */
        const val PUSH_PUSHING = "PUSHING"

        /**
         * 推成功了，服务器已经知道这个条码。
         *
         * ⚠ `ProductDao.countPendingPush` 的 SQL 里硬编码了这个字面量，
         * 改这里必须同时改那里 —— Room 的 `@Query` 是编译期常量，引用不到 Kotlin 常量。
         *
         * 这一行此刻已经**冗余**：增量同步回来的属性流 `ADD` 会把它写进 [ProductSynced]，
         * 而合并视图是 synced 优先。`ProductSyncEngine` 在那时把它删掉；
         * 收到服务器侧的 DELETE 时也用它判断"这个本地行是不是服务器那一行的副本"。
         */
        const val PUSH_PUSHED = "PUSHED"

        /** 推失败了，`lastPushError` 里有原因。UI 要显示，并允许手动重试。 */
        const val PUSH_FAILED = "FAILED"
    }
}

/**
 * 已推送商品的**离线改动**队列。
 *
 * 与 [ProductLocal] 分开：那张表放"服务器根本不知道的商品"，这张表放
 * "服务器知道、但我在离线时改了它"。混在一起的话，FULL 覆盖 synced 表之后
 * 就分不清哪些改动还没推上去。
 */
@Entity(tableName = "product_pending_change", primaryKeys = ["storeKey", "barcode"])
data class ProductPendingChange(
    val storeKey: String,
    val barcode: String,
    /** [OP_UPSERT] | [OP_DELETE]。 */
    val op: String,
    val name: String,
    val price: BigDecimal,
    val boxPrice: BigDecimal?,
    val boxSize: Int,
    val stock: Int,
    val orderable: Boolean,
    /** 只用于 UI 排序与"挂起多久了"的显示，**不参与任何判定**。 */
    val queuedAt: Long,
    val attemptCount: Int,
    val lastError: String?,
) {
    companion object {
        /**
         * 走 `POST /api/products/batch-upsert`（需要 `PRODUCT_MANAGE` **且** `PRICE_CHANGE`）。
         *
         * ⚠ 推送时**必须带全部字段**，尤其是 `orderable` ——
         * 服务端 `ProductAddRequest.orderable` 是原始 `boolean`，漏传就是 `false`，
         * 商品被静默改成不可订。见 `ProductAddRequestDto` 的注释。
         */
        const val OP_UPSERT = "UPSERT"

        /**
         * 走 `DELETE /api/products/{barcode}`，**逐条**、且只有店主能成功
         * （`ProductController:53` 是 `isOwner` 门控，不看权限位）。
         * 员工排队的那笔必然 403 → 一直重试到 FAILED，UI 要提前禁用入口。
         */
        const val OP_DELETE = "DELETE"
    }
}

/**
 * 本地扩展字段。**FULL 永远不碰这张表**，所以分类和备注能在同步之间活下来。
 *
 * ⚠ 这两个字段服务端没有，`batch-upsert` 的请求体里也没有它们。
 * 换设备登录同一账号时它们是空的——唯一可靠的携带方式是导入/导出文件
 * （见 `docs/INTERCHANGE_FORMAT.md` §1.2）。这一点必须在 UI 上写清楚。
 */
@Entity(tableName = "product_extra", primaryKeys = ["storeKey", "barcode"])
data class ProductExtra(
    val storeKey: String,
    val barcode: String,
    val category: String,
    val note: String,
)

/**
 * 已删商品的名字，**只增不删**。
 *
 * items 里虽然带了 `name`，但补传的历史订单、以及从 FULL 恢复的旧订单可能没有。
 * 商品被删之后，历史订单页只剩一串条码 —— 这张表让它仍然可读。
 */
@Entity(tableName = "product_tombstone", primaryKeys = ["storeKey", "barcode"])
data class ProductTombstone(
    val storeKey: String,
    val barcode: String,
    val name: String,
    val deletedAt: Long,
)

/**
 * 商品名倒排索引。
 *
 * 分词规则（与现有 `ProductRepository.splitQueryIntoTokens` 逐字一致）：
 * **连续数字算一个词、其他每个字符各算一个词、空格不算词**。
 *
 * 一个意外的好处：数字 token 的 `(?<!\d)N(?!\d)` 边界规则**不需要正则就能实现**。
 * 按此规则分词，「可口可乐330ml」只产出 token `330`；查 `33` 得到 token `33`，
 * 索引里没有 → 不匹配，而正则同样不匹配（33 后面是数字 0）。两者等价，等值查询即可。
 *
 * ⚠ 必须与商品写入在**同一个 `@Transaction`** 里维护，否则索引会漂移。
 * [ProductSynced] 与 [ProductLocal] 都要建索引（读取走 UNION 视图）。
 */
@Entity(
    tableName = "product_token",
    primaryKeys = ["storeKey", "token", "barcode", "pos"],
    indices = [Index(value = ["storeKey", "token"])],
)
data class ProductToken(
    val storeKey: String,
    val token: String,
    val barcode: String,
    /** 该 token 在商品名里的起始**字符**位置（不是字节）。用于"相邻位置加分"。 */
    val pos: Int,
)

// ==================== 订单文件镜像 ====================

/**
 * per-fileId 的游标与自检值。**每个 fileId 各存一份**，不是 per-type：
 * 同一类型的多个月份共用一条 stream（`stream:orderB:alice` 里混着 2026-01、2026-02…），
 * 但游标按 fileId 记才对（README §3.2）。全店共用一个游标会漏数据。
 *
 * `firstSyncedAt` 必须是**三态**：`null` = 从未同步过这个月份；
 * 非 null 且 `version == 0` = 同步过了但那月确实没单。
 * 压成两态的话 UI 会把"还没拉"显示成"这个月 0 笔"，用户以为丢单。
 */
@Entity(tableName = "file_state", primaryKeys = ["storeKey", "fileId"])
data class FileStateEntity(
    val storeKey: String,
    val fileId: String,
    val fileType: String,          // "A" | "B"
    val year: Int,
    val month: Int,                // A 类为 0
    val version: Int,
    val lastStreamId: String?,
    val checksum: Int,
    val mirrorBytes: Long,
    val firstSyncedAt: Long?,
    val syncState: String,         // LIVE | ARCHIVED | DIVERGED
    val lastSyncAttemptAt: Long,
)

/**
 * 订单派生索引。**镜像文件是字节权威，这张表是给 UI 与统计用的**。
 * 历史页、统计、销量聚合全部查这里，**不碰镜像文件**。
 *
 * ⚠ **不存镜像路径**。路径由 `MirrorLocator` 按 `syncState` 解析
 * （LIVE → `mirror/`、ARCHIVED → `mirror-archive/`）。存了的话归档时要改写几千行。
 *
 * `appliedFileVersion` / `cancelAppliedVersion` 是 StockLedger 的吸收边界切分依据，
 * 缺了它们就无法判断"这笔订单的扣减是否已被某个库存绝对值吸收"。
 */
@Entity(
    tableName = "order_index",
    primaryKeys = ["storeKey", "fileId", "orderUuid"],
    indices = [
        Index(value = ["storeKey", "writer", "orderTs"]),
        Index(value = ["storeKey", "fileId", "orderTs"]),
    ],
)
data class OrderIndexEntity(
    val storeKey: String,
    val fileId: String,
    val orderUuid: String,
    val writer: String,
    /** `1` 正常 / `0` 已取消 / `2` 已完成（仅 A 类）/ `3` 已完成后被删除（仅 A 类）。 */
    val status: Int,
    val orderTs: Long,
    /** 分为单位的总额。用 Long 而不是 BigDecimal：统计要大量求和，整数更快也更不易出错。 */
    val totalCents: Long,
    /** `true` 表示总额被手动覆盖过（`Σ items.amount != total`），UI 要打标记。 */
    val manualTotal: Boolean,
    val itemCount: Int,
    val lineStart: Long,
    val uuidOffset: Long,
    val statusOffset: Long,
    val remarkOffset: Long?,
    val remark: String?,
    val appliedFileVersion: Int,
    val cancelAppliedVersion: Int?,
    val groupId: String?,
)

/** 订单行。销量聚合、库存派生、统计都从这里 JOIN [OrderIndexEntity]。 */
@Entity(
    tableName = "order_item_index",
    primaryKeys = ["storeKey", "fileId", "orderUuid", "lineNo"],
    indices = [Index(value = ["storeKey", "barcode"])],
)
data class OrderItemIndexEntity(
    val storeKey: String,
    val fileId: String,
    val orderUuid: String,
    /** 同一订单里同一条码理论上只出现一次，但主键带 lineNo 更稳（服务器不查重）。 */
    val lineNo: Int,
    val barcode: String,
    val name: String,
    val quantity: Int,
    val amountCents: Long,
)

/**
 * 拆单产生的订单组。
 *
 * 20KB 请求体上限下，一笔上百行的销售可能要拆成多个 `order_uuid`。
 * `grp` 字段塞进订单 JSON 的顶层（服务端 `parseOrderJson` 的 `default -> skipChildren()`
 * 会静默跳过未知字段并原样追加），**同时**存这张本地表——只存本地的话换设备就看不出
 * "这 3 行是同一笔"。
 *
 * ⚠ `groupId` 是 8 位十六进制短 id，**绝不复用 orderUuid**：服务端
 * `normalizeUuidOffset` 的兜底是 `indexOf("\""+uuid+"\"")`（第一次出现），
 * 若别的字段值等于 uuid 且排在前面，算出的 uuidOffset 就是错的。
 *
 * ## ⚠ 一个组**永不跨 fileId**，所以这张表不存 fileId
 *
 * `OrderGroupAssembler.Request` 只带一个 `fileType/year/month`，
 * 切出来的每一块都进同一个订单文件 —— 这是结构性的，不是巧合。
 *
 * 于是"列出某个组的成员"永远能写成
 * `order_index WHERE storeKey=? AND fileId=? AND groupId=?`：
 * 调用方总是从某一笔订单出发拿到 groupId 的，那一行自带 fileId，
 * 而 `(storeKey, fileId, orderTs)` 索引的前两列正好把范围收到这个月的行。
 *
 * 这也就是**不给 `order_index.groupId` 单建索引**的理由：那条查询已经有可用的索引，
 * 而 `order_index` 是全库最热的写入表（每来一笔订单就插一行），
 * 为一个已经被 `(storeKey, fileId)` 收窄过的过滤再收一次写放大不值得。
 * 反过来，**任何不带 fileId 的组成员查询都是全表扫描**，写之前先想清楚为什么拿不到 fileId。
 *
 * `orderCount` / `totalCents` 覆盖的是**逻辑订单**（含还在 outbox 里没上传的块），
 * 所以重算时必须同时看 `order_index` 与 `order_outbox`（后者排除 `PROMOTED`，
 * 否则提升进镜像的那一块会被数两遍）。见 `OrderFileDao.orderIndexesOfGroup`。
 */
@Entity(
    tableName = "order_group",
    primaryKeys = ["storeKey", "groupId"],
    indices = [Index(value = ["storeKey", "createdAt"])],
)
data class OrderGroupEntity(
    val storeKey: String,
    val groupId: String,
    val writer: String,
    val orderCount: Int,
    val totalCents: Long,
    val createdAt: Long,
    /** `NONE` | `PARTIAL` | `ALL`。UI 要明示"3 笔中已取消 2 笔"，不能只显示"已取消"。 */
    val cancelState: String,
) {
    companion object {
        /** 一笔都没取消。 */
        const val CANCEL_NONE = "NONE"

        /** 取消了一部分。**这一态必须存在**：UI 只显示"已取消"的话，
         *  用户看不出那张 34KB 的进货单其实是"3 块里只撤了 1 块"，
         *  而剩下 2 块在服务器上是活的、会继续扣库存。 */
        const val CANCEL_PARTIAL = "PARTIAL"

        /** 全部取消。 */
        const val CANCEL_ALL = "ALL"
    }
}

/**
 * 未上传订单（outbox）。**不在镜像文件里**，所以没有偏移，用客户端预生成的 uuid 做键。
 *
 * ⚠ `payload` 一次写入**不可变**——DAO 里没有任何 UPDATE 它的方法。
 * 重推时重新序列化会让字节变掉 → 偏移变掉 → `remarkOffset` 全错。
 *
 * ⚠ `state=IN_FLIGHT` 是 write-ahead 标记：**发请求之前**就置位并落盘。
 * 重启后看到 IN_FLIGHT **不许直接重推**，要先同步该 fileId、在 [OrderIndexEntity] 里
 * 找自己的 uuid：找到 → 当成功并补录偏移；没找到 → `attemptCount++` 重推；
 * ≥3 次 → FAILED_PERMANENT 交人工。
 * 少了这一步，"/add 超时但服务器其实已提交"会导致同一个 uuid 在服务器文件里出现两次
 * （服务端不查重），镜像比服务器长，此后所有偏移静默错位。
 *
 * ## ⚠ "用新价重算"是**换一行**，不是改这一行
 *
 * 上传前发现价格漂移（`PriceDrift` 检测）、用户选了"用新价重算"，payload 就必须变。
 * 而 payload 不可变。两条规则的交点只有一个走法：**新 uuid + 新 payload 插一行，
 * 旧行在同一事务里删掉** —— 那就是 `OutboxStore.enqueue(replacing = 旧uuid)`。
 *
 * ## ⚠ 不要再加一个 `OutboxStore.reprice`
 *
 * 早先的注释指向过这么一个方法，但它**不该存在**：重算需要知道每行是"几箱 + 几个"
 * （整箱价 ≠ 箱规 × 单价，只给个数与新单价推不出新金额），而箱/个拆分**故意没有存进
 * payload**。所以新 draft 只能由还持有购物车的开单页给出，而"删旧插新 + 减旧贡献 +
 * 重算受影响的组"这套事务 `enqueue(replacing=…)` 已经做完了。
 * 再加一个入口只会有**两处**能搬 `OUTBOX` 桶，而它们各自的幂等判据不同 ——
 * 结果要么双算要么漏算，两种都静默。
 *
 * 不这么做的话，就得给 DAO 开一个 UPDATE payload 的口子 —— 那个口子一旦存在，
 * 就无法再阻止有人在 `IN_FLIGHT` 之后调它，而后果是"发出去的字节与库里存的字节不一致"：
 * 偏移全部作废，且没有任何机制会报错。删旧插新则天然做不到这件事。
 */
@Entity(
    tableName = "order_outbox",
    primaryKeys = ["storeKey", "orderUuid"],
    indices = [Index(value = ["storeKey", "state", "createdAt"])],
)
data class OrderOutboxEntity(
    val storeKey: String,
    val orderUuid: String,
    val groupId: String,
    val fileType: String,
    val year: Int,
    val month: Int,
    val payload: ByteArray,
    /** 取值见下面的 companion。 */
    val state: String,
    val blockedReason: String?,
    /** 缺箱规的商品条码，逗号分隔。UI 直接显示"这几个商品要补规格"。 */
    val blockedBarcodes: String?,
    val attemptCount: Int,
    val lastError: String?,
    /** 挂起时算价的时刻。上传前据此比对当前价，有变动就提示用户。 */
    val pricedAt: Long,
    val createdAt: Long,
    val writer: String,
    val totalCents: Long,
    val itemCount: Int,
    val orderTs: Long,
    /**
     * 创建这一行的**设备 id**（`ServiceLocator.deviceId`）。
     *
     * ## 为什么需要它
     *
     * 离线时两台设备通过热点互相同步未上传的订单，同步之后双方手上都有同一批 uuid。
     * 上线时若两边都自动上传：服务端 `order_write.lua` 里**没有任何 uuid 去重**，
     * 重复上传返回 200、追加为新行、**库存再扣一次**，而镜像比服务器长、
     * 此后所有偏移静默错位 —— 等长覆写不报错、文件长度不变、校验和自洽，
     * **没有任何机制会发现**。
     *
     * 有了出处就能定一条简单规则：**只自动上传本机创建的单**（`uploadReady` 按它过滤），
     * 别的设备传来的单在「订单」页单独分组、只有用户明确勾选时才上传（接管）。
     * 正常情况下创建者自己会上传它；接管只用于"那台设备再也不会开机了"。
     *
     * ## ⚠ 空串 = 本机创建（迁移前的老行）
     *
     * v5 之前没有这一列，迁移时全部填空串。空串按"本机"处理是安全的：
     * 那些行本来就是这台设备自己开的，从来没有被别人同步走过。
     */
    @ColumnInfo(defaultValue = "")
    val originDevice: String = "",
) {
    companion object {
        /**
         * 结算框还没点"确定上传"，或者箱规表单还开着。
         *
         * ⚠ **DRAFT 不贡献统计**：它连"这一单成交了吗"都还没确定（用户可能直接关掉对话框）。
         * 算进去的话，开单页每加一行都会让"今日营业额"跳一下。
         * `OrderFileDao` 里所有与贡献相关的 SQL 都硬编码排除了这个字面量。
         */
        const val DRAFT = "DRAFT"

        /**
         * 有 `箱数 > 0 且 boxSize == 0` 的条目（方案 §5.1 第 3 步的"暂不上传"出口）。
         *
         * [blockedBarcodes] 列出缺规格的商品，补齐才允许上传。
         *
         * ⚠ 填箱规需要 `PRICE_CHANGE` 权限，而 B 级员工默认只有 `SYNC_SUB + STOCK_CHANGE`
         * （服务端 `Permission.defaultsForLevel`）—— 他填不了，只能挂起等店主。
         * UI 必须把这一点写出来，否则员工会当成 bug 报。
         */
        const val BLOCKED_MISSING_BOX_SIZE = "BLOCKED_MISSING_BOX_SIZE"

        /** 可以上传。**只有这个状态能起飞**（`OrderFileDao.markOutboxInFlight` 的 WHERE 里钉死了）。 */
        const val READY = "READY"

        /**
         * 已经发出 `/add`、结果未知。write-ahead 标记，见类注释。
         *
         * ⚠ 重启后看到它**不许直接重推**。`OutboxUploader.recoverInFlight` 会先同步该 fileId、
         * 在 `order_index` 里找自己的 uuid。
         */
        const val IN_FLIGHT = "IN_FLIGHT"

        /**
         * 服务器已收下，偏移已补录，贡献已从 `OUTBOX` 桶搬到 `FILE:<fileId>` 桶。
         *
         * 行**保留不删**：它是"这个 uuid 已经上传过了"的唯一凭据。
         * 删掉的话，重启后 `recoverInFlight` 找不到它，会把已经成功的单再推一次 →
         * 服务器文件里同一个 uuid 出现两行 → 镜像比服务器长 → 此后所有偏移静默错位。
         */
        const val PROMOTED = "PROMOTED"

        /**
         * 重推 3 次仍失败，或者拿到了 413/400 这类**重推也不会有不同结果**的错误。
         * 交人工。UI 要显示 [lastError] 并允许"删掉这一单"（唯一的出路）。
         */
        const val FAILED_PERMANENT = "FAILED_PERMANENT"

        /** 重推上限。超过就转 [FAILED_PERMANENT]，不再烧流量与 `attemptCount`。 */
        const val MAX_ATTEMPTS = 3
    }

    override fun equals(other: Any?): Boolean =
        other is OrderOutboxEntity && storeKey == other.storeKey && orderUuid == other.orderUuid &&
            state == other.state && attemptCount == other.attemptCount &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = orderUuid.hashCode()
}

// ==================== 统计 ====================

/**
 * 统计的**可重算贡献行**。
 *
 * 分 `scope` 是因为未上传订单也要算进"今日开单金额"（不算的话用户开完单看统计没变，
 * 以为丢单），但它的贡献不属于任何 fileId、FULL 覆盖不到 —— 必须单独一个桶。
 * 订单从 outbox 提升进镜像时，要在**同一个事务**里把贡献从 `OUTBOX` 搬到 `FILE:<fileId>`。
 *
 * `frozen=1` 用于归档月份：归档只是"不再同步"，**不是"不再统计"**。
 * FULL 重投影路径必须 `WHERE frozen=0`，否则一次误解档会把历史统计清零。
 */
@Entity(tableName = "stats_contrib", primaryKeys = ["storeKey", "scope", "dimKey", "metric"])
data class StatsContribEntity(
    val storeKey: String,
    /** `"FILE:<fileId>"` | `"OUTBOX"`。构造与解析只走 [com.example.myapplication.sync.StatsProjection]。 */
    val scope: String,
    /**
     * 日粒度的维度键，形如 `"2026-09-15|w:alice"`（**日期在前**，才能用范围查询走上主键索引）。
     * 月/年/自定义区间由它聚合出来。构造与 SQL 提取表达式只走
     * [com.example.myapplication.sync.StatsProjection]，**不要在这里手拼**。
     */
    val dimKey: String,
    /** `"amountCents"` | `"orderCount"` | `"pieceCount"`（件数 = Σquantity，**不是**行数）。 */
    val metric: String,
    val value: Long,
    val frozen: Int,
)

/**
 * 统计聚合缓存。**必须恒等于 [StatsContribEntity] 的聚合**——诊断页有一条常驻自检 SQL，
 * 返回任何一行都是 bug。
 */
@Entity(tableName = "stats_agg", primaryKeys = ["storeKey", "dimKey", "metric"])
data class StatsAggEntity(
    val storeKey: String,
    val dimKey: String,
    val metric: String,
    val value: Long,
    val updatedAt: Long,
)

// ==================== 库存 ====================

/**
 * 库存吸收边界：**per-(barcode, fileId)** 记"这个条码在这个文件里的订单增量，
 * 算到哪个版本为止已经被它的绝对基线吸收了"。
 *
 * 每次拿到某个条码的绝对库存观测（库存流 `new_stock` / stock 接口响应 / 商品 FULL /
 * batch-upsert 回推），就把**那个条码**在所有 fileId 上的边界推到当时的 `file_state.version`。
 *
 * ## ⚠ 边界必须带 barcode 维度，只按 fileId 会算出偏**高**的库存
 *
 * 直觉写法是 `primaryKeys = ["storeKey","fileId"]`（"边界是文件的属性"）。那是错的，
 * 而且错在**不安全的那一侧**。反例：
 *
 * ```
 * FULL：baseline(X)=100, baseline(Y)=100，边界=0
 * 同步到一笔 3×Y 的订单（appliedFileVersion=1）→ effective(Y)=100-3=97 ✓
 * 有人显式把 X 的库存改成 50 → applyAbsolute(X,50)
 *   若边界只按 fileId：X 的观测把边界推到 1 → Y 那笔 appliedFileVersion=1 的扣减
 *   不再满足 `> 边界` → effective(Y)=100，而真值是 97
 * ```
 *
 * Y 的基线还是 FULL 时那份（不含那笔销售），可它的扣减被 X 的观测"吸收"掉了。
 * **偏高比偏低危险**：偏低会在补货清单上被看见（顶多多订一次货），
 * 偏高不会——要等到卖断货才发现。
 *
 * 根因是"边界"的语义本来就是**每个条码各自的**：它表示"这个条码的基线观测发生在
 * 哪个文件版本上"。X 与 Y 的基线来自不同时刻的观测，共用一个边界等于假设
 * "所有条码的基线总是同时刷新"，而那只在 FULL 时成立。
 *
 * ## 行数与成本
 *
 * 一次观测写 `|file_state|` 行（≈ 使用年数 × 12 + 年份数），商品 FULL 写
 * `|商品数| × |file_state|` 行（400 商品 × 26 文件 ≈ 1 万行）。
 * FULL 罕见且整批在一个事务里，实测代价可接受；换来的是**结构上不可能**出现上面那个反例。
 * （曾经考虑过"全局边界 + per-barcode 覆盖，FULL 时清空覆盖表"来省掉那一万行，
 * 但那要依赖"清空与推进必须同时发生"这个约定 —— 忘了清空就是偏高。
 * 现在这版没有需要记住的约定。）
 */
@Entity(tableName = "stock_absorb_boundary", primaryKeys = ["storeKey", "fileId", "barcode"])
data class StockAbsorbBoundaryEntity(
    val storeKey: String,
    val fileId: String,
    /** 这一行属于哪个条码。见类注释里的反例。 */
    val barcode: String,
    val boundaryVersion: Int,
    val updatedAt: Long,
)

/**
 * 绝对库存观测的去重日志。主键 `(storeKey, dedupKey)` UNIQUE，
 * 重复应用同一条目会冲突 → 捕获后跳过。这是"不双算"的第二道防线。
 */
@Entity(tableName = "stock_apply_log", primaryKeys = ["storeKey", "dedupKey"])
data class StockApplyLogEntity(
    val storeKey: String,
    val dedupKey: String,
    val barcode: String,
    val newStock: Int,
    val source: String,     // STOCK_STREAM | STOCK_API | PRODUCT_FULL | BATCH_UPSERT
    val appliedAt: Long,
)

// ==================== 补货 ====================

/** 补货清单条目（可持久化，用户手动加 + 自动规则加）。 */
@Entity(tableName = "restock_item", primaryKeys = ["storeKey", "barcode"])
data class RestockItemEntity(
    val storeKey: String,
    val barcode: String,
    val addedAt: Long,
    val source: String,     // MANUAL | RULE
    val checked: Boolean,
    val note: String?,
)

/** 自动入清单规则：某几个商品（或全部）库存低于阈值就自动加入。 */
@Entity(tableName = "restock_rule", primaryKeys = ["storeKey", "ruleId"])
data class RestockRuleEntity(
    val storeKey: String,
    val ruleId: String,
    /** 逗号分隔的条码；空串 = 适用于全部商品。 */
    val barcodesCsv: String,
    val threshold: Int,
    val enabled: Boolean,
    val lastRunAt: Long,
)

/**
 * 筛选条件树，序列化成 JSON 持久化，**记住上次用的那份**。
 *
 * 树形：`{"op":"AND","children":[{"field":"stock","cmp":"<","value":10},
 * {"op":"OR","children":[...]}]}`
 */
@Entity(
    tableName = "restock_filter",
    primaryKeys = ["storeKey", "filterId"],
    indices = [Index(value = ["storeKey", "isLastUsed"])],
)
data class RestockFilterEntity(
    val storeKey: String,
    val filterId: String,
    val name: String,
    val treeJson: String,
    val isLastUsed: Boolean,
    val updatedAt: Long,
)

/** 各页面的排序偏好。`screen` 例如 `"restock"` / `"history"` / `"product_list"`。 */
@Entity(tableName = "sort_pref", primaryKeys = ["storeKey", "screen"])
data class SortPrefEntity(
    val storeKey: String,
    val screen: String,
    val field: String,
    val ascending: Boolean,
)

// ==================== 管理员商品目录（丽华金龙） ====================

/**
 * 总部（管理员店铺）的商品目录。进货表据此标出"哪些商品可以直接向丽华金龙购买"。
 *
 * 打开补货页时**后台异步**拉取，不阻塞 UI。
 */
@Entity(tableName = "admin_catalog", primaryKeys = ["storeKey", "barcode"])
data class AdminCatalogItemEntity(
    val storeKey: String,
    val barcode: String,
    val name: String,
    val price: BigDecimal,
    val boxPrice: BigDecimal?,
    val boxSize: Int,
    val stock: Int,
    val orderable: Boolean,
)

/** 目录同步游标。`lastId` 原样带回下次请求。 */
@Entity(tableName = "admin_catalog_cursor", primaryKeys = ["storeKey"])
data class AdminCatalogCursorEntity(
    val storeKey: String,
    val adminCode: String,
    val lastId: String,
    val updatedAt: Long,
)
