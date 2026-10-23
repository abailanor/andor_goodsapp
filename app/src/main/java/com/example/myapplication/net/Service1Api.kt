package com.example.myapplication.net

import com.example.myapplication.net.dto.AdminAccountUpdateRequestDto
import com.example.myapplication.net.dto.AdminCatalogSyncRequestDto
import com.example.myapplication.net.dto.AdminCatalogSyncResponseDto
import com.example.myapplication.net.dto.AdminLoginRequestDto
import com.example.myapplication.net.dto.AdminLoginResponseDto
import com.example.myapplication.net.dto.ApiEnvelope
import com.example.myapplication.net.dto.BatchStockChangeRequestDto
import com.example.myapplication.net.dto.BatchUpsertResultDto
import com.example.myapplication.net.dto.ChangePasswordRequestDto
import com.example.myapplication.net.dto.EmployeeInfoDto
import com.example.myapplication.net.dto.EmployeeLoginRequestDto
import com.example.myapplication.net.dto.EmployeeManageRequestDto
import com.example.myapplication.net.dto.LoginRequestDto
import com.example.myapplication.net.dto.LoginResponseDto
import com.example.myapplication.net.dto.OrderAddResultDto
import com.example.myapplication.net.dto.OrderPatchRequestDto
import com.example.myapplication.net.dto.OrderPatchResultDto
import com.example.myapplication.net.dto.OrderStreamMessageDto
import com.example.myapplication.net.dto.OrderSyncJsonResultDto
import com.example.myapplication.net.dto.OrderSyncRequestDto
import com.example.myapplication.net.dto.PendingRegistrationDto
import com.example.myapplication.net.dto.PermissionsRequestDto
import com.example.myapplication.net.dto.PriceChangeRequestDto
import com.example.myapplication.net.dto.PriceChangeResultDto
import com.example.myapplication.net.dto.ProductAddRequestDto
import com.example.myapplication.net.dto.ProductBatchUpsertRequestDto
import com.example.myapplication.net.dto.ProductDataDto
import com.example.myapplication.net.dto.RegisterRequestDto
import com.example.myapplication.net.dto.StockAdjustRequestDto
import com.example.myapplication.net.dto.StockAdjustResultDto
import com.example.myapplication.net.dto.StockChangeRequestDto
import com.example.myapplication.net.dto.StockChangeResultDto
import com.example.myapplication.net.dto.SyncKind
import com.example.myapplication.net.dto.requireSuccess
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.lang.reflect.Type

/**
 * service1 的 HTTP 客户端。**不引 Retrofit**：DTO 少，而 FULL 同步是**裸字节流**
 * （`Content-Type: application/octet-stream`，26MB 级别），Retrofit 的转换器链在那里是负担。
 *
 * ## 这个类只做三件事
 *
 *  1. 拼 URL / 请求体、挂 `Authorization`；
 *  2. 非 2xx → 交给 [ApiErrorClassifier]；网络异常 → [ApiError.Network]；
 *  3. 2xx → Gson 解析信封，缺字段 → [ApiError.MalformedResponse]。
 *
 * **不做**重试、不做重登、不做限速排队、不碰镜像与 Room —— 那些是引擎层的职责。
 * 把它们混进来会让"429 绝不自动重试"这类硬规则变得难以保证。
 *
 * ## ⚠ `/add` 用的是**禁用连接级重试**的 client
 *
 * `apiClient` 设了 `retryOnConnectionFailure(true)`，这对幂等的读是净收益。
 * 但 OkHttp 的 `isRecoverable()` 在"请求体**已经发出去了**、然后连接被重置"这种情况下
 * 返回 **true** —— 它会**静默重发整个 POST**。
 *
 * 对 `/add` 这是灾难：服务端可能已经提交（`order_write.lua` 是原子的），
 * 重发就会在文件里追加**同一个 `order_uuid` 两次**（`parseOrderJson` 不查重）。
 * 两台设备各收一条 ADD、各追加一行 → `order_index` 主键冲突、镜像比服务器长 →
 * **之后所有偏移错位，而且是静默的**。
 *
 * 所以 [addOrder] 走 [noRetryClient]。这与 outbox 的 `IN_FLIGHT` write-ahead 是
 * **两道独立的防线**，都要有：write-ahead 管"进程重启后怎么办"，
 * 禁用重试管"同一次调用里别偷偷重发"。
 *
 * ## 它实现 [ProductApi] 与 [OrderFileApi] 两个窄接口
 *
 * 本类是 `final` 的，而项目没引 Mockito，所以两个同步引擎认的都是窄接口而不是本类 ——
 * 引擎的分支才能在 JVM 上用 fake 测。接口本身也写明了错误约定（失败一律抛，不用 null 表示）。
 */
class Service1Api(
    private val apiClient: OkHttpClient,
    /** 用户在设置里能改服务器 IP，所以每次请求现取，不在构造时固化。 */
    private val baseUrlProvider: () -> String,
    /** 同理：401 重登后 token 变了，必须每次现取。返回 null 表示未登录。 */
    private val tokenProvider: () -> String?,
) : ProductApi, OrderFileApi, AuthApi {

    private val gson = Gson()

    /**
     * 与 [apiClient] 唯一区别是关掉连接级重试。只给 [addOrder] 用。
     * `newBuilder()` **共享** ConnectionPool / Dispatcher / TLS 配置，所以这是廉价的。
     *
     * `internal` 而不是 `private` 纯粹是为了让测试能断言这个标志位。
     * 见 `Service1ApiTest` 里"为什么不靠模拟连接重置来证明"那段注释。
     */
    internal val noRetryClient: OkHttpClient by lazy {
        apiClient.newBuilder().retryOnConnectionFailure(false).build()
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ==================== 传输原语 ====================

    /**
     * FULL 同步的响应体。**在 [onFull] 回调返回之前有效**，之后底层连接就关了。
     *
     * @param contentLength `Content-Length`。**可能是 -1**（分块传输），别拿它当断言用，
     *                      只用于进度显示；落地后的真实长度以镜像文件为准。
     */
    class FullFileBody(
        val version: Int,
        val streamId: String,
        val contentLength: Long,
        val source: BufferedSource,
    )

    /** `/api/orderfiles/sync` 的四种结果。 */
    sealed class SyncOutcome {
        /** 客户端已是最新。仍然要保存 [streamId]（它是游标）。 */
        data class Latest(val version: Int, val streamId: String) : SyncOutcome()

        /**
         * 按 [messages] 的**顺序**逐条应用。顺序错了镜像就错了。
         *
         * [streamId] 是流的**队尾** record id，四个分支都会带回来，一律存成新游标。
         */
        data class Incremental(
            val version: Int,
            val streamId: String,
            val messages: List<OrderStreamMessageDto>,
        ) : SyncOutcome()

        /**
         * 已通过 [onFull] 回调流式落地。
         *
         * ## 落地与 Room 事务都在回调里做完，本对象只是"做完了"的回执
         *
         * 顺序必须是"内容 + fsync → 版本头 + fsync → Room 事务"（与服务端一致），
         * 而这个顺序由 [com.example.myapplication.mirror.MirrorCommitter.commitFull]
         * **在它内部**强制，所以调用方应当在 `onFull` 里直接调它，
         * 不必（也不该）把 Room 那半段挪到拿到本对象之后：
         *  - [version] / [streamId] 与 [FullFileBody] 的两个字段**完全同源**（都取自响应头），
         *    等回调结束再读不会多拿到任何信息；
         *  - 挪出去反而制造一个窗口：文件已落盘、游标还没提交，进程此刻被杀的话
         *    下次同步会用旧游标重取增量，撞上守卫的 ALREADY_APPLIED 走对账路径 —— 能自愈，但没必要。
         *
         * Room 事务在连接还没关时执行是**可接受**的：此刻全部字节已经 fsync，
         * 事务失败或被杀都不会丢镜像内容，下一次 sync 会走 LATEST/FULL 自愈。
         */
        data class Full(val version: Int, val streamId: String) : SyncOutcome()
    }

    private fun url(path: String, query: List<Pair<String, String?>> = emptyList()): String {
        val builder = (baseUrlProvider().trimEnd('/') + path).toHttpUrl().newBuilder()
        for ((k, v) in query) if (v != null) builder.addQueryParameter(k, v)
        return builder.toString()
    }

    private fun Request.Builder.auth(): Request.Builder = apply {
        // 服务端 `ReactiveJwtAuthFilter:64` 判的是**区分大小写**的 `"Bearer "` 前缀。
        tokenProvider()?.let { header("Authorization", "Bearer $it") }
    }

    /**
     * 执行并把响应体解析成 [T]。
     *
     * @param endpoint 形如 `POST /api/orderfiles/add`，只用于错误文案定位。
     */
    private suspend fun <T> call(
        endpoint: String,
        request: Request,
        client: OkHttpClient = apiClient,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            // DNS / 连接被拒 / 超时 / TLS 握手失败 / 证书与地址不匹配。可以退避重试。
            throw ApiError.Network(e)
        }
        response.use { resp ->
            val body = runCatching { resp.body?.string() }.getOrNull()
            if (!resp.isSuccessful) throw ApiErrorClassifier.classify(resp.code, body)
            parse(body ?: "")
        }
    }

    /** 解析 `{success, message, data}` 信封并取出 data。 */
    private inline fun <reified T> envelopeType(): Type =
        object : TypeToken<ApiEnvelope<T>>() {}.type

    private inline fun <reified D> parseData(body: String, endpoint: String): D {
        val env = try {
            gson.fromJson<ApiEnvelope<D>>(body, envelopeType<D>())
        } catch (e: JsonSyntaxException) {
            // 反代返回的 HTML 错误页、被截断的响应……都落到这里。
            // 绝不把 body 原文塞进 message：那可能是一整页 HTML。
            throw ApiError.MalformedResponse("$endpoint 的响应不是合法 JSON")
        }
        if (env == null) throw ApiError.MalformedResponse("$endpoint 的响应是 JSON null")
        return env.requireData(endpoint)
    }

    private inline fun <reified D> parseEnvelopeOnly(body: String, endpoint: String): ApiEnvelope<D> {
        val env = try {
            gson.fromJson<ApiEnvelope<D>>(body, envelopeType<D>())
        } catch (e: JsonSyntaxException) {
            throw ApiError.MalformedResponse("$endpoint 的响应不是合法 JSON")
        }
        if (env == null) throw ApiError.MalformedResponse("$endpoint 的响应是 JSON null")
        return env
    }

    private fun jsonBody(value: Any) = gson.toJson(value).toRequestBody(jsonMediaType)

    // ==================== 鉴权 ====================

    /**
     * `POST /api/auth/login`（店主 / 管理员）。
     *
     * ⚠ 走**全局串行队列、间隔 ≥12 秒**（限速是 Guava 平滑桶，几乎没有突发额度），
     * 失败**不自动重试**。这两条都在调用方（登录协调器）实现，本方法不做。
     *
     * @param priceVersion / @param stockVersion 本地游标，首次登录传 0。
     *        **漏传等于每次登录都拿 FULL**（服务端只有收到游标才 `setSyncData`）。
     */
    override suspend fun login(
        username: String,
        password: String,
        priceVersion: Int,
        stockVersion: Int,
    ): LoginResponseDto = call(
        "POST /api/auth/login",
        Request.Builder().url(url("/api/auth/login"))
            .post(jsonBody(LoginRequestDto(username, password, priceVersion, stockVersion)))
            .build(),
    ) { parseData(it, "POST /api/auth/login") }

    /**
     * `POST /api/auth/login/employee`。
     *
     * ⚠ 字段名与店主登录**不同**（`merchantCode` + `employeeUsername`），
     * 且限速是**独立的桶**（`employee-login-per-minute`），两者互不影响。
     */
    override suspend fun loginEmployee(
        merchantCode: String,
        employeeUsername: String,
        password: String,
        priceVersion: Int,
        stockVersion: Int,
    ): LoginResponseDto = call(
        "POST /api/auth/login/employee",
        Request.Builder().url(url("/api/auth/login/employee"))
            .post(
                jsonBody(
                    EmployeeLoginRequestDto(
                        merchantCode, employeeUsername, password, priceVersion, stockVersion
                    )
                )
            ).build(),
    ) { parseData(it, "POST /api/auth/login/employee") }

    /**
     * `POST /api/admin/login`（总部版）。
     *
     * ⚠ 响应里**没有 `level` 也没有 `syncData`**。总部版的商品全量要靠
     * `GET /api/products/list`，订单要靠 `GET /api/admin/orders?after=`。
     */
    suspend fun adminLogin(username: String, password: String): AdminLoginResponseDto = call(
        "POST /api/admin/login",
        Request.Builder().url(url("/api/admin/login"))
            .post(jsonBody(AdminLoginRequestDto(username, password)))
            .build(),
    ) { parseData(it, "POST /api/admin/login") }

    /** `POST /api/auth/register`。用户名冲突 → [ApiError.Conflict]（`isOrderFileServerBehind=false`）。 */
    suspend fun register(username: String, password: String, address: String?): String = call(
        "POST /api/auth/register",
        Request.Builder().url(url("/api/auth/register"))
            .post(jsonBody(RegisterRequestDto(username, password, address)))
            .build(),
    ) { parseEnvelopeOnly<Void>(it, "POST /api/auth/register").let { e -> e.message ?: "已提交申请" } }

    /**
     * `PUT /api/user/password`（商户端）。
     *
     * ⚠ 成功之后**自己的 token 也已失效**（改密码会递增账户纪元），必须立刻重新登录。
     */
    suspend fun changePassword(oldPassword: String, newPassword: String) = call(
        "PUT /api/user/password",
        Request.Builder().url(url("/api/user/password")).auth()
            .put(jsonBody(ChangePasswordRequestDto(oldPassword, newPassword)))
            .build(),
    ) { parseEnvelopeOnly<Void>(it, "PUT /api/user/password").requireSuccess("PUT /api/user/password") }

    /** `PUT /api/admin/account`（总部版改用户名/密码）。字段名与商户端**完全不同**。 */
    suspend fun updateAdminAccount(
        currentPassword: String,
        newUsername: String?,
        newPassword: String?,
    ) = call(
        "PUT /api/admin/account",
        Request.Builder().url(url("/api/admin/account")).auth()
            .put(jsonBody(AdminAccountUpdateRequestDto(currentPassword, newUsername, newPassword)))
            .build(),
    ) { parseEnvelopeOnly<Void>(it, "PUT /api/admin/account").requireSuccess("PUT /api/admin/account") }

    // ==================== 员工与权限 ====================

    /** `GET /api/employees`。仅店主/管理员，否则 403。 */
    suspend fun listEmployees(): List<EmployeeInfoDto> = call(
        "GET /api/employees",
        Request.Builder().url(url("/api/employees")).auth().get().build(),
    ) { parseData(it, "GET /api/employees") }

    /** `POST /api/employees`。`level` 是 `A` / `B`。 */
    suspend fun addEmployee(request: EmployeeManageRequestDto) = call(
        "POST /api/employees",
        Request.Builder().url(url("/api/employees")).auth().post(jsonBody(request)).build(),
    ) { parseEnvelopeOnly<Void>(it, "POST /api/employees").requireSuccess("POST /api/employees") }

    /** `DELETE /api/employees/{username}`。 */
    suspend fun deleteEmployee(username: String) = call(
        "DELETE /api/employees/{username}",
        Request.Builder().url(url("/api/employees/$username")).auth().delete().build(),
    ) { parseEnvelopeOnly<Void>(it, "DELETE /api/employees").requireSuccess("DELETE /api/employees") }

    /**
     * `PUT /api/employees/me/permissions`（改**自己**的权限）。
     *
     * 返回服务端**规范化后**的权限列表（`perms.stream().sorted()`），
     * 要以它为准更新本地缓存，不要用请求里发出去的那份。
     */
    suspend fun setMyPermissions(perms: List<String>): List<String> = call(
        "PUT /api/employees/me/permissions",
        Request.Builder().url(url("/api/employees/me/permissions")).auth()
            .put(jsonBody(PermissionsRequestDto(perms)))
            .build(),
    ) { parseData(it, "PUT /api/employees/me/permissions") }

    /** `PUT /api/employees/{username}/permissions`。 */
    suspend fun setEmployeePermissions(username: String, perms: List<String>): List<String> = call(
        "PUT /api/employees/{username}/permissions",
        Request.Builder().url(url("/api/employees/$username/permissions")).auth()
            .put(jsonBody(PermissionsRequestDto(perms)))
            .build(),
    ) { parseData(it, "PUT /api/employees/{username}/permissions") }

    /**
     * `PUT /api/employees/{username}/password`（店主给员工重置密码）。
     *
     * ⚠ 请求体是裸 `Map<String,String>`，**只有 `newPassword` 一个键**
     * （`EmployeeController:59`）。与商户自己改密码的 [ChangePasswordRequestDto]
     * （要 `oldPassword` + `newPassword`）不是一回事，别复用。
     */
    suspend fun setEmployeePassword(username: String, newPassword: String) = call(
        "PUT /api/employees/{username}/password",
        Request.Builder().url(url("/api/employees/$username/password")).auth()
            .put(jsonBody(mapOf("newPassword" to newPassword)))
            .build(),
    ) {
        parseEnvelopeOnly<Void>(it, "PUT /api/employees/{username}/password")
            .requireSuccess("PUT /api/employees/{username}/password")
    }

    // ==================== 商品 ====================

    /**
     * `GET /api/products/list`。
     *
     * ⚠ 这是**当前全量商品**的唯一非 SSE 入口（没有 `GET /api/sync/data`）。
     * 400 条商品约 40KB，一次拿完没问题；但它**不带版本号**，
     * 所以拿它当基线时必须同时更新吸收边界，见 `StockLedger`。
     */
    suspend fun listProducts(): List<ProductDataDto> = call(
        "GET /api/products/list",
        Request.Builder().url(url("/api/products/list")).auth().get().build(),
    ) { parseData(it, "GET /api/products/list") }

    /** `GET /api/products/{barcode}`。不存在 → [ApiError.NotFound]。 */
    override suspend fun getProduct(barcode: String): ProductDataDto = call(
        "GET /api/products/{barcode}",
        Request.Builder().url(url("/api/products/$barcode")).auth().get().build(),
    ) { parseData(it, "GET /api/products/{barcode}") }

    /** `POST /api/products/add`。 */
    suspend fun addProduct(request: ProductAddRequestDto) = call(
        "POST /api/products/add",
        Request.Builder().url(url("/api/products/add")).auth().post(jsonBody(request)).build(),
    ) { parseEnvelopeOnly<Void>(it, "POST /api/products/add").requireSuccess("POST /api/products/add") }

    /** `DELETE /api/products/{barcode}`。**仅店主**，员工调 → 403。 */
    override suspend fun deleteProduct(barcode: String) = call(
        "DELETE /api/products/{barcode}",
        Request.Builder().url(url("/api/products/$barcode")).auth().delete().build(),
    ) { parseEnvelopeOnly<Void>(it, "DELETE /api/products/{barcode}").requireSuccess("DELETE /api/products/{barcode}") }

    /**
     * `POST /api/products/batch-upsert`。**一批最多 100 条**，超了直接 400。
     *
     * ⚠ 回推本地商品时 `orderable` 必须显式带上（服务端是原始 `boolean`，
     * 漏传会静默变成"不可订"）。见 [ProductAddRequestDto]。
     */
    override suspend fun batchUpsertProducts(
        products: List<ProductAddRequestDto>,
    ): List<BatchUpsertResultDto> = call(
        "POST /api/products/batch-upsert",
        Request.Builder().url(url("/api/products/batch-upsert")).auth()
            .post(jsonBody(ProductBatchUpsertRequestDto(products)))
            .build(),
    ) { parseData(it, "POST /api/products/batch-upsert") }

    /** `PUT /api/products/price`。需要 `PRICE_CHANGE`（B 级员工默认**没有**）。 */
    suspend fun changePrice(request: PriceChangeRequestDto): PriceChangeResultDto = call(
        "PUT /api/products/price",
        Request.Builder().url(url("/api/products/price")).auth().put(jsonBody(request)).build(),
    ) { parseData(it, "PUT /api/products/price") }

    /** `PUT /api/products/stock`。`change` 带符号。 */
    suspend fun changeStock(barcode: String, change: Int): StockChangeResultDto = call(
        "PUT /api/products/stock",
        Request.Builder().url(url("/api/products/stock")).auth()
            .put(jsonBody(StockChangeRequestDto(barcode, change)))
            .build(),
    ) { parseData(it, "PUT /api/products/stock") }

    /** `PUT /api/products/stock/batch`。 */
    suspend fun batchChangeStock(
        changes: List<StockChangeRequestDto>,
    ): List<StockChangeResultDto> = call(
        "PUT /api/products/stock/batch",
        Request.Builder().url(url("/api/products/stock/batch")).auth()
            .put(jsonBody(BatchStockChangeRequestDto(changes)))
            .build(),
    ) { parseData(it, "PUT /api/products/stock/batch") }

    /**
     * `PUT /api/products/stock/adjust`（盘点校正，绝对值）。
     *
     * ⚠ 需要 `PRODUCT_MANAGE`（不是 `STOCK_CHANGE`），且请求字段名是 `correctedStock`。
     * 响应里的绝对值字段名**也是** `correctedStock` 而不是 `newStock` ——
     * `StockLedger` 两个都要认，漏一个就不推进吸收边界。
     */
    suspend fun adjustStock(barcode: String, correctedStock: Int): StockAdjustResultDto = call(
        "PUT /api/products/stock/adjust",
        Request.Builder().url(url("/api/products/stock/adjust")).auth()
            .put(jsonBody(StockAdjustRequestDto(barcode, correctedStock)))
            .build(),
    ) { parseData(it, "PUT /api/products/stock/adjust") }

    /**
     * `POST /api/products/admin-catalog/sync`（拉"丽华金龙"目录）。
     *
     * `adminCode` 不是管理员店铺 → **403**（不是 404）。
     * 打开补货页时**后台异步**调，不阻塞 UI。
     */
    suspend fun syncAdminCatalog(
        adminCode: String,
        lastId: String?,
    ): AdminCatalogSyncResponseDto = call(
        "POST /api/products/admin-catalog/sync",
        Request.Builder().url(url("/api/products/admin-catalog/sync")).auth()
            .post(jsonBody(AdminCatalogSyncRequestDto(adminCode, lastId)))
            .build(),
    ) { parseData(it, "POST /api/products/admin-catalog/sync") }

    // ==================== 订单文件 ====================

    /**
     * `POST /api/orderfiles/add?type=&year=&month=`，body 是**裸订单 JSON**。
     * 协议契约（write-ahead、不重试、偏移权威性）见 [OrderFileApi.addOrder]，这里只记实现细节。
     *
     * @param payloadJson **必须单行、不含控制字符**（服务端 `validateSingleLine` 会拒），
     *        且 `order_uuid` 必须是第一个字段（服务端 `normalizeUuidOffset:1122` 的兜底
     *        是 `indexOf` 找**第一次出现**，别的字段值撞上就会算出错误偏移）。
     *
     * ⚠ 走 [noRetryClient]，见类注释。
     * ⚠ 超 20KB → [ApiError.PayloadTooLarge]。正确反应是**拆单**，不是重试。
     * ⚠ `month` 对 A 类必须传 `null`：A 类没有月份，而服务端 `validateTypeAndMonth`
     *    对 `month < 1 || month > 12` 直接 400。用 [com.example.myapplication.sync.OrderFileId.Parts.monthOrNull]。
     */
    override suspend fun addOrder(
        type: String,
        year: Int,
        month: Int?,
        payloadJson: String,
    ): OrderAddResultDto = call(
        "POST /api/orderfiles/add",
        client = noRetryClient,
        request = Request.Builder()
            .url(
                url(
                    "/api/orderfiles/add",
                    listOf(
                        "type" to type,
                        "year" to year.toString(),
                        "month" to month?.toString(),
                    )
                )
            ).auth()
            .post(payloadJson.toRequestBody(jsonMediaType))
            .build(),
    ) { parseData(it, "POST /api/orderfiles/add") }

    /**
     * `POST /api/orderfiles/patch`。等长覆写。
     *
     * 这个是**幂等**的（同样的字节写同样的偏移两次结果一样），所以可以走默认 client；
     * 重复应用的后果只是版本号多推一次 + 流里多一条消息，
     * 而幂等守卫 `guardPatch` 会把第二次识别成 `AlreadyApplied`。
     */
    suspend fun patchOrder(request: OrderPatchRequestDto): OrderPatchResultDto = call(
        "POST /api/orderfiles/patch",
        Request.Builder().url(url("/api/orderfiles/patch")).auth()
            .post(jsonBody(request))
            .build(),
    ) { parseData(it, "POST /api/orderfiles/patch") }

    /**
     * `POST /api/orderfiles/sync`。
     *
     * ## 三种响应形状，一个方法
     *
     * | 情况 | 响应 | 怎么认 |
     * |---|---|---|
     * | LATEST / INCREMENTAL | JSON 信封 | 无 `X-File-Version` 头 |
     * | **FULL** | **裸字节流** | **有 `X-File-Version` 头** |
     * | SERVER_BEHIND | **HTTP 409** + JSON | 抛 [ApiError.Conflict] |
     *
     * ⚠ 判 FULL 的**唯一可靠依据是 `X-File-Version` 头存在**，不是 Content-Type。
     * OkHttp 的 `Headers.get` 本身不区分大小写，而 HTTP/2 下头名会被强制小写，
     * 所以这里写 `X-File-Version` 是安全的。
     *
     * @param onFull FULL 时**在 IO 线程上同步回调**，响应体仍然打开。
     *        实现里必须**流式**写入 `mirror-tmp/`（边读边写边算校验和），
     *        绝不 `readByteArray()` 把 26MB 读进内存。
     *        回调返回后连接即关闭，[SyncOutcome.Full] 随之产生。
     *
     * ## 409 的 `X-Stream-Id` 被**故意丢弃**
     *
     * 409 响应体不含服务器版本号，处理它必须再发一次 `version=0, lastStreamId=""` 探测
     * （见 [probeAfter409]），那次响应会给出权威的 streamId。
     * 留着 409 的 streamId 只会诱导调用方用一个已经过期的游标。
     */
    override suspend fun syncOrderFile(
        request: OrderSyncRequestDto,
        onFull: suspend (FullFileBody) -> Unit,
    ): SyncOutcome = withContext(Dispatchers.IO) {
        val httpRequest = Request.Builder().url(url("/api/orderfiles/sync")).auth()
            .post(jsonBody(request))
            .build()
        val response = try {
            apiClient.newCall(httpRequest).execute()
        } catch (e: IOException) {
            throw ApiError.Network(e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body?.string() }.getOrNull()
                throw ApiErrorClassifier.classify(resp.code, body)
            }

            val fileVersionHeader = resp.header(HEADER_FILE_VERSION)
            if (fileVersionHeader != null) {
                // ---- FULL：裸字节流，游标只在响应头里 ----
                val version = fileVersionHeader.toIntOrNull()
                    ?: throw ApiError.MalformedResponse("$HEADER_FILE_VERSION 不是整数: $fileVersionHeader")
                val streamId = resp.header(HEADER_STREAM_ID) ?: ""
                val body = resp.body ?: throw ApiError.MalformedResponse("FULL 响应没有 body")
                onFull(FullFileBody(version, streamId, body.contentLength(), body.source()))
                SyncOutcome.Full(version, streamId)
            } else {
                // ---- LATEST / INCREMENTAL：JSON 信封 ----
                val dto = parseData<OrderSyncJsonResultDto>(
                    resp.body?.string() ?: "", "POST /api/orderfiles/sync"
                )
                val version = dto.version
                    ?: throw ApiError.MalformedResponse("sync 响应缺 version")
                val streamId = dto.streamId ?: ""
                when (dto.kind) {
                    SyncKind.INCREMENTAL ->
                        SyncOutcome.Incremental(version, streamId, dto.messages.orEmpty())

                    SyncKind.LATEST -> SyncOutcome.Latest(version, streamId)

                    // JSON 信封自称 FULL 却没有字节流：形状自相矛盾，不能猜。
                    SyncKind.FULL -> throw ApiError.MalformedResponse(
                        "sync 响应的 type=FULL 但缺少 $HEADER_FILE_VERSION 头（响应体不是字节流）"
                    )

                    // ⚠ 订单文件同步的 SERVER_BEHIND **不走 type 字段，走 HTTP 409**
                    // （`OrderFileController:111-115`），上面 `!resp.isSuccessful` 那一支
                    // 已经把它抛成 `ApiError.Conflict` 了，由 `resolve409()` 接手。
                    // 所以 2xx 的信封里出现它 = 形状自相矛盾。**不能**顺手当 409 处理：
                    // 那会让"服务端改了协议"这件事被一条现成的兼容路径悄悄吸收掉。
                    SyncKind.SERVER_BEHIND -> throw ApiError.MalformedResponse(
                        "sync 响应的 type=SERVER_BEHIND 但状态码是 2xx（服务端应当用 409 表达它）"
                    )

                    // 服务端加了新类型。**绝不**默认当 FULL —— FULL 会覆盖本地数据。
                    SyncKind.UNKNOWN -> throw ApiError.MalformedResponse(
                        "sync 响应有未知的 type=${dto.type}"
                    )
                }
            }
        }
    }

    /**
     * 409 之后的二值探测：**再发一次 `version=0, lastStreamId=""`**。
     *
     * 两个值是**字面量**，不接受参数 —— 传进来的任何 version 都会让探测失去意义。
     *
     * | 探测结果 | 含义 | 正确处理 |
     * |---|---|---|
     * | [SyncOutcome.Latest] | 服务器**根本没这个文件**（运维已归档删除） | `syncState='ARCHIVED'`，镜像移入 `mirror-archive/`，**游标原样冻结不归零** |
     * | [SyncOutcome.Full] | 服务器确实落后但文件还在，`X-File-Version` 就是它当前的版本 | 问用户：接受服务器版本 / `batch-upsert` 回推 |
     * | [SyncOutcome.Incremental] | 不该出现（version=0 时服务端只会给 FULL 或 LATEST） | 当成形状错误，记日志 + 排一次 FULL |
     *
     * ⚠ "接受服务器版本"意味着**丢弃本地多出来的尾部**；
     * "重推本地尾部"在现有 API 上**做不到**（没有上传文件尾部的接口），
     * 可行路径只有 FULL 到 `mirror-tmp/probe-<fileId>` → 与本地镜像做 `order_uuid` 差集 →
     * 差集逐条 `/add`（限速）→ **丢弃本地镜像、强制 FULL 落地、重建 order_index 与统计贡献**。
     * 最后一步不能省：重推后服务器上的偏移与本地记录的完全不同，
     * 不重建的话之后每次取消都会打到错误偏移，而且是**静默**的。
     */
    override suspend fun probeAfter409(
        type: String,
        year: Int,
        month: Int?,
        onFull: suspend (FullFileBody) -> Unit,
    ): SyncOutcome = syncOrderFile(
        OrderSyncRequestDto(type, year, month, version = 0, lastStreamId = ""),
        onFull,
    )

    // ==================== 总部版（hq flavor） ====================

    /**
     * `GET /api/admin/orders?after=<cursor>`（消费流列表）。
     *
     * ⚠ `/api/admin/subscribe` **没有 `reconcileNow`**（建连时不补发一次性快照），
     * 所以总部版断线重连后**必须**自己调这个接口补齐，否则会永久漏掉重连期间的新订单。
     *
     * 元素是裸 `Map`：`NEW_ORDER` 消息（字段见 `ConsumeStreamFields`）或处理结果消息。
     */
    suspend fun adminOrders(after: String?): List<Map<String, Any?>> = call(
        "GET /api/admin/orders",
        Request.Builder()
            .url(url("/api/admin/orders", listOf("after" to after)))
            .auth().get().build(),
    ) { parseData(it, "GET /api/admin/orders") }

    /** `POST /api/admin/orders/{orderUuid}/complete`。客户文件状态 1→2 + 追加完成记录文件。 */
    suspend fun adminCompleteOrder(orderUuid: String): Map<String, Any?> = call(
        "POST /api/admin/orders/{uuid}/complete",
        Request.Builder().url(url("/api/admin/orders/$orderUuid/complete")).auth()
            .post(EMPTY_BODY).build(),
    ) { parseData(it, "POST /api/admin/orders/{uuid}/complete") }

    /** `POST /api/admin/orders/{orderUuid}/cancel`。 */
    suspend fun adminCancelOrder(orderUuid: String): Map<String, Any?> = call(
        "POST /api/admin/orders/{uuid}/cancel",
        Request.Builder().url(url("/api/admin/orders/$orderUuid/cancel")).auth()
            .post(EMPTY_BODY).build(),
    ) { parseData(it, "POST /api/admin/orders/{uuid}/cancel") }

    /** `POST /api/admin/orders/{orderUuid}/delete-completed`。 */
    suspend fun adminDeleteCompleted(orderUuid: String): Map<String, Any?> = call(
        "POST /api/admin/orders/{uuid}/delete-completed",
        Request.Builder().url(url("/api/admin/orders/$orderUuid/delete-completed")).auth()
            .post(EMPTY_BODY).build(),
    ) { parseData(it, "POST /api/admin/orders/{uuid}/delete-completed") }

    /**
     * `GET /api/admin/pending-registrations`。
     *
     * ⚠ 这个接口服务端是 `SMEMBERS pending_reg:index` + 逐个 `HGETALL`，
     * 而那个索引键的维护要**遍历整个键空间**（`RedisDataService:462` 的注释明说了
     * 这是一个客户端可触发的 DoS）。所以**不要轮询**，只在用户打开审批页时调一次。
     */
    suspend fun pendingRegistrations(): List<PendingRegistrationDto> = call(
        "GET /api/admin/pending-registrations",
        Request.Builder().url(url("/api/admin/pending-registrations")).auth().get().build(),
    ) { parseData(it, "GET /api/admin/pending-registrations") }

    /** `POST /api/admin/approve/{username}`。 */
    suspend fun approveRegistration(username: String) = call(
        "POST /api/admin/approve/{username}",
        Request.Builder().url(url("/api/admin/approve/$username")).auth()
            .post(EMPTY_BODY).build(),
    ) { parseEnvelopeOnly<Void>(it, "POST /api/admin/approve").requireSuccess("POST /api/admin/approve") }

    /** `POST /api/admin/reject/{username}`。 */
    suspend fun rejectRegistration(username: String) = call(
        "POST /api/admin/reject/{username}",
        Request.Builder().url(url("/api/admin/reject/$username")).auth()
            .post(EMPTY_BODY).build(),
    ) { parseEnvelopeOnly<Void>(it, "POST /api/admin/reject").requireSuccess("POST /api/admin/reject") }

    companion object {
        /** OkHttp 的 `post()` 不接受 null body，用零长度 body 顶替。 */
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null, 0, 0)

        /**
         * 从响应头取游标的辅助。SSE 的 `Last-Event-ID` 续传与 FULL 的 `X-Stream-Id` 都靠头，
         * 而 OkHttp 的 `header()` 已经不区分大小写，所以这里只是把"哪些头有意义"集中记下来。
         */
        const val HEADER_FILE_VERSION = "X-File-Version"
        const val HEADER_STREAM_ID = "X-Stream-Id"

        /** 请求体上限（`spring.codec.max-in-memory-size=20KB`）。超了服务端回 413。 */
        const val MAX_BODY_BYTES = 20 * 1024
    }
}
