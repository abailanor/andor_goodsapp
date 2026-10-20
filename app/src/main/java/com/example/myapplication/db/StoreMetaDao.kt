package com.example.myapplication.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StoreMetaDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: StoreMeta)

    @Query("SELECT * FROM store_meta WHERE storeKey = :storeKey")
    suspend fun get(storeKey: String): StoreMeta?

    @Query("SELECT * FROM store_meta WHERE storeKey = :storeKey")
    fun observe(storeKey: String): Flow<StoreMeta?>

    @Query("SELECT * FROM store_meta ORDER BY adoptedAt DESC")
    suspend fun getAll(): List<StoreMeta>

    @Query("SELECT storeKey FROM store_meta")
    suspend fun getAllStoreKeys(): List<String>

    // ---------- 版本号 ----------

    @Query("UPDATE store_meta SET priceVersion = :v WHERE storeKey = :storeKey")
    suspend fun setPriceVersion(storeKey: String, v: Int)

    @Query("UPDATE store_meta SET stockVersion = :v WHERE storeKey = :storeKey")
    suspend fun setStockVersion(storeKey: String, v: Int)

    // ---------- 月份枚举下界 ----------

    /**
     * 把枚举下界**往前**推（只会变小，不会变大）。
     *
     * 下界必须是 `min(createdAt 所在月, 本地写入过的最早月份, 2020-01)`。
     * 只往前推是因为它的唯一作用是"别漏掉更早的月份"——往后推会漏数据。
     *
     * 触发点：补传历史订单时（订单可能落进 `createdAt` 之前的月份，
     * 而 README §3.2 的枚举从 `createdAt` 开始，那些月份永远不会被同步）。
     */
    @Query(
        """
        UPDATE store_meta
        SET enumFloorYear = :year, enumFloorMonth = :month
        WHERE storeKey = :storeKey
          AND (enumFloorYear > :year OR (enumFloorYear = :year AND enumFloorMonth > :month))
        """
    )
    suspend fun lowerEnumFloor(storeKey: String, year: Int, month: Int): Int

    @Query("SELECT enumFloorYear, enumFloorMonth FROM store_meta WHERE storeKey = :storeKey")
    suspend fun getEnumFloor(storeKey: String): EnumFloor?

    // ---------- 权限 ----------

    /** 收到 SSE `PERMISSIONS` 事件后就地更新。**不要只在登录时读一次**——权限会被店主随时改。 */
    @Query("UPDATE store_meta SET permsCsv = :permsCsv WHERE storeKey = :storeKey")
    suspend fun setPerms(storeKey: String, permsCsv: String)

    // ---------- 登录时间 ----------

    /**
     * 记录本次登录时间。
     *
     * 下次登录时拿响应里的 `previousLoginAt` 与本地存的 `lastLoginAt` 比对，
     * 不一致就说明**自己离线期间有别的设备登录过**——这是 `KICKED` 推送覆盖不到的情况
     * （被踢时你可能根本没连着）。服务器只存权威值、不接受客户端上传，
     * 所以不用担心时钟或伪造。
     */
    @Query(
        """
        UPDATE store_meta
        SET lastLoginAt = :currentLoginAt, previousLoginAt = :previousLoginAt
        WHERE storeKey = :storeKey
        """
    )
    suspend fun recordLogin(storeKey: String, previousLoginAt: Long, currentLoginAt: Long)

    /**
     * 收养：把离线模式（`__local__`）的全部数据改写成真实店铺。
     *
     * 一个事务里逐表 UPDATE storeKey，由 [AdoptionTx] 组织。这里只提供 store_meta 那一步。
     */
    @Query("UPDATE store_meta SET storeKey = :newKey, adoptedAt = :now WHERE storeKey = :oldKey")
    suspend fun adoptStoreKey(oldKey: String, newKey: String, now: Long)
}

/** [StoreMetaDao.getEnumFloor] 的投影。 */
data class EnumFloor(val enumFloorYear: Int, val enumFloorMonth: Int)
