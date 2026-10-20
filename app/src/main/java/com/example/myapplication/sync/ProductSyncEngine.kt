package com.example.myapplication.sync

import com.example.myapplication.data.Product
import com.example.myapplication.db.ProductDao
import com.example.myapplication.db.ProductLocal
import com.example.myapplication.db.ProductPendingChange
import com.example.myapplication.db.ProductSynced
import com.example.myapplication.db.ProductToken
import com.example.myapplication.db.ProductTokenizer
import com.example.myapplication.db.ProductTombstone
import com.example.myapplication.db.StockObservation
import com.example.myapplication.db.StoreMetaDao
import com.example.myapplication.net.ApiError
import com.example.myapplication.net.ProductApi
import com.example.myapplication.net.dto.ProductAddRequestDto
import com.example.myapplication.net.dto.ProductBatchUpsertRequestDto
import com.example.myapplication.net.dto.StreamFields
import com.example.myapplication.net.dto.SyncDataResponseDto
import com.example.myapplication.net.dto.SyncKind
import java.math.BigDecimal

/**
 * 商品同步：把 [SyncDataResponseDto] 落地成本地的两张商品表 + 库存基线。
 *
 * 它出现在两个地方（登录响应的 `syncData`、SSE 建连后的一次性补发），形状一样，
 * 所以只有一个入口 [apply]。
 *
 * ## 三态（其实是四态）与各自的陷阱
 *
 * | `type` | 动作 | 陷阱 |
 * |---|---|---|
 * | `FULL` | 整表替换 `product_synced` + 墓碑 + 一批绝对库存观测 | **只碰 synced 表**，见下面「分区」 |
 * | `INCREMENTAL` | 逐条覆盖 | 条目可能有**空洞**，见 [Outcome.HoleDetected] |
 * | `SERVER_BEHIND` | **什么都不做**，交给用户二选一 | 顺手写游标 = 不可逆的商品丢失 |
 * | `LATEST` | 商品同步**不会**返回它 | 收到就说明协议变了，见 [Outcome.UnknownType] |
 *
 * ## 分区：FULL 删不光本地商品，是结构保证而不是代码检查
 *
 * `product_local`（从没推上去的）与 `product_synced`（服务器权威）是两张表。
 * FULL 走 [ProductDao.replaceSyncedWithTokens]，那个方法只 `clearSynced` ——
 * 所以"首次商品 FULL 删光 400 条手录商品"在这套结构下**没有代码路径**。
 * 单表 + `syncState='LOCAL_ONLY'` 标记列也能做到同样的事，但那要靠每个写入点
 * 都记得先检查标记，漏一次就是不可逆的丢失（方案 R1）。
 *
 * ## ⚠ 库存落地一律走 [StockLedger]，且**必须在商品行写完之后**
 *
 * `StockDao.applyAbsolute*` 写基线用的是 `UPDATE`：命中 0 行就退回去写 `product_local`，
 * 两张表都没有就**什么都不写但去重键已经烧掉了**。所以顺序反了的后果是静默的：
 * 那笔库存永远丢了，重投递也救不回来（去重命中）。
 * FULL 与 INCREMENTAL 两条路径都遵守「先写行、再 [StockLedger.applyAbsoluteBatch]、最后推游标」。
 *
 * ## ⚠ INCREMENTAL 里**价格条目必须先于库存条目**
 *
 * 同一次商品新增会**同时**在两条流里各产生一条（版本号各自独立）。库存那条带来一个
 * 绝对观测；如果它先跑，商品行还不存在 → 上面那个「去重键烧掉、库存丢失」的分支。
 * 价格条目先跑就把行建好了。见 [applyIncremental] 里的注释。
 *
 * ## 未验证假设（照方案 §八 的规矩记在这里，不凭直觉填空）
 *
 * **库存流条目的 `new_stock` 是「写这条 entry 那一刻」的服务器绝对值，而 B 类订单的扣减
 * 不写这条流。** 于是"把边界推到当前 `file_state.version`"在一种时序下会偏高：
 * 本地镜像**已经**含有某笔订单、而我们此刻应用的库存条目在服务端**早于**那笔订单。
 * 协议不提供 `version_stock` 与 per-fileId 版本之间的交叉参照，客户端无法判定这种时序。
 * 兜底是 [StockLedger.reconcile]：每次 `/add` 的响应都带一个**当下**的绝对值，
 * 窗口因此只开到下一笔订单为止。偏低会被补货清单看见，偏高不会 —— 所以这个残留风险
 * 记在这里而不是假装解决了。
 */
class ProductSyncEngine(
    private val products: ProductDao,
    private val storeMeta: StoreMetaDao,
    private val stockLedger: StockLedger,
    private val api: ProductApi,
    /** 只写进 `updatedAt` / `deletedAt` / `queuedAt` 这类**给人看**的字段，不参与任何判定。 */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 应用一次商品同步响应。
     *
     * @return 一律返回值，**不抛异常**（网络层已经抛过了，这里抛出去的是数据问题）。
     *         调用方必须检查 [Outcome]：[Outcome.ServerBehind] 与 [Outcome.HoleDetected]
     *         都需要它做点事，静默忽略会让本地与服务器永久分歧。
     */
    suspend fun apply(storeKey: String, resp: SyncDataResponseDto): Outcome {
        // ⚠ 这一行对 **FULL 也是必需的**，看着像多余：`setPriceVersion` 是 UPDATE，
        // 没有 store_meta 行时它命中 0 行、**不报错**，于是游标永远停在 0 →
        // 每次同步都 FULL 一遍（400 条商品 × 8 台设备，每次登录都来一轮）。
        val meta = storeMeta.get(storeKey)
            ?: return Outcome.Malformed("store_meta 里没有 $storeKey，游标无处可写")

        return when (resp.kind) {
            SyncKind.FULL -> applyFull(storeKey, resp)
            SyncKind.INCREMENTAL -> applyIncremental(storeKey, meta.priceVersion, meta.stockVersion, resp)
            SyncKind.SERVER_BEHIND -> Outcome.ServerBehind(
                serverPriceVersion = resp.priceVersion ?: 0,
                serverStockVersion = resp.stockVersion ?: 0,
                localPriceVersion = meta.priceVersion,
                localStockVersion = meta.stockVersion,
            )
            // LATEST 落在这里：`RedisSyncService.buildSyncResponse` 的三个分支是
            // SERVER_BEHIND / FULL / INCREMENTAL，商品同步**没有** LATEST
            // （那是订单文件与目录同步的取值）。收到它说明服务端改了协议 ——
            // 此时唯一安全的动作是不动本地数据。
            SyncKind.LATEST, SyncKind.UNKNOWN -> Outcome.UnknownType(resp.type)
        }
    }

    // ==================== FULL ====================

    private suspend fun applyFull(storeKey: String, resp: SyncDataResponseDto): Outcome {
        val snapshot = resp.products
            ?: return Outcome.Malformed("FULL 响应里没有 products")
        // `SyncDataResponse.priceVersion/stockVersion` 是原始 `int`，JSON 里必然存在。
        // 这里判 null 只是因为 Gson 会把它填成 null（字段声明成了可空）——
        // 少了它就没法算去重键（见 `DedupKeys.productFull` 为什么必须带两个版本号）。
        val priceV = resp.priceVersion
            ?: return Outcome.Malformed("FULL 响应里没有 priceVersion")
        val stockV = resp.stockVersion
            ?: return Outcome.Malformed("FULL 响应里没有 stockVersion")

        // 旧行留着有两个用处：给消失的条码写墓碑（要名字），以及在快照里 `name` 缺失时兜底。
        val old = products.getAllSynced(storeKey).associateBy { it.barcode }
        val at = now()

        val rows = ArrayList<ProductSynced>(snapshot.size)
        val tokens = ArrayList<ProductToken>()
        val observations = ArrayList<StockObservation>(snapshot.size)
        for ((barcode, p) in snapshot) {
            if (barcode.isBlank()) continue
            // FULL 是带 `name` 与 `orderable` 的（只有两条属性流不带，见 StreamFields 陷阱三）。
            // 兜底成空串而不是跳过这一行：跳过 = 商品从 synced 表消失 = 库存变 null =
            // 从补货清单里静默消失。空名字看得见，消失看不见。
            val name = p.name ?: old[barcode]?.name ?: ""
            val row = ProductSynced(
                storeKey = storeKey,
                barcode = barcode,
                name = name,
                // 服务端把 null 价格编码成 `"0"`（`ProductDataResponse` 的 getter 上就这么写的），
                // 所以走到 `?: ZERO` 说明数据本身有问题；0 比"整行跳过"安全（同上）。
                price = p.price?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                boxPrice = boxPriceOf(p.boxPrice),
                boxSize = p.boxSize ?: 0,
                stock = p.stock ?: 0,
                // 缺 `orderable` 时取 true：反了的话商品会静默变成不可订（进不了 A 类进货单），
                // 而 true 最多是下单时被服务端 400 挡回来 —— 看得见的那种错。
                orderable = p.orderable ?: true,
                updatedAt = at,
            )
            rows += row
            tokens += tokensOf(storeKey, barcode, name)
            observations += StockObservation(
                StockLedger.DedupKeys.productFull(priceV, stockV, barcode), barcode, row.stock,
            )
        }

        // ① 整表替换。`replaceSyncedWithTokens` 内部会把 `product_local` 的索引一起重建 ——
        //    本地行此前可能因 barcode 撞车被 synced 遮住，替换之后不一定了。
        products.replaceSyncedWithTokens(rows, tokens, storeKey)

        // ② 消失的条码写墓碑（只增不删，历史订单页靠它显示商品名）。
        //    IGNORE 冲突策略 = 保留**第一次**删除时的名字；商品被删→重建→再删时墓碑里是旧名。
        //    这是 `insertTombstone` 的既有语义，历史订单显示旧名比显示不出名字好。
        var deleted = 0
        for ((barcode, row) in old) {
            if (snapshot.containsKey(barcode)) continue
            products.insertTombstone(ProductTombstone(storeKey, barcode, row.name, at))
            deleted++
        }

        // ③ 库存观测。**必须在 ① 之后**：`writeSyncedStock` 是 UPDATE，行还不存在就命中 0 行。
        //    注意 ① 的整行 upsert 已经把 `stock` 写过了，这一步真正不可替代的贡献是
        //    **去重日志 + 推进吸收边界**（少了它，订单侧那笔扣减会被再算一次）。
        val absorbed = stockLedger.applyAbsoluteBatch(storeKey, observations, StockLedger.SOURCE_PRODUCT_FULL)

        // ④ 最后推游标。①②③ 之间任何一步崩了，游标没动 → 下次同步还是 FULL →
        //    同样的去重键 → 幂等地重来一遍。反过来（先推游标）崩溃就永久丢一批商品。
        storeMeta.setPriceVersion(storeKey, priceV)
        storeMeta.setStockVersion(storeKey, stockV)

        return Outcome.Applied(
            kind = SyncKind.FULL,
            priceVersion = priceV,
            stockVersion = stockV,
            productsChanged = rows.size,
            stockObservations = absorbed,
            deleted = deleted,
        )
    }

    // ==================== INCREMENTAL ====================

    private suspend fun applyIncremental(
        storeKey: String,
        localPrice: Int,
        localStock: Int,
        resp: SyncDataResponseDto,
    ): Outcome {
        val priceEntries = resp.priceStreamEntries.orEmpty()
        val stockEntries = resp.stockStreamEntries.orEmpty()

        // ⚠ 价格先、库存后。理由见类注释：库存条目带的绝对观测要求商品行**已经存在**，
        // 而新增商品的行是价格条目（`action=ADD`）建的。
        val price = applyPriceEntries(storeKey, priceEntries, localPrice)
        val stock = applyStockEntries(storeKey, stockEntries, localStock)

        // 空洞判定：两条游标**各自独立**地推。
        //
        // `resp.priceVersion` 是"服务端此刻的版本"，不是"我给你的条目里最大的版本"。
        // 两者不等就说明中间有条目没送到 —— 典型原因是 `PRICE_STREAM_MAXLEN` 把老的裁掉了
        // （方案 R5：默认 200 小于 400 条商品，首次上线就会裁）。
        //
        // ⚠ 此时的正确动作是**把游标归零**，与 SERVER_BEHIND 的处理正好相反，
        // 别把两处"统一"了：
        //  - 空洞 = 服务器比本地**多**，FULL 是补数据；
        //  - SERVER_BEHIND = 服务器比本地**少**，FULL 是用短的那份覆盖长的那份（不可逆丢失）。
        // 只"不推进"是不够的：下次同步还从同一个游标要，被裁掉的条目永远回不来，
        // 于是每次同步都报同一个空洞，本地永久停在旧数据上。
        val priceHole = price.maxSeen < (resp.priceVersion ?: localPrice)
        val stockHole = stock.maxSeen < (resp.stockVersion ?: localStock)
        val priceNeedsFull = priceHole || price.bad > 0 || price.tooManyFetches
        val stockNeedsFull = stockHole || stock.bad > 0

        storeMeta.setPriceVersion(storeKey, if (priceNeedsFull) 0 else resp.priceVersion ?: localPrice)
        storeMeta.setStockVersion(storeKey, if (stockNeedsFull) 0 else resp.stockVersion ?: localStock)

        if (priceNeedsFull || stockNeedsFull) {
            return Outcome.HoleDetected(
                reason = when {
                    price.tooManyFetches -> "有 ${price.toFetch} 个陌生条码要补拉，超过 $MAX_ADD_FETCHES 个，改成一次 FULL 更省"
                    price.bad > 0 || stock.bad > 0 -> "有 ${price.bad + stock.bad} 条 entry 解析不了（协议可能变了）"
                    priceHole && stockHole -> "价格流与库存流都有空洞（本地 ${localPrice}/${localStock}，服务端 ${resp.priceVersion}/${resp.stockVersion}）"
                    priceHole -> "价格流有空洞（本地 $localPrice，收到最大 ${price.maxSeen}，服务端 ${resp.priceVersion}）"
                    else -> "库存流有空洞（本地 $localStock，收到最大 ${stock.maxSeen}，服务端 ${resp.stockVersion}）"
                },
                appliedPriceEntries = price.applied,
                appliedStockObservations = stock.applied,
                resetPriceCursor = priceNeedsFull,
                resetStockCursor = stockNeedsFull,
            )
        }

        return Outcome.Applied(
            kind = SyncKind.INCREMENTAL,
            priceVersion = resp.priceVersion ?: localPrice,
            stockVersion = resp.stockVersion ?: localStock,
            productsChanged = price.applied,
            stockObservations = stock.applied,
            deleted = price.deleted,
        )
    }

    /**
     * 应用价格流条目。
     *
     * 逐条串行、边解析边写：条目里的 `action=ADD` 可能要补拉一次 `GET /api/products/{barcode}`，
     * 而**同一条码的后续条目必须看到前面写下的行**（否则 v5 的 ADD 与 v7 的 UPDATE 都去补拉，
     * 而 v7 那次会用旧名字覆盖 v5 的结果）。所以不能先收集再批量。
     */
    private suspend fun applyPriceEntries(
        storeKey: String,
        entries: List<Map<String, String>>,
        localCursor: Int,
    ): PricePass {
        var applied = 0
        var deleted = 0
        var bad = 0
        var fetches = 0
        // 没有条目时以本地游标为基准：服务端版本更高却一条都没给 = 条目被裁掉了 = 空洞。
        var maxSeen = localCursor

        for (e in entries) {
            val version = e[StreamFields.VERSION]?.toIntOrNull()
            val barcode = e[StreamFields.BARCODE]?.takeIf { it.isNotBlank() }
            val action = e[StreamFields.ACTION]
            if (version == null || barcode == null || action == null) {
                // ⚠ 解析不了就**不计入 maxSeen**。计进去等于宣布"这一条我处理过了"，
                // 于是游标推过去、这条变更永久丢失。不计 → 空洞 → FULL 自愈。
                bad++
                continue
            }
            if (version > maxSeen) maxSeen = version

            when (action) {
                StreamFields.ACTION_DELETE -> {
                    handleDelete(storeKey, barcode)
                    deleted++
                    applied++
                }

                StreamFields.ACTION_ADD, StreamFields.ACTION_UPDATE -> {
                    val existing = products.getProductByBarcode(storeKey, barcode)
                    if (existing == null) {
                        // 协议缺口：两条流都不带 `name`/`orderable`，建不出一个能显示的商品行。
                        if (fetches >= MAX_ADD_FETCHES) {
                            // 400 条商品逐个 GET 不如一个 40KB 的 FULL。
                            return PricePass(applied, deleted, bad, fetches, maxSeen, tooManyFetches = true)
                        }
                        fetches++
                        if (fetchUnknown(storeKey, barcode, version)) applied++ else bad++
                    } else if (writeFromPriceEntry(storeKey, barcode, existing, e)) {
                        applied++
                        if (existing.origin == Product.ORIGIN_LOCAL) dropRedundantLocal(storeKey, barcode)
                    } else {
                        bad++
                    }
                }

                else -> bad++     // 未知的 action：不猜
            }
        }
        return PricePass(applied, deleted, bad, fetches, maxSeen, tooManyFetches = false)
    }

    /**
     * `action=ADD/UPDATE` 但本地根本没有这个条码 → 补拉整行。
     *
     * @return false = 拉不到（网络问题等），调用方按"坏条目"处理 → 空洞 → FULL 自愈。
     */
    private suspend fun fetchUnknown(storeKey: String, barcode: String, version: Int): Boolean {
        val dto = try {
            api.getProduct(barcode)
        } catch (nf: ApiError.NotFound) {
            // ADD 之后又被删了（两台设备一前一后）。当成一次删除处理，
            // 与"服务器说没有"保持一致 —— 凭空造一行会造出一个没有名字的幽灵商品。
            handleDelete(storeKey, barcode)
            return true
        } catch (_: ApiError) {
            // 拉不到（网络断了、限流、500）。**保留现场**：这一条按坏条目算 →
            // 游标不推 → 下次同步重来。绝不能凭空造一行没有名字的商品。
            return false
        }
        val name = dto.name ?: ""
        val at = now()
        val row = ProductSynced(
            storeKey = storeKey,
            barcode = barcode,
            name = name,
            price = dto.price?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            boxPrice = boxPriceOf(dto.boxPrice),
            boxSize = dto.boxSize ?: 0,
            stock = dto.stock ?: 0,
            orderable = dto.orderable ?: true,
            // `ProductDataDto` 是**唯一**带 `updatedAt` 的形状（FULL 的快照没有）。
            // 有就用服务端的，没有才退回本地时钟 —— 这个字段只用于 UI 排序与显示。
            updatedAt = dto.updatedAt ?: at,
        )
        products.putSyncedWithTokens(row, tokensOf(storeKey, barcode, name))
        // 整行写入 = 一次绝对库存观测，必须配套推边界，否则订单侧那笔扣减会被再算一次。
        // 去重键用**属性流的 version**：同一条 entry 重投递时得到同一个键（正是需要的性质）。
        stockLedger.applyAbsolute(
            storeKey, StockLedger.DedupKeys.stream(KIND_PRICE, version), barcode, row.stock,
            StockLedger.SOURCE_PRODUCT_FETCH,
        )
        dropRedundantLocal(storeKey, barcode)
        return true
    }

    /**
     * 用一条价格流条目覆盖已有行。**只改 price/boxPrice/boxSize 三项** ——
     * 条目里没有 `name`/`orderable`/`stock`，那三项保持原值。
     *
     * @return false = 条目缺必需字段，调用方按"坏条目"处理。
     */
    private suspend fun writeFromPriceEntry(
        storeKey: String,
        barcode: String,
        existing: Product,
        e: Map<String, String>,
    ): Boolean {
        // ⚠ 必须先按 `action == DELETE` 分支再读这三个键（调用方已经做了）：
        // DELETE 的条目里它们**不存在**，先读就会把 null 写进一个还活着的商品 ——
        // 表现为"价格静默变空"，而不是报错。
        val newPrice = e[StreamFields.NEW_PRICE]?.toBigDecimalOrNull() ?: return false
        // 缺 `new_box_size` 也按坏条目处理，不退回 0：0 的语义是"未设箱规"，
        // 会把一个本来能整箱卖的商品静默改成不能。
        val newBoxSize = e[StreamFields.NEW_BOX_SIZE]?.toIntOrNull() ?: return false
        val row = ProductSynced(
            storeKey = storeKey,
            barcode = barcode,
            name = existing.name,
            price = newPrice,
            boxPrice = boxPriceOf(e[StreamFields.NEW_BOX_PRICE]),
            boxSize = newBoxSize,
            stock = existing.stock,
            orderable = existing.orderable,
            updatedAt = now(),
        )
        products.putSyncedWithTokens(row, tokensOf(storeKey, row.barcode, row.name))
        return true
    }

    /**
     * 服务器删掉了一个条码。
     *
     * ⚠ `product_token` 是 synced 与 local **共用**的一张索引表，
     * 而 [ProductDao.removeSyncedWithTokens] 按 barcode 删索引 —— 它会把同时存在的
     * 本地行的索引一起带走，于是那个本地商品**搜不出来但还在列表里**（不报错）。
     * 所以本地行活下来时必须把索引补回去。
     */
    private suspend fun handleDelete(storeKey: String, barcode: String) {
        val current = products.getProductByBarcode(storeKey, barcode)
        if (current != null) {
            products.insertTombstone(ProductTombstone(storeKey, barcode, current.name, now()))
        }
        products.removeSyncedWithTokens(storeKey, barcode)

        val local = products.getLocal(storeKey, barcode) ?: return
        if (local.pushState == ProductLocal.PUSH_PUSHED) {
            // 这一行只是服务器那行的副本（推送成功后就该删掉，见 R1.3），跟着一起删。
            // 没推过的本地行是用户自己的数据，**不许**因为服务器上同条码的商品被删就删掉它。
            products.removeLocalWithTokens(storeKey, barcode)
        } else {
            products.putLocalWithTokens(local, tokensOf(storeKey, barcode, local.name))
        }
    }

    /**
     * 服务器已经知道这个条码了，`product_local` 那一行就是冗余的（方案 R1.3）。
     *
     * 只删**推上去过**的：`PUSH_PENDING` 的行是用户还没同意上传的数据，删掉就是丢数据。
     * 不删也不会出错（合并视图是 synced 优先，它被遮住了），只是白占一行、
     * 并且会让 [ProductDao.countPendingPush] 一直显示"N 条待推送"。
     */
    private suspend fun dropRedundantLocal(storeKey: String, barcode: String) {
        val local = products.getLocal(storeKey, barcode) ?: return
        if (local.pushState == ProductLocal.PUSH_PUSHED) {
            products.removeLocalWithTokens(storeKey, barcode)
        }
    }

    /**
     * 应用库存流条目。**整批一次** [StockLedger.applyAbsoluteBatch]。
     *
     * 一批可能几十条（一次 FULL 之后的追赶），逐条就是几十个事务。
     * 同一条码出现多次时按列表顺序后者覆盖前者 —— 条目本来就是版本升序，
     * 所以"后者"就是"更新的那个绝对值"，正确。
     */
    private suspend fun applyStockEntries(
        storeKey: String,
        entries: List<Map<String, String>>,
        localCursor: Int,
    ): StockPass {
        var bad = 0
        var maxSeen = localCursor
        val observations = ArrayList<StockObservation>(entries.size)

        for (e in entries) {
            val version = e[StreamFields.VERSION]?.toIntOrNull()
            val barcode = e[StreamFields.BARCODE]?.takeIf { it.isNotBlank() }
            // 一律**绝对值覆盖**，绝不拿 `change` 做加减（理由见 `StreamFields.CHANGE` 的注释）。
            // DELETE 时服务端显式给 `"0"`，所以不需要按 action 分支：
            // 商品行已经不在了，`applyAbsolute` 的 UPDATE 命中 0 行，什么都不会发生。
            val newStock = e[StreamFields.NEW_STOCK]?.toIntOrNull()
            if (version == null || barcode == null || newStock == null) {
                bad++          // 不计入 maxSeen，理由同 applyPriceEntries
                continue
            }
            if (version > maxSeen) maxSeen = version
            observations += StockObservation(
                StockLedger.DedupKeys.stream(KIND_STOCK, version), barcode, newStock,
            )
        }

        val applied = stockLedger.applyAbsoluteBatch(storeKey, observations, StockLedger.SOURCE_STOCK_STREAM)
        return StockPass(applied, bad, maxSeen)
    }

    // ==================== 回推（SERVER_BEHIND 的出口之二 / 首次上线） ====================

    /**
     * 把本地数据推上服务器：`product_pending_change` 里的改动 + `product_local` 里没推过的行。
     *
     * 两个调用点：
     *  1. 用户在 [Outcome.ServerBehind] 的对话框里选了"把本地推上去"；
     *  2. 首次上线向导（离线用了几个月、400 条手录商品要一次推上去）。
     *
     * ## ⚠ `stock` 的补偿是**调用方**的责任
     *
     * 方案 R2：离线期补传的历史订单会把库存从 0 扣成大负数，所以推上去的
     * `stock` 必须是 `用户填的当前真实库存 + 该商品历史总销量`。
     * 这个算式要读 `order_item_index`，而那张表在 P4 之前根本没有数据 ——
     * 所以引擎只负责"把行里的值原样推上去"，补偿由 P8 的向导在写行时做好。
     *
     * @param batchId 本次推送的批次标识。**重试同一批必须复用**：
     *        它进了 `DedupKeys.batchUpsert`，换一个新 id 会让同一次观测被吸收两次
     *        → 边界推过头 → 库存偏高。见 `DedupKeys.stockApi` 的注释（同一套规则）。
     */
    suspend fun pushOutbox(storeKey: String, batchId: String): PushResult {
        val pending = products.pendingChanges(storeKey)
        val failures = ArrayList<PushFailure>()

        // ---------- DELETE：逐条，且只有店主能成功 ----------
        var deleted = 0
        for (d in pending.filter { it.op == ProductPendingChange.OP_DELETE }) {
            if (d.attemptCount >= MAX_PUSH_ATTEMPTS) {
                failures += PushFailure(d.barcode, d.lastError ?: "已重试 ${d.attemptCount} 次", permanent = true)
                continue
            }
            try {
                api.deleteProduct(d.barcode)
                products.deletePendingChange(storeKey, d.barcode)
                deleted++
            } catch (nf: ApiError.NotFound) {
                // 服务器上已经没有它了 —— 目标达成，别再排队。
                products.deletePendingChange(storeKey, d.barcode)
                deleted++
            } catch (f: ApiError.Forbidden) {
                // `ProductController:53` 是 `isOwner(ma)` 门控，**不看权限位**：
                // 员工排的 DELETE 永远推不上去。标成非永久失败 —— 换店主登录就能推，
                // 而 UI 要提前把入口禁用掉，否则员工会以为这是 bug。
                products.markPendingChangeAttempt(storeKey, d.barcode, FORBIDDEN_DELETE_MSG)
                failures += PushFailure(d.barcode, FORBIDDEN_DELETE_MSG, permanent = false)
            } catch (e: ApiError) {
                val msg = e.message ?: e.javaClass.simpleName
                products.markPendingChangeAttempt(storeKey, d.barcode, msg)
                failures += PushFailure(d.barcode, msg, permanent = false)
            }
        }

        // ---------- UPSERT：分批 ≤100 ----------
        // 同一个条码可能同时出现在两个来源（改动队列 + 本地表）。`distinctBy` 保留**前者**：
        // 待推送的改动是用户更晚的意图。
        val items = (pending.filter { it.op == ProductPendingChange.OP_UPSERT }.map { it.toRequest() } +
            products.getAllLocal(storeKey)
                .filter { it.pushState != ProductLocal.PUSH_PUSHED }
                .map { it.toRequest() })
            .distinctBy { it.barcode }

        var pushed = 0
        val observations = ArrayList<StockObservation>(items.size)
        for (chunk in items.chunked(ProductBatchUpsertRequestDto.MAX_PER_BATCH)) {
            try {
                api.batchUpsertProducts(chunk)
            } catch (e: ApiError) {
                val msg = e.message ?: e.javaClass.simpleName
                for (r in chunk) {
                    products.markPendingChangeAttempt(storeKey, r.barcode, msg)
                    products.setLocalPushState(storeKey, r.barcode, ProductLocal.PUSH_FAILED, msg)
                    failures += PushFailure(r.barcode, msg, permanent = false)
                }
                continue
            }
            for (r in chunk) {
                products.deletePendingChange(storeKey, r.barcode)
                products.setLocalPushState(storeKey, r.barcode, ProductLocal.PUSH_PUSHED, null)
                // 推上去的 `stock` 是客户端算的绝对值，服务端按绝对值落地 ——
                // 所以它立刻就是服务器上的真值，当成一次观测吸收掉，
                // 免得订单侧那笔扣减被再算一次。
                observations += StockObservation(
                    StockLedger.DedupKeys.batchUpsert(batchId, r.barcode), r.barcode, r.stock,
                )
            }
            pushed += chunk.size
        }
        if (observations.isNotEmpty()) {
            stockLedger.applyAbsoluteBatch(storeKey, observations, StockLedger.SOURCE_BATCH_UPSERT)
        }
        return PushResult(pushed, deleted, failures)
    }

    // ==================== 小工具 ====================

    private fun tokensOf(storeKey: String, barcode: String, name: String): List<ProductToken> =
        ProductTokenizer.tokenizeWithPositions(name).map { (tok, pos) ->
            ProductToken(storeKey, tok, barcode, pos)
        }

    private fun ProductPendingChange.toRequest() = ProductAddRequestDto(
        barcode = barcode,
        name = name,
        // ⚠ 金额一律 `toPlainString()`：Gson 序列化 `BigDecimal` 可能写出科学计数法，
        // 而服务端收的是字符串再转 `BigDecimal`。
        price = price.toPlainString(),
        boxPrice = boxPrice?.toPlainString(),
        boxSize = boxSize,
        stock = stock,
        // ⚠ 服务端 `ProductAddRequest.orderable` 是原始 `boolean`：漏传就是 `false`，
        // 商品被静默改成不可订。`ProductAddRequestDto` 把它声明成非空就是为了编译期挡住。
        orderable = orderable,
    )

    private fun ProductLocal.toRequest() = ProductAddRequestDto(
        barcode = barcode,
        name = name,
        price = price.toPlainString(),
        boxPrice = boxPrice?.toPlainString(),
        boxSize = boxSize,
        stock = stock,
        orderable = orderable,
    )

    /** [pushOutbox] 的结果。 */
    data class PushResult(
        /** `batch-upsert` 成功的条数。 */
        val pushed: Int,
        /** `DELETE` 成功的条数（含"服务器上本来就没有"）。 */
        val deleted: Int,
        val failures: List<PushFailure>,
    ) {
        val ok: Boolean get() = failures.isEmpty()
    }

    /**
     * 一条推不上去的商品。
     *
     * @param permanent true = 再重试也没用（已重试 [MAX_PUSH_ATTEMPTS] 次）。
     *        false = 换个时机/换个账号可能就成（尤其是"只有店主能删"那条）。
     */
    data class PushFailure(val barcode: String, val reason: String, val permanent: Boolean)

    /** [apply] 的结果。**每个分支都要求调用方做点什么**，所以做成 sealed 而不是布尔。 */
    sealed interface Outcome {

        /**
         * 落地成功，游标已推进。
         *
         * @param productsChanged 写进 `product_synced` 的商品数（FULL 时 = 全表行数）
         * @param stockObservations 实际被吸收的库存观测数（撞去重的不算）
         * @param deleted 本次消失/被删的条码数
         */
        data class Applied(
            val kind: SyncKind,
            val priceVersion: Int,
            val stockVersion: Int,
            val productsChanged: Int,
            val stockObservations: Int,
            val deleted: Int,
        ) : Outcome

        /**
         * **服务器落后于客户端**（典型是 Redis 被清空后重建）。本地一个字都没改。
         *
         * ## ⚠ 调用方必须让用户二选一，不能自动挑
         *
         * | 选择 | 动作 |
         * |---|---|
         * | 接受服务器版本 | 把两个游标**降**到 `server*Version`（于是下次 FULL），并明说本地超前的改动会丢 |
         * | 把本地推上去 | [pushOutbox]（每批 ≤100），推完再对游标 |
         *
         * 挑错的代价是**不可逆的商品丢失**，所以这里只报告、不动手。
         * 尤其别照着 INCREMENTAL 的收尾代码把 `server*Version` 写进游标 ——
         * 那是"往回拨"，下一次 FULL 会用服务器那份更短的数据覆盖客户端唯一那份更完整的副本。
         */
        data class ServerBehind(
            val serverPriceVersion: Int,
            val serverStockVersion: Int,
            val localPriceVersion: Int,
            val localStockVersion: Int,
        ) : Outcome

        /**
         * 条目有空洞 / 有解析不了的条目 / 陌生条码太多。
         *
         * **已经应用的条目保留**（全是绝对值覆盖，幂等，应用了只会更接近真值），
         * 但相关游标已被**归零**，所以下一次同步必然是 FULL —— 自愈，不需要调用方配合。
         * 调用方要做的是记日志、并提示用户"正在重新拉取全部商品"。
         *
         * @param resetPriceCursor / @param resetStockCursor 哪条游标被归零了（另一条正常推进）
         */
        data class HoleDetected(
            val reason: String,
            val appliedPriceEntries: Int,
            val appliedStockObservations: Int,
            val resetPriceCursor: Boolean,
            val resetStockCursor: Boolean,
        ) : Outcome

        /**
         * 不认识的 `type`（**包括 `LATEST`** —— 商品同步没有这个取值）。
         * 本地数据一个字没动。正确处理是记日志 + 提示升级客户端，
         * **绝不能默认当成 FULL**（FULL 会覆盖本地数据，误判一次就是不可逆的丢失）。
         */
        data class UnknownType(val type: String?) : Outcome

        /** 响应本身缺字段，本地数据一个字没动。 */
        data class Malformed(val reason: String) : Outcome
    }

    private data class PricePass(
        val applied: Int,
        val deleted: Int,
        val bad: Int,
        val toFetch: Int,
        val maxSeen: Int,
        val tooManyFetches: Boolean,
    )

    private data class StockPass(val applied: Int, val bad: Int, val maxSeen: Int)

    companion object {
        /**
         * 一次 INCREMENTAL 里最多补拉几个陌生条码，超过就改成强制一次 FULL。
         *
         * 400 条商品逐个 GET 是 400 次 HTTP 往返（还都串在同步路径上），
         * 而一个 FULL 只有 ~40KB。阈值定 20 是因为"正常的多设备使用"下一次增量里
         * 陌生条码通常是个位数；一次性冒出来几十个说明本地落后很多，FULL 更合适。
         */
        const val MAX_ADD_FETCHES = 20

        /** 一条待推送改动最多重试几次，之后标成 [PushFailure.permanent]。 */
        const val MAX_PUSH_ATTEMPTS = 5

        /** `DedupKeys.stream` 的 `kind` 段。两条流的版本号各自独立计数，必须分开。 */
        private const val KIND_PRICE = "PRICE"
        private const val KIND_STOCK = "STOCK"

        private const val FORBIDDEN_DELETE_MSG = "只有店主能删除商品"

        /**
         * `new_box_price` 的**空串**表示 null（不按整箱卖），不是"整箱价 0 元"。
         * `"".toBigDecimal()` 会抛异常，`?: ZERO` 会把商品变成整箱白送。
         * 见 `StreamFields` 类注释的陷阱二。
         */
        private fun boxPriceOf(raw: String?): BigDecimal? =
            raw?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    }
}
