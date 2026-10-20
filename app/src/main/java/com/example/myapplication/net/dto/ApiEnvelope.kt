package com.example.myapplication.net.dto

import com.example.myapplication.net.ApiError

/**
 * 服务端的统一响应信封 `{success, message, data}`（`model/dto/ApiResponse.java`）。
 *
 * ## ⚠ 为什么所有字段都是可空的
 *
 * Gson 反序列化 Kotlin 类时**绕过构造器**（`Unsafe.allocateInstance`），所以：
 *  - 字段默认值**不会被应用**（`val x: String = ""` 在 JSON 缺 `x` 时仍然是 null）；
 *  - Kotlin 的非空类型**不会被校验**（`val x: String` 可以被塞进 null，NPE 在很远的地方才炸）。
 *
 * 服务端 `ApiResponse.ok(data)` **不写 message**（只有 `ok(message, data)` 才写），
 * 所以 `message` 在成功响应里天然缺失 —— 声明成非空 `String` 必然踩这个坑。
 *
 * 结论：DTO 层一律可空，**校验集中在 [requireData]**，让"字段缺了"变成一个有明确
 * 文案的错误，而不是一个迟到的 NullPointerException。
 */
class ApiEnvelope<T>(
    val success: Boolean?,
    val message: String?,
    val data: T?,
) {
    /**
     * 取出 `data`，失败归一成 [ApiError]。
     *
     * 两种失败要分开，因为它们的含义不同：
     *  - `success == false`：服务端**明确**拒绝了，`message` 就是给用户看的话；
     *  - `success == true` 但 `data == null`：形状不对（服务端改了、或反代插了一页 HTML）。
     *    这不是"业务失败"，按 [ApiError.MalformedResponse] 处理，**不要**把 null message 显示给用户。
     *
     * @param endpoint 出错时用于日志定位，例如 `POST /api/orderfiles/add`。
     */
    fun requireData(endpoint: String): T {
        if (success != true) {
            throw ApiError.BadRequest(message ?: "$endpoint 返回 success=false 且没有 message")
        }
        return data ?: throw ApiError.MalformedResponse("$endpoint 的响应缺少 data 字段")
    }
}

/** 服务端**成功但不带 data** 的响应（改密码、删除商品、员工管理…）。 */
fun ApiEnvelope<*>.requireSuccess(endpoint: String) {
    if (success != true) {
        throw ApiError.BadRequest(message ?: "$endpoint 返回 success=false 且没有 message")
    }
}
