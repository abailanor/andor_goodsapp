package com.example.myapplication.ui.order

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.core.money.Money
import com.example.myapplication.data.CartLine
import com.example.myapplication.data.CartPricing
import com.example.myapplication.data.OrderItem
import com.example.myapplication.data.OrderHistoryStorage
import com.example.myapplication.data.OrderStorage
import com.example.myapplication.data.PendingOrderStorage
import com.example.myapplication.data.Product
import com.example.myapplication.data.ProductRepository
import com.example.myapplication.data.PurchaseCart
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.outbox.OutboxStore
import com.example.myapplication.sync.outbox.OutboxUploader
import com.example.myapplication.ui.CurrentOrderMode
import com.example.myapplication.ui.OrderMode
import com.example.myapplication.util.AssetsSoundPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.util.Calendar
import java.util.regex.Pattern

/**
 * 开单页的 ViewModel。**销售与进货各有一辆车**，切换模式不清空任何一边。
 *
 * ## 结算走 outbox，不再写 JSON 文件
 *
 * 「确定并上传」调 [confirmCheckout] → `CartPricing.toRequest` → `OutboxStore.enqueue`。
 * 之后由 `OutboxUploader` 发 `/add`、`MirrorCommitter` 落镜像、`RoomMirrorStateStore`
 * 建索引并搬统计桶。**离线也能开单**：enqueue 不需要网络，单子会留在 outbox 里等上线。
 *
 * ⚠ [saveOrderToHistory]（写 `filesDir/Orders/` 下的 JSON）暂时保留，因为打印还依赖它，
 * 而且 P8 的数据迁移向导要靠那些文件。等历史页改读 `order_index` 之后再摘掉。
 */
class OrderViewModel(
    private val repository: ProductRepository,
    private val context: Context?,
    private val outbox: OutboxStore? = null,
    private val uploader: OutboxUploader? = null,
    private val storeKey: () -> String = { "" },
    /** **店主**用户名，拼 fileId 用。⚠ 不是登录名 —— 见 `OutboxContribution.partsOf` 的警告。 */
    private val ownerName: () -> String = { "" },
    /** 当前登录用户名，作为订单 `writer` 的默认值。 */
    private val loginName: () -> String = { "" },
) : ViewModel() {

    // ==================== 模式与两辆车 ====================

    private val _mode = MutableStateFlow(CurrentOrderMode.now)
    val mode: StateFlow<OrderMode> = _mode.asStateFlow()

    private val _saleItems = MutableStateFlow<List<CartLine>>(emptyList())
    private val _purchaseItems = MutableStateFlow<List<CartLine>>(emptyList())

    /** 当前模式那辆车。UI 只观察这一个。 */
    private val _orderItems = MutableStateFlow<List<CartLine>>(emptyList())
    val orderItems: StateFlow<List<CartLine>> = _orderItems.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 结算流程走到哪一步了。UI 据此决定弹哪个框。 */
    private val _checkout = MutableStateFlow<CheckoutStep>(CheckoutStep.Idle)
    val checkout: StateFlow<CheckoutStep> = _checkout.asStateFlow()

    /**
     * 应收总额（**行金额之和**，不含手工抹零），BigDecimal 只为兼容打印那边的旧签名。
     *
     * ⚠ 必须走 [CartPricing]，不能是 `Σ(price × quantity)`：
     * 整箱价 ≠ 箱规 × 单价，后者在有整箱参与时是错的 —— 30 个会算出 105.00
     * 而实收 101.00。行内显示的数、总计、结算框、小票必须是同一个来源，
     * 否则顾客当场就会发现对不上。
     */
    val totalPrice: BigDecimal
        get() = BigDecimal.valueOf(CartPricing.totals(validLines()).amountSum).movePointLeft(2)

    /** 与 [totalPrice] 同源的分单位版本。新代码用这个，别再转 BigDecimal。 */
    val totalCents: Long
        get() = CartPricing.totals(validLines()).amountSum

    // ==================== 手工总额（抹零）====================

    /**
     * 手工改过的总额文本。**null = 没改过**，此时界面上那个框显示实时计算值。
     *
     * 用 null 而不是空串区分"没改过"：空串是"改成了一个解析不出来的东西"，
     * 而 null 是"跟随计算值"。两者的界面表现一样（都显示计算值），
     * 但语义不同 —— 前者要报错，后者是正常的。
     */
    private val _manualTotalText = MutableStateFlow<String?>(null)
    val manualTotalText: StateFlow<String?> = _manualTotalText.asStateFlow()

    /** 当前生效的手工总额（分）。没改过、或改了个解析不出来的值，都是 null。 */
    val manualTotalCents: Long?
        get() = _manualTotalText.value?.let { runCatching { Money.toCents(it) }.getOrNull() }

    /** 填了东西但解析不出来。界面要标红，不能静默当成"没改过"。 */
    val manualTotalInvalid: Boolean
        get() = _manualTotalText.value != null && manualTotalCents == null

    fun setManualTotal(text: String) {
        _manualTotalText.value = text.trim().ifEmpty { null }
    }

    fun resetManualTotal() {
        _manualTotalText.value = null
    }

    // ==================== 待开单来源/目标（按模式分槽）====================

    /** 一张待开单在开单页上的两个指针：从哪个文件读的、要存回哪个文件。 */
    private data class PendingRef(val source: String? = null, val target: String? = null)

    /**
     * ⚠ 必须**按模式分槽**，切换模式时整对换进换出。
     *
     * 曾经是一对全局字段，后果是两张单互相吃：存了一张销售待开单（target=A.json），
     * 切到进货再存一张 —— savePendingOrder 先删 `_targetPendingFileName` 指的 A.json，
     * 于是销售那张在磁盘上没了，而销售车里的指针还写着 A.json。
     * `OrderStorage` 本来就按模式分槽落盘，内存里不分槽等于白分。
     */
    private val _pendingRef = MutableStateFlow(PendingRef())

    /** 当前不在编辑的那个模式的指针暂存在这里，切回去时换回来。 */
    private val parkedRefs = mutableMapOf<OrderMode, PendingRef>()

    val sourcePendingFileName: Flow<String?> = _pendingRef.map { it.source }

    var onSaveComplete: (() -> Unit)? = null

    private var soundPlayer: AssetsSoundPlayer? = null

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    /** 同店其它店员的名字，供"记在谁名下"下拉选（代传）。加载失败就是空列表，不影响开单。 */
    private val _clerkNames = MutableStateFlow<List<String>>(emptyList())
    val clerkNames: StateFlow<List<String>> = _clerkNames.asStateFlow()

    init {
        context?.let {
            soundPlayer = AssetsSoundPlayer(it)
            _soundEnabled.value = soundPlayer?.returnstat() ?: true
        }
        if (context != null) restoreCarts()
    }

    /**
     * 冷启动：把两辆车都读回来，再决定进哪个模式。
     *
     * ## 模式判据（用户的规则 + 一处我故意收紧的地方）
     *
     * 规则是"有销售车就进销售模式，否则进进货模式"。但**两边都空**时我让它进销售模式，
     * 不是进货：冷启动直接落在进货模式的话，当天第一个顾客来买东西，
     * 店员照着界面点下去就开出一张 A 类进货单 —— 不计营收、不扣库存，那一单凭空消失，
     * 而且账上完全看不出来。底色是防这个的唯一实时信号，但它只在用户**看**的时候有用。
     *
     * 所以：有销售车 → 销售；否则有进货车 → 进货；两边都空 → 销售。
     */
    private fun restoreCarts() {
        viewModelScope.launch {
            val storage = OrderStorage(context ?: return@launch)
            val sale = storage.loadOrderData(OrderMode.SALE.name)
            val purchase = storage.loadOrderData(OrderMode.PURCHASE.name)

            val saleItems = hydrate(sale.orderItems)
            val purchaseItems = hydrate(purchase.orderItems)
            _saleItems.value = saleItems
            _purchaseItems.value = purchaseItems
            parkedRefs[OrderMode.SALE] = PendingRef(sale.sourcePendingFileName, sale.targetPendingFileName)
            parkedRefs[OrderMode.PURCHASE] =
                PendingRef(purchase.sourcePendingFileName, purchase.targetPendingFileName)

            val restored = when {
                saleItems.isNotEmpty() -> OrderMode.SALE
                purchaseItems.isNotEmpty() -> OrderMode.PURCHASE
                else -> OrderMode.SALE
            }
            // ⚠ 先把"当前模式"那槽的真实值放进 _pendingRef：下面 setMode 会把
            // _pendingRef 泊回旧模式名下，如果这里还是空的初始值，就会把刚读回来的
            // 指针盖掉（重启后"已加载待开单"的提示和覆盖目标全丢）。
            _pendingRef.value = parkedRefs[_mode.value] ?: PendingRef()
            setMode(restored, persist = false)
        }
    }

    /**
     * 存的是条码 + 箱/个，`Product` 要现查（价格可能已经变了）。查不到的条目直接丢掉。
     *
     * 旧格式（只有 `quantity`）走 [CartPricing.fromTotalQuantity] 按箱规反推 ——
     * 那是猜测，但比丢掉整行车好，而且用户一眼能看见箱/个两个框里的数对不对。
     */
    private suspend fun hydrate(saved: List<com.example.myapplication.data.SavedOrderItem>): List<CartLine> {
        val out = ArrayList<CartLine>(saved.size)
        for (s in saved) {
            val p = repository.getProductByBarcode(s.barcode) ?: continue
            // 查不到就丢：商品被删了。留着的话结算时会拼出一条没有价格的行，
            // 而"少了一个条目"用户看得见，"多了一行 0.00"看不见。
            val line = if (s.isLegacy) {
                CartPricing.fromTotalQuantity(p, s.quantity)
            } else {
                runCatching { CartLine(p, boxes = s.boxes, singles = s.singles) }.getOrNull()
            }
            if (line != null) out += line
        }
        return out
    }

    /**
     * 转成旧的 [OrderItem] 形状，只为跨过三个还没改造的边界：
     * 打印（`PrintUtil.generateReceipt`）、待开单（`PendingOrderStorage`）、
     * 历史（`OrderHistoryStorage`）。它们都只认 `product + quantity`。
     *
     * ⚠ 这一步**会丢掉箱/个拆分**（只剩总个数）。待开单是按用户决定继续用 JSON
     * 文件存的，所以那里的箱/个信息本来就保不住；等 P12 改打印模板时，
     * 打印那条路要换成直接吃 [CartLine]。
     */
    private fun toOrderItems(): List<OrderItem> = validLines().mapNotNull { l ->
        l.quantity?.let { OrderItem(product = l.product, quantity = it) }
    }

    private companion object {
        /**
         * 扫码/搜索新增一个商品时的默认散卖数。沿用旧行为（原来是 5）。
         *
         * ⚠ 不能默认成 0：`CartLine` 允许两个框都空（那是编辑中间态），
         * 但刚扫上来的商品如果显示成 0/0，用户会以为没扫上。
         */
        const val DEFAULT_ADD = 5
    }

    /**
     * 切换模式。
     *
     * @param persist false 只用于 [restoreCarts] 里回填，那时不该反过来再写一次盘。
     */
    fun setMode(mode: OrderMode, persist: Boolean = true) {
        if (_mode.value == mode && persist) return
        if (_mode.value != mode) {
            // 待开单指针跟购物车一样按模式分槽，切换时整对换进换出。
            parkedRefs[_mode.value] = _pendingRef.value
            _pendingRef.value = parkedRefs[mode] ?: PendingRef()
        }
        _mode.value = mode
        CurrentOrderMode.set(mode)
        _orderItems.value = if (mode == OrderMode.SALE) _saleItems.value else _purchaseItems.value
        _checkout.value = CheckoutStep.Idle
        if (persist) saveOrder()
    }

    /** 当前模式那辆车。所有增删改都必须走它，不能直接碰 `_orderItems`。 */
    private fun currentCart(): MutableStateFlow<List<CartLine>> =
        if (_mode.value == OrderMode.SALE) _saleItems else _purchaseItems

    private fun setCart(items: List<CartLine>) {
        currentCart().value = items
        _orderItems.value = items
        // ⚠ 购物车一变就**清掉手工总额**，让它重新跟随计算值。
        //
        // 保留的话会静默少收钱：用户抹零到 100.00，然后又补扫了一件 20 块的商品，
        // 计算值变成 120，而框里还写着 100 —— 上传出去的就是 100。
        // 抹零本来就是结账前最后一步，清掉它比留着安全，而且框里的数字会当场跳回
        // 计算值，用户看得见。
        _manualTotalText.value = null
    }

    private fun saveOrder() {
        viewModelScope.launch {
            val ctx = context ?: return@launch
            OrderStorage(ctx).saveOrderData(
                mode = _mode.value.name,
                orderItems = _orderItems.value,
                sourcePendingFileName = _pendingRef.value.source,
                targetPendingFileName = _pendingRef.value.target,
            )
        }
    }

    fun setClerkNames(names: List<String>) {
        _clerkNames.value = names
    }

    // ==================== 待开单互转 ====================

    /**
     * 从待开单载入。待开单存的是 `product + quantity`（没有箱/个拆分），
     * 所以这里按箱规反推 —— 载入之后用户能在两个框里看见并改正。
     *
     * @param mode 这张待开单是销售还是进货。**必须先切模式再放购物车**：
     *        `setCart` 写的是 `currentCart()`，也就是当前模式那辆车。
     *        顺序反了的话进货单会被塞进销售车，用户看到的是一张带价格的进货单，
     *        而结算时会走 B 类文件上传 —— 一笔进货被当成销售，计营收、扣库存。
     *        null 表示旧文件（没存模式），按销售处理。
     */
    fun loadPendingOrderItems(
        items: List<OrderItem>,
        sourceFileName: String,
        targetFileName: String? = null,
        mode: OrderMode? = null,
    ) {
        if (mode != null && mode != _mode.value) setMode(mode)
        setCart(CartPricing.fromCart(items.map { it.product to it.quantity }))
        _pendingRef.value = PendingRef(source = sourceFileName, target = targetFileName)
        saveOrder()
    }

    // ==================== 搜索与录入 ====================

    private val _searchResults = MutableStateFlow<List<Product>>(emptyList())
    val searchResults: StateFlow<List<Product>> = _searchResults.asStateFlow()

    fun setsound() {
        soundPlayer?.setSoundEnabled()
        _soundEnabled.value = soundPlayer?.returnstat() ?: true
    }

    private fun containsChinese(text: String): Boolean {
        val pattern = Pattern.compile("[\\u4e00-\\u9fa5]")
        return pattern.matcher(text).find()
    }

    private var searchJob: Job? = null

    fun searchProducts(query: String) {
        if (query.isEmpty()) {
            _searchResults.value = emptyList()
            return
        }
        // 如果是纯数字，视为条形码，不进行查询
        val isNumeric = query.matches(Regex("\\d+"))
        if (isNumeric && !containsChinese(query)) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            try {
                val results = repository.searchProductsByCharacters(query)
                _searchResults.value = results
                if (results.isEmpty()) _message.value = "未找到包含 '${query}' 所有字符的商品"
            } catch (e: Exception) {
                _message.value = "查询失败: ${e.message}"
                _searchResults.value = emptyList()
            }
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    fun addProductFromSearch(product: Product) {
        viewModelScope.launch {
            try {
                val existingItem = _orderItems.value.find { it.product.barcode.contains(product.barcode) }
                if (existingItem != null) {
                    setCart(listOf(existingItem) + _orderItems.value.filter { it.product.barcode != product.barcode })
                    _message.value = "已更新: ${product.name} (移至最前)"
                    soundPlayer?.playAlreadyInList()
                } else {
                    // 默认 5 个散卖（沿用旧行为）。CartLine 要求至少有一个，
                    // 所以不能加成 0/0 —— 那样这一行在界面上是空的，用户不知道扫上了没有。
                    setCart(listOf(CartLine(product, boxes = 0, singles = DEFAULT_ADD)) + _orderItems.value)
                    _message.value = "已添加: ${product.name}"
                    soundPlayer?.playScanSuccess()
                }
                saveOrder()
                clearSearchResults()
            } catch (e: Exception) {
                _message.value = "添加失败: ${e.message}"
                soundPlayer?.playError()
            }
        }
    }

    fun scanBarcode(barcode: String) {
        viewModelScope.launch {
            try {
                val product = repository.getProductByBarcode(barcode)
                if (product != null) {
                    val existingItem = _orderItems.value.find { it.product.barcode.contains(barcode) }
                    if (existingItem != null) {
                        _message.value = "${product.name} 已在订单中，当前数量: ${existingItem.quantity}"
                        soundPlayer?.playAlreadyInList()
                    } else {
                        // 旧约定：0 前缀的整件条码数量给 1。
                        // ⚠ v4 迁移之后合并过的商品**已经没有** 0 前缀那一条了
                        // （BoxRowMerger 把它吸收进 boxPrice），所以这个分支现在只会命中
                        // 迁移时保留下来的孤儿整件行。新数据不该再走到这里。
                        val qty = if (barcode[0] == '0') 1 else DEFAULT_ADD
                        setCart(listOf(CartLine(product, boxes = 0, singles = qty)) + _orderItems.value)
                        _message.value = "已添加: ${product.name}"
                        soundPlayer?.playScanSuccess()
                        saveOrder()
                    }
                } else {
                    _message.value = "未找到商品: $barcode"
                    soundPlayer?.playNotExsist()
                }
            } catch (e: Exception) {
                _message.value = "扫描失败: ${e.message}"
                soundPlayer?.playError()
            }
        }
    }

    /** 改「件」（整箱数）。 */
    fun updateBoxes(barcode: String, boxes: Int) =
        updateLine(barcode) { it.copy(boxes = boxes.coerceAtLeast(0)) }

    /** 改「个」（散卖数）。 */
    fun updateSingles(barcode: String, singles: Int) =
        updateLine(barcode) { it.copy(singles = singles.coerceAtLeast(0)) }

    /**
     * 两个框都清空时**不删这一行**。
     *
     * 那是输入过程中的正常中间态（先删掉 5 再输 30，中间必然经过 0），
     * 删行的话用户刚扫上来的商品会凭空消失，而他只是想在框里改个数字。
     * 空行在 [CartPricing.toOrderLines] 出口处被过滤掉，不会进 payload ——
     * ⚠ 绝不能让它变成一条 `quantity = 0` 的行，服务端要求正整数，0 会让整张 B 类单拿 400。
     *
     * 真正的删除走 [removeItem]。
     */
    private fun updateLine(barcode: String, transform: (CartLine) -> CartLine) {
        setCart(_orderItems.value.map { if (it.product.barcode == barcode) transform(it) else it })
        saveOrder()
    }

    /** 真正有效的行（至少有一个箱或个）。结算、金额、件数都只看这些。 */
    private fun validLines(): List<CartLine> = _orderItems.value.filterNot { it.isEmpty }

    fun removeItem(barcode: String) {
        setCart(_orderItems.value.filter { it.product.barcode != barcode })
        saveOrder()
    }

    fun clearOrder() {
        setCart(emptyList())
        _message.value = "订单已清空"
        _pendingRef.value = PendingRef()
        viewModelScope.launch {
            context?.let { OrderStorage(it).clearOrderItems(_mode.value.name) }
        }
    }

    /** 清除订单（不清除来源标记，用于加载新订单前）。 */
    fun clearOrderWithoutSource() {
        setCart(emptyList())
        viewModelScope.launch {
            context?.let { OrderStorage(it).clearOrderItems(_mode.value.name) }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * 订单 `writer` 的默认值 = 当前登录用户名。
     *
     * 离线（没登录）时退回店主名，再退回空串 —— 空串会让 [CheckoutDialog] 的
     * "确定"按钮禁用，因为服务端要求 writer 非空白，而统计是按它分组的，
     * 空 writer 会让这一单归到"（未署名）"，之后再也认不出是谁卖的。
     */
    fun defaultWriter(): String = loginName().ifBlank { ownerName() }

    // ==================== 结算 ====================

    /** 当前购物车里**有效**的行（空行已过滤）。 */
    private fun cartLines(): List<CartLine> = validLines()

    /**
     * 点「结算」。
     *
     * 三种走向：缺箱规 → [CheckoutStep.NeedBoxSize]；正常 → [CheckoutStep.Ready]；
     * 空车 → 只提示。
     */
    fun startCheckout() {
        if (_orderItems.value.isEmpty()) {
            _message.value = "订单是空的"
            return
        }
        val lines = cartLines()
        if (lines.isEmpty()) {
            _message.value = "订单里没有有效的行"
            return
        }
        val missing = CartPricing.missingBoxSize(lines)
        if (missing.isNotEmpty()) {
            _checkout.value = CheckoutStep.NeedBoxSize(lines.filter { it.needsBoxSize })
            return
        }
        // 设了整箱价却没箱规的商品：这种在"只有总数"的录入方式下拆不了箱，
        // 会被按散卖计价 —— 整箱价被忽略、多收顾客的钱。不拦住结算，但必须说出来。
        _checkout.value = CheckoutStep.Ready(lines, CartPricing.ambiguousBoxProducts(lines))
    }

    fun dismissCheckout() {
        _checkout.value = CheckoutStep.Idle
    }

    /** 在箱规表单里补了一个箱规：写回商品表，然后重新走一遍结算判定。 */
    fun fillBoxSize(barcode: String, boxSize: Int) {
        viewModelScope.launch {
            val p = repository.getProductByBarcode(barcode)
            if (p == null) {
                _message.value = "商品不存在：$barcode"
                return@launch
            }
            repository.updateProduct(p.copy(boxSize = boxSize))
            // 购物车里的 Product 是快照，得换成新的，否则拆箱还是用旧箱规
            setCart(_orderItems.value.map {
                if (it.product.barcode == barcode) it.copy(product = p.copy(boxSize = boxSize)) else it
            })
            saveOrder()
            _checkout.value = CheckoutStep.Idle
            startCheckout()
        }
    }

    /**
     * 整单挂起：进 outbox 标 `BLOCKED_MISSING_BOX_SIZE`，等箱规补齐后自动放行。
     *
     * ⚠ 挂起的单**仍然计入营业额**（货已经给客人了），只是不能上传。
     */
    fun blockOrder() {
        val missing = CartPricing.missingBoxSize(cartLines())
        enqueue(missingBoxSizeBarcodes = missing, manualTotalCents = null, remarkText = null)
    }

    /**
     * 确认并上传。
     *
     * 归属人**不接受参数**，一律是 [defaultWriter]（当前登录用户名）。
     * 理由见 [CheckoutDialog] 的 `writer` 参数注释：在收银台上给一个可编辑的
     * 归属人选择器纯粹是误操作来源，而选错一次这一单就永久记在别人名下。
     */
    fun confirmCheckout(remarkText: String?) {
        enqueue(
            missingBoxSizeBarcodes = emptyList(),
            manualTotalCents = manualTotalCents,
            remarkText = remarkText,
        )
    }

    private fun enqueue(
        missingBoxSizeBarcodes: List<String>,
        manualTotalCents: Long?,
        remarkText: String?,
    ) {
        val ob = outbox
        val key = storeKey()
        val owner = ownerName()
        if (ob == null || key.isEmpty() || owner.isEmpty()) {
            _message.value = "还没接上 outbox（storeKey/ownerName 为空），这一单存不下来"
            return
        }
        val purchase = _mode.value == OrderMode.PURCHASE
        val lines = cartLines()
        val cal = Calendar.getInstance()

        // ⚠ 两条路**完全分开**，不是一个函数的两个分支。
        // 进货走 PurchaseCart：它的行类型里没有价格字段，所以"进货不算钱"是类型保证的，
        // 不是靠这里记得传 `manualTotalCents = null`。理由见 PurchaseCart 的类注释。
        val writer = defaultWriter().ifBlank { owner }
        val now = System.currentTimeMillis()
        val req = if (purchase) {
            PurchaseCart.toRequest(
                cart = lines,
                writer = writer,
                ts = now,
                year = cal.get(Calendar.YEAR),
                remarkText = remarkText,
            )
        } else {
            CartPricing.toRequest(
                lines = lines,
                // 一律是当前登录用户名。离线且从没登录过时会是空串 —— 那时
                // CheckoutDialog 的"确定"是禁用的，走不到这里。
                writer = writer,
                ts = now,
                year = cal.get(Calendar.YEAR),
                month = cal.get(Calendar.MONTH) + 1,
                manualTotalCents = manualTotalCents,
            )
        }
        if (req == null) {
            _message.value = "有商品缺箱规，算不出个数，请先补齐"
            return
        }

        viewModelScope.launch {
            try {
                val result = ob.enqueue(
                    storeKey = key,
                    ownerName = owner,
                    req = req,
                    missingBoxSizeBarcodes = missingBoxSizeBarcodes,
                )
                _checkout.value = CheckoutStep.Idle
                setCart(emptyList())
                viewModelScope.launch { context?.let { OrderStorage(it).clearOrderItems(_mode.value.name) } }

                val split = if (result.chunkCount > 1) "（太大，拆成 ${result.chunkCount} 块同组上传）" else ""

                // 立刻尝试上传。离线也没关系：失败会退回 READY 或留在 IN_FLIGHT，
                // 下次启动/重连时 recoverInFlight 会接着处理。
                val uploaded = uploader?.let { u -> u.uploadReady(key, owner).promoted } ?: 0

                // ⚠ 文案必须区分"已上传"与"存下来了但没传上去"。
                // 只说"已保存"的话，离线开一天的单用户会以为都上去了，
                // 而它们其实全躺在 outbox 里等网络。
                _message.value = when {
                    result.blocked ->
                        "已挂起：缺 ${missingBoxSizeBarcodes.size} 个商品的箱规。补齐后自动放行，单子不会丢。"
                    uploaded > 0 -> "已上传$split"
                    else -> "已存入未上传$split —— 网络恢复后会自动上传，可在「订单」页查看"
                }
            } catch (e: Exception) {
                _message.value = "保存失败: ${e.message}"
            }
        }
    }

    // ==================== 打印 / 历史 / 待开单 ====================

    fun saveOrderToHistory() {
        viewModelScope.launch {
            try {
                val ctx = context ?: return@launch
                OrderHistoryStorage(ctx).saveNewOrder(toOrderItems(), totalPrice.toPlainString())
            } catch (e: Exception) {
                _message.value = "保存历史订单失败: ${e.message}"
            }
        }
    }

    /**
     * 保存待开单。有目标文件就覆盖，否则新建。
     *
     * @return 保存后的文件名；无订单项时 null。
     */
    fun savePendingOrder(): String? {
        if (_orderItems.value.isEmpty()) return null
        var savedFileName: String? = null
        viewModelScope.launch {
            try {
                val ctx = context ?: return@launch
                val storage = PendingOrderStorage(ctx)
                val purchase = _mode.value == OrderMode.PURCHASE
                // ⚠ 进货单存空串，**不存金额**：`totalPrice` 走的是 CartPricing，
                // 拿的是本地目录里的**卖出价**。进货价服务端根本不存，
                // 把它写进待开单等于编一个数字出来，之后还会被当成应收显示给用户。
                val total = if (purchase) "" else totalPrice.toPlainString()
                _pendingRef.value.target?.let { name ->
                    val f = java.io.File(ctx.filesDir, "PendingOrders/$name")
                    if (f.exists()) f.delete()
                }
                // ⚠ 必须把当前模式一起存下去：待开单页要按模式分开列，
                // 而加载回来时要先切到对应模式再放购物车。
                val savedFile = storage.savePendingOrder(toOrderItems(), total, mode = _mode.value.name)
                savedFileName = savedFile.record.fileName
                _pendingRef.value = PendingRef(source = savedFileName, target = savedFileName)
                _message.value = "已保存到待开单"
                saveOrder()
                onSaveComplete?.invoke()
            } catch (e: Exception) {
                _message.value = "保存待开单失败: ${e.message}"
            }
        }
        return savedFileName
    }

    /** 有效行数（两个框都空的中间态不算）。 */
    fun getOrderItemCount(): Int = validLines().size

    fun getTargetPendingFileName(): String? = _pendingRef.value.target
}

/**
 * 结算流程的状态。
 *
 * 分成三态而不是"弹/不弹"，是因为缺箱规与可以结算是**两个不同的框**：
 * 前者要用户填数字或选择挂起，后者是确认金额与归属人。
 * 合成一个框的话，用户会在"确认金额"的界面上看到一堆填不了的输入框。
 */
sealed interface CheckoutStep {
    data object Idle : CheckoutStep

    /** 有商品缺箱规。补上，或者整单挂起。 */
    data class NeedBoxSize(val lines: List<CartLine>) : CheckoutStep

    /**
     * 可以结算了。
     *
     * @param ambiguous 设了整箱价却没箱规的条码。这些商品**会被按散卖计价**
     *        （只有总数、拆不出箱），也就是整箱价被忽略、多收顾客的钱。
     *        不拦结算，但必须在框里说出来。
     */
    data class Ready(val lines: List<CartLine>, val ambiguous: List<String>) : CheckoutStep
}
