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
import com.example.myapplication.sync.OrderFileId
import com.example.myapplication.sync.outbox.OutboxStore
import com.example.myapplication.sync.outbox.OutboxUploader
import com.example.myapplication.ui.CurrentOrderMode
import com.example.myapplication.ui.OrderMode
import com.example.myapplication.util.AssetsSoundPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _saleItems = MutableStateFlow<List<OrderItem>>(emptyList())
    private val _purchaseItems = MutableStateFlow<List<OrderItem>>(emptyList())

    /** 当前模式那辆车。UI 只观察这一个。 */
    private val _orderItems = MutableStateFlow<List<OrderItem>>(emptyList())
    val orderItems: StateFlow<List<OrderItem>> = _orderItems.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 结算流程走到哪一步了。UI 据此决定弹哪个框。 */
    private val _checkout = MutableStateFlow<CheckoutStep>(CheckoutStep.Idle)
    val checkout: StateFlow<CheckoutStep> = _checkout.asStateFlow()

    val totalPrice: BigDecimal
        get() = _orderItems.value.fold(BigDecimal.ZERO) { acc, item -> acc.add(item.subtotal) }

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

    // 待开单相关状态
    private val _sourcePendingFileName = MutableStateFlow<String?>(null)
    val sourcePendingFileName: StateFlow<String?> = _sourcePendingFileName.asStateFlow()

    private val _targetPendingFileName = MutableStateFlow<String?>(null)
    val targetPendingFileName: StateFlow<String?> = _targetPendingFileName.asStateFlow()

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
            _sourcePendingFileName.value = sale.sourcePendingFileName
            _targetPendingFileName.value = sale.targetPendingFileName

            val restored = when {
                saleItems.isNotEmpty() -> OrderMode.SALE
                purchaseItems.isNotEmpty() -> OrderMode.PURCHASE
                else -> OrderMode.SALE
            }
            setMode(restored, persist = false)
        }
    }

    /** 存的是条码 + 数量，`Product` 要现查（价格可能已经变了）。查不到的条目直接丢掉。 */
    private suspend fun hydrate(saved: List<com.example.myapplication.data.SavedOrderItem>): List<OrderItem> {
        val out = ArrayList<OrderItem>(saved.size)
        for (s in saved) {
            val p = repository.getProductByBarcode(s.barcode)
            if (p != null) out += OrderItem(product = p, quantity = s.quantity)
            // 查不到就丢：商品被删了。留着的话结算时会拼出一条没有价格的行，
            // 而"少了一个条目"用户看得见，"多了一行 0.00"看不见。
        }
        return out
    }

    /**
     * 切换模式。
     *
     * @param persist false 只用于 [restoreCarts] 里回填，那时不该反过来再写一次盘。
     */
    fun setMode(mode: OrderMode, persist: Boolean = true) {
        if (_mode.value == mode && persist) return
        _mode.value = mode
        CurrentOrderMode.set(mode)
        _orderItems.value = if (mode == OrderMode.SALE) _saleItems.value else _purchaseItems.value
        _checkout.value = CheckoutStep.Idle
        if (persist) saveOrder()
    }

    /** 当前模式那辆车。所有增删改都必须走它，不能直接碰 `_orderItems`。 */
    private fun currentCart(): MutableStateFlow<List<OrderItem>> =
        if (_mode.value == OrderMode.SALE) _saleItems else _purchaseItems

    private fun setCart(items: List<OrderItem>) {
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
                sourcePendingFileName = _sourcePendingFileName.value,
                targetPendingFileName = _targetPendingFileName.value,
            )
        }
    }

    fun setClerkNames(names: List<String>) {
        _clerkNames.value = names
    }

    // ==================== 待开单互转 ====================

    fun loadPendingOrderItems(items: List<OrderItem>, sourceFileName: String, targetFileName: String? = null) {
        setCart(items)
        _sourcePendingFileName.value = sourceFileName
        _targetPendingFileName.value = targetFileName
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
                    setCart(listOf(OrderItem(product = product, quantity = 5)) + _orderItems.value)
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
                        val qty = if (barcode[0] == '0') 1 else 5
                        setCart(listOf(OrderItem(product = product, quantity = qty)) + _orderItems.value)
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

    fun updateQuantity(barcode: String, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeItem(barcode)
            return
        }
        setCart(_orderItems.value.map { item ->
            if (item.product.barcode == barcode) OrderItem(product = item.product, quantity = newQuantity) else item
        })
        saveOrder()
    }

    fun removeItem(barcode: String) {
        setCart(_orderItems.value.filter { it.product.barcode != barcode })
        saveOrder()
    }

    fun clearOrder() {
        setCart(emptyList())
        _message.value = "订单已清空"
        _sourcePendingFileName.value = null
        _targetPendingFileName.value = null
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

    /** 把当前购物车转成 [CartLine]（按箱规把总数拆成箱 + 零散）。 */
    private fun cartLines(): List<CartLine> =
        CartPricing.fromCart(_orderItems.value.map { it.product to it.quantity })

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
        val pairs = _orderItems.value.map { it.product to it.quantity }
        val missing = CartPricing.missingBoxSize(lines)
        if (missing.isNotEmpty()) {
            _checkout.value = CheckoutStep.NeedBoxSize(lines.filter { it.needsBoxSize })
            return
        }
        // 设了整箱价却没箱规的商品：这种在"只有总数"的录入方式下拆不了箱，
        // 会被按散卖计价 —— 整箱价被忽略、多收顾客的钱。不拦住结算，但必须说出来。
        _checkout.value = CheckoutStep.Ready(lines, CartPricing.ambiguousBoxProducts(pairs))
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

        // ⚠ A 类**必须** month = null：服务端 validateTypeAndMonth 对 month<1||month>12 直接 400。
        val req = CartPricing.toRequest(
            lines = lines,
            // 一律是当前登录用户名。离线且从没登录过时会是空串 —— 那时
            // CheckoutDialog 的"确定"是禁用的，走不到这里。
            writer = defaultWriter().ifBlank { owner },
            ts = System.currentTimeMillis(),
            fileType = if (purchase) OrderFileId.TYPE_A else OrderFileId.TYPE_B,
            year = cal.get(Calendar.YEAR),
            month = if (purchase) null else cal.get(Calendar.MONTH) + 1,
            // 进货单不算钱：手工总额只对销售单有意义
            manualTotalCents = if (purchase) null else manualTotalCents,
            remarkText = if (purchase) remarkText else null,
        )
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
                OrderHistoryStorage(ctx).saveNewOrder(_orderItems.value, totalPrice.toPlainString())
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
                val total = totalPrice.toPlainString()
                _targetPendingFileName.value?.let { name ->
                    val f = java.io.File(ctx.filesDir, "PendingOrders/$name")
                    if (f.exists()) f.delete()
                }
                val savedFile = storage.savePendingOrder(_orderItems.value, total)
                savedFileName = savedFile.record.fileName
                _targetPendingFileName.value = savedFile.record.fileName
                _sourcePendingFileName.value = _targetPendingFileName.value
                _message.value = "已保存到待开单"
                saveOrder()
                onSaveComplete?.invoke()
            } catch (e: Exception) {
                _message.value = "保存待开单失败: ${e.message}"
            }
        }
        return savedFileName
    }

    fun getOrderItemCount(): Int = _orderItems.value.size

    fun getTargetPendingFileName(): String? = _targetPendingFileName.value
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
