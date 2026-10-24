package com.example.myapplication.data



/**
 * 待开单数据加载器（全局单例）
 * 用于在页面之间传递待开单数据
 */
object PendingOrderLoader {

    /** 一次"把待开单搬到开单页"的完整请求。 */
    data class Request(
        val items: List<OrderItem>?,
        val sourceFileName: String?,
        val targetOrder: String?,
        /**
         * `OrderMode` 的 name（`"SALE"` / `"PURCHASE"`），与 [PendingOrderRecord.mode] 同一套字符串。
         *
         * ⚠ 必须跟着一起传：开单页要**先切模式再放购物车**。少传的话进货单会落进销售车，
         * 而销售车是 B 类文件 —— 计营收、扣库存，正好是进货单不该做的两件事。
         * null = 老数据（没有这个字段），按销售处理。
         */
        val mode: String?,
    )

    private var _orderItems: List<OrderItem>? = null
    private var _sourceFileName: String? = null
    private var _targetOrder: String? = null // 当前订单保存的目标文件
    private var _mode: String? = null

    var orderItems: List<OrderItem>?
        get() = _orderItems
        set(value) {
            _orderItems = value
        }

    var sourceFileName: String?
        get() = _sourceFileName
        set(value) {
            _sourceFileName = value
        }

    var targetOrder: String?
        get() = _targetOrder
        set(value) {
            _targetOrder = value
        }

    var mode: String?
        get() = _mode
        set(value) {
            _mode = value
        }

    /**
     * 加载数据后立即清除
     */
    fun consume(): Request {
        val request = Request(
            items = _orderItems,
            sourceFileName = _sourceFileName,
            targetOrder = _targetOrder,
            mode = _mode,
        )
        clear()
        return request
    }

    /**
     * 检查是否有待加载的数据
     */
    fun hasData(): Boolean = _orderItems != null && _sourceFileName != null

    /**
     * 清除所有数据
     */
    fun clear() {
        _orderItems = null
        _sourceFileName = null
        _targetOrder = null
        _mode = null
    }
}
