package com.example.myapplication.util

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.*

object PriceFormatter {
    private val currencyFormat: NumberFormat = NumberFormat.getCurrencyInstance(Locale.CHINA)

    init {
        currencyFormat.minimumFractionDigits = 2
        currencyFormat.maximumFractionDigits = 2
    }

    fun format(price: BigDecimal): String {
        return currencyFormat.format(price)
    }

    fun format(price: Double): String {
        return currencyFormat.format(price)
    }

    fun formatPlain(price: BigDecimal): String {
        return String.format("%.2f", price)
    }

    fun formatPlain(price: Double): String {
        return String.format("%.2f", price)
    }

    fun format(price: String): String {
        return try {
            currencyFormat.format(price.toBigDecimal())
        } catch (e: Exception) {
            price
        }
    }
}
