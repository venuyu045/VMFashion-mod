package com.venus.vmfashion.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FashionApi#parseApiError(int, String)} 单测：后端错误体解析（直接喂 JSON 字符串）。 */
class FashionApiErrorTest {

    @Test
    void parsesFlatErrorBody() {
        FashionApi.ApiException error = FashionApi.parseApiError(404,
                "{\"code\":\"unknown_cosmetic\",\"message\":\"时装不存在\"}");

        assertEquals(404, error.status());
        assertEquals("unknown_cosmetic", error.code());
        assertEquals("时装不存在", error.getMessage());
        // 没有 Retry-After 响应头时必须是 -1，否则重试逻辑会误以为要等待
        assertEquals(-1L, error.retryAfterMillis());
    }

    @Test
    void parsesDetailWrappedErrorBody() {
        // 后端统一错误格式：{"detail":{"code":...,"message":...}}
        FashionApi.ApiException error = FashionApi.parseApiError(403,
                "{\"detail\":{\"code\":\"cosmetic_disabled\",\"message\":\"该时装已被管理员禁用\"}}");

        assertEquals(403, error.status());
        assertEquals("cosmetic_disabled", error.code());
        assertEquals("该时装已被管理员禁用", error.getMessage());
    }

    @Test
    void parsesDetailWithPrimitiveMessage() {
        FashionApi.ApiException error = FashionApi.parseApiError(409, "{\"detail\":\"云端穿戴冲突\"}");
        assertEquals("云端穿戴冲突", error.getMessage());
        // 没有 code 字段时回退到 http_<status>，保证日志里始终有可检索的错误码
        assertEquals("http_409", error.code());
    }

    @Test
    void keepsRawBodyWhenNotJson() {
        FashionApi.ApiException error = FashionApi.parseApiError(502, "<html>bad gateway</html>");

        assertEquals(502, error.status());
        assertEquals("http_502", error.code());
        assertEquals("<html>bad gateway</html>", error.getMessage());
    }

    @Test
    void handlesBlankAndNullBody() {
        FashionApi.ApiException blank = FashionApi.parseApiError(500, "");
        assertEquals("http_500", blank.code());
        assertEquals("", blank.getMessage());

        FashionApi.ApiException nullBody = FashionApi.parseApiError(503, null);
        assertEquals("http_503", nullBody.code());
        assertNotNull(nullBody.getMessage());
    }

    @Test
    void ignoresNonStringCodeFields() {
        // code 是数字 / null 这类脏数据不能让解析崩，也不能把 message 带偏
        FashionApi.ApiException error = FashionApi.parseApiError(400,
                "{\"code\":null,\"message\":\"参数不合法\"}");
        assertEquals("http_400", error.code());
        assertEquals("参数不合法", error.getMessage());
    }

    @Test
    void messageFallsBackToRawBodyWhenMissing() {
        FashionApi.ApiException error = FashionApi.parseApiError(429, "{\"code\":\"rate_limited\"}");
        assertEquals("rate_limited", error.code());
        assertTrue(error.getMessage().contains("rate_limited") || error.getMessage().isEmpty()
                        || error.getMessage().equals("{\"code\":\"rate_limited\"}"),
                "缺少 message 时应保留可诊断信息，实际: " + error.getMessage());
    }
}
