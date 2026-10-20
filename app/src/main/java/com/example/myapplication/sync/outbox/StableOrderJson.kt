package com.example.myapplication.sync.outbox

import com.example.myapplication.core.bytes.ByteOps
import java.io.ByteArrayOutputStream

/** 订单里的一条商品行。 */
data class OrderLine(
    val barcode: String,
    /**
     * 商品名。**必须带**：商品被删之后，镜像文件里的历史订单就只剩一串条码，
     * 统计与历史页都无法还原。服务端只读 `barcode` 与 `quantity`
     * （`OrderFileService.extractItems:1064`），多余字段原样落盘并忽略。
     */
    val name: String,
    /**
     * **一律是个数**，不是箱数。1 箱（箱规 10）+ 4 个 → `quantity = 14`。
     *
     * 这正好对上服务端语义：README §2.3 说 `quantity` 是个数、直接用于扣库存，
     * 所以 items 里**不需要**存箱数。箱/个的拆分只是开单页的录入便利。
     */
    val quantity: Int,
    /** 该条总价，**字符串**（避免 `12.5` / `12.50` 的精度与序列化差异，README §2.3）。 */
    val amount: String,
    /**
     * 仅在该行单价被**手动改过**时才写。没改就不写，省字节。
     * 写了之后 `amount` 才可复算（否则价格一变就无法还原当时的单价）。
     */
    val unitPrice: String? = null,
    /** A 类订单额外带 `productName`（总部版要显示）；B 类为 null。 */
    val productName: String? = null,
)

/** 一张待上传的订单。 */
data class OrderDraft(
    val orderUuid: String,
    /** 写订单的人。统计按它分组。员工用员工名，店主用店主名。 */
    val writer: String,
    val items: List<OrderLine>,
    /**
     * 权威总额，字符串。
     *
     * README §2.3 说的"不记总额"是指**服务器**不算不存；`content` 是任意 JSON、
     * 服务端原样落盘，客户端放总额不违反协议。
     *
     * 读取时若 `Σ items.amount != total`，UI 要打"含手工调整"标记，**不静默取其一**；
     * 统计一律用 `total`（手动覆盖发生在总额层）。
     */
    val total: String,
    /**
     * 客户端 epoch millis。**协议本身没有订单时间字段**，而 FULL 恢复的订单拿不到
     * 流消息里的 `timestamp`，所以必须自带，否则按日/月统计无从下手。
     */
    val ts: Long,
    val status: Int = 1,
    /**
     * 拆单的订单组短 id，**8 位十六进制，绝不复用 orderUuid**。
     *
     * 这是未声明的协议扩展，靠服务端 `parseOrderJson` 的 `default -> skipChildren()`
     * （`OrderFileService:1031-1035`）生效。本地另存 `order_group` 表，不依赖解析。
     */
    val groupId: String? = null,
    /**
     * A 类备注。**必须已经用 [ByteOps.padRemark] padding 到恰好 150 字节。**
     *
     * ⚠ 必须"出生即 150 字节"：补丁是等长覆写，下单时没带 remark 的那一行
     * 之后**无法插入** remark（插入会变长）。「补货清单一键转进货单」这条路径最容易漏。
     */
    val remark: String? = null,
)

/** 序列化结果。字节下标是**payload 内部**的，配合服务端返回的 `uuidOffset` 推算绝对偏移。 */
data class SerializedOrder(
    /** 订单原文的 UTF-8 字节，**不含**末尾 LF。一次算出后不可变。 */
    val payload: ByteArray,
    /** `u`：uuid **值**首字节在 payload 里的下标。 */
    val uuidValueByteIndex: Int,
    /** `r`：remark **值**首字节在 payload 里的下标；无 remark 时为 null。 */
    val remarkValueByteIndex: Int?,
    /** status **数字**在 payload 里的下标。用于与服务端返回的 `statusOffset` 交叉校验。 */
    val statusValueByteIndex: Int,
) {
    val byteSize: Int get() = payload.size

    /** 单行形态：payload + 一个 LF。上传时服务端会自己追加 LF，本地镜像也要加。 */
    fun lineBytes(): ByteArray = payload + ByteOps.LF

    override fun equals(other: Any?): Boolean =
        other is SerializedOrder && payload.contentEquals(other.payload) &&
            uuidValueByteIndex == other.uuidValueByteIndex &&
            remarkValueByteIndex == other.remarkValueByteIndex &&
            statusValueByteIndex == other.statusValueByteIndex

    override fun hashCode(): Int = payload.contentHashCode()
}

/**
 * 订单 JSON 的**手写**序列化器。字节关键路径，必须完全可控。
 *
 * ## 为什么不用 Gson
 *
 * Gson 有三个默认行为会破坏字节可控性，要同时对抗它们不值得：
 *  1. 默认做 **HTML 转义**（`<` `>` `&` `=` `'` → `<!{`），凭空多出字节；
 *  2. 字段顺序依赖反射，跨版本/混淆后不保证；
 *  3. 数字格式（`BigDecimal` 的尾零）不好控制。
 *
 * 而"逐字节一致"是整个补丁机制的前提，所以这里手写。
 * REST DTO 仍然用 Gson——那些不进镜像文件，字节形状无关紧要。
 *
 * ## 它保证的四件事
 *
 *  1. **`order_uuid` 是第一个字段。** 服务端 `normalizeUuidOffset`（`OrderFileService:1122`）
 *     的兜底是 `rawJson.indexOf("\"" + uuid + "\"")`——**第一次出现**。若别的字段值恰好等于
 *     uuid 且排在前面，服务端算出的 `uuidOffset` 就是错的，所有补丁偏移都跟着错。
 *  2. **输出不含任何裸控制字符**（`0x00`–`0x1F`、`0x7F`）。字符串值里的 `"` `\` 转义成
 *     2 字节，控制字符转义成 `\uXXXX` 6 字节，所以商品名里混进换行也不会把订单劈成两行。
 *     （服务端现在会拦并返回 400，但客户端不该依赖服务端兜这个。）
 *  3. **零空白、单行。** 美化缩进的 JSON 是完全合法的 JSON、`parseOrderJson` 不会拦，
 *     但含裸换行，追加进去会把一个订单劈成多行，此后所有订单的定位全部错乱且不可恢复。
 *  4. **返回精确的字节下标**，使 `remarkOffset = uuidOffset + (r - u)`（README §2.5）
 *     可以直接算出来，不需要事后再去文件里搜。
 */
object StableOrderJson {

    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )

    fun serialize(draft: OrderDraft): SerializedOrder {
        require(UUID_REGEX.matches(draft.orderUuid)) {
            "order_uuid 不是合法 UUID 格式（服务端要求 8-4-4-4-12 位十六进制）: ${draft.orderUuid}"
        }
        require(draft.writer.isNotBlank()) { "writer 不能为空（服务端校验）" }
        require(draft.items.isNotEmpty()) { "content.items 不能为空" }
        draft.items.forEach {
            require(it.barcode.isNotBlank()) { "items[].barcode 不能为空（服务端校验）" }
            require(it.quantity > 0) { "items[].quantity 必须是正整数（服务端校验），实际 ${it.quantity}" }
        }
        require(draft.status.toString().length == 1) {
            "status 必须恰为一个数字字符（服务端校验），实际 ${draft.status}"
        }
        draft.groupId?.let {
            require(it.length == 8 && it.all { c -> c in '0'..'9' || c in 'a'..'f' }) {
                "groupId 必须是 8 位十六进制短 id，绝不复用 orderUuid（见 KDoc 第 1 条）: $it"
            }
            require(it != draft.orderUuid) { "groupId 不得等于 orderUuid" }
        }
        draft.remark?.let {
            require(ByteOps.utf8Len(it) == ByteOps.REMARK_WIDTH) {
                "A 类 remark 必须恰好 ${ByteOps.REMARK_WIDTH} 字节，实际 ${ByteOps.utf8Len(it)}。" +
                    "先用 ByteOps.padRemark() 处理。写错的后果是这条备注以后永远改不动。"
            }
        }

        val out = ByteArrayOutputStream()
        fun raw(s: String) = out.write(ByteOps.utf8(s))
        fun str(v: String) {
            raw("\"")
            raw(escape(v))
            raw("\"")
        }

        // 字段顺序固定。order_uuid 必须第一。
        raw("{\"order_uuid\":")
        val uuidValueByteIndex = out.size() + 1   // +1 是即将写入的开引号
        str(draft.orderUuid)
        raw(",\"writer\":")
        str(draft.writer)
        raw(",\"content\":{\"items\":[")
        draft.items.forEachIndexed { i, it ->
            if (i > 0) raw(",")
            raw("{\"barcode\":")
            str(it.barcode)
            raw(",\"name\":")
            str(it.name)
            if (it.productName != null) {
                raw(",\"productName\":")
                str(it.productName)
            }
            raw(",\"quantity\":")
            raw(it.quantity.toString())
            raw(",\"amount\":")
            str(it.amount)
            if (it.unitPrice != null) {
                raw(",\"unitPrice\":")
                str(it.unitPrice)
            }
            raw("}")
        }
        raw("],\"total\":")
        str(draft.total)
        raw(",\"ts\":")
        raw(draft.ts.toString())
        raw("},\"status\":")
        val statusValueByteIndex = out.size()
        raw(draft.status.toString())

        val remarkValueByteIndex: Int?
        if (draft.groupId != null) {
            raw(",\"grp\":")
            str(draft.groupId)
        }
        if (draft.remark != null) {
            raw(",\"remark\":")
            remarkValueByteIndex = out.size() + 1   // +1 是开引号
            str(draft.remark)
        } else {
            remarkValueByteIndex = null
        }
        raw("}")

        val payload = out.toByteArray()

        // ---- 出厂断言：这四条任何一条不成立都说明序列化器坏了，绝不允许上传 ----
        check(!ByteOps.containsControlChar(payload)) {
            "序列化结果含裸控制字符，位置 ${ByteOps.firstControlCharIndex(payload)}。" +
                "上传会被服务端 400 拒绝；更糟的是若被放行，一个订单会被劈成多行，" +
                "该位置之后所有订单的定位全部错乱且不可恢复。"
        }
        check(payload[payload.size - 1] == '}'.code.toByte()) { "序列化结果未正常收尾" }
        val prefix = ByteOps.utf8("{\"order_uuid\":\"")
        check(payload.size >= prefix.size && payload.copyOfRange(0, prefix.size).contentEquals(prefix)) {
            "order_uuid 必须是第一个字段（服务端 normalizeUuidOffset 的兜底搜索依赖它）"
        }
        // 下标自校验：按记录的下标读回来必须正是那个值
        check(readUuidAt(payload, uuidValueByteIndex) == draft.orderUuid) {
            "uuidValueByteIndex 记录错误: $uuidValueByteIndex"
        }
        check(payload[statusValueByteIndex].toInt().toChar().digitToInt() == draft.status) {
            "statusValueByteIndex 记录错误: $statusValueByteIndex"
        }
        if (remarkValueByteIndex != null) {
            val remarkBytes = payload.copyOfRange(
                remarkValueByteIndex, remarkValueByteIndex + ByteOps.REMARK_WIDTH
            )
            check(String(remarkBytes, Charsets.UTF_8) == draft.remark) {
                "remarkValueByteIndex 记录错误: $remarkValueByteIndex"
            }
        }

        return SerializedOrder(payload, uuidValueByteIndex, remarkValueByteIndex, statusValueByteIndex)
    }

    /**
     * 与服务端返回的偏移交叉校验。
     *
     * 服务端用 Jackson 在 **UTF-8 字节数组**上解析（`OrderFileService:974/981`）算出偏移，
     * 再用 `charToByteOffset`(:1157) 换算回字节。我们这里是独立实现。
     * **两边不一致就说明有一方错了**，而且错的一方会让此后所有补丁打到错误字节上——静默损坏。
     *
     * 所以每次上传都要调这个。它很便宜（两次整数比较），而它能抓住的 bug 极贵。
     */
    fun verifyAgainstServer(
        serialized: SerializedOrder,
        serverUuidOffset: Long,
        serverStatusOffset: Long,
    ) {
        val expectedUuid = serverUuidOffset - serialized.uuidValueByteIndex
        val expectedStatus = serverStatusOffset - serialized.statusValueByteIndex
        check(expectedUuid == expectedStatus) {
            "偏移不一致：服务端 uuidOffset=$serverUuidOffset statusOffset=$serverStatusOffset，" +
                "本地 u=${serialized.uuidValueByteIndex} statusIdx=${serialized.statusValueByteIndex}。" +
                "推算出的行起点相差 ${expectedStatus - expectedUuid} 字节。" +
                "极可能是商品名里的多字节字符让某一方的偏移算错了 —— 停下来查，不要继续上传。"
        }
    }

    /** 从 payload 的 `uuidValueByteIndex` 处读回 36 字节的 UUID 文本。 */
    private fun readUuidAt(payload: ByteArray, idx: Int): String =
        String(payload.copyOfRange(idx, idx + 36), Charsets.UTF_8)

    /**
     * JSON 字符串转义。**不做 HTML 转义**（Gson 的默认行为会凭空增加字节）。
     *
     * 非 ASCII 字符原样输出为 UTF-8（不转成 `\uXXXX`）：文件本来就是 UTF-8，
     * 转义会让一个汉字从 3 字节变成 6 字节，白白吃掉 20KB 请求体预算。
     */
    fun escape(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (c in v) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c.code < 0x20 -> sb.append("\\u").append("%04x".format(c.code))
                c.code == 0x7F -> sb.append("\\u007f")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 估算一条 item 序列化后的字节数，供拆单的字节预算用。
     *
     * ⚠ **拆单阈值必须按字节算，不能按行数算。** 20KB 是请求体聚合上限
     * （`spring.codec.max-in-memory-size`），超出返回 413。
     * "每行 86 字节"只是估算：条码 20 位、name 更长、amount 更长时能到 110+。
     */
    fun estimateItemBytes(line: OrderLine): Int {
        var n = ByteOps.utf8Len("{\"barcode\":") + 2 + ByteOps.utf8Len(escape(line.barcode))
        n += ByteOps.utf8Len(",\"name\":") + 2 + ByteOps.utf8Len(escape(line.name))
        n += ByteOps.utf8Len(",\"quantity\":") + line.quantity.toString().length
        n += ByteOps.utf8Len(",\"amount\":") + 2 + ByteOps.utf8Len(escape(line.amount))
        if (line.unitPrice != null) {
            n += ByteOps.utf8Len(",\"unitPrice\":") + 2 + ByteOps.utf8Len(escape(line.unitPrice))
        }
        if (line.productName != null) {
            n += ByteOps.utf8Len(",\"productName\":") + 2 + ByteOps.utf8Len(escape(line.productName))
        }
        return n + 1 + 1   // "}" + 分隔逗号
    }
}
