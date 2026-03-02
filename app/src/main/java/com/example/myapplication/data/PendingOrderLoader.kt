package com.example.myapplication.data



/**
 * 待开单数据加载器（全局单例）
 * 用于在页面之间传递待开单数据
 */
object PendingOrderLoader {
    private var _orderItems: List<OrderItem>? = null
    private var _sourceFileName: String? = null
    private var _targetOrder: String? = null // 当前订单保存的目标文件

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

    /**
     * 加载数据后立即清除
     */
    fun consume(): Triple<List<OrderItem>?, String?, String?> {
        val items = _orderItems
        val source = _sourceFileName
        val target = _targetOrder
        _orderItems = null
        _sourceFileName = null
        _targetOrder = null
        return Triple(items, source, target)
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
    }
}

