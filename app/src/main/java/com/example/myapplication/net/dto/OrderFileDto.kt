package com.example.myapplication.net.dto

/**
 * 订单文件协议 DTO。
 *
 * ## ⚠ `/add` 的请求体是**裸 JSON 字符串**，不是 DTO
 *
 * `OrderFileController:50` 签名是 `@RequestBody String body`，年份/月份走**查询参数**
 * （`?type=B&year=2026&month=10`）。服务端把 body **原样**追加进文件
 * （`OrderFileService:256` `payload = (rawJson + "\n").getBytes(UTF_8)` → `:299` `appendBytesBlocking`）。
 *
 * 所以这条路径上**绝不能让序列化库自由发挥**：多一个空格、字段换个顺序、把中文转成 `\uXXXX`，
 * 落盘的字节就和你本地镜像不一样了，而"逐字节一致"是整个协议的前提。
 * 请求体由 `StableOrderJson` 手工生成，本文件里只有**响应**的 DTO。
 */

// ==================== 请求 ====================

/**
 * `POST /api/orderfiles/sync` 的请求体（服务端 `OrderSyncRequest`）。
 *
 * [month] 只有 B 类需要（A 类按年分文件）。[type] 是 `"A"` 或 `"B"`。
 *
 * ## ⚠ 409 探测必须用字面量
 *
 * 收到 409（SERVER_BEHIND）后要**再发一次 `version=0, lastStreamId=""`** 来区分
 * "服务器真落后但文件还在"（→ FULL）与"运维已把文件归档删除"（→ LATEST）。
 * 这两个值是**协议里没有的探测手段**，必须写字面量，不接受参数 ——
 * 传进来的任何 version 都会让探测失去意义。
 */
class OrderSyncRequestDto(
    val type: String,
    val year: Int,
    val month: Int?,
    val version: Int,
    val lastStreamId: String?,
)

/**
 * `POST /api/orderfiles/patch` 的请求体（服务端 `OrderPatchRequest`）。
 *
 * ## 四个硬约束（`OrderFileService:361` / `:383-391`）
 *
 *  1. [action] **只能是 `"CANCEL"`**。改备注也得用它 —— 服务端用户侧只允许这一个值。
 *  2. [patchLength] 必须**恰好等于** [patchContent] 的 **UTF-8 字节长度**（不是字符数）。
 *     补丁是等长覆写，长度对不上直接 400。
 *  3. A 类的 `remark` **必须出生就是 150 字节**。下单时没带 remark 就无法事后插入
 *     （插入会让文件变长，之后所有偏移全部错位）。
 *  4. 库存回退的条件是逐字的：`patchOffset == statusOffset && patchContent == "0"
 *     && patchLength == 1 && 旧字节 == '1'`。少一个条件就不回退库存。
 *
 * 由此推出：**流消息里的 `type` 恒为 `CANCEL`，无法区分"取消订单"与"改备注"**。
 * 客户端只能按偏移三分支判定，见 [OrderStreamFields]。
 */
class OrderPatchRequestDto(
    val type: String,
    val year: Int,
    val month: Int?,
    val action: String,
    /** 该订单 `order_uuid` **值**在文件里的绝对字节偏移（不是行首）。 */
    val uuidOffset: Long,
    val uuid: String,
    val patchOffset: Long,
    val patchLength: Int,
    val patchContent: String,
) {
    companion object {
        /** 服务端唯一接受的 action。 */
        const val ACTION_CANCEL = "CANCEL"

        /** A 类 remark 的固定字节长度。改备注补丁的 `patchLength` 恒等于它。 */
        const val REMARK_BYTE_LEN = 150
    }
}

// ==================== 响应 ====================

/**
 * `POST /api/orderfiles/add` 的 `data`（`OrderFileService:334-341` + `:308`/`:322`）。
 *
 * ## 这是本地镜像**唯一**的偏移来源
 *
 * [uuidOffset] 与 [statusOffset] 由服务端在**它自己落盘的字节**上算出来，
 * 是权威值。客户端必须用它们，而不是用自己推算的值 —— 推算只在 FULL 恢复
 * （流消息里没有偏移）时才不得已为之。
 *
 * `remarkOffset` **不在响应里**。它要从自己上传的 payload 算：
 * `remarkOffset = uuidOffset + (本地 payload 里 remark 值的字节偏移 − uuid 值的字节偏移)`。
 * 这要求 `payloadBytes` **一次写入不可变**（重推时重新序列化会让字节变掉、偏移跟着变）。
 *
 * ## [stockChanges] 只对 B 类出现
 *
 * 它是服务器**那一刻**的绝对库存 → `StockLedger` 用它推进吸收边界。
 *
 * ⚠ **对账规则必须不对称**：本地 `effectiveStock` 可能还含此后别的设备下的单。
 *  - `effectiveStock < newStock` → **告警**（本地重复扣了，或漏吸收了）
 *  - `effectiveStock > newStock` → 正常，不告警
 */
class OrderAddResultDto(
    val fileId: String?,
    val orderUuid: String?,
    val uuidOffset: Long?,
    val statusOffset: Long?,
    val version: Int?,
    /** 本条 ADD 在订单流里的 record id。**它才是增量游标**，不能靠 SSE 回声拿。 */
    val streamId: String?,
    val stockChanges: List<StockChangeEntryDto>?,
)

/** `/add` 响应里 `stockChanges` 的元素（`OrderFileService:859-860`）。 */
class StockChangeEntryDto(
    val barcode: String?,
    val newStock: Int?,
)

/** `POST /api/orderfiles/patch` 的 `data`（`OrderFileService:432-436`）。**没有偏移字段**。 */
class OrderPatchResultDto(
    val fileId: String?,
    val orderUuid: String?,
    val action: String?,
    val version: Int?,
    val streamId: String?,
)

/**
 * `POST /api/orderfiles/sync` 在 **LATEST / INCREMENTAL** 时的 `data`
 * （`OrderSyncService.SyncOutcome.toJsonResponse()`）。
 *
 * ## ⚠ FULL 时这个 DTO **完全不适用**
 *
 * FULL 的响应体是**裸文件字节流**（`Content-Type: application/octet-stream`），
 * 不是 JSON 信封。游标与版本走**响应头**：`X-File-Version` / `X-Stream-Id`。
 *
 * 判定 FULL 的**唯一可靠依据是 `X-File-Version` 头存在**，
 * **不是** Content-Type —— 反代、错误页、甚至 409 的 JSON 都可能带上别的 Content-Type。
 * 头名要**不区分大小写**地取（HTTP/2 下头名一律被强制小写成 `x-file-version`）。
 *
 * ## [messages] 的元素是**嵌套**结构
 *
 * `order_sync.lua:86-90` 逐条编码的是 `{ id = <recordId>, fields = {…} }`，
 * **不是**把字段摊平。所以元素类型是 [OrderStreamMessageDto]，不是 `Map<String,String>`。
 *
 * ⚠ 这个形状踩过一次：早先按 `List<Map<String,String>>` 声明，Gson 解到 `fields`
 * 那个嵌套对象时抛 `JsonSyntaxException: Expected a string but was BEGIN_OBJECT`
 * → 被 `parseData` 包成 `ApiError.MalformedResponse` → **每一次 INCREMENTAL 同步都失败**，
 * 而 LATEST 与 FULL 照常工作，所以现象是"偶尔同步不动"，极难定位。
 */
class OrderSyncJsonResultDto(
    val type: String?,
    val version: Int?,
    val streamId: String?,
    val messages: List<OrderStreamMessageDto>?,
) {
    val kind: SyncKind get() = SyncKind.of(type)
}

/**
 * INCREMENTAL 的一条流消息。
 *
 * [fields] 的值全部来自 Redis hash → **实际都是字符串**（数字也是），字段名见 [OrderStreamFields]。
 *
     * [id] 是这条消息自己的 Redis record id。⚠ **游标不要用它**：`order_sync.lua:16-18`
     * 明确要求客户端一律保存响应顶层的 [OrderSyncJsonResultDto.streamId]（队尾 id），
     * FULL 分支也只有队尾 id 可用（响应体是字节流，没别的地方带游标），两条路径必须一致。
     *
     * 队尾 id 由 `XREVRANGE stream + - COUNT 1`（`:48`）取得，与后面的 XRANGE（`:69`）
     * **在同一个 Lua 脚本里原子执行**，所以它 `>=` 本次返回的每一条 [id]：
     * 存队尾不会漏消息，代价只是可能跳过几条**属于其它 fileId** 的消息 ——
     * 那些消息本来就会被 `:85` 的 `entryFileId == fileId` 过滤掉。
     *
     * ⚠ 反过来，若逐条存 [id]，链在中途崩掉时游标只到"已应用的最后一条"，
     * 下次 XRANGE 还能续上 —— 看着更省，但游标会**永久落后于队尾**
     * （同一条 stream 里混着多个月份，别的月份推进得更快），
     * 于是每次同步都要重扫越来越长的一段。所以一律存队尾。
 */
class OrderStreamMessageDto(
    val id: String?,
    val fields: Map<String, String>?,
)

/**
 * 订单流消息的字段名（`OrderFileService:792-822`，已逐字核对源码）。
 *
 * ## ⚠ `line_start` / `line_length` **不存在**
 *
 * 流消息只给 `uuid_offset`。行首与行长必须自己推：
 * ```
 * lineStart  = uuidOffset − 15          // `{"order_uuid":"` 恰好 15 字节
 * lineLength = utf8Len(order_json) + 1  // +1 是行尾那个 `\n`
 * ```
 * 前一条**要求 `order_uuid` 必须是 payload 的第一个字段** —— 这正是
 * `StableOrderJson` 固定字段顺序、把 `order_uuid` 放最前面的原因。
 * 顺序一变，这个减法就错，而且是**静默**的。
 *
 * ## ⚠ ADD 消息**不幂等**
 *
 * 它带 `uuid_offset`/`status_offset`，重复应用会在镜像里追加两行同一个 order_uuid，
 * 之后所有偏移全部错位。所以幂等守卫（`MirrorCommitter`）是承重墙，不是优化。
 */
object OrderStreamFields {
    const val FILE_ID = "file_id"

    /**
     * 应用这条消息之后文件应达到的版本。
     *
     * ⚠ **它可能等于本地当前版本，不是恒等于 +1**：总部处理订单时若状态已经是目标值
     * （`OrderFileService:471-476` 的 `skipPatch`），服务端**不推进版本号也不写磁盘，
     * 但仍然发一条流消息**，`version` 就是当前值。此时幂等守卫会走
     * `msgVersion <= headerVersion` 那一支，靠"当前字节 == patchContent"判成 ALREADY_APPLIED。
     * 所以守卫的那条分支不是防御性代码，是**正常路径**。
     */
    const val VERSION = "version"

    /**
     * 四个取值（`OrderFileService:67-70`，逐字核对）。⚠ **不止 ADD/CANCEL**：
     *
     * | 值 | 谁发的 | patch 内容 |
     * |---|---|---|
     * | [TYPE_ADD] | `/add` | —（带 `order_json`） |
     * | [TYPE_CANCEL] | `/patch`（用户侧**唯一**允许的 action，`:361`） | `"0"` 或 150 字节备注 |
     * | [TYPE_COMPLETE] | 总部处理 A 类订单（`:498` 用 `streamAction` 原样写入） | `"2"` |
     * | [TYPE_DELETE_COMPLETED] | 总部删除已完成记录 | `"3"` |
     *
     * 所以 [TYPE_CANCEL] **区分不了"取消订单"与"改 A 类备注"**（两者都是它），
     * 只能靠偏移三分支判定，见 `PatchClassifier`。
     *
     * ⚠ 反过来也成立：**别用 `type` 去推断补丁语义**。总部那两种也是 `patchLength=1`
     * 打在 `statusOffset` 上，与取消同形；判据一律是偏移与长度。
     */
    const val TYPE = "type"
    const val TYPE_ADD = "ADD"
    const val TYPE_CANCEL = "CANCEL"
    const val TYPE_COMPLETE = "COMPLETE"
    const val TYPE_DELETE_COMPLETED = "DELETE_COMPLETED"

    const val ORDER_UUID = "order_uuid"
    const val UUID_OFFSET = "uuid_offset"
    const val OPERATOR = "operator"
    const val TIMESTAMP = "timestamp"

    /** 仅 ADD。`order_json` 是**上传时的原文**（`OrderFileService:270` 传的是 `rawJson`，
     *  不含末尾 LF），所以它与镜像里那一行逐字节相同 —— 建索引不必再读文件。 */
    const val STATUS_OFFSET = "status_offset"
    const val ORDER_JSON = "order_json"

    /** 仅补丁类（CANCEL / COMPLETE / DELETE_COMPLETED）。 */
    const val PATCH_OFFSET = "patch_offset"
    const val PATCH_LENGTH = "patch_length"
    const val PATCH_CONTENT = "patch_content"
}

/**
 * 总部版消费流的字段名（`OrderFileService:826-837` 写入、`:460-462` 读取）。
 *
 * 与订单流的差别：多了 [CUSTOMER]，**没有** `version`/`operator`。
 * `id` 是 Redis record id，总部版断线重连后要靠 `GET /api/admin/orders?after=<id>` 续传。
 */
object ConsumeStreamFields {
    const val TYPE = "type"
    const val TYPE_NEW_ORDER = "NEW_ORDER"
    const val ORDER_UUID = "order_uuid"
    const val CUSTOMER = "customer"
    const val FILE_ID = "file_id"
    const val ORDER_JSON = "order_json"
    const val UUID_OFFSET = "uuid_offset"
    const val STATUS_OFFSET = "status_offset"
    const val TIMESTAMP = "timestamp"
}

/**
 * `fileId` 的构造与解析。
 *
 * 格式（`OrderFileService:117-125`，逐字核对）：
 * ```
 * A:<ownerName>:<yyyy>          按年分文件（向丽华金龙进货）
 * B:<ownerName>:<yyyy>-<MM>     按月分文件（卖出），月份**补零到两位**
 * C:<yyyy>-<MM>                 总部版的完成记录，没有 owner 段
 * ```
 *
 * ## 为什么按 `:` 切分是安全的
 *
 * `<ownerName>` 来自 `validateUsername`，正则是
 * `^[^/\\:*?"<>|\p{Cntrl}.]{1,64}$` —— **用户名里不可能有 `:`，也不可能有 `.`**。
 * 所以 A/B 类切出来**恰好 3 段**，C 类恰好 2 段。
 *
 * ⚠ 但 `<ownerName>` **不等于** `merchantCode`（员工操作的是店主的文件）。
 * 这个混淆在 P0 期间真实造成过一次镜像路径错误，所以这里显式把它命名成 `ownerName`。
 */
object FileId {

    const val TYPE_A = "A"
    const val TYPE_B = "B"
    const val TYPE_C = "C"

    /**
     * 月份补零到两位。
     *
     * ⚠ **不要用 `"...%d...".format(...)` 拼 fileId**：`validateUsername` 的正则
     * `^[^/\\:*?"<>|\p{Cntrl}.]{1,64}$` **允许 `%`**，用户名里带一个 `%` 就会让
     * `String.format` 抛 `UnknownFormatConversionException`。全程用字符串模板。
     */
    private fun mm(month: Int) = if (month < 10) "0$month" else month.toString()

    fun ofA(ownerName: String, year: Int) = "$TYPE_A:$ownerName:$year"

    fun ofB(ownerName: String, year: Int, month: Int) = "$TYPE_B:$ownerName:$year-${mm(month)}"

    fun ofCompleted(year: Int, month: Int) = "$TYPE_C:$year-${mm(month)}"

    /**
     * 解析结果。[month] 对 A 类与 C 类的语义不同：A 类**没有月份**（null），
     * C 类有月份但没有 owner。
     */
    data class Parsed(
        val type: String,
        /** A/B 类 = 文件归属店主名；C 类 = null。 */
        val ownerName: String?,
        val year: Int,
        /** A 类 = null（按年分文件）。 */
        val month: Int?,
    )

    /**
     * 解析 fileId。**格式不认识返回 null，不抛** ——
     * 流消息里的 `file_id` 是服务端给的，将来加了新类型时客户端不该崩，
     * 该做的是记日志 + 忽略这条消息。
     */
    fun parse(fileId: String?): Parsed? {
        if (fileId.isNullOrEmpty()) return null
        val parts = fileId.split(":")
        return when {
            parts.size == 3 && parts[0] == TYPE_A ->
                parts[2].toIntOrNull()?.let { Parsed(TYPE_A, parts[1], it, null) }

            parts.size == 3 && parts[0] == TYPE_B -> {
                val ym = parts[2].split("-")
                if (ym.size != 2) return null
                val y = ym[0].toIntOrNull() ?: return null
                val m = ym[1].toIntOrNull() ?: return null
                if (m !in 1..12) return null
                Parsed(TYPE_B, parts[1], y, m)
            }

            parts.size == 2 && parts[0] == TYPE_C -> {
                val ym = parts[1].split("-")
                if (ym.size != 2) return null
                val y = ym[0].toIntOrNull() ?: return null
                val m = ym[1].toIntOrNull() ?: return null
                if (m !in 1..12) return null
                Parsed(TYPE_C, null, y, m)
            }

            else -> null
        }
    }
}
