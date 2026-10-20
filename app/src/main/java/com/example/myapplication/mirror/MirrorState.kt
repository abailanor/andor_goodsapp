package com.example.myapplication.mirror

/**
 * 镜像侧的持久化状态（P1 之后由 Room 的 `file_state` 表实现）。
 *
 * `version` / `lastStreamId` 是**两个独立的游标**，分工不同（README §3.2）：
 *  - `version` 是文件状态的**权威**，用来判 LATEST / SERVER_BEHIND 与守卫增量链连续性；
 *  - `lastStreamId` 只是消息游标，负责高效取增量。
 *
 * 只用 stream id 的话，"我的游标比流里任何 id 都新"与"游标被 MAXLEN 裁掉了"是同一个现象，
 * 无法区分——而前者意味着服务器丢过数据，此时返回 FULL 会用较短的文件覆盖客户端唯一的副本。
 *
 * `checksum` / `mirrorBytes` 用于三重自检（见 [MirrorCommitter.selfCheck]）。
 */
data class MirrorState(
    val version: Int,
    val lastStreamId: String?,
    val checksum: Int,
    val mirrorBytes: Long,
    /**
     * 首次成功同步的时刻（墙钟 millis）。**必须是三态语义**：
     *  - `null` = 从未同步过这个月份
     *  - 非 null 且 `version == 0` = 同步过了，但那个月确实没有订单
     *
     * 压成两态（都用 version=0 表示）的话，UI 会把"还没拉"显示成"这个月 0 笔"，
     * 用户会以为丢单。懒同步策略下这个区分尤其重要。
     */
    val firstSyncedAt: Long?,
    val syncState: SyncState,
) {
    companion object {
        /** 从未同步过的初始状态。 */
        fun fresh() = MirrorState(0, null, 0, 4L, null, SyncState.LIVE)
    }
}

enum class SyncState {
    /** 正常参与同步。 */
    LIVE,

    /**
     * 服务端已把这个文件归档删除（409 → `version=0` 探测得到 LATEST）。
     *
     * 调度器直接跳过；UI 该月置灰、只显示、**不允许再往里开单**。
     *
     * ⚠ 归档时 `version`/`lastStreamId` **原样冻结、不归零**。归零会导致两件事：
     *  1. `stats_contrib` 按 `(fileId, version)` 记账，与游标行不一致；
     *  2. 运维哪天把文件恢复了，`version=0` → FULL → 把归档镜像覆盖掉，
     *     而 `order_index` 还指着归档路径。
     *
     * 归档只是"不再同步"，**不是"不再统计"**：`stats_contrib` 的 scope 保留
     * `FILE:<fileId>` 不变，另置 `frozen=1`；FULL 重投影路径必须 `WHERE frozen=0`，
     * 否则一次误解档会把历史统计清零。
     */
    ARCHIVED,

    /**
     * 本地镜像与期望状态不符（守卫判定 DIVERGED）。
     *
     * 证据已隔离到 `mirror-quarantine/`。调度器看到这个状态必须**强制走 FULL**，
     * 不接受任何增量。
     */
    DIVERGED
}

/** 一次镜像提交要写进 Room 的全部信息。**在文件已落盘之后**才允许调用。 */
data class MirrorCommitRecord(
    val storeKey: String,
    val fileId: String,
    val state: MirrorState,
    val order: OrderDelta?,
    /**
     * `true` 表示文件写入被跳过了（守卫判定 ALREADY_APPLIED），但 Room 状态**仍需提交**。
     * 统计与索引要据此补录。
     */
    val alreadyApplied: Boolean,
)

/** 这次提交对订单索引造成的变化。 */
sealed class OrderDelta {

    /** 追加了一条订单。偏移全部来自服务端响应或流消息，**不要自己算**（除了 remarkOffset）。 */
    data class Added(
        val orderUuid: String,
        val lineStart: Long,
        val uuidOffset: Long,
        val statusOffset: Long,
        /**
         * A 类备注的绝对偏移。服务端**不返回**它，由客户端按 README §2.5 推算：
         * `remarkOffset = uuidOffset + (r - u)`，其中 `u`、`r` 是**你上传那段 JSON 里**
         * uuid 值与 remark 值的字节下标。B 类订单为 null。
         */
        val remarkOffset: Long?,
        /** 订单原文（不含末尾 LF）。用于解析出 writer/items/total 建索引，不入库。 */
        val payload: ByteArray,
        val appliedFileVersion: Int,
    ) : OrderDelta() {
        override fun equals(other: Any?): Boolean =
            other is Added && orderUuid == other.orderUuid && lineStart == other.lineStart &&
                appliedFileVersion == other.appliedFileVersion && payload.contentEquals(other.payload)

        override fun hashCode(): Int = orderUuid.hashCode()
    }

    /** 打了一个补丁。 */
    data class Patched(
        val orderUuid: String?,
        val patchOffset: Long,
        val patchKind: PatchKind,
        /**
         * [patchKind] == [PatchKind.STATUS] 时的**新状态数字**（`0`/`2`/`3`）；其余为 null。
         *
         * `order_index.status` 要靠它更新。不能靠 [cancelAppliedVersion] 推断：
         * 总部把 A 类订单标成已完成（`'2'`）或已删除（`'3'`）时同样是
         * "1 字节打在 statusOffset 上"，但**不该**回退统计与库存。
         */
        val newStatusDigit: Int?,
        /**
         * [patchKind] == [PatchKind.REMARK] 时的**新备注文本**（已 `trimEnd()`）；其余为 null。
         *
         * 为什么要由提交方带过来、而不是让 `MirrorStateStore` 自己去镜像里读那 150 字节：
         * 读文件是 I/O，而 [MirrorStateStore.commit] 的整个实现必须是一个 Room 事务，
         * **事务里不许做文件 I/O**（事务持有数据库写锁，fsync 会把整个 App 卡住）。
         * 不带这个字段的话 `order_index.remark` 会永远停在旧值 ——
         * 历史页显示的备注与镜像里的不一致，而且不报错。
         */
        val newRemark: String? = null,
        /**
         * 仅当 [patchKind] == STATUS **且**确认了旧字节是 `'1'`、新字节是 `'0'` 时非 null。
         *
         * 这个条件必须与服务端 `OrderFileService:383-391` 逐字对齐：
         * ```java
         * if (patchOffset == absoluteStatusOffset && "0".equals(patchContent)
         *         && oldBytes.length == 1 && oldBytes[0] == '1') { refundItems = ...; }
         * ```
         * 服务端只有在满足它时才回退库存。客户端如果条件更宽，就会在服务端没回退的情况下
         * 回退了统计与库存 → 长期偏离。
         */
        val cancelAppliedVersion: Int?,
    ) : OrderDelta()
}

/**
 * 补丁的语义分类。
 *
 * ## 为什么需要分类
 *
 * 流消息里的 `type` 区分不了"取消订单"与"修改 A 类备注"：
 * 服务端 `OrderFileService:361` 把**用户侧**补丁的 `action` 限制为只允许 `CANCEL`，
 * 所以改备注（`patchLength=150`）也得用 `action=CANCEL` 发，两者 `type` 完全相同。
 *
 * 把改备注误判成取消，会错误回退库存与统计——而且不会报错。只能靠偏移区分。
 *
 * ⚠ `type` 还有 `COMPLETE` / `DELETE_COMPLETED` 两个值（总部处理 A 类订单，`:498`），
 * 它们与取消**同形**（1 字节打在 statusOffset 上），所以这里一律**不看 `type`**。
 */
enum class PatchKind {
    /**
     * 状态字节变更（`patchLength == 1` 且落在 `statusOffset`）。
     *
     * 覆盖四种服务端动作：取消（`1→0`）、总部标完成（`1→2`）、
     * 总部删已完成（`2→3`）。具体是哪个由 [OrderDelta.Patched.newStatusDigit] 与
     * [OrderDelta.Patched.cancelAppliedVersion] 区分，**只有 `1→0` 才回退库存与统计**。
     */
    STATUS,

    /** A 类备注变更（`patchLength == 150` 且落在 `remarkOffset`）。 */
    REMARK,

    /**
     * 两者都不匹配。**不要猜。**
     *
     * 记审计日志 + 触发该 fileId 一次 FULL 自愈。可能的来源：
     * 别的设备用了我们不知道的偏移、`order_index` 里的偏移过期了、
     * 或者有人拿着 `ORDER_A`/`ORDER_B` 在自己店铺的文件里做任意等长覆写
     * （README §9 明确说服务端不校验补丁目标是否真的是 status 字节）。
     */
    UNKNOWN
}

/**
 * 按偏移给补丁分类。**纯函数**，可单测。
 *
 * @param statusOffset 该订单的 status 数字绝对偏移；`order_index` 里没有则为 null
 * @param remarkOffset 该订单的 remark 值绝对偏移；B 类订单或没有 remark 则为 null
 */
object PatchClassifier {

    fun classify(
        patchOffset: Long,
        patchLength: Int,
        statusOffset: Long?,
        remarkOffset: Long?,
    ): PatchKind = when {
        statusOffset != null && patchOffset == statusOffset && patchLength == 1 -> PatchKind.STATUS
        remarkOffset != null && patchOffset == remarkOffset &&
            patchLength == com.example.myapplication.core.bytes.ByteOps.REMARK_WIDTH -> PatchKind.REMARK
        else -> PatchKind.UNKNOWN
    }
}
