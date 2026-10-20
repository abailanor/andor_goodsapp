package com.example.myapplication.sync.outbox

import com.example.myapplication.core.bytes.ByteOps
import com.example.myapplication.core.money.Money
import com.example.myapplication.sync.OrderFileId
import java.util.UUID
import kotlin.random.Random

/**
 * 把一张**逻辑订单**切成若干张**服务器订单**（方案 §3.8）。
 *
 * ## 为什么必须切
 *
 * 服务端 `spring.codec.max-in-memory-size=20KB`（`application.properties:24`），而 `/add` 的
 * body 就是**裸订单 JSON**——`type`/`year`/`month` 走 query 参数，没有外层包装、
 * 也没有"把 payload 当字符串字段再转义一遍"这回事。所以那 20480 字节是 payload 本身的
 * 硬上限，超了返回 413。
 *
 * ⚠ **413 的正确反应是拆单，不是重试**：同样大小的 body 重发只会再拿一个 413，
 * 而 outbox 的 `attemptCount` 会被白烧三次然后进 FAILED_PERMANENT。
 *
 * 最容易撞上去的是「补货清单一键转进货单」：400 个商品一行一个，
 * 按方案 R6 的实测标定（13 位条码 + 17 字节中文名 = 87 字节/行）就是 34KB。
 *
 * ## 切分只按**字节**，不按行数
 *
 * 行数是个骗人的度量。`{"barcode":"6901234567890","name":"农夫山泉550ml","quantity":14,"amount":"52.00"}`
 * 是 87 字节，而条码 20 位、名字更长、amount 上万时同一行能到 110+ 字节。
 * 按"180 行"切的话，同样行数下有的 payload 15KB、有的 21KB —— 后者直接 413，
 * 而且是在用户点了"上传"、outbox 行已经 IN_FLIGHT 之后才炸。
 *
 * 所以这里**先按 [StableOrderJson.estimateItemBytes] 贪心装箱，再真序列化一次核对**，
 * 超预算就退一行重来。估算只影响性能（多序列化一两次），正确性由
 * "序列化后的真实字节数 ≤ [BYTE_BUDGET]"这道闸保证。
 *
 * ## 不设行数上限（与方案措辞的有意偏离）
 *
 * 方案写的是"行数只做次要上限"，这里**没有实现**它：[BYTE_BUDGET] 已经把一块限制在
 * 约 200 行以内，而 `MirrorLineScanner.MAX_LINE_BYTES`（256KB）是这个预算的 14 倍——
 * 再加一道行数闸拦不住任何字节闸拦不住的东西，只会多一个要向后世解释的常数。
 *
 * ## 手工覆盖的总额整笔落在**最后一块**
 *
 * 结算框允许用户直接改总额（方案 §5.1 第 2 步），于是 `Σ items.amount ≠ total`。
 * 拆单时每块的 `total` 只能是自己那些行的和，差额（可正可负）整笔放到最后一块：
 *  - Σ 各块 total **恒等于**用户填的那个数，统计不会因为一单被拆开而变；
 *  - 只有最后一块会被读成"含手工调整"（`OrderPayloadIndex.manualTotal`），
 *    前面的块仍然干净（`Σ == total`）。
 *
 * 反过来把差额摊到每块的话，每块都会被打上"含手工调整"标记，
 * 而摊出来的数字对不上任何一个真实单价——**编账比少账更难查**。
 *
 * ## `grp` 只在真的拆了的时候才写
 *
 * 单块订单不写 `grp` 字段：省 20 字节，也让"这一单是不是被拆过"在镜像里一眼可辨。
 * 代价是 `order_group` 表里**没有**单块订单的行（`OrderOutboxEntity.groupId` 存空串），
 * UI 判分组时要把空串当"自成一组"。
 */
object OrderGroupAssembler {

    /** 服务端 `spring.codec.max-in-memory-size`。超了这个数拿 413。 */
    const val HARD_LIMIT_BYTES = 20 * 1024

    /**
     * 拆单预算，比 [HARD_LIMIT_BYTES] 小 2480 字节。
     *
     * 这 2KB 余量是给"估算与真实字节数的差"留的，不是给外层包装留的（没有外层包装，
     * 见类注释）。方案 R6 的实测标定：200 行 = 17338 字节，正好落在这个预算里。
     */
    const val BYTE_BUDGET = 18000

    /**
     * 给 `total` 字符串长度变化留的余量。
     *
     * 第一遍装箱时用的是"本块行金额之和"，第二遍最后一块要再加上手工差额，
     * 数字变长会让 payload 跟着长几个字节（`"52.00"` → `"1052.00"`）。
     * 第一遍就按 [BYTE_BUDGET] − 这个余量装，第二遍才不会撑破预算。
     *
     * 24 字节足够：`Long` 分级金额最长也就 `"-922337203685477.58"` = 20 字符。
     */
    private const val TOTAL_SLACK = 24

    /**
     * 探路用的假 uuid。**长度恒为 36**，所以用它量出来的字节数与任何真 uuid 完全一样。
     *
     * 装箱阶段要序列化几十次，每次都 `UUID.randomUUID()` 纯属浪费；
     * 更重要的是这让装箱结果**与 uuidFactory 无关**，同一份输入永远切出同样的行分布
     * （测试才能断言"切成 3 块，第一块 120 行"这种结构性结论）。
     */
    private const val PROBE_UUID = "00000000-0000-0000-0000-000000000000"

    /** 探路用的假 grp。同样是长度恒定（8 字节十六进制）。 */
    private const val PROBE_GROUP = "00000000"

    /** 一张待切的逻辑订单。 */
    data class Request(
        /** [OrderFileId.TYPE_A] | [OrderFileId.TYPE_B]。 */
        val fileType: String,
        val year: Int,
        /** B 类 1..12；**A 类必须为 null**（传 0 会撞 `OrderSyncService:60` 的月份校验拿 400）。 */
        val month: Int?,
        /** 登录名。统计按它分组，所以补传的历史订单要统一写店主名。 */
        val writer: String,
        val lines: List<OrderLine>,
        /** 客户端 epoch millis。协议本身没有订单时间字段，必须自带（见 [OrderDraft.ts]）。 */
        val ts: Long,
        /**
         * 用户在结算框里手改过的总额（**分**）。null = 用 `Σ items.amount`。
         *
         * 存"分"而不是字符串：这一层要做加减（拆块、算差额），
         * 而字符串金额做算术就得先转 BigDecimal 再转回来，两处转换就是两处出错的机会。
         */
        val totalOverrideCents: Long? = null,
        /**
         * A 类备注原文，**未 padding、可以超长、可以含引号**——[ByteOps.coerceRemark] 会整理。
         *
         * ⚠ B 类传非 null 会直接抛：B 类订单模型里没有备注，
         * 而 150 字节的空备注会给每一张销售单白白加上 160 字节。
         */
        val remarkText: String? = null,
    )

    /** 切好的一块。[draft] 用来入库，[serialized] 用来上传——两者必须同源。 */
    data class Chunk(
        val draft: OrderDraft,
        val serialized: SerializedOrder,
    )

    /**
     * @param groupId 只有一块时为 **null**（那时 payload 里也没有 `grp` 字段）。
     *                入库到 `OrderOutboxEntity.groupId`（NOT NULL）时要 `?: ""`。
     */
    data class Result(
        val groupId: String?,
        val chunks: List<Chunk>,
    ) {
        val chunkCount: Int get() = chunks.size
        val isSplit: Boolean get() = chunks.size > 1
    }

    /**
     * @param uuidFactory 每块一个 uuid。**绝不让多块共用一个**：服务端不查重
     *        （`parseOrderJson` 只读字段、不比 uuid），同 uuid 两行会让 `order_index`
     *        主键冲突、镜像比服务器长，此后所有偏移静默错位。
     * @param groupIdFactory 只在真的拆成多块时调用一次。
     */
    fun assemble(
        req: Request,
        uuidFactory: () -> String = { UUID.randomUUID().toString() },
        groupIdFactory: () -> String = { randomGroupId() },
    ): Result {
        require(req.lines.isNotEmpty()) {
            "空订单不该进 outbox：服务端 items 不能为空，进了也只会永远 FAILED_PERMANENT"
        }
        val isA = when (req.fileType) {
            OrderFileId.TYPE_A -> true
            OrderFileId.TYPE_B -> false
            else -> throw IllegalArgumentException("fileType 只能是 A 或 B，实际 '${req.fileType}'")
        }
        if (isA) {
            require(req.month == null) {
                "A 类没有月份维度，month 必须是 null；传 0 会撞 OrderSyncService:60 的 month<1||month>12 拿 400"
            }
        } else {
            require(req.month in 1..12) { "B 类的 month 必须在 1..12，实际 ${req.month}" }
            require(req.remarkText == null) {
                "只有 A 类订单有备注字段。B 类带上它会平白多出 160 字节，" +
                    "而且 §3.9 的备注补丁分支是按 A 类的 remarkOffset 设计的"
            }
        }

        val budget = BYTE_BUDGET - TOTAL_SLACK

        // ---- 第一遍：只决定"每块装哪些行"。序列化用 PROBE_UUID/PROBE_GROUP，尺寸恒定。 ----
        //
        // ⚠ 探路时**总是**带上 grp（哪怕最后只有一块、不写这个字段）。
        // 这样量出来的是**上界**，第二遍去掉 grp 只会更小 —— 反过来的话
        // "单块不写 grp" 这条优化会让真实 payload 比探路时更大，预算就白留了。
        val ranges = ArrayList<IntRange>()
        var from = 0
        while (from < req.lines.size) {
            var to = from
            var est = 0
            while (to < req.lines.size) {
                val n = StableOrderJson.estimateItemBytes(req.lines[to])
                // 第一行无条件收下：否则单行超预算时会在这里死循环
                if (to > from && est + n > budget) break
                est += n
                to++
            }
            // 估算偏低时真实字节数会超预算 → 退行重算。退到只剩一行还超就是真的装不下。
            while (to - from > 1 && probeBytes(req, isA, from, to) > budget) to--
            if (probeBytes(req, isA, from, to) > budget) {
                val line = req.lines[from]
                throw IllegalArgumentException(
                    "单行就超过 $budget 字节预算，无法拆单：barcode=${line.barcode} " +
                        "name=${line.name}（${probeBytes(req, isA, from, from + 1)} 字节）。" +
                        "这一行只能单独开一单，或者把商品名缩短"
                )
            }
            ranges += from until to
            from = to
        }

        // ---- 第二遍：真 uuid、真 grp、真 total。 ----
        val groupId = if (ranges.size > 1) groupIdFactory() else null
        val sumAll = req.lines.sumOf { Money.toCents(it.amount) }
        val delta = (req.totalOverrideCents ?: sumAll) - sumAll

        val chunks = ArrayList<Chunk>(ranges.size)
        ranges.forEachIndexed { i, r ->
            // ⚠ `toList()` 拷贝一份：`subList` 是**视图**，而 draft 的寿命远超本函数
            // （它会被序列化进 outbox 行、被上传、被 UI 读取）。留着视图的话，
            // 调用方哪天复用同一个 MutableList 装下一单，这一单的 items 就跟着变了。
            val lines = req.lines.subList(r.first, r.last + 1).toList()
            val sum = lines.sumOf { Money.toCents(it.amount) }
            val totalCents = if (i == ranges.lastIndex) sum + delta else sum
            val draft = OrderDraft(
                orderUuid = uuidFactory(),
                writer = req.writer,
                items = lines,
                total = Money.fromCents(totalCents),
                ts = req.ts,
                groupId = groupId,
                remark = remarkFor(req, isA, i),
            )
            val serialized = StableOrderJson.serialize(draft)
            check(serialized.byteSize <= BYTE_BUDGET) {
                "第 ${i + 1}/${ranges.size} 块序列化后 ${serialized.byteSize} 字节，超过预算 $BYTE_BUDGET。" +
                    "装箱阶段量的是 $budget 字节上界，说明 TOTAL_SLACK($TOTAL_SLACK) 不够覆盖 total 的变化"
            }
            chunks += Chunk(draft, serialized)
        }
        return Result(groupId, chunks)
    }

    /**
     * A 类**强制**带 remark，哪怕用户一个字都没填。
     *
     * ⚠ 这一条不是洁癖：字节补丁是**等长覆写**，下单时没带 remark 的那一行
     * 之后**无法插入** remark（插入会变长，而变长就意味着这一行之后所有订单的偏移全部作废）。
     * 「补货清单一键转进货单」这条自动生成路径最容易漏，所以在这里兜住，不靠调用方记得。
     *
     * 只有**第一块**带用户填的文本，其余块是 150 个空格：备注描述的是这张进货单，
     * 复制到每一块的话，用户改其中一块另外几块不会跟着改，反而制造"同一单三个不同备注"。
     * `OrderPayloadIndex` 会把全空格读成 null（`trimEnd().takeIf { isNotEmpty() }`），
     * 所以历史页不会显示一堆空白。
     */
    private fun remarkFor(req: Request, isA: Boolean, chunkIndex: Int): String? {
        if (!isA) return null
        val text = if (chunkIndex == 0) req.remarkText.orEmpty() else ""
        return ByteOps.coerceRemark(text)
    }

    private fun probeBytes(req: Request, isA: Boolean, from: Int, toExclusive: Int): Int {
        val lines = req.lines.subList(from, toExclusive)
        val draft = OrderDraft(
            orderUuid = PROBE_UUID,
            writer = req.writer,
            items = lines,
            total = Money.fromCents(lines.sumOf { Money.toCents(it.amount) }),
            ts = req.ts,
            groupId = PROBE_GROUP,
            // `remarkFor` 只区分"第一块 / 其余块"，而第一块的判据就是 from==0。
            // 两者都恰好 150 字节（[ByteOps.coerceRemark] 恒定 padding），所以
            // 探路尺寸在 remark 这一项上与第二遍**完全一致**，不需要为它留余量。
            remark = remarkFor(req, isA, if (from == 0) 0 else 1),
        )
        return StableOrderJson.serialize(draft).byteSize
    }

    /** 8 位小写十六进制。**绝不复用 orderUuid**（`StableOrderJson` 会拦，但别指望它）。 */
    private fun randomGroupId(): String {
        val sb = StringBuilder(8)
        repeat(8) { sb.append(HEX[Random.nextInt(HEX.length)]) }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
