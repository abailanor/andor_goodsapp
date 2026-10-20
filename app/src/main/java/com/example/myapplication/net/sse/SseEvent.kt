package com.example.myapplication.net.sse

import com.example.myapplication.net.dto.SyncDataResponseDto
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException

/**
 * 服务端 SSE 推来的一条消息。
 *
 * ## ⚠ SSE 是**提示通道**，不是数据通道
 *
 * 除了 [Update] 携带的 `SyncDataResponse`，其余事件都只告诉你"该去拉了"：
 *  - [OrderFileHint] → 自己发 `POST /api/orderfiles/sync`（服务端注释里写明了"不含内容"）；
 *  - [Permissions] → 就地更新缓存；
 *  - [Heartbeat] → 只是活着；
 *  - [Kicked] → 别再连了。
 *
 * 所以**丢一条提示不会丢数据**：重连时 `subscribe` 会带上本地游标，服务端 `reconcileNow`
 * 补发一次；订单文件则由 `SyncScheduler` 的三通道兜住。`SseSupervisor.events`
 * 用 `DROP_OLDEST` 正是建立在这个前提上。
 *
 * ## 五个 type 是从服务端源码里数出来的，不是猜的
 *
 * `MerchantSyncRegistry` 只 emit 这五种：`HEARTBEAT`(:346/:360)、`PERMISSIONS`(:151)、
 * `KICKED`(:201)、`ORDER_FILE`(:261)、`UPDATE`(:366)。
 * `NEW_ORDER` 是**管理员端**的（`OrderFileService:313` → `adminSyncNotifier`），商户端收不到。
 *
 * 服务端将来加一种，客户端落到 [Unknown]：不崩、不猜、记审计。
 */
sealed class SseEvent {

    /**
     * 心跳。**两个版本号都可能是 null。**
     *
     * 三种情形都会产生"裸心跳"：
     *  1. 会话没有 `SYNC_SUB` 权限（`MerchantSyncRegistry.bareHeartbeat()`，建连即发，当存活探针）；
     *  2. 管理员端 `/api/admin/subscribe`（`AdminController:198`，**永远**只有 type）；
     *  3. 将来服务端改形状。
     *
     * 把 `priceVersion` 当非空 Int 解析的话，第 1、2 种情形会在 `asInt` 上抛异常 ——
     * 而异常发生在 SSE 回调里，表现为"连上了就断、断了就重连"的死循环。
     */
    data class Heartbeat(val priceVersion: Int?, val stockVersion: Int?) : SseEvent()

    /**
     * 商品/库存变更。[data] 与登录响应里的 `syncData` **形状完全一样**
     * （同一个 `SyncDataResponse`），所以直接复用 [SyncDataResponseDto] 与 `ProductSyncEngine`。
     *
     * ⚠ `data.type == SERVER_BEHIND` 时里面只有两个版本号，绝不能写进游标
     * （见 [com.example.myapplication.net.dto.SyncKind.SERVER_BEHIND] 的注释）。
     */
    data class Update(val data: SyncDataResponseDto) : SseEvent()

    /**
     * 订单文件变更**提示**。收到后自己发 sync，别指望消息里有内容。
     *
     * @param fileType `A` / `B`，服务端 `OrderFileService.TYPE_A/TYPE_B`。
     * @param version  服务端该文件的新版本号，仅用于日志与"本地落后多少"的显示。
     */
    data class OrderFileHint(val fileId: String, val fileType: String?, val version: Int?) : SseEvent()

    /**
     * 自己的权限被改了。服务端在 emit 之后**已经就地更新**了接收资格
     * （失去 `SYNC_SUB` 的会话立刻停止收 UPDATE），所以客户端也必须立刻更新缓存与 UI，
     * 不能等下次登录。
     */
    data class Permissions(val username: String?, val perms: List<String>) : SseEvent()

    /**
     * 单会话策略：同一账户在别处登录了。
     *
     * ⚠ **收到它之后必须永久停止自动重连与自动登录**，直到用户明确操作。
     * 自动重登会和新登录方互相踢，形成风暴（`MerchantSyncRegistry.kick()` 的注释）。
     * 这是 [SseEvent] 里唯一一个"必须改变连接策略"而不是"触发一次拉取"的事件。
     */
    data class Kicked(val reason: String?) : SseEvent()

    /**
     * 服务端发了一个本客户端不认识的 `type`。**版本偏移，不是错误。**
     * 处理：记审计 + 不动本地数据 + 提示用户升级客户端。
     */
    data class Unknown(val type: String?, val raw: String) : SseEvent()

    /**
     * 数据段不是合法 JSON、或缺了没法继续的字段。
     *
     * 与 [Unknown] 分开是因为两者的含义不同：[Unknown] 是"服务端比客户端新"，
     * 而本类是"链路或实现出了问题"（被反代插入的 HTML、被截断的响应）。
     * 混在一起的话，一个配置错误的 nginx 会表现成"服务端加了新事件类型"。
     */
    data class Malformed(val reason: String, val raw: String) : SseEvent()
}

/**
 * `data:` 段 → [SseEvent]。
 *
 * ## 为什么不用 `@SerializedName` 的 DTO 直接反序列化
 *
 * 五个 type 的**字段集完全不同**，而 Gson 对缺字段一律给 null、对多余字段一律忽略 ——
 * 于是"服务端把 `version` 写成了字符串"这类问题会静默变成一个 null，
 * 而不是一个能看见的错误。手写解析能让每个字段的缺失/类型错误都落到 [Malformed] 并带上原因。
 *
 * 只有 [SseEvent.Update] 的 `data` 用 Gson 整体反序列化：那是个真正的嵌套 DTO，
 * 而且与登录响应共用同一个类，两边形状必须一致，手写反而会写歪。
 */
object SseEventParser {

    private val gson = Gson()

    /** 记进 [SseEvent.Malformed.raw] / [SseEvent.Unknown.raw] 的上限，防止把整个 FULL 商品表写进日志。 */
    const val RAW_SNIPPET_LIMIT = 300

    fun parse(data: String): SseEvent {
        val root = try {
            gson.fromJson(data, JsonObject::class.java)
        } catch (e: JsonSyntaxException) {
            return SseEvent.Malformed("data 段不是合法 JSON", snippet(data))
        }
        // `gson.fromJson("null", JsonObject)` 返回 null；数组/字符串也会抛或返回 null
        if (root == null) return SseEvent.Malformed("data 段是 JSON null 或不是对象", snippet(data))

        return when (val type = root.str("type")) {
            "HEARTBEAT" -> SseEvent.Heartbeat(root.int("priceVersion"), root.int("stockVersion"))
            "KICKED" -> SseEvent.Kicked(root.str("reason"))
            "PERMISSIONS" -> SseEvent.Permissions(root.str("username"), root.stringList("perms"))
            "ORDER_FILE" -> {
                val fileId = root.str("fileId")
                    ?: return SseEvent.Malformed("ORDER_FILE 缺 fileId，无法定位文件", snippet(data))
                SseEvent.OrderFileHint(fileId, root.str("fileType"), root.int("version"))
            }
            "UPDATE" -> {
                val payload = root.get("data")
                if (payload == null || payload.isJsonNull) {
                    return SseEvent.Malformed("UPDATE 缺 data，没有可应用的载荷", snippet(data))
                }
                val dto = try {
                    gson.fromJson(payload, SyncDataResponseDto::class.java)
                } catch (e: JsonSyntaxException) {
                    return SseEvent.Malformed("UPDATE.data 不是合法对象", snippet(data))
                }
                if (dto == null) SseEvent.Malformed("UPDATE.data 是 null", snippet(data))
                else SseEvent.Update(dto)
            }
            else -> SseEvent.Unknown(type, snippet(data))
        }
    }

    private fun snippet(data: String): String =
        if (data.length <= RAW_SNIPPET_LIMIT) data else data.take(RAW_SNIPPET_LIMIT) + "…(${data.length} 字节)"

    // ==================== 防御性取值 ====================
    //
    // ⚠ 一律不用 `JsonElement.asString` / `.asInt` 直接取：它们在元素是 JsonNull 时
    // 抛 UnsupportedOperationException，在类型不符时抛 NumberFormatException。
    // 异常从 SSE 回调里逃出去会直接断连接，而断连接又触发重连 —— 一个字段形状变化
    // 就能让客户端进入无限重连循环，且日志里只有一堆栈。

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.runCatching { asInt }?.getOrNull()

    /** 服务端发的是 `new ArrayList<>(perms)`，元素恒为字符串；不是字符串的那几个丢掉而不是整体失败。 */
    private fun JsonObject.stringList(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { e -> e?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
            ?: emptyList()
}
