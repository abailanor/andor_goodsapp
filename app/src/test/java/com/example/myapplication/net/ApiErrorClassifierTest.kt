package com.example.myapplication.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误分类的测试。
 *
 * 每一条都对应一个"分错就会造成真实损害"的分支——尤其是 401 的细分
 * （自动重登 vs 停止重登）与 429（绝不自动重试）。
 */
class ApiErrorClassifierTest {

    // ==================== 401：响应体可能是空的 ====================

    @Test
    fun `401_空响应体_判为 token 过期_允许自动重登`() {
        // SecurityConfig 没有自定义 authenticationEntryPoint，httpBasic/formLogin 都禁用，
        // 所以 Spring Security 走默认的 HttpStatusServerEntryPoint → **401 带空 body**。
        // 这是最常见的路径（20 小时到期、每月 1 号 3:40 密钥轮换），必须能自动重登。
        for (body in listOf(null, "", "   ")) {
            val e = ApiErrorClassifier.classify(401, body) as ApiError.Unauthorized
            assertEquals(
                "body=${body ?: "null"}",
                UnauthorizedReason.TOKEN_INVALID_OR_EXPIRED, e.reason
            )
            assertTrue(ApiErrorClassifier.mayAutoRelogin(e.reason))
        }
    }

    @Test
    fun `401_别处登录_绝不自动重登`() {
        // 单会话策略：自动重登会和新登录方互相踢，形成风暴
        val e = ApiErrorClassifier.classify(
            401, """{"success":false,"message":"该账户已在别处登录，请重新登录","data":null}"""
        ) as ApiError.Unauthorized
        assertEquals(UnauthorizedReason.SESSION_TAKEN_OVER, e.reason)
        assertFalse(ApiErrorClassifier.mayAutoRelogin(e.reason))
    }

    @Test
    fun `401_凭证已变更_允许自动重登一次`() {
        val e = ApiErrorClassifier.classify(
            401, """{"success":false,"message":"账户凭证已变更，请重新登录"}"""
        ) as ApiError.Unauthorized
        assertEquals(UnauthorizedReason.CREDENTIALS_CHANGED, e.reason)
        assertTrue(ApiErrorClassifier.mayAutoRelogin(e.reason))
    }

    @Test
    fun `401_账户被删除_绝不自动重登`() {
        val e = ApiErrorClassifier.classify(
            401, """{"success":false,"message":"账户不存在或已被删除，请重新登录"}"""
        ) as ApiError.Unauthorized
        assertEquals(UnauthorizedReason.ACCOUNT_GONE, e.reason)
        assertFalse(ApiErrorClassifier.mayAutoRelogin(e.reason))
    }

    @Test
    fun `401_用户名或密码错误_不自动重试`() {
        val e = ApiErrorClassifier.classify(
            401, """{"success":false,"message":"用户名或密码错误"}"""
        ) as ApiError.Unauthorized
        assertEquals(UnauthorizedReason.BAD_CREDENTIALS, e.reason)
        assertFalse("登录失败绝不能做成自动重试（会撞 5 次/分钟的桶）",
            ApiErrorClassifier.mayAutoRelogin(e.reason))
    }

    @Test
    fun `401_认不出的文案_保守处理不自动重登`() {
        val e = ApiErrorClassifier.classify(
            401, """{"success":false,"message":"某种没见过的错误"}"""
        ) as ApiError.Unauthorized
        assertEquals(UnauthorizedReason.UNKNOWN, e.reason)
        assertFalse(ApiErrorClassifier.mayAutoRelogin(e.reason))
    }

    // ==================== 409：两种语义 ====================

    @Test
    fun `409_订单文件落后于客户端_标记为 ServerBehind`() {
        // OrderFileController:116 的固定文案
        val e = ApiErrorClassifier.classify(
            409, """{"success":false,"message":"服务器版本落后于客户端，请重推本地文件尾部增量"}"""
        ) as ApiError.Conflict
        assertTrue(e.isOrderFileServerBehind)
        // 绝不能拿服务器内容覆盖本地 —— 那是客户端唯一那份更完整的数据
    }

    @Test
    fun `409_用户名冲突_不是 ServerBehind`() {
        val e = ApiErrorClassifier.classify(
            409, """{"success":false,"message":"用户名已存在"}"""
        ) as ApiError.Conflict
        assertFalse(e.isOrderFileServerBehind)
    }

    @Test
    fun `409_空响应体_不是 ServerBehind_不能猜`() {
        val e = ApiErrorClassifier.classify(409, null) as ApiError.Conflict
        assertFalse(e.isOrderFileServerBehind)
    }

    // ==================== 500：追踪号 ====================

    @Test
    fun `500_提取 8 位追踪号`() {
        // GlobalExceptionHandler:128 → UUID.substring(0,8)，文案 :131
        val e = ApiErrorClassifier.classify(
            500, """{"success":false,"message":"服务器内部错误（追踪号 ab12cd34，请提供给运维查日志）"}"""
        ) as ApiError.ServerError
        assertEquals("ab12cd34", e.traceId)
    }

    @Test
    fun `500_没有追踪号时不崩`() {
        val e = ApiErrorClassifier.classify(500, """{"success":false,"message":"炸了"}""") as ApiError.ServerError
        assertNull(e.traceId)
    }

    @Test
    fun `500_空响应体也能分类`() {
        val e = ApiErrorClassifier.classify(500, null) as ApiError.ServerError
        assertNull(e.traceId)
    }

    @Test
    fun `502_503_也归入 ServerError`() {
        // 反代/代理返回的网关错误。本机就装了全局代理（ALL_PROXY=127.0.0.1:9850），
        // 服务没起时它返回的就是 502 —— 必须能分类而不是崩。
        assertTrue(ApiErrorClassifier.classify(502, "<html>Bad Gateway</html>") is ApiError.ServerError)
        assertTrue(ApiErrorClassifier.classify(503, null) is ApiError.ServerError)
    }

    // ==================== 其余状态码 ====================

    @Test
    fun `429_分类为限速_供调用方拒绝自动重试`() {
        val e = ApiErrorClassifier.classify(
            429, """{"success":false,"message":"登录操作过于频繁，请稍后再试"}"""
        )
        assertTrue(e is ApiError.RateLimited)
        assertEquals("登录操作过于频繁，请稍后再试", (e as ApiError.RateLimited).msg)
    }

    @Test
    fun `413_分类为请求体过大`() {
        val e = ApiErrorClassifier.classify(
            413, """{"success":false,"message":"请求体超过大小上限（20KB）"}"""
        )
        assertTrue(e is ApiError.PayloadTooLarge)
    }

    @Test
    fun `400_403_404_各自分类`() {
        assertTrue(ApiErrorClassifier.classify(400, """{"message":"content.items必须是数组"}""") is ApiError.BadRequest)
        assertTrue(ApiErrorClassifier.classify(403, """{"message":"权限不足"}""") is ApiError.Forbidden)
        assertTrue(ApiErrorClassifier.classify(404, """{"message":"商品不存在: X"}""") is ApiError.NotFound)
        // 404 也用于"接口路径不存在"（README_FOR_ANDROID §9 第 5 条，已修）
        assertTrue(ApiErrorClassifier.classify(404, """{"message":"接口不存在"}""") is ApiError.NotFound)
    }

    @Test
    fun `400_空响应体时用兜底文案`() {
        val e = ApiErrorClassifier.classify(400, null) as ApiError.BadRequest
        assertTrue(e.msg.isNotBlank())
    }

    @Test
    fun `未收录的状态码归入 HttpOther`() {
        val e = ApiErrorClassifier.classify(405, """{"message":"Method Not Allowed"}""") as ApiError.HttpOther
        assertEquals(405, e.code)
        val e2 = ApiErrorClassifier.classify(418, null) as ApiError.HttpOther
        assertEquals(418, e2.code)
    }

    // ==================== 健壮性：绝不因为响应体奇怪而抛异常 ====================

    @Test
    fun `非 JSON 响应体不会让分类器崩`() {
        val bodies = listOf(
            "<html><body>502 Bad Gateway</body></html>",
            "not json at all",
            "{",
            "{\"message\":",
            "{\"message\":\"没闭合的字符串",
            "[]",
            "null",
            "{\"success\":true}",
            "{\"message\":null}",
            "{\"message\":123}",
        )
        for (code in listOf(400, 401, 403, 404, 409, 413, 429, 500, 502)) {
            for (b in bodies) {
                // 不许抛。分类器在错误路径上再抛一个错误是最糟的行为。
                val e = ApiErrorClassifier.classify(code, b)
                assertNotNull("code=$code body=$b 应当有分类", e)
                // 也不能把原始响应体漏进用户可见的文案。
                // `bodies` 是非空的 `List<String>`，这里不需要判空（Kotlin 会告警恒真）；
                // 长度 ≤4 的跳过是因为 `"{"` 这种前缀太短，任何文案都可能碰巧含它。
                if (b.length > 4) {
                    assertFalse(
                        "code=$code 的文案漏了原始响应体: ${e.message}",
                        (e.message ?: "").contains(b.take(20))
                    )
                }
            }
        }
    }

    @Test
    fun `message 里的转义字符被正确还原`() {
        val e = ApiErrorClassifier.classify(
            400, """{"message":"发现 0xa 于位置 1\n请检查"}"""
        ) as ApiError.BadRequest
        assertTrue("换行转义应当被还原: ${e.msg}", e.msg.contains("\n"))
    }

    @Test
    fun `message 里含中文标点与括号也能取到`() {
        val e = ApiErrorClassifier.classify(
            400,
            """{"success":false,"message":"订单JSON必须是单行且不含控制字符（发现 0xa 于位置 1）…","data":null}"""
        ) as ApiError.BadRequest
        assertTrue(e.msg.contains("控制字符"))
    }

    @Test
    fun `只有 success 没有 message 时用兜底文案_不把原始 JSON 显示给用户`() {
        val e = ApiErrorClassifier.classify(403, """{"success":false}""") as ApiError.Forbidden
        assertEquals("权限不足", e.msg)
        assertFalse(e.msg.contains("{"))
    }

    @Test
    fun `反代返回的 HTML 不会被当成 message 显示`() {
        val e = ApiErrorClassifier.classify(502, "<html><body>Bad Gateway</body></html>") as ApiError.ServerError
        assertFalse("HTML 不能漏进用户可见的文案: ${e.msg}", e.msg.contains("<html>"))
    }
}
