package com.example.myapplication.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.math.BigDecimal

@Entity(tableName = "products")
data class Product(
    @PrimaryKey
    val barcode: String,  // 条形码
    val name: String,     // 商品名
    val price: BigDecimal // 价格，使用 BigDecimal 保证计算精度
)
