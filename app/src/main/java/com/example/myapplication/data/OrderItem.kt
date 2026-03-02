package com.example.myapplication.data

import java.math.BigDecimal

data class OrderItem(
    val product: Product,
    var quantity: Int = 5
) {
    val subtotal: BigDecimal
        get() = product.price.multiply(BigDecimal(quantity))
}
