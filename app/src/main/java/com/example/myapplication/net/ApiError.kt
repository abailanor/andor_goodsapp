package com.example.myapplication.net

/**
 * 服务端错误的分类。
 *
 * ## 为什么分类要这么细
 *
 * 每个状态码对应**完全不同的客户端动作**，混用同一套处理会造成真实损害：
 *
 * | 状态 | 动作 | 做错的后果 |
 * |---|---|---|
 * | 400 | 展示 message，**不重试** | 重试只会一直 400 |
 * | 401 | **重新登录**，不重试同一 token | 无限重试 → 撞登录限速 → 429 雪球 |
 * | 403 | 提示权限不足，**不重试** | 权限是每请求实时读 Redis 的，重试无意义 |
 * | 404 | 资源不存在 | — |
 * | 409 | 用户名冲突；**或订单文件 SERVER_BEHIND** | 后者若拿服务器内容覆盖本地 = 丢掉客户端唯一那份更完整的数据 |
 * | 413 | 请求体超 20KB | 应当拆单，不是重试 |
 * | 429 | **绝不自动重试** | 自动重试会把限速桶彻底占死 |
 * | 500 | 只有追踪号，连同时间记下来 | 丢了追踪号就没法报障 |
 *
 * ## ⚠ 401 的响应体可能是空的
 *
 * `SecurityConfig` 没有自定义 `authenticationEntryPoint`，且 `httpBasic`/`formLogin`
 * 都禁用了 → Spring Security 用默认的 `HttpStatusServerEntryPoint(UNAUTHORIZED)`，
 * **401 带空响应体，不是 JSON 信封**。
 *
 * 所以"token 过期"（20 小时到期、每月 1 号 3:40 密钥轮换）这条最常见的路径上
 * **根本没有 message 可以分类**，只能落到 [UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED]。
 * 解析器必须容忍 body 为 null / 空串 / 非 JSON。
 *
 * ## 有 message 的 401 来自 EmployeeService（受权限保护的端点）
 *
 * 三条文案是**源码里核实过的原文**，不是猜的：
 *  - `账户不存在或已被删除，请重新登录`（EmployeeService:168）
 *  - `账户凭证已变更，请重新登录`（:172，账户改密码/改名导致 epoch 递增）
 *  - `该账户已在别处登录，请重新登录`（:178，单会话策略）
 *
 * 登录失败走 `InvalidCredentialsException()` 无参构造 → `用户名或密码错误`。
 */
sealed class ApiError(message: String?, cause: Throwable? = null) : Exception(message, cause) {

    /** 400：参数/校验错误。展示 message，不要重试。 */
    data class BadRequest(val msg: String) : ApiError(msg)

    /** 401：需要重新登录。**不要**拿旧 token 重试。 */
    data class Unauthorized(val reason: UnauthorizedReason, val msg: String?) : ApiError(msg)

    /** 403：权限不足。权限是每次请求实时读 Redis 的，改了立刻生效，重试无意义。 */
    data class Forbidden(val msg: String) : ApiError(msg)

    /** 404：资源不存在（订单不存在或已被处理、文件不存在、**接口路径不存在**、商品不存在）。 */
    data class NotFound(val msg: String) : ApiError(msg)

    /**
     * 409：两种完全不同的语义，靠 [isOrderFileServerBehind] 区分。
     *
     *  - 用户名冲突（注册/改名）→ 提示用户换一个
     *  - **订单文件同步时服务器版本落后于客户端** → 绝不能用服务器内容覆盖本地
     *
     * ⚠ 409 的响应体**不含服务器版本号**（`OrderFileController:116` 只回一句固定文案 +
     * `X-Stream-Id` 头）。所以客户端无法从这里判断"服务器落后多少"，也无法区分
     * "服务器真落后"与"服务器已把这个文件归档删除"。要靠 `version=0` 再探一次，
     * 见 `OrderFileSyncEngine.resolve409()`。
     */
    data class Conflict(val msg: String, val isOrderFileServerBehind: Boolean) : ApiError(msg)

    /** 413：请求体超过 20KB（`spring.codec.max-in-memory-size`）。应当拆单，不是重试。 */
    data class PayloadTooLarge(val msg: String) : ApiError(msg)

    /**
     * 429：触发限速。**绝不自动重试。**
     *
     * 服务端是 Guava 平滑令牌桶，几乎没有突发额度：`RateLimiter.create(5/60.0)` 的
     * `maxBurstSeconds=1.0` 只能存 0.083 个令牌，所以 5/min 就是严格每 12 秒一个。
     * 实测连续两次同类登录必须间隔 ≥12 秒，否则必然 429。
     */
    data class RateLimited(val msg: String) : ApiError(msg)

    /**
     * 500：未预期异常。响应里**只有一个追踪号**，不含异常消息
     * （内部类名/Redis 键名/脚本片段/文件路径对调用方无用、对攻击者有用）。
     *
     * 追踪号格式（`GlobalExceptionHandler:128`）：`UUID.randomUUID().toString().substring(0,8)`
     * → 恰好 8 位十六进制；完整文案是 `服务器内部错误（追踪号 ab12cd34，请提供给运维查日志）`。
     * 报障时要把它**连同时间**记下来交给运维，服务端日志按 `traceId=` 检索。
     */
    data class ServerError(val traceId: String?, val msg: String) : ApiError(msg)

    /** 其它非 2xx。 */
    data class HttpOther(val code: Int, val msg: String?) : ApiError(msg)

    /**
     * 网络层失败（DNS/连接/超时/TLS 握手失败/证书不匹配）。可以退避重试。
     *
     * 不是 data class：`cause` 要交给 `Throwable` 的构造器以保留堆栈链，
     * 声明成 `val cause` 会与 `Throwable.cause` 冲突（需要 override）。
     */
    class Network(cause: Throwable) : ApiError(cause.message, cause)

    /** 响应体形状不符合预期（不是 JSON 信封、缺字段等）。 */
    data class MalformedResponse(val msg: String) : ApiError(msg)
}

/**
 * 401 的细分。**这个区分决定了"能不能自动重新登录"**。
 *
 * 自动登录 + 单会话策略是一对危险组合：两台设备用同一账号会无限互踢。
 * 所以自动登录必须只在"确实是 token 自己失效了"的情况下发生。
 */
enum class UnauthorizedReason {
    /**
     * token 过期或签名无效（20 小时到期、每月 1 号 3:40 密钥轮换）。
     * **这是唯一应当自动重新登录的情况**，也是最常见的（响应体为空）。
     */
    TOKEN_INVALID_OR_EXPIRED,

    /**
     * 同一账户在别处登录（单会话策略，EmployeeService:178）。
     *
     * ⚠ **绝不能自动重新登录** —— 那会和新登录方互相踢，形成风暴。
     * 收到 SSE 的 `KICKED` 事件时也是同一处理：停止自动重连 + 提示用户 + 等用户明确操作。
     */
    SESSION_TAKEN_OVER,

    /**
     * 账户改密码/改名导致纪元递增（EmployeeService:172）。
     *
     * 可以自动重登**一次**，但本地存的密码很可能已经过期（如果改密码的是别人），
     * 所以要准备好失败后引导用户手动输入。
     * ⚠ 自己改密码成功后也必须重新登录——自己的 token 同样已失效。
     */
    CREDENTIALS_CHANGED,

    /** 账户被删除（EmployeeService:168）。**绝不自动重登**，直接告知用户。 */
    ACCOUNT_GONE,

    /** 登录接口返回的"用户名或密码错误"。提示用户，不自动重试。 */
    BAD_CREDENTIALS,

    /** 文案对不上任何一种。按最保守处理：不自动重登，交给用户。 */
    UNKNOWN
}

object ApiErrorClassifier {

    /** 追踪号：`服务器内部错误（追踪号 ab12cd34，...）`。 */
    private val TRACE_ID = Regex("追踪号\\s*([0-9a-fA-F]{6,32})")

    /**
     * 把 (状态码, 响应体) 分类成 [ApiError]。
     *
     * @param body 响应体原文。**可以是 null 或空串**（401 就是空的），
     *             也可以是非 JSON（例如反代返回的 HTML 错误页）。都不许抛。
     */
    fun classify(code: Int, body: String?): ApiError {
        // 只取信封里的 message。**不要**在解析失败时把整个响应体当 message 用 ——
        // 那会把 `{"success":false}` 或一段反代返回的 HTML 直接显示给用户。
        // 没有 message 就让各状态码的兜底文案顶上。
        val msg = parseEnvelope(body)?.message?.takeIf { it.isNotBlank() }

        return when (code) {
            400 -> ApiError.BadRequest(msg ?: "请求参数有误")
            401 -> ApiError.Unauthorized(classifyUnauthorized(msg), msg)
            403 -> ApiError.Forbidden(msg ?: "权限不足")
            404 -> ApiError.NotFound(msg ?: "资源不存在")
            409 -> ApiError.Conflict(
                msg ?: "冲突",
                // 订单文件 SERVER_BEHIND 的文案是固定的（OrderFileController:116），
                // 且注册冲突的文案完全不同，所以按文案判定是可靠的。
                isOrderFileServerBehind = msg?.contains("落后") == true,
            )
            413 -> ApiError.PayloadTooLarge(msg ?: "请求体超过大小上限（20KB）")
            429 -> ApiError.RateLimited(msg ?: "操作过于频繁，请稍后再试")
            in 500..599 -> ApiError.ServerError(extractTraceId(msg), msg ?: "服务器内部错误")
            else -> ApiError.HttpOther(code, msg)
        }
    }

    /**
     * 按服务端**源码里的原文**分类 401。
     *
     * 匹配不到时返回 [UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED] 而不是 UNKNOWN ——
     * 因为空响应体的 401（token 过期，最常见）本来就没有文案，
     * 落到 UNKNOWN 会让每次正常的 20 小时过期都不敢自动重登。
     */
    private fun classifyUnauthorized(msg: String?): UnauthorizedReason = when {
        msg == null -> UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED
        msg.contains("别处登录") -> UnauthorizedReason.SESSION_TAKEN_OVER
        msg.contains("已被删除") || msg.contains("不存在") -> UnauthorizedReason.ACCOUNT_GONE
        msg.contains("凭证已变更") -> UnauthorizedReason.CREDENTIALS_CHANGED
        msg.contains("用户名或密码错误") -> UnauthorizedReason.BAD_CREDENTIALS
        // 有文案但认不出来：保守处理，不自动重登
        else -> UnauthorizedReason.UNKNOWN
    }

    /**
     * 这个 401 能不能触发自动重新登录。
     *
     * 只有"token 自己失效了"才允许。别处登录/账户被删/文案认不出来一律不允许 ——
     * 单会话策略下自动重登会和对方互踢。
     *
     * ⚠ 即使这里返回 true，调用方仍然必须：① 全局串行、间隔 ≥12 秒；② 一次会话内最多自动重登一次；
     * ③ 重登成功后比对 `previousLoginAt` 与本地存的 `lastLoginAt`，不一致就警告
     * "离线期间有别的设备登录过"；④ 收到过 SSE `KICKED` 就永久闩死，不再自动重登。
     */
    fun mayAutoRelogin(reason: UnauthorizedReason): Boolean =
        reason == UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED ||
            reason == UnauthorizedReason.CREDENTIALS_CHANGED

    private fun extractTraceId(msg: String?): String? =
        msg?.let { TRACE_ID.find(it)?.groupValues?.get(1) }

    /**
     * 解析 `{success, message, data}` 信封。
     *
     * **绝不抛**：body 为 null / 空 / 非 JSON / JSON 但不是信封，都返回 null。
     * 分类器的职责是"尽力给出最有用的分类"，不是"在错误路径上再抛一个错误"。
     *
     * 这里手工解析而不是用 Gson 反序列化成类，是因为：
     *  1. 只需要 `message` 一个字段，反序列化整个信封是浪费；
     *  2. 错误路径上 Gson 的异常种类多（JsonSyntaxException / IllegalStateException /
     *     JsonParseException），逐个兜住比手工找键更容易漏。
     */
    private fun parseEnvelope(body: String?): Envelope? {
        if (body.isNullOrBlank()) return null
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        val message = extractStringField(trimmed, "message")
        val success = extractBooleanField(trimmed, "success")
        if (message == null && success == null) return null
        return Envelope(success, message)
    }

    private data class Envelope(val success: Boolean?, val message: String?)

    /** 从 JSON 文本里取一个顶层字符串字段。只做尽力而为的提取，不是完整解析器。 */
    private fun extractStringField(json: String, field: String): String? {
        val key = "\"$field\""
        val i = json.indexOf(key)
        if (i < 0) return null
        var j = i + key.length
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length || json[j] != ':') return null
        j++
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length) return null
        if (json[j] == 'n') return null            // null
        if (json[j] != '"') return null
        val sb = StringBuilder()
        var k = j + 1
        while (k < json.length) {
            val c = json[k]
            if (c == '\\' && k + 1 < json.length) {
                when (val e = json[k + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'u' -> {
                        if (k + 5 < json.length) {
                            val hex = json.substring(k + 2, k + 6)
                            val cp = hex.toIntOrNull(16)
                            if (cp != null) sb.append(cp.toChar())
                            k += 4
                        }
                    }
                    else -> sb.append(e)
                }
                k += 2
                continue
            }
            if (c == '"') return sb.toString()
            sb.append(c)
            k++
        }
        return null                                  // 引号没闭合，不是合法 JSON
    }

    private fun extractBooleanField(json: String, field: String): Boolean? {
        val key = "\"$field\""
        val i = json.indexOf(key)
        if (i < 0) return null
        var j = i + key.length
        while (j < json.length && json[j].isWhitespace()) j++
        if (j >= json.length || json[j] != ':') return null
        j++
        while (j < json.length && json[j].isWhitespace()) j++
        return when {
            json.startsWith("true", j) -> true
            json.startsWith("false", j) -> false
            else -> null
        }
    }
}
