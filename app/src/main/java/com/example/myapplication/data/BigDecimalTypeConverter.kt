package com.example.myapplication.data

import androidx.room.TypeConverter
import java.math.BigDecimal

/**
 * BigDecimal 类型转换器
 * 用于在数据库 REAL 类型和 BigDecimal 之间转换
 */
class BigDecimalTypeConverter {
    @TypeConverter
    fun fromBigDecimal(value: BigDecimal?): String? {
        return value?.toPlainString()  // 保留完整精度
    }

    @TypeConverter
    fun toBigDecimal(value: String?): BigDecimal? {
        return value?.toBigDecimalOrNull()
    }

    @TypeConverter
    fun toBigDecimal(value: Double?): BigDecimal? {
        return value?.let { BigDecimal(it.toString()) }
    }
}
