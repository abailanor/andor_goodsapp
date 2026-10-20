package com.example.myapplication.sync

import com.example.myapplication.db.FileStateEntity
import com.example.myapplication.db.OrderFileDao
import com.example.myapplication.db.StoreMetaDao
import com.example.myapplication.mirror.MirrorCommitter
import com.example.myapplication.mirror.SyncState
import com.example.myapplication.sync.OrderFileSyncEngine.Outcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference

/**
 * 懒同步调度器（方案 §3.4 的三通道）。
 *
 * ## 为什么不是"登录时把所有月份都拉一遍"
 *
 * 一个用了三年的店有 36 个 B 类文件 + 3 个 A 类文件。全拉一遍是 39 次请求、
 * 其中绝大多数返回 LATEST（一次 Redis GET，不碰磁盘），但**第一次**登录时它们全是 FULL。
 * 用户点开 App 到看见数据之间不该插一段几分钟的下载。
 *
 * 所以分三通道，按"用户此刻关心什么"排序：
 *
 * | 通道 | 覆盖范围 | 触发时机 | 入口 |
 * |---|---|---|---|
 * | 急 | 当月 + 上月（B）、当年（A） | 登录成功 / SSE 重连 / 回前台 | [urgentSync] |
 * | 按需 | 用户翻到的那一个月 | 历史页、统计页切月份 | [syncPeriod] |
 * | 后台 | `enumFloor`..当月的**全部**月份 | WorkManager 周期任务 | [backgroundCatchUp] |
 *
 * 三条通道的并集覆盖 `enumFloor..now`，符合 README §3.2 反对"只带近两个月"的要求：
 * 只带近两个月的话，第三台设备登录时看不到更早的订单，数据永久分歧。
 *
 * ## ⚠ 这个类**不做**的事
 *
 *  - 不写 Room、不碰镜像文件（那是 [OrderFileSyncEngine] 与 [MirrorCommitter] 的职责）；
 *  - 不重试。429 与 401 直接中止整批，理由见 [runBatch]；
 *  - 不自己登录。[Outcome.NeedsRelogin] 交给调用方（P6 的会话层）；
 *  - 不决定 `ServerBehind` 怎么办。那是必须问用户的，见 [BatchReport.serverBehind]。
 *
 * 它只做一件事：**决定"现在该同步哪些 fileId、以什么顺序、什么时候必须停下来"**。
 */
class SyncScheduler(
    /**
     * 同步一个 fileId。生产装配传 [OrderFileSyncEngine.sync] 的方法引用。
     *
     * 用函数类型而不是直接持有引擎，是因为调度器要测的东西全在"决定同步哪些、
     * 什么时候停"这一层，而引擎的每个分支都要一整套 FakeApi + 真镜像文件才能驱动。
     * 注入函数之后，"429 中止整批"这类断言可以只盯着 fileId 序列本身。
     */
    private val syncOne: suspend (storeKey: String, fileId: String) -> Outcome,
    /** 三重自检。生产装配传 [MirrorCommitter.selfCheck] 的方法引用。 */
    private val checkOne: suspend (storeKey: String, fileId: String, verifyChecksum: Boolean) -> Boolean,
    private val dao: OrderFileDao,
    private val storeMetaDao: StoreMetaDao,
    private val timeZone: () -> TimeZone = { TimeZone.getDefault() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val audit: (String) -> Unit = {},
    /**
     * 并发度。**只有不同 fileId 才能并行** —— 同一个 fileId 的两次提交会被
     * `StripedMutex` 串行化，并行发只会白等。默认 2：手机在蜂窝网上开 8 条连接
     * 挤一个店的服务端没有意义，而 429 的代价（整批中止）远大于慢一点。
     */
    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DEFAULT_PARALLELISM),
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {

    companion object {
        const val DEFAULT_PARALLELISM = 2

        /** 一次后台补同步最多发多少个文件。见 [backgroundCatchUp] 里关于"预算"的说明。 */
        const val DEFAULT_CATCHUP_BUDGET = 12

        /**
         * 月份枚举的**硬下界**（夹，不是 min 的一项，理由见 [refreshEnumFloor]）。
         *
         * 为什么必须有：`order_index.orderTs` 可能是垃圾值（别的客户端写进来的），
         * `createdAt` 也可能是 `0`。没有夹的话后台补同步会从 1970-01 开始枚举 ——
         * 672 个月、672 次请求，每一次都在服务端 `ensureFileBlocking` 里建一个
         * 4 字节空文件，把磁盘和 Redis 键空间填满永远用不到的 fileId。
         *
         * 2020-01 是"这个 App 不可能有更早的订单"的保守估计。
         */
        val HARD_FLOOR = SyncMonth(2020, 1)

        /** 单次枚举的月份数上限，防止 `enumFloor` 被写坏成 1970 时打爆服务端。 */
        private const val MAX_ENUMERATED_MONTHS = 240
    }

    // ==================== enumFloor ====================

    /**
     * 重算并持久化"月份枚举下界"。
     *
     * ## 为什么下界不是 `createdAt` 所在月
     *
     * 用户的真实业务流是**先离线用几个月、之后才向丽华金龙购买服务**（方案 R2）。
     * `createdAt` 是审批通过时服务端写的：2025-03 开始离线用、2026-10 才买服务
     * ⇒ `createdAt = 2026-10`。照 README §3.2 从 `createdAt` 枚举的话，
     * 补传上去的那些 2025-03..2026-09 的历史订单**永远不会被同步** ——
     * 服务端会正常创建 `B:alice:2025-03`（`ensureFileBlocking` 按需建），
     * 但第二台设备登录时看不到它们，两台设备的数据永久分歧。
     *
     * 所以下界是"我们知道的最早月份"：
     * ```
     * max( min( createdAt 所在月, 本地写入过的最早月份, extra ), HARD_FLOOR )
     * ```
     *
     * ## ⚠ [HARD_FLOOR] 是**夹**，不是 min 的一项（这里与方案 R2 的字面写法不同）
     *
     * 方案 R2 写的是 `min(createdAt 所在月, 本地最早月份, 2020-01)`。照字面实现的话
     * 每个店铺的下界都恒等于 2020-01 —— 而 2026 年注册的店要枚举 82 个月、89 个文件，
     * [backgroundCatchUp] 的预算（默认一次 12 个）得跑七八轮才收敛，
     * 其中绝大多数请求换来一句"这个月没有文件"。
     *
     * 夹的语义才是 §3.4 的原意（"硬下界：不早于 2020-01"）：正常店铺从 `createdAt` 起枚举，
     * 离线补传过的店铺从最早那笔订单起枚举，而 `createdAt = 0`（README §4.1 允许"未知"）
     * 时既不会一路退到 1970 打爆服务端，也不会漏掉本地已有的月份。
     *
     * ## ⚠ 必须持久化，不能每次现算
     *
     * "本地写入过的最早月份"来自 `order_index`。卸载重装之后 `createdAt` 还在
     * （服务端有），但 `order_index` 空了 —— 现算的话下界会**跳回** `createdAt`，
     * 那些补传过的月份又不同步了。[StoreMetaDao.lowerEnumFloor] 的 SQL 带
     * `AND (enumFloorYear > :year OR …)`，**只会往前推、不会往后收**，正是为此。
     *
     * @param createdAt 登录响应里的店铺成立时间，`0` = 未知（此时这一项不参与取最小）。
     * @param extra     额外的候选下界。P5 的 outbox 会传"最早一笔未上传订单的月份"——
     *                  补传历史订单时它们会落进 `createdAt` 之前的月份。
     * @return 生效的下界；店铺还没有 `store_meta` 行（离线模式）时返回 null。
     */
    suspend fun refreshEnumFloor(storeKey: String, createdAt: Long, extra: SyncMonth? = null): SyncMonth? {
        val meta = storeMetaDao.get(storeKey) ?: run {
            audit("refreshEnumFloor：storeKey=$storeKey 还没有 store_meta 行（离线模式），跳过")
            return null
        }
        val tz = timeZone()
        val earliestTs = dao.earliestOrderTs(storeKey)
        val candidates = buildList {
            if (createdAt > 0L) add(SyncMonth.of(createdAt, tz))
            if (earliestTs != null) add(SyncMonth.of(earliestTs, tz))
            if (extra != null) add(extra)
        }
        val floor = if (candidates.isEmpty()) {
            // `createdAt` 未知且本地一单没有 → 用**当前月**（§3.4 说的 epochFloor）。
            // 这是唯一什么信息都没有的情形：往回猜会枚举几十年，往前只看当下
            // 则靠通道二（用户翻到哪个月就补哪个月）兜住。
            audit("storeKey=$storeKey 的 createdAt 未知且本地无订单，enumFloor 退回当前月")
            maxOf(SyncMonth.of(now(), tz), HARD_FLOOR)
        } else {
            maxOf(candidates.min(), HARD_FLOOR)
        }
        val changed = storeMetaDao.lowerEnumFloor(storeKey, floor.year, floor.month)
        if (changed > 0) {
            audit("enumFloor 下推 storeKey=$storeKey → $floor（原 ${meta.enumFloorYear}-${meta.enumFloorMonth}）")
        }
        // 读回来的才是生效值：lowerEnumFloor 只往前推，本次算出的 floor 可能比库里的大。
        return storeMetaDao.getEnumFloor(storeKey)?.let { SyncMonth(it.enumFloorYear, it.enumFloorMonth) }
            ?: floor
    }

    /** 当前生效的下界。没有 `store_meta` 行时退回 [HARD_FLOOR]。 */
    suspend fun enumFloor(storeKey: String): SyncMonth =
        storeMetaDao.getEnumFloor(storeKey)?.let { SyncMonth(it.enumFloorYear, it.enumFloorMonth) }
            ?: HARD_FLOOR

    // ==================== 通道一：急同步 ====================

    /**
     * 当月 + 上月的 B 类文件，加当年的 A 类文件。
     *
     * 调用时机：登录成功、SSE 重连、`onResume` 且空闲超过阈值。
     *
     * ## 为什么带上 A 类
     *
     * A 类是"向丽华金龙进货"，总部处理订单时会把状态从 `1` 改成 `2`（COMPLETE）。
     * 不同步 A 类的话，用户在进货单页看到的永远是"待处理"，而总部那边早就完成了 ——
     * 这是两个方向的可见性，不只 B 类重要。
     *
     * A 类**按年**分文件，所以这里只有一个 fileId（跨年时上月可能属于去年，
     * 那就还要加上去年那一个）。
     */
    suspend fun urgentSync(storeKey: String): BatchReport {
        val meta = storeMetaDao.get(storeKey) ?: return BatchReport.aborted("没有 store_meta 行，无法确定店主名")
        val thisMonth = SyncMonth.of(now(), timeZone())
        val lastMonth = thisMonth.prev()
        val fileIds = linkedSetOf(
            OrderFileId.ofB(meta.ownerName, thisMonth.year, thisMonth.month),
            OrderFileId.ofB(meta.ownerName, lastMonth.year, lastMonth.month),
            OrderFileId.ofA(meta.ownerName, thisMonth.year),
        )
        // 跨年：1 月时上月是去年 12 月，去年那个 A 类文件也要拉。
        if (lastMonth.year != thisMonth.year) fileIds += OrderFileId.ofA(meta.ownerName, lastMonth.year)
        return runBatch(storeKey, fileIds.toList())
    }

    // ==================== 通道二：按需 ====================

    /**
     * 同步"用户正在看的这一个月"：该月的 B 类文件 + 该年的 A 类文件。
     *
     * 历史页/统计页切月份时调。**要等它返回**再渲染吗？不要 ——
     * 先渲染 `order_index` 里已有的数据，同步完成后 Room 的 Flow 会自动刷新。
     * 这个方法存在的意义是"翻到哪个月就把那个月补齐"，不是"阻塞 UI 直到补齐"。
     */
    suspend fun syncPeriod(storeKey: String, year: Int, month: Int): BatchReport {
        val meta = storeMetaDao.get(storeKey) ?: return BatchReport.aborted("没有 store_meta 行，无法确定店主名")
        require(month in 1..12) { "月份必须在 1..12，实际 $month" }
        return runBatch(
            storeKey,
            listOf(
                OrderFileId.ofB(meta.ownerName, year, month),
                OrderFileId.ofA(meta.ownerName, year),
            ),
        )
    }

    // ==================== 通道三：后台补同步 ====================

    /**
     * 把 `enumFloor..当月` 里**还没同步过的**月份补齐。
     *
     * ## "还没同步过"的判据是 `firstSyncedAt == null`，不是 `version == 0`
     *
     * `firstSyncedAt` 是三态的（见 [com.example.myapplication.mirror.MirrorState]）：
     * `null` = 从没拉过；非 null 且 `version == 0` = 拉过了、那月确实没单。
     * 用 `version == 0` 判的话，"这个月真的没有订单"会被反复重拉，
     * 而且是**永远**重拉 —— 每次都是一次 FULL 探测。
     *
     * `DIVERGED` 的也要补：`commitDiverged` 保留了 `firstSyncedAt`，
     * 但它把游标归零了，正等着一次 FULL 自愈。
     *
     * ## ⚠ 预算（[maxFiles]）不是优化，是必需的
     *
     * 下界 2020-01、今天 2026-10 ⇒ 82 个月 ⇒ 82 个 B 类 + 7 个 A 类 = 89 个文件。
     * 一次 WorkManager 运行里发 89 个请求（第一次登录时全是 FULL）会把手机和服务端
     * 一起拖住几分钟，而 WorkManager 有 10 分钟的执行上限。
     * 分批之后每次运行只推进 [maxFiles] 个，几次运行就收敛。
     *
     * **被预算截断的部分必须记审计**（下面那句 `audit`）：静默截断的表现是
     * "某些月份永远同步不到"，而日志里什么都看不到。
     *
     * 顺序是**新月优先**：用户最可能翻最近的月份，而最老的月份大概率已经被前几次运行补掉了。
     */
    suspend fun backgroundCatchUp(storeKey: String, maxFiles: Int = DEFAULT_CATCHUP_BUDGET): BatchReport {
        val meta = storeMetaDao.get(storeKey) ?: return BatchReport.aborted("没有 store_meta 行，无法确定店主名")
        val floor = enumFloor(storeKey)
        val today = SyncMonth.of(now(), timeZone())
        val months = today.monthsBackTo(floor, MAX_ENUMERATED_MONTHS)
        if (months.size == MAX_ENUMERATED_MONTHS) {
            audit("⚠ enumFloor=$floor 距今超过 $MAX_ENUMERATED_MONTHS 个月，本次只枚举到 " +
                "${months.last()}——请核对 store_meta.enumFloor* 是不是被写坏了")
        }

        val states = dao.allFileStates(storeKey).associateBy { it.fileId }
        val wanted = ArrayList<String>()
        var skippedArchived = 0

        for (m in months) {
            val b = OrderFileId.ofB(meta.ownerName, m.year, m.month)
            if (classify(states[b]) == Skip.ARCHIVED) skippedArchived++ else wanted += b
        }
        // A 类按年，去重后逐年判定。
        for (year in months.map { it.year }.distinct()) {
            val a = OrderFileId.ofA(meta.ownerName, year)
            if (classify(states[a]) == Skip.ARCHIVED) skippedArchived++ else wanted += a
        }

        val pending = wanted.filter { classify(states[it]) == Skip.NEEDED }
        val skippedDone = wanted.size - pending.size
        val batch = pending.take(maxFiles)
        if (pending.size > batch.size) {
            audit("后台补同步预算 $maxFiles 用尽：本次跳过 ${pending.size - batch.size} 个文件" +
                "（最老的到 ${pending.last()}），下次运行继续")
        }

        val report = runBatch(storeKey, batch)
        return report.copy(skippedArchived = report.skippedArchived + skippedArchived, skippedDone = skippedDone)
    }

    private enum class Skip { NEEDED, ARCHIVED, DONE }

    /**
     * 这个 fileId 该不该进后台批次。
     *
     * `null`（`file_state` 里还没有行）= 从没同步过 = **要补**。
     */
    private fun classify(state: FileStateEntity?): Skip = when {
        state == null -> Skip.NEEDED
        state.syncState == SyncState.ARCHIVED.name -> Skip.ARCHIVED
        // DIVERGED 的游标已归零，正等着 FULL 自愈 —— 即使 firstSyncedAt 非 null 也要补。
        state.syncState == SyncState.DIVERGED.name -> Skip.NEEDED
        state.firstSyncedAt == null -> Skip.NEEDED
        else -> Skip.DONE
    }

    // ==================== 三重自检 ====================

    /**
     * 启动时的自检：只查当月 + 上月，**不校验 checksum**。
     *
     * 为什么只查两个月：全量抽检对几十 MB 的镜像是流式求和（30~80ms/个），
     * 89 个文件就是几秒，放在启动路径上会直接变成 ANR。
     * 为什么不算 checksum：启动时只需要发现"版本头/长度对不上"这种硬伤，
     * 那两项是 O(1) 的 `read` 与 `length()`。深度校验留给 [selfCheckAll]。
     *
     * @return 自检不通过的 fileId（**已排除 ARCHIVED**，见下）。
     */
    suspend fun selfCheckRecent(storeKey: String): List<String> {
        val meta = storeMetaDao.get(storeKey) ?: return emptyList()
        val thisMonth = SyncMonth.of(now(), timeZone())
        val lastMonth = thisMonth.prev()
        val ids = listOf(
            OrderFileId.ofB(meta.ownerName, thisMonth.year, thisMonth.month),
            OrderFileId.ofB(meta.ownerName, lastMonth.year, lastMonth.month),
        )
        return selfCheck(storeKey, ids, verifyChecksum = false)
    }

    /**
     * 后台每天一次的**全量**抽检：所有 LIVE 文件，含 checksum。
     *
     * ⚠ 别在主线程或每次操作前调（[MirrorCommitter.selfCheck] 的注释）。
     * 跑完之后再跑一次 [backgroundCatchUp]：自检判了 DIVERGED 的文件会被归零游标，
     * 而 `classify` 把 DIVERGED 归成 NEEDED，于是自愈是**自动接上**的，不需要额外代码。
     *
     * @return 自检不通过的 fileId。
     */
    suspend fun selfCheckAll(storeKey: String): List<String> {
        val states = dao.allFileStates(storeKey)
        // ARCHIVED 必须排除：归档月份本来就可能没有本地镜像（归档发生在下载之前），
        // `selfCheck` 对它返回 false 只是"报一下"，不代表坏了。把它列进来的话
        // 调用方会去排一次 FULL，而 FULL 会覆盖那份本该冻结的归档镜像。
        val ids = states.filter { it.syncState != SyncState.ARCHIVED.name }.map { it.fileId }
        return selfCheck(storeKey, ids, verifyChecksum = true)
    }

    private suspend fun selfCheck(storeKey: String, fileIds: List<String>, verifyChecksum: Boolean): List<String> {
        val bad = ArrayList<String>()
        for (id in fileIds) {
            val ok = runCatching { checkOne(storeKey, id, verifyChecksum) }
                .getOrElse { e ->
                    audit("自检抛异常 fileId=$id: ${e.message}")
                    false
                }
            if (!ok) bad += id
        }
        if (bad.isNotEmpty()) {
            audit("三重自检不通过 ${bad.size} 个文件（verifyChecksum=$verifyChecksum）：$bad")
        }
        return bad
    }

    // ==================== 批次执行 ====================

    /**
     * 跑一批 fileId。
     *
     * ## ⚠ 429 与 401 会**中止整批**，不是跳过这一个
     *
     * 这两个是"继续发只会更糟"的错误：
     *
     *  - **429**：服务端的 Guava 平滑令牌桶 `maxBurstSeconds=1.0`，几乎没有突发额度。
     *    已经 429 了还继续发，只会把窗口一直顶满 —— 而那个桶是**按 IP** 的，
     *    同店其它设备也跟着被锁死（方案 R4）。
     *  - **401**：token 死了。后面每一个请求都会 401，白跑一趟还刷满审计日志。
     *    重登必须由会话层做，而且**最多一次**、带 0–60 秒随机抖动
     *    （专治每月 1 号 3:40 密钥轮换之后早上开门的重登风暴）。
     *
     * 其余失败（断网、500、400）只记下来继续跑：它们是这个 fileId 自己的问题，
     * 中止整批会让别的月份白等一轮。
     *
     * ## 并发与中止的配合
     *
     * 按 [parallelism] 分块，**每块之间检查一次中止标志**。用 `async` 一次性铺开全部
     * 请求的话，中止标志要等到所有请求都发出去才有机会被看到 —— 那就不是中止了。
     * 分块的代价是块内最慢的那个会挡住下一块，对 2 的并发度可以接受。
     */
    private suspend fun runBatch(storeKey: String, fileIds: List<String>): BatchReport {
        if (fileIds.isEmpty()) return BatchReport()
        val states = dao.allFileStates(storeKey).associateBy { it.fileId }

        val todo = ArrayList<String>(fileIds.size)
        var skippedArchived = 0
        for (id in fileIds) {
            // 归档是终态。引擎里也挡了一道，但在这里挡掉可以省一次无谓的 HTTP 往返。
            if (states[id]?.syncState == SyncState.ARCHIVED.name) skippedArchived++ else todo += id
        }
        if (todo.isEmpty()) return BatchReport(skippedArchived = skippedArchived)

        val abort = AtomicReference<Abort?>(null)
        val results = ArrayList<Pair<String, Outcome>>(todo.size)

        coroutineScope {
            for (chunk in todo.chunked(parallelism.coerceAtLeast(1))) {
                if (abort.get() != null) break
                val partial = chunk.map { id ->
                    async(io) { id to runOne(storeKey, id, abort) }
                }.awaitAll()
                results += partial
            }
        }

        var report = BatchReport(skippedArchived = skippedArchived, aborted = abort.get()?.reason)
        for ((id, o) in results) report = report.merge(id, o)
        audit(
            "批次结束 storeKey=$storeKey 请求 ${report.attempted}/${fileIds.size}" +
                " 追平 ${report.upToDate} 增量 ${report.applied} 重建 ${report.rebuilt}" +
                " 归档 ${report.archived} 失败 ${report.failed.size}" +
                (report.aborted?.let { " 中止=$it" } ?: "")
        )
        return report
    }

    private suspend fun runOne(storeKey: String, fileId: String, abort: AtomicReference<Abort?>): Outcome {
        val o = syncOne(storeKey, fileId)
        when (o) {
            is Outcome.RateLimited -> abort.compareAndSet(null, Abort("429 限流：$fileId"))
            is Outcome.NeedsRelogin -> abort.compareAndSet(null, Abort("401 需要重登：$fileId（${o.reason}）"))
            else -> Unit
        }
        // 无论成败都记一次尝试时刻：调度器下次据此退避，UI 据此显示"上次同步失败于…"。
        // 返回 0 表示这个 fileId 还没有 file_state 行（请求就失败了），那就没什么可记的。
        runCatching { dao.touchAttempt(storeKey, fileId, now()) }
        return o
    }

    private class Abort(val reason: String)
}

// ==================== 批次结果 ====================

/**
 * 一批同步的结果汇总。
 *
 * ## 为什么不是 `Map<String, Outcome>`
 *
 * 调用方（WorkManager、会话层、UI）关心的从来不是"每个 fileId 分别怎么了"，
 * 而是四个问题：**要不要中止后续动作**（[aborted]）、**要不要弹对话框**
 * （[serverBehind]）、**要不要引导重登**（[needsRelogin]）、**要不要给用户看个错误**
 * （[failed]）。逐个 fileId 遍历 Outcome 会把这四个判断散在每个调用点。
 *
 * 需要逐条明细时看审计日志（`runBatch` 结尾那句），那里带 fileId。
 */
data class BatchReport(
    /** 真的发了请求的文件数（不含 [skippedArchived]）。 */
    val attempted: Int = 0,
    /** 因为已归档而跳过的文件数。**不是错误**：归档是终态，本来就不该再同步。 */
    val skippedArchived: Int = 0,
    /** 因为"已经同步过"而跳过的文件数（只有后台通道会产生）。 */
    val skippedDone: Int = 0,
    val upToDate: Int = 0,
    val applied: Int = 0,
    val rebuilt: Int = 0,
    /** 本次新判成归档的文件数（409 → `version=0` 探测得 LATEST）。 */
    val archived: Int = 0,
    /**
     * 本地版本比服务器高。**必须问用户**，不能自动处理：
     * 两个出口（接受服务器版本 / 回推本地尾部）都会丢数据或改写服务器，
     * 见 [OrderFileSyncEngine.acceptServerVersion]。
     */
    val serverBehind: List<ServerBehindItem> = emptyList(),
    val needsRelogin: List<ReloginItem> = emptyList(),
    val failed: List<FailedItem> = emptyList(),
    /** 非 null 表示批次被提前中止（429 / 401 / 前置条件不满足）。 */
    val aborted: String? = null,
) {

    /** 有没有"必须让用户知道"的事。UI 据此决定要不要弹提示。 */
    val needsAttention: Boolean
        get() = serverBehind.isNotEmpty() || needsRelogin.isNotEmpty() || aborted != null

    fun merge(fileId: String, o: Outcome): BatchReport = copy(
        attempted = attempted + 1,
        upToDate = upToDate + if (o is Outcome.UpToDate) 1 else 0,
        applied = applied + if (o is Outcome.Applied) 1 else 0,
        rebuilt = rebuilt + if (o is Outcome.Rebuilt) 1 else 0,
        archived = archived + if (o is Outcome.Archived) 1 else 0,
        serverBehind = serverBehind + when (o) {
            is Outcome.ServerBehind -> listOf(ServerBehindItem(fileId, o.localVersion, o.serverVersion))
            else -> emptyList()
        },
        needsRelogin = needsRelogin + when (o) {
            is Outcome.NeedsRelogin -> listOf(ReloginItem(fileId, o.reason, o.mayAutoRelogin))
            else -> emptyList()
        },
        failed = failed + when (o) {
            is Outcome.Failed -> listOf(FailedItem(fileId, o.msg, o.retriable))
            // 429 也是"失败了"，但 retriable 恒 false：调用方不该退避后重试，
            // 该做的是等窗口过去（BatchReport.aborted 会同时非 null）。
            is Outcome.RateLimited -> listOf(FailedItem(fileId, o.msg, retriable = false))
            else -> emptyList()
        },
    )

    companion object {
        /** 前置条件不满足（例如离线模式没有 `store_meta` 行），一个请求都没发。 */
        fun aborted(reason: String) = BatchReport(aborted = reason)
    }
}

data class ServerBehindItem(val fileId: String, val localVersion: Int, val serverVersion: Int)

data class ReloginItem(
    val fileId: String,
    val reason: com.example.myapplication.net.UnauthorizedReason,
    val mayAutoRelogin: Boolean,
)

data class FailedItem(val fileId: String, val msg: String, val retriable: Boolean)

// ==================== 年月 ====================

/**
 * 调度器的月份词汇表。
 *
 * ## 为什么不直接用 `java.time.YearMonth`
 *
 * minSdk 24，`java.time` 要 API 26（或 desugaring）。项目现在没开 core library desugaring，
 * 而为一个年月对引入它不划算。也不用 `Calendar` 到处传：`Calendar` 是可变对象，
 * `cal.add(MONTH, -1)` 之后原来的引用跟着变，跨月枚举时踩一次就够受的了。
 *
 * 所以自己写一个**不可变**的年月对，只暴露调度器真正需要的四个操作。
 */
data class SyncMonth(val year: Int, val month: Int) : Comparable<SyncMonth> {

    init {
        require(month in 1..12) { "月份必须在 1..12，实际 $month" }
        require(year in MIN_YEAR..MAX_YEAR) { "年份超出范围: $year" }
    }

    fun next(): SyncMonth = if (month == 12) SyncMonth(year + 1, 1) else SyncMonth(year, month + 1)

    fun prev(): SyncMonth = if (month == 1) SyncMonth(year - 1, 12) else SyncMonth(year, month - 1)

    /**
     * 从 `this` 倒着数到 [floor]（含两端），**新月在前**，最多 [limit] 个。
     *
     * 两种"到头"都不抛异常：
     *  - `this < floor` → 空列表。下界跑到未来去了（时钟被改过），这时候什么都不该同步；
     *  - 倒数到 [MIN] → 就地停住。`MIN.prev()` 会撞 [init] 里的 `require(year in 1970..)`，
     *    而 `store_meta.enumFloor*` 被写坏成 1970-01 是**运维能做出来的事**，
     *    不该让一次后台补同步崩在 WorkManager 里反复重试。
     *
     * [limit] 是 [SyncScheduler.MAX_ENUMERATED_MONTHS] 的落点：不传的话会先把
     * 几百个月全部构造出来再 `take`，白占内存，也白走一遍上面的边界。
     */
    fun monthsBackTo(floor: SyncMonth, limit: Int = Int.MAX_VALUE): List<SyncMonth> {
        if (this < floor || limit <= 0) return emptyList()
        val out = ArrayList<SyncMonth>()
        var cur: SyncMonth? = this
        while (cur != null && cur >= floor && out.size < limit) {
            out += cur
            cur = if (cur == MIN) null else cur.prev()
        }
        return out
    }

    override fun compareTo(other: SyncMonth): Int {
        val byYear = year.compareTo(other.year)
        return if (byYear != 0) byYear else month.compareTo(other.month)
    }

    override fun toString(): String = "%04d-%02d".format(Locale.US, year, month)

    companion object {
        private const val MIN_YEAR = 1970
        private const val MAX_YEAR = 9999

        /** 能表示的最早月份。[monthsBackTo] 倒数到这里就停，见那里的注释。 */
        val MIN = SyncMonth(MIN_YEAR, 1)

        fun of(epochMillis: Long, timeZone: TimeZone): SyncMonth {
            val cal = Calendar.getInstance(timeZone)
            cal.timeInMillis = epochMillis
            return SyncMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
        }
    }
}
