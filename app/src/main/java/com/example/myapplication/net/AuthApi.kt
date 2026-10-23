package com.example.myapplication.net

import com.example.myapplication.net.dto.LoginResponseDto

/**
 * [Service1Api] 里商户端登录那一小段的窄接口。
 *
 * ## 为什么要有这个接口
 *
 * 与 [ProductApi] / [OrderFileApi] 同一个理由：`Service1Api` 是 `final` 的，
 * 项目又没引 Mockito，所以 `SessionRepository` 若直接依赖它就没法在 JVM 上测。
 *
 * 而登录这一段恰恰是**分支最多、错了最贵**的一处：
 *  - `ownerName` 缺失时能不能退回 `username`，取决于 `level` 是不是 `A`/`B`
 *    （退错的后果是订单落进一个只有本机知道的私有文件，同店其它设备永远同步不到）；
 *  - `previousLoginAt` 与本地 `lastLoginAt` 不等 ⇒ 离线期间有别的设备登录过，必须警告；
 *  - 收养（`AdoptionTx`）只该在作用域还是 `__local__` 时跑，且必须在切作用域**之前**；
 *  - `store_meta` 的 upsert 是 `REPLACE`，直接写一个新对象会把 `enumFloor` /
 *    `priceVersion` / `adoptedAt` 全部冲掉。
 *
 * 这些分支每一条都必须能被单测钉住，所以接口是必需的，不是过度设计。
 *
 * ## 只抽这两个方法
 *
 * `/api/admin/login`（总部版）**不抽**：那是 hq flavor 的事，响应类型都不同
 * （[com.example.myapplication.net.dto.AdminLoginResponseDto] 没有 `level` 也没有 `syncData`），
 * 混进同一个接口只会逼调用方到处判空。
 *
 * ## 错误约定
 *
 * 与其它窄接口一致：失败一律**抛 [ApiError]**，绝不返回 null 表示失败。
 * "登录失败"与"登录成功但响应缺字段"必须能区分：前者可以重试，
 * 后者说明服务端版本对不上，重试一万次也是同一个结果。
 */
interface AuthApi {

    /**
     * `POST /api/auth/login`。店主登录，响应里 `level` = `OWNER`。
     *
     * @param priceVersion / @param stockVersion 本地游标，首次登录传 0。
     *        **漏传等于每次登录都拿 FULL**（服务端只有收到游标才 `setSyncData`）。
     */
    suspend fun login(
        username: String,
        password: String,
        priceVersion: Int,
        stockVersion: Int,
    ): LoginResponseDto

    /**
     * `POST /api/auth/login/employee`。员工登录，`level` = `A` / `B`。
     *
     * ⚠ 字段名与店主登录**不同**（`merchantCode` + `employeeUsername`），
     * 且限速是**独立的桶**（`employee-login-per-minute`），两者互不影响。
     *
     * ⚠ 这条路径上 [LoginResponseDto.ownerName] 与 `username` **必然不同**。
     * 拿 `username` 拼 fileId 的后果见 [LoginResponseDto.fileOwnerOrNull]。
     */
    suspend fun loginEmployee(
        merchantCode: String,
        employeeUsername: String,
        password: String,
        priceVersion: Int,
        stockVersion: Int,
    ): LoginResponseDto
}
