package com.example.myapplication.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * 订单文件镜像的存储层：`file_state` + `order_index` + `order_item_index` + `stats_contrib`
 * + `order_outbox` + `order_group`。
 *
 * ## 两个调用方，各管一半，**都不要绕过**
 *
 *  - **`RoomMirrorStateStore`**（实现 `MirrorStateStore`）管已同步区：游标、索引、`FILE:` 桶的贡献。
 *  - **`OutboxStore`** 管未同步区：`order_outbox` 的状态机、`OUTBOX` 桶的贡献、`order_group`。
 *
 * 两边在**同一个事务里交接**：订单提升进镜像时，`applyAdded` 负责加 `FILE:` 桶的贡献，
 * 同时调 [markOutboxPromoted]，**只有它返回 1** 才减 `OUTBOX` 桶。
 * 于是"这笔钱在两个桶里各算一次"与"两个桶里都没算"这两种错都被这一个返回值挡住。
 *
 * 绕过它们直接写这个 DAO 会破坏三条不变量：
 *  1. **游标必须在文件落盘之后写**（反了就是永久丢数据，`MirrorCommitter` 的类注释）；
 *  2. **`order_index` 的插入与 `stats_contrib` 的增减必须在同一个事务里**
 *     （分开的话进程被杀会留下"有索引没贡献"或反之，而两者都是静默的）；
 *  3. **`payload` 永不被 UPDATE**（见下面 order_outbox 那一节的注释）。
 *
 * ## ⚠ 这里没有 SQLite 的 `ON CONFLICT DO UPDATE`
 *
 * UPSERT 语法要 SQLite 3.24 = **API 30**，而 minSdk 是 24。所以"加一个增量"一律是
 * `UPDATE … SET value = value + :delta` → 影响 0 行再 `INSERT`，
 * 与 [StockDao.applyAbsoluteBatch] 用的是同一套房子规矩。
 *
 * ## ⚠ 幂等靠"插入是否真的发生了"，不靠 `alreadyApplied` 标记
 *
 * `order_index` 用 [OnConflictStrategy.IGNORE] 插入并返回 rowId：
 *  - `-1` = 这一行**已经在了** → 贡献也已经加过了 → 什么都不做；
 *  - 其它 = 新插入 → 才加贡献。
 *
 * 这一条同时覆盖了两种完全不同的重复：SSE 回声 + HTTP 响应的**双投递**，
 * 以及"文件已 fsync、Room 事务还没跑就被杀"之后重启重发。
 * 后者恰恰是 `MirrorCommitRecord.alreadyApplied == true` 的情形，那时**必须**补齐 Room。
 * 用 rowId 判断的话两种情况自动分流，不需要调用方区分。
 */
@Dao
interface OrderFileDao {

    // ==================== file_state ====================

    @Query("SELECT * FROM file_state WHERE storeKey = :storeKey AND fileId = :fileId")
    suspend fun fileState(storeKey: String, fileId: String): FileStateEntity?

    /** 调度器（三通道懒同步）要一次拿到全部游标，逐条查会慢一个数量级。 */
    @Query("SELECT * FROM file_state WHERE storeKey = :storeKey ORDER BY fileType, year, month")
    suspend fun allFileStates(storeKey: String): List<FileStateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFileState(row: FileStateEntity)

    /**
     * 标 DIVERGED 并把游标归零。
     *
     * 归零是**故意的**：即使调度器逻辑有 bug，`version=0 + lastStreamId=NULL`
     * 也保证下次一定走 FULL。取证材料是隔离到 `mirror-quarantine/` 的那个文件，
     * 不依赖数据库里留旧游标。
     *
     * ⚠ `firstSyncedAt` **不在 SET 列表里**，所以原值保留。
     * 清掉它的话，FULL 自愈之后"这个月第一次同步是什么时候"就永久丢了 ——
     * 而 UI 靠它区分"从没同步过"与"同步过但没单"（三态语义）。
     *
     * @return 受影响行数。0 = 这个 fileId 还没有行，调用方必须补一次 INSERT
     *         （`RoomMirrorStateStore.commitDiverged` 已经做了）。
     */
    @Query(
        """
        UPDATE file_state
        SET syncState = 'DIVERGED', version = 0, lastStreamId = NULL,
            checksum = 0, mirrorBytes = 4, lastSyncAttemptAt = :at
        WHERE storeKey = :storeKey AND fileId = :fileId
        """
    )
    suspend fun markDiverged(storeKey: String, fileId: String, at: Long): Int

    /**
     * 标 ARCHIVED。**`version` / `lastStreamId` / `checksum` / `mirrorBytes` 原样冻结，一个都不动。**
     *
     * 归零会导致两件事：
     *  1. `stats_contrib` 按 `(fileId, version)` 记账，与游标行不一致；
     *  2. 运维哪天把文件恢复了，`version=0` → FULL → 把归档镜像覆盖掉，
     *     而 `order_index` 还指着归档路径。
     *
     * @return 受影响行数。0 = 还没有行（服务器从来没这个文件，探测直接得 LATEST），
     *         调用方补一次 INSERT。
     */
    @Query(
        """
        UPDATE file_state SET syncState = 'ARCHIVED', lastSyncAttemptAt = :at
        WHERE storeKey = :storeKey AND fileId = :fileId
        """
    )
    suspend fun markArchived(storeKey: String, fileId: String, at: Long): Int

    /** 只更新"上次尝试同步的时刻"。调度器用来做退避与"该不该现在补这个月"。 */
    @Query(
        """
        UPDATE file_state SET lastSyncAttemptAt = :at
        WHERE storeKey = :storeKey AND fileId = :fileId
        """
    )
    suspend fun touchAttempt(storeKey: String, fileId: String, at: Long): Int

    // ==================== order_index ====================

    @Query(
        """
        SELECT * FROM order_index
        WHERE storeKey = :storeKey AND fileId = :fileId AND orderUuid = :orderUuid
        """
    )
    suspend fun orderIndex(storeKey: String, fileId: String, orderUuid: String): OrderIndexEntity?

    /**
     * 按 status 偏移反查订单。流消息里的 CANCEL **可能不带 `order_uuid`**，
     * 那时补丁偏移是唯一的线索。
     *
     * @return null 表示这个偏移不在本文件的任何订单上 —— 也就是
     *         [com.example.myapplication.mirror.PatchKind.UNKNOWN]，不猜，触发 FULL 自愈。
     */
    @Query(
        """
        SELECT * FROM order_index
        WHERE storeKey = :storeKey AND fileId = :fileId AND statusOffset = :offset
        LIMIT 1
        """
    )
    suspend fun orderIndexByStatusOffset(storeKey: String, fileId: String, offset: Long): OrderIndexEntity?

    /** 同 [orderIndexByStatusOffset]，用于改 A 类备注的补丁。 */
    @Query(
        """
        SELECT * FROM order_index
        WHERE storeKey = :storeKey AND fileId = :fileId AND remarkOffset = :offset
        LIMIT 1
        """
    )
    suspend fun orderIndexByRemarkOffset(storeKey: String, fileId: String, offset: Long): OrderIndexEntity?

    /**
     * IGNORE + 返回 rowId。**`-1` 表示这一行已经在了**，调用方据此跳过贡献的重复累加。
     * 见类注释里"幂等靠插入是否真的发生了"。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrderIndex(row: OrderIndexEntity): Long

    /**
     * FULL 重建用的批量插入。IGNORE 是必需的：**服务端不查重**，
     * 同一个 `order_uuid` 完全可能在文件里出现两行（某台设备盲重试过 `/add`）。
     * 主键容不下两行，IGNORE 让第一行赢、第二行被丢弃。
     *
     * ⚠ 于是"扫描出的行数"会大于"插入的行数"。调用方**必须**比对这两个数字并记审计日志：
     * 差值就是文件里重复 uuid 的条数，那是数据已经出问题的证据，不能静默吞掉。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrderIndexes(rows: List<OrderIndexEntity>)

    @Query("DELETE FROM order_index WHERE storeKey = :storeKey AND fileId = :fileId")
    suspend fun deleteOrderIndexesOfFile(storeKey: String, fileId: String): Int

    @Query("SELECT COUNT(*) FROM order_index WHERE storeKey = :storeKey AND fileId = :fileId")
    suspend fun countOrderIndexesOfFile(storeKey: String, fileId: String): Int

    /**
     * 本地写入过的最早订单时刻，供 [com.example.myapplication.sync.SyncScheduler.refreshEnumFloor]
     * 收紧月份枚举下界。
     *
     * ## ⚠ `orderTs > 0` 这个条件不能省
     *
     * `orderTs = 0` 表示"时间未知"（别的客户端不写 `content.ts`，见
     * [com.example.myapplication.sync.StatsProjection.effectiveTs]）。把它算进 MIN 的话
     * 结果恒为 0 → 下界恒被夹到硬下界 2020-01 → 每次后台补同步都要枚举 80 多个月，
     * 而其中绝大多数是空的。加了这个条件之后 0 只是"这一笔不知道落在哪个月"，
     * 不会把整个下界拖走。
     *
     * @return 一条订单都没有时为 null。
     */
    @Query("SELECT MIN(orderTs) FROM order_index WHERE storeKey = :storeKey AND orderTs > 0")
    suspend fun earliestOrderTs(storeKey: String): Long?

    /**
     * 打状态补丁之后更新索引。
     *
     * @param cancelAppliedVersion 仅当确认了 `1→0` 时非 null。
     *        ⚠ **只有它从 NULL 变成非 NULL 的那一次才允许回退统计与库存**：
     *        同一条 CANCEL 消息会被投递两次（SSE 回声 + HTTP 响应），
     *        第二次必须被"已经是非 NULL 了"挡掉，否则那笔钱被减两次。
     *        `RoomMirrorStateStore` 在事务里先读旧值再决定。
     */
    @Query(
        """
        UPDATE order_index SET status = :status, cancelAppliedVersion = :cancelAppliedVersion
        WHERE storeKey = :storeKey AND fileId = :fileId AND orderUuid = :orderUuid
        """
    )
    suspend fun updateOrderStatus(
        storeKey: String,
        fileId: String,
        orderUuid: String,
        status: Int,
        cancelAppliedVersion: Int?,
    ): Int

    /**
     * 改 A 类备注之后更新索引里的文本。
     *
     * ⚠ `remarkOffset` **不动**：补丁是等长覆写，偏移永远有效。
     * ⚠ 新文本必须由提交方带进来（`OrderDelta.Patched.newRemark`），
     * 不能在这里去镜像里读那 150 字节 —— 事务里不许做文件 I/O。
     */
    @Query(
        """
        UPDATE order_index SET remark = :remark
        WHERE storeKey = :storeKey AND fileId = :fileId AND orderUuid = :orderUuid
        """
    )
    suspend fun updateOrderRemark(storeKey: String, fileId: String, orderUuid: String, remark: String?): Int

    // ==================== order_item_index ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(rows: List<OrderItemIndexEntity>)

    @Query("DELETE FROM order_item_index WHERE storeKey = :storeKey AND fileId = :fileId")
    suspend fun deleteItemsOfFile(storeKey: String, fileId: String): Int

    /**
     * 某个订单的件数（`Σ quantity`，**不是**行数）。
     *
     * 回退统计时要拿它：`order_index.itemCount` 存的是明细行数，
     * 而 `pieceCount` 这个指标要的是个数。两个数字都"看着像件数"，用错了不会报错。
     */
    @Query(
        """
        SELECT SUM(quantity) FROM order_item_index
        WHERE storeKey = :storeKey AND fileId = :fileId AND orderUuid = :orderUuid
        """
    )
    suspend fun sumQuantityOfOrder(storeKey: String, fileId: String, orderUuid: String): Int?

    // ==================== stats_contrib ====================

    /**
     * 给一条贡献加一个增量（可以是负数）。**先 UPDATE，影响 0 行再 INSERT。**
     *
     * @param frozen 仅在需要新建行时用到（现有行的 `frozen` **不动** ——
     *        归档是文件级的事件，由 [freezeContribOfScope] 统一置位，
     *        在这里顺手写会覆盖掉它）。
     */
    @Transaction
    suspend fun applyContribDelta(
        storeKey: String,
        scope: String,
        dimKey: String,
        metric: String,
        delta: Long,
        frozen: Int = 0,
    ) {
        val n = addContribDelta(storeKey, scope, dimKey, metric, delta)
        if (n == 0) {
            insertContrib(
                StatsContribEntity(
                    storeKey = storeKey,
                    scope = scope,
                    dimKey = dimKey,
                    metric = metric,
                    value = delta,
                    frozen = frozen,
                )
            )
        }
    }

    @Query(
        """
        UPDATE stats_contrib SET value = value + :delta
        WHERE storeKey = :storeKey AND scope = :scope AND dimKey = :dimKey AND metric = :metric
        """
    )
    suspend fun addContribDelta(storeKey: String, scope: String, dimKey: String, metric: String, delta: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertContrib(row: StatsContribEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContribs(rows: List<StatsContribEntity>)

    /**
     * 删掉某个 scope 的全部贡献行。FULL 重建的第一步（"先减旧贡献再加新贡献"）。
     *
     * ## ⚠ 这里**不带** `AND frozen = 0`，与方案 §5.4 的字面写法有意不同
     *
     * 方案里那句"FULL 重投影路径必须 `WHERE frozen=0`"要防的是**归档动作把历史统计清零**。
     * 这个防护落在别处、而且更靠得住：归档走 [freezeContribOfScope]，**根本不删行**。
     *
     * 而 FULL 路径如果只删 `frozen=0`，紧接着插入的新行（`frozen=0`）会与留下的
     * `frozen=1` 旧行**并存于同一个 (dimKey, metric)**，于是同一个月被算两遍。
     * 那比"归档被清零"更隐蔽：清零看得见（数字突然变 0），双算看不见（数字只是偏大）。
     *
     * 唯一会走到"对 ARCHIVED 文件做 FULL"的路径是 409 探测发现服务器其实还有这个文件，
     * 那时 `syncState` 本来就要回到 LIVE，重建出来的行 `frozen=0` 正是对的。
     */
    @Query("DELETE FROM stats_contrib WHERE storeKey = :storeKey AND scope = :scope")
    suspend fun deleteContribOfScope(storeKey: String, scope: String): Int

    /** 归档：把这个文件的贡献行标成冻结，**值一行都不改**。见 [deleteContribOfScope]。 */
    @Query("UPDATE stats_contrib SET frozen = 1 WHERE storeKey = :storeKey AND scope = :scope")
    suspend fun freezeContribOfScope(storeKey: String, scope: String): Int

    /**
     * 归档一个文件：改 `file_state` + 冻结贡献，**一个事务**。
     *
     * 分开做的话，进程在两步之间被杀会留下"游标说归档了、贡献还是活的"，
     * 之后一次 FULL 就会把它当成普通文件重建。
     */
    @Transaction
    suspend fun archiveFile(storeKey: String, fileId: String, fileType: String, year: Int, month: Int, at: Long) {
        if (markArchived(storeKey, fileId, at) == 0) {
            upsertFileState(
                FileStateEntity(
                    storeKey = storeKey,
                    fileId = fileId,
                    fileType = fileType,
                    year = year,
                    month = month,
                    // ⚠ 这里没有权威版本号可填：409 的响应体**不含**服务器版本，
                    // 而探测得到 LATEST 说明服务器根本没这个文件。0 是唯一诚实的值。
                    version = 0,
                    lastStreamId = null,
                    checksum = 0,
                    mirrorBytes = 0,
                    // 非 null + version=0 = "同步过了，但那个月确实没有订单"（三态语义）。
                    // 能走到归档，说明本地曾经同步过它。
                    firstSyncedAt = at,
                    syncState = "ARCHIVED",
                    lastSyncAttemptAt = at,
                )
            )
        }
        freezeContribOfScope(storeKey, com.example.myapplication.sync.StatsProjection.fileScope(fileId))
    }

    // ==================== order_outbox ====================
    //
    // ## ⚠ 这一节里没有任何 UPDATE `payload` 的方法，将来也不许加
    //
    // `payload` 是"将要原样发给服务端的那些字节"。它一旦被改，
    // ①已经发出去的那份与库里这份就不是同一个订单了（偏移全错），
    // ②而等长覆写不报错、校验和自洽、WAL 忠实回放 —— 没有任何机制会发现。
    // "用新价重算"因此是**删旧行 + 插新行（新 uuid）**：
    // `OutboxStore.enqueue(replacing = 旧uuid)`。⚠ 不要再加一个叫 `reprice` 的方法，
    // 理由见 `OrderOutboxEntity` 的类注释（会变成第二个能搬 OUTBOX 桶的入口）。
    //
    // ## ⚠ 状态字面量在这里是硬编码的
    //
    // `@Query` 是编译期常量，引用不到 `OrderOutboxEntity.DRAFT` 这些 Kotlin 常量。
    // 改常量名时必须逐条搜这一节的 SQL。

    /**
     * ABORT，**不是** REPLACE。
     *
     * REPLACE 在主键撞上时会先删旧行再插新行 —— 对一张 `payload` 不可变的表来说，
     * 那正好是"悄悄换掉字节"的唯一入口，而且它长得像个正常的 upsert，review 时看不出来。
     * 撞主键意味着客户端生成了重复的 uuid（`UUID.randomUUID` 不会，代码 bug 会），
     * 那时**必须炸**：静默覆盖会让先前那一单的贡献行留在 OUTBOX 桶里永远搬不走。
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(row: OrderOutboxEntity): Long

    /** 一笔逻辑订单拆成多块时整批插入。冲突策略与理由同 [insertOutbox]。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutboxRows(rows: List<OrderOutboxEntity>)

    @Query("SELECT * FROM order_outbox WHERE storeKey = :storeKey AND orderUuid = :orderUuid")
    suspend fun outboxRow(storeKey: String, orderUuid: String): OrderOutboxEntity?

    @Query(
        """
        SELECT * FROM order_outbox
        WHERE storeKey = :storeKey AND state = :state ORDER BY createdAt
        """
    )
    suspend fun outboxByState(storeKey: String, state: String): List<OrderOutboxEntity>

    /**
     * 「未上传订单」列表（方案 §5.2）。
     *
     * 排除 `PROMOTED` 而不是只挑 `READY`：`BLOCKED_MISSING_BOX_SIZE`、`IN_FLIGHT`、
     * `FAILED_PERMANENT` 都得让用户看见 —— 尤其 `IN_FLIGHT`，它是"结果未知"，
     * 藏起来的话用户会以为那一单丢了、然后手动再开一张。
     */
    @Query(
        """
        SELECT * FROM order_outbox
        WHERE storeKey = :storeKey AND state <> 'PROMOTED' ORDER BY createdAt
        """
    )
    suspend fun outboxPending(storeKey: String): List<OrderOutboxEntity>

    /**
     * **正在给 OUTBOX 桶贡献统计**的那些 uuid。
     *
     * 与 [outboxPending] 的差别只在多排除了 `DRAFT`：草稿还没成交，不算钱。
     * FULL 重建之后要靠这份名单把 OUTBOX 桶重新算出来（FULL 只覆盖 `FILE:` 桶，
     * 碰不到 OUTBOX，但重建过程里两边都得对得上账）。
     */
    @Query(
        """
        SELECT orderUuid FROM order_outbox
        WHERE storeKey = :storeKey AND state <> 'PROMOTED' AND state <> 'DRAFT'
        """
    )
    suspend fun outboxContributingUuids(storeKey: String): List<String>

    /**
     * 某个订单组里**还没提升**的块。
     *
     * 不带 fileId：outbox 行本来就不知道自己在哪个文件里（还没上传，没有偏移），
     * 而这张表的规模是"未上传的单"，几十行封顶，扫得起。
     *
     * ⚠ `groupId = ''` 表示"这一单没被拆过"（单块订单不写 `grp` 字段，
     * 见 `OrderGroupAssembler` 的类注释）。**不要用它查组** —— 所有单块订单会挤成一组。
     */
    @Query(
        """
        SELECT * FROM order_outbox
        WHERE storeKey = :storeKey AND groupId = :groupId AND state <> 'PROMOTED'
        """
    )
    suspend fun outboxRowsOfGroup(storeKey: String, groupId: String): List<OrderOutboxEntity>

    /**
     * 全部仍在贡献的 outbox 行按 `groupId` 汇总，给 FULL 重建 `order_group` 用。
     *
     * 一次查询拿全店，比"每个组查一次"少一个数量级的往返。
     * `groupId <> ''` 的理由同 [outboxRowsOfGroup]。
     */
    @Query(
        """
        SELECT groupId AS groupId, COUNT(*) AS orderCount, SUM(totalCents) AS totalCents
        FROM order_outbox
        WHERE storeKey = :storeKey AND groupId <> ''
              AND state <> 'PROMOTED' AND state <> 'DRAFT'
        GROUP BY groupId
        """
    )
    suspend fun outboxGroupSummary(storeKey: String): List<OutboxGroupSummary>

    /**
     * write-ahead 起飞标记。**发 `/add` 之前**调用，返回 1 才允许真的发。
     *
     * `AND state = 'READY'` 是这里的承重墙：它让"同一行被两个协程同时起飞"
     * 只有一个能拿到 1。少了它，两个上传器会各发一次 `/add`，
     * 服务端不查重 → 同一个 uuid 在文件里出现两行 → 镜像比服务器长 → 偏移全错。
     *
     * @return 1 = 抢到了；0 = 这一行不在 READY（已经起飞过 / 已被提升 / 被阻塞）。
     */
    @Query(
        """
        UPDATE order_outbox SET state = 'IN_FLIGHT'
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid AND state = 'READY'
        """
    )
    suspend fun markOutboxInFlight(storeKey: String, orderUuid: String): Int

    /**
     * CAS 式状态迁移：`WHERE state = :expectedState`。
     *
     * @return 受影响行数。0 = 状态已经被别人改过了，调用方**必须**当成失败处理，
     *         不能继续往下做依赖这次迁移的动作（尤其是搬统计贡献）。
     */
    @Query(
        """
        UPDATE order_outbox SET state = :toState, lastError = :lastError
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid AND state = :expectedState
        """
    )
    suspend fun transitionOutbox(
        storeKey: String,
        orderUuid: String,
        expectedState: String,
        toState: String,
        lastError: String?,
    ): Int

    /**
     * 失败一次。**不带状态条件**：起飞前失败（429 退回 READY）与起飞后失败
     * （结果未知，留在 IN_FLIGHT）都要计数，而这两种情况下的当前状态不同。
     */
    @Query(
        """
        UPDATE order_outbox SET attemptCount = attemptCount + 1, lastError = :lastError
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid
        """
    )
    suspend fun bumpOutboxAttempt(storeKey: String, orderUuid: String, lastError: String?): Int

    /**
     * 提升完成。**返回受影响行数，只有 1 才授权把 OUTBOX 桶的贡献减掉。**
     *
     * `state NOT IN ('DRAFT','PROMOTED')` 两条都不能少：
     *  - `PROMOTED` 已经搬过一次了，再搬就是**双减**（营业额少一笔，静默）；
     *  - `DRAFT` 从来没贡献过，减它会把别人的贡献吃掉。
     *
     * 这一条与 `OrderFileDao.insertOrderIndex` 的 `-1` 判据是同一个思路的两侧：
     * FILE 桶那边靠"索引行插进去了没有"，OUTBOX 桶这边靠"状态迁移发生了没有"，
     * 两边都必须是**数据库告诉我们**，不能靠调用方记着一个 boolean。
     */
    @Query(
        """
        UPDATE order_outbox SET state = 'PROMOTED', lastError = NULL
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid
              AND state <> 'PROMOTED' AND state <> 'DRAFT'
        """
    )
    suspend fun markOutboxPromoted(storeKey: String, orderUuid: String): Int

    /**
     * 删掉一行。**`IN_FLIGHT` 与 `PROMOTED` 都拒绝**。
     *
     * 这两行是"这个 uuid 已经发出去过了"的唯一凭据：
     *  - 删掉 `IN_FLIGHT` → 重启后认不出它、必然重推 → 服务器文件里两行同 uuid；
     *  - 删掉 `PROMOTED` → 同上，而且它已经在镜像里了，重推是**确定**会重复的。
     *
     * 已上传的订单要撤销只能走 `/patch` 取消（`status 1→0`），不能从这里删。
     *
     * @return 0 = 拒绝删。调用方要告诉用户"这一单已经在服务器上/正在上传"。
     */
    @Query(
        """
        DELETE FROM order_outbox
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid
              AND state <> 'IN_FLIGHT' AND state <> 'PROMOTED'
        """
    )
    suspend fun deleteOutboxRow(storeKey: String, orderUuid: String): Int

    /** 挂起：记下缺哪几个商品的箱规。只在 [markOutboxBlocked] 里被用到。 */
    @Query(
        """
        UPDATE order_outbox
        SET state = 'BLOCKED_MISSING_BOX_SIZE', blockedReason = :reason, blockedBarcodes = :barcodesCsv
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid AND state <> 'PROMOTED'
        """
    )
    suspend fun markOutboxBlocked(
        storeKey: String,
        orderUuid: String,
        reason: String,
        barcodesCsv: String?,
    ): Int

    /**
     * 箱规补齐后放行。`WHERE state = 'BLOCKED_MISSING_BOX_SIZE'` 是 CAS：
     * 只有真被箱规挡住的那一行才该被这条语句放行，
     * 否则一次"补齐箱规"会把 `FAILED_PERMANENT` 的单也一起放出去重推。
     */
    @Query(
        """
        UPDATE order_outbox SET state = 'READY', blockedReason = NULL, blockedBarcodes = NULL
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid
              AND state = 'BLOCKED_MISSING_BOX_SIZE'
        """
    )
    suspend fun unblockOutbox(storeKey: String, orderUuid: String): Int

    /** 全部因缺箱规而挂起的行。`OutboxStore.refreshBlocked` 逐行核对哪些规格已经补上了。 */
    @Query(
        """
        SELECT * FROM order_outbox
        WHERE storeKey = :storeKey AND state = 'BLOCKED_MISSING_BOX_SIZE' ORDER BY createdAt
        """
    )
    suspend fun outboxBlockedRows(storeKey: String): List<OrderOutboxEntity>

    /**
     * 收窄"还缺哪几个规格"。**不解除阻塞** —— 解除只发生在剩下的 CSV 变成空串时，
     * 由 `OutboxStore.refreshBlocked` 改调 [unblockOutbox]。
     *
     * 分成两个方法是有意的：一条 SQL 里既改 CSV 又按"改完是否为空"决定状态，
     * 就得在 SQL 里写 `CASE WHEN :csv = '' THEN 'READY' ELSE …`，
     * 而那个判据一旦与 Kotlin 侧不一致（比如 CSV 里留了个尾随逗号），
     * 结果是"一行永远卡在 BLOCKED 且看不出缺什么"。
     */
    @Query(
        """
        UPDATE order_outbox SET blockedBarcodes = :barcodesCsv, blockedReason = :reason
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid
              AND state = 'BLOCKED_MISSING_BOX_SIZE'
        """
    )
    suspend fun updateOutboxBlockedBarcodes(
        storeKey: String,
        orderUuid: String,
        reason: String,
        barcodesCsv: String?,
    ): Int

    // ==================== order_group ====================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderGroup(row: OrderGroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderGroups(rows: List<OrderGroupEntity>)

    @Query("SELECT * FROM order_group WHERE storeKey = :storeKey AND groupId = :groupId")
    suspend fun orderGroup(storeKey: String, groupId: String): OrderGroupEntity?

    @Query("SELECT * FROM order_group WHERE storeKey = :storeKey ORDER BY createdAt DESC")
    suspend fun allOrderGroups(storeKey: String): List<OrderGroupEntity>

    @Query("DELETE FROM order_group WHERE storeKey = :storeKey AND groupId = :groupId")
    suspend fun deleteOrderGroup(storeKey: String, groupId: String): Int

    /**
     * 某个组**已进镜像**的成员。
     *
     * ⚠ 必须带 fileId。见 `OrderGroupEntity` 的类注释："组不跨 fileId"是结构性的，
     * 而少了这个条件就是 `order_index` 的全表扫描（十万行量级）。
     */
    @Query(
        """
        SELECT * FROM order_index
        WHERE storeKey = :storeKey AND fileId = :fileId AND groupId = :groupId
        """
    )
    suspend fun orderIndexesOfGroup(storeKey: String, fileId: String, groupId: String): List<OrderIndexEntity>

    /** FULL 重建前，先记下这个文件里出现过哪些组（重建后逐组重算）。 */
    @Query(
        """
        SELECT DISTINCT groupId FROM order_index
        WHERE storeKey = :storeKey AND fileId = :fileId AND groupId IS NOT NULL
        """
    )
    suspend fun groupIdsOfFile(storeKey: String, fileId: String): List<String>

    /**
     * 从两个成员表**重算**一个组，一个事务。
     *
     * ## 为什么是重算而不是增量维护
     *
     * 增量维护要存"已取消几块"这样的计数器，而计数器会漂移：FULL 重建把 `order_index`
     * 整表换掉、`commitDiverged` 之后索引与镜像不一致、进程在两步之间被杀 —— 每一种都会让
     * 计数器停在错的值上，而且 UI 显示的是"3 笔中已取消 2 笔"这种用户会照着做决定的数字。
     * 重算每次都从成员表现状得出，**自愈**。
     *
     * 代价是每次多两条查询。这两条都被索引收窄过（见 `OrderGroupEntity` 的类注释），
     * 而触发点是用户动作（下单、取消、删除），不是每条流消息。
     *
     * ## 成员来自两张表，`PROMOTED` 必须排除
     *
     * 一个组可能一部分块已经进镜像、另一部分还躺在 outbox 里（上传到一半断网）。
     * `order_index` 与 `order_outbox` 各数一半，合起来才是逻辑订单的块数。
     * [outboxRowsOfGroup] 已经排除了 `PROMOTED` —— 提升进镜像的那一块此刻同时存在于
     * 两张表，排除它才不会被数两遍。
     *
     * @param fileId 组不跨文件，所以这个参数总是拿得到的；见 `OrderGroupEntity` 的类注释
     * @param now 仅在需要新建组行时用作 `createdAt`（已有行的 `createdAt` **保留**，
     *        否则一次取消就会把"这一单是什么时候开的"改成"什么时候取消的"）
     */
    @Transaction
    suspend fun recomputeOrderGroup(storeKey: String, fileId: String, groupId: String, now: Long) {
        val synced = orderIndexesOfGroup(storeKey, fileId, groupId)
        val pending = outboxRowsOfGroup(storeKey, groupId)
        if (synced.isEmpty() && pending.isEmpty()) {
            deleteOrderGroup(storeKey, groupId)
            return
        }

        val total = synced.size + pending.size
        // 只有已进镜像的块才可能被取消：outbox 里的还没上服务器，谈不上取消。
        val cancelled = synced.count { it.status == 0 }
        val cancelState = when {
            cancelled == 0 -> OrderGroupEntity.CANCEL_NONE
            cancelled < total -> OrderGroupEntity.CANCEL_PARTIAL
            else -> OrderGroupEntity.CANCEL_ALL
        }

        val existing = orderGroup(storeKey, groupId)
        upsertOrderGroup(
            OrderGroupEntity(
                storeKey = storeKey,
                groupId = groupId,
                writer = synced.firstOrNull()?.writer ?: pending.first().writer,
                orderCount = total,
                totalCents = synced.sumOf { it.totalCents } + pending.sumOf { it.totalCents },
                createdAt = existing?.createdAt
                    ?: pending.minOfOrNull { it.createdAt }
                    ?: synced.minOf { it.orderTs },
                cancelState = cancelState,
            )
        )
    }

    // ==================== 诊断 ====================

    /**
     * `orderTs = 0` 的订单数。这些订单的时间是**猜的**
     * （[com.example.myapplication.sync.StatsProjection.effectiveTs] 用文件月份兜底），
     * 诊断页要列出来。别的客户端不写 `content.ts` 就会产生它们。
     */
    @Query("SELECT COUNT(*) FROM order_index WHERE storeKey = :storeKey AND orderTs = 0")
    suspend fun countOrdersMissingTs(storeKey: String): Int

    /**
     * `OUTBOX` 桶的金额合计 **减去** 应在其中贡献的 outbox 行金额合计。
     *
     * **返回非 0 就是 bug**，而且是不会报错的那一种：搬桶（`markOutboxPromoted` 的返回值判据）
     * 少做一次就永久多算一笔，多做一次就永久少算一笔，两者都只体现为"营业额对不上"。
     *
     * 只查 `amountCents` 这一个指标就够：三个指标由同一个 `contributionOf` 一次产出，
     * 搬桶也是一起搬的，不可能只漏一个。
     *
     * ⚠ SQL 里的 `'OUTBOX'` 与 `'B'` 是硬编码字面量，对应
     * [com.example.myapplication.sync.StatsProjection.OUTBOX_SCOPE] 与
     * [com.example.myapplication.sync.OrderFileId.TYPE_B]；
     * `state` 的两个排除项与 [outboxContributingUuids] 必须保持一致，
     * 否则这条自检会稳定地报出一个根本不存在的差值 —— 那比没有自检更糟，
     * 因为下一次真的出问题时没人会信它。
     */
    @Query(
        """
        SELECT IFNULL((SELECT SUM(value) FROM stats_contrib
                       WHERE storeKey = :storeKey
                             AND scope IN ('OUTBOX', 'OUTBOX_FAILED')
                             AND metric = 'amountCents'), 0)
             - IFNULL((SELECT SUM(totalCents) FROM order_outbox
                       WHERE storeKey = :storeKey AND fileType = 'B'
                             AND state <> 'PROMOTED' AND state <> 'DRAFT'), 0)
        """
    )
    suspend fun outboxContribSelfCheck(storeKey: String): Long

    /**
     * `stats_agg` 与 `stats_contrib` 的一致性自检。
     *
     * **返回任何一行都是 bug。** 两张表必须由同一组增量维护，
     * 出现差值说明有一条路径只写了其中一张 —— 而统计页读的是 `stats_agg`，
     * 所以用户看到的数字是错的，且没有任何报错。
     *
     * ⚠ `stats_agg` 由 P8 的 `StatsProjector` 维护。在它落地之前这张表是空的，
     * 本查询会把所有非零贡献都报成差值 —— 那是预期的，不是 bug。
     */
    @Query(
        """
        SELECT c.dimKey AS dimKey, c.metric AS metric,
               c.sum AS contribValue, IFNULL(a.value, 0) AS aggValue
        FROM (
            SELECT dimKey, metric, SUM(value) AS sum
            FROM stats_contrib WHERE storeKey = :storeKey GROUP BY dimKey, metric
        ) c
        LEFT JOIN stats_agg a
          ON a.storeKey = :storeKey AND a.dimKey = c.dimKey AND a.metric = c.metric
        WHERE c.sum <> IFNULL(a.value, 0)
        """
    )
    suspend fun statsSelfCheck(storeKey: String): List<StatsMismatch>

    // ==================== UI 读取 ====================
    //
    // 上面那些查询全是给**同步机器**用的按主键点查。下面两条是给界面用的，
    // 返回 `Flow` 而不是 `suspend`：同步随时可能在后台落地一笔新订单，
    // 列表必须自己跟上。用 suspend 的话每次同步完都得有人记得去刷一次，
    // 忘了就是"明明上传成功了，列表里还挂在未上传那一栏"。

    /**
     * 已同步区（镜像里）的订单，按下单时间倒序。
     *
     * @param writer null = 不按人筛。传了就只返回这个店员开的单。
     * @param limit  上限。**必须给**：一个店几年下来能有几万笔，
     *               一次全读进内存再让 Compose 摆列表是会 OOM 的。
     *
     * ⚠ 只用得上 `(storeKey, …)` 这段索引前缀：现有两个索引是
     * `(storeKey, writer, orderTs)` 与 `(storeKey, fileId, orderTs)`，
     * 都没有"只按 storeKey + orderTs 范围"的形状，传了 [writer] 才走得上第一个。
     * 用户规模（一天上百单）下全店扫描可以接受；订单量再上一个数量级就要补
     * `(storeKey, orderTs)` 索引 —— 那是 Room v5 的事。
     */
    @Query(
        """
        SELECT * FROM order_index
        WHERE storeKey = :storeKey
          AND orderTs >= :fromTs AND orderTs < :toTs
          AND (:writer IS NULL OR writer = :writer)
        ORDER BY orderTs DESC
        LIMIT :limit
        """
    )
    fun observeOrderIndexInRange(
        storeKey: String,
        fromTs: Long,
        toTs: Long,
        writer: String?,
        limit: Int,
    ): Flow<List<OrderIndexEntity>>

    /**
     * 全部文件游标，响应式版本。统计页用它算那个 `*`（哪些月份本地数据不完整）。
     *
     * 与 [allFileStates] 的区别只是返回 `Flow`：同步落地后 `firstSyncedAt` 会从 null 变成时间戳，
     * 而 `*` 必须当场消失 —— 用户做完一次全量同步还看到星号，会以为同步没生效。
     */
    @Query("SELECT * FROM file_state WHERE storeKey = :storeKey ORDER BY fileType, year, month")
    fun observeFileStates(storeKey: String): Flow<List<FileStateEntity>>

    /**
     * 未上传的订单（[observeOrderIndexInRange] 的另一半），响应式版本。
     *
     * 排除 `PROMOTED`：那些行**保留不删**（是"这个 uuid 已上传"的唯一凭据），
     * 但它们对应的订单已经在 `order_index` 里了 —— 两边都列出来就是同一笔单显示两遍。
     *
     * `DRAFT` 也在这里返回，由调用方决定要不要显示：草稿连"成交了吗"都没确定，
     * 混进"未上传"列表会让用户以为有一笔钱没上去。
     */
    @Query(
        """
        SELECT * FROM order_outbox
        WHERE storeKey = :storeKey AND state != 'PROMOTED'
        ORDER BY createdAt
        """
    )
    fun observeOutboxPending(storeKey: String): Flow<List<OrderOutboxEntity>>

    /**
     * 一笔订单的明细行，按 (fileId, lineNo) 排序。订单详情页用。
     *
     * 按 **uuid** 查而不是 (fileId, uuid)：UI 手上的列表行不带 fileId
     * （`OrderRow` 省掉了那一列），而一笔订单不可能跨文件 ——
     * 拆单是同一个 fileId 下的多行、各自有独立 uuid。
     * 真出现了同 uuid 跨文件（那已经是"服务端不查重"级别的事故），
     * 全列出来比只列一个文件更容易看出问题。
     */
    @Query(
        """
        SELECT * FROM order_item_index
        WHERE storeKey = :storeKey AND orderUuid = :orderUuid
        ORDER BY fileId, lineNo
        """
    )
    suspend fun orderItemsOfUuid(storeKey: String, orderUuid: String): List<OrderItemIndexEntity>

    /**
     * 按 `dimKey`（`<yyyy-MM-dd>|w:<writer>`）与 `metric` 聚合的贡献合计。
     *
     * ## 为什么用 `substr(dimKey, 1, 10)` 裁区间
     *
     * `dimKey` 的日期部分是**恒宽**的（`%04d-%02d-%02d`，见 `StatsProjection.dateKey`），
     * 所以字典序 `BETWEEN` 与日期序等价。拆一列出来要改 schema + 迁移，
     * 而收益只是让这条查询能走索引 —— 现在它靠主键的 `storeKey` 前缀过滤，
     * 一个店的贡献行是"天数 × 店员数 × 3 个指标"，量级几千行，全扫可以接受。
     *
     * ## 不筛 `scope`，也**不排除** `frozen`
     *
     * `FILE:<fileId>`（已同步）与 `OUTBOX`（未上传）都要算进营业额：
     * 未上传的单货已经给客人了，不算的话用户开完单看统计没变，以为丢单。
     * 归档月份同理 —— 归档只是"不再同步"，不是"不再统计"。
     *
     * ⚠ 这与 FULL 重投影路径的 `WHERE frozen = 0` 不矛盾：
     * 那边是"不要覆盖已冻结的行"，这边是"冻结的钱仍然是钱"。
     *
     * ## [includeFailed] 是唯一的 scope 过滤
     *
     * `OUTBOX_FAILED`（重推到顶 / 413 / 400 的单）算不算营业额由用户决定：
     * 货确实给客人了、钱真收了，所以**默认算**；但有的店主只想看"成功进了系统的钱"。
     *
     * ⚠ 这个开关必须是**读取侧**的事，不能靠切换时改库：
     * 改库就要把贡献在两个桶之间搬来搬去，来回切几次之后任何一次算错都会永久留在账上，
     * 而且看不出来。放在查询里，切多少次都不会漂移。
     */
    @Query(
        """
        SELECT dimKey AS dimKey, metric AS metric, SUM(value) AS total
        FROM stats_contrib
        WHERE storeKey = :storeKey
          AND substr(dimKey, 1, 10) BETWEEN :fromDate AND :toDate
          AND (:includeFailed = 1 OR scope <> 'OUTBOX_FAILED')
        GROUP BY dimKey, metric
        """
    )
    fun observeContribInRange(
        storeKey: String,
        fromDate: String,
        toDate: String,
        includeFailed: Boolean,
    ): Flow<List<ContribAggregate>>
}

/** [OrderFileDao.observeContribInRange] 的一行。`SUM()` 分组后每组至少有一行，不可能为 NULL。 */
data class ContribAggregate(
    val dimKey: String,
    val metric: String,
    val total: Long,
)

/** [OrderFileDao.statsSelfCheck] 的一行差值。 */
data class StatsMismatch(
    val dimKey: String,
    val metric: String,
    val contribValue: Long,
    val aggValue: Long,
)

/**
 * [OrderFileDao.outboxGroupSummary] 的一行：某个订单组里**还没提升**的块数与金额合计。
 *
 * `totalCents` 声明成 `Long` 而不是 `Long?`：`GROUP BY` 出来的每组至少有一行，
 * `SUM()` 不可能是 NULL。（`SUM()` 返回 NULL 只发生在"一行都没有"的时候，
 * 而那时这一组根本不会出现在结果里。）
 */
data class OutboxGroupSummary(
    val groupId: String,
    val orderCount: Int,
    val totalCents: Long,
)
