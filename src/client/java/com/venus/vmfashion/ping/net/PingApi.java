package com.venus.vmfashion.ping.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.net.PackDownloader;
import com.venus.vmfashion.net.RetryPolicy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * 中继后端（{@code /api/ping}）的 HTTP 客户端。
 *
 * <p>只负责「发请求 + 解析 JSON + 把错误码翻译成 {@link ApiException}」，不关心业务状态。
 * 所有方法异步返回 {@link CompletableFuture}，调用方（渲染线程 / tick）不会被网络阻塞。
 *
 * <p>重试策略（复用 {@link RetryPolicy}）：
 * <ul>
 *   <li><b>拉取（GET）</b>：幂等，弱网可重试；但长轮询超时属于正常返回，不算失败；</li>
 *   <li><b>发送（POST）</b>：**不重试** —— 重复提交会出现两个一样的标点。</li>
 * </ul>
 *
 * <p>安全：只在 https（或本机回环 http）下带 {@code Authorization: Bearer}；
 * 后端地址是明文 http 时直接失败（{@code insecure_backend}），宁可不工作也不泄露 token。
 *
 * <p>合订版适配：
 * <ul>
 *   <li>认证端点（challenge / verify）已删除 —— 合订版复用时装会话，token 由
 *       {@link PingRelay} 通过 {@code PingAuthBridge#ensureSession()} 拿到后 {@link #setToken} 注入；</li>
 *   <li>User-Agent 改成 {@code VMFashion/<版本>}（{@code X-VMPing-Version} 头保留，后端按它做协议版本判断）；</li>
 *   <li>安全判定用 {@link PackDownloader#isSecureUrl(String)}，重试策略用
 *       {@link com.venus.vmfashion.net.RetryPolicy}，不重复实现。</li>
 * </ul>
 */
public final class PingApi {

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 不跟随重定向：Bearer token 不能被 302 带到别的 host
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;
    private volatile String token;
    /** 弱网重试 + 熔断：整个客户端共用一个实例，熔断状态才能跨请求生效。 */
    private final RetryPolicy retryPolicy = new RetryPolicy();

    public PingApi(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public String token() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public boolean hasToken() {
        return token != null && !token.isBlank();
    }

    /**
     * 释放底层 {@link HttpClient}（配置热更新换后端地址时调用）。
     *
     * <p>用 {@code shutdownNow} 而不是 {@code close}：后者会等所有在飞请求结束，
     * 在渲染/命令线程上调用可能卡住主线程。
     */
    public void close() {
        try {
            http.shutdownNow();
        } catch (Throwable t) {
            VMFashionClient.LOGGER.debug("关闭标点后端 HttpClient 失败（忽略）: {}", t.toString());
        }
    }

    // ==================== 业务端点 ====================

    /**
     * 发送一条标点。
     *
     * <p>不重试：重复提交会在房间里出现两条一样的标点。
     */
    public CompletableFuture<JsonObject> sendPing(String roomId, JsonObject body) {
        return post("/rooms/" + encodePath(roomId) + "/pings", body, true);
    }

    /**
     * 长轮询拉取标点。
     *
     * <p>请求超时要比服务端等待时长多留几秒（服务端最多等 {@code timeout} 秒，
     * 客户端 HTTP 超时 {@link #REQUEST_TIMEOUT} 固定 15s 会提前掐断长轮询，所以这里
     * 单独用「等待时长 + 8s」作为本次请求的超时）。
     */
    public CompletableFuture<JsonObject> pollPings(String roomId, long since, int timeoutSeconds, String dimension) {
        int waitSeconds = Math.max(1, Math.min(25, timeoutSeconds));
        String basePath = "/rooms/" + encodePath(roomId) + "/pings?since=" + Math.max(0L, since)
                + "&timeout=" + waitSeconds;
        final String path = (dimension == null || dimension.isBlank())
                ? basePath
                : basePath + "&dimension=" + java.net.URLEncoder.encode(dimension, StandardCharsets.UTF_8);
        final Duration timeout = Duration.ofSeconds(waitSeconds + 8L);
        return guarded(() -> {
            HttpRequest.Builder builder = request(path, true, timeout);
            if (builder == null) {
                return CompletableFuture.failedFuture(new ApiException(-1, "no_base_url", "未配置中继后端地址"));
            }
            // GET 幂等：弱网可以重试
            return send(builder.GET().build(), true);
        });
    }

    // ==================== 底层 ====================

    /** 保证所有异常都以 failedFuture 形式返回，绝不从调用方线程同步抛出。 */
    private CompletableFuture<JsonObject> guarded(java.util.function.Supplier<CompletableFuture<JsonObject>> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    private CompletableFuture<JsonObject> post(String path, JsonObject body, boolean withAuth) {
        return guarded(() -> {
            HttpRequest.Builder builder = request(path, withAuth, REQUEST_TIMEOUT);
            if (builder == null) {
                return CompletableFuture.failedFuture(new ApiException(-1, "no_base_url", "未配置中继后端地址"));
            }
            // 写操作一律不重试（标点重复提交会产生两条一样的标点）
            return send(builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .build(), false);
        });
    }

    private HttpRequest.Builder request(String path, boolean withAuth, Duration timeout) {
        if (baseUrl.isBlank()) {
            return null;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Accept", "application/json")
                // 后端用它判断协议版本；合订版沿用同一个头名，值仍是本模组版本号
                .header("X-VMPing-Version", VMFashionClient.version())
                .header("User-Agent", "VMFashion/" + VMFashionClient.version());
        if (withAuth) {
            String current = token;
            if (current == null || current.isBlank()) {
                throw new ApiException(401, "no_token", "尚未登录标点后端");
            }
            if (!PackDownloader.isSecureUrl(baseUrl)) {
                throw new ApiException(400, "insecure_backend", "后端地址不是 https，出于安全已停止发送登录凭据");
            }
            builder.header("Authorization", "Bearer " + current);
        }
        return builder;
    }

    private CompletableFuture<JsonObject> send(HttpRequest request, boolean retryable) {
        return sendAttempt(request, retryable, 1);
    }

    /**
     * 发一次请求；失败时问 {@link RetryPolicy} 要不要重试、等多久。
     *
     * <p>重试用 {@code delayedExecutor} 而不是 {@code Thread.sleep}：不占线程，也不会卡主线程。
     */
    private CompletableFuture<JsonObject> sendAttempt(HttpRequest request, boolean retryable, int attempt) {
        long now = System.currentTimeMillis();
        if (!retryPolicy.tryAcquire(now)) {
            return CompletableFuture.failedFuture(
                    new ApiException(-1, "circuit_open", "后端连续失败，熔断中，稍后重试"));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, throwable) -> {
                    if (throwable != null) {
                        return new Attempt(0, null, "network_error", "网络错误: " + rootMessage(throwable), -1L);
                    }
                    int status = response.statusCode();
                    String bodyText = response.body();
                    if (status / 100 == 2) {
                        JsonObject json = parseObject(bodyText);
                        // 2xx 但 body 为空 / 不是 JSON 对象：当作"没有数据"而不是失败。
                        // 两个端点正常都返回 JSON，但网关/反代可能回 204 或吞掉 body ——
                        // 那不该让整个中继降级到本地（旧实现会构造 http_200 错误）。
                        return new Attempt(status, json == null ? new JsonObject() : json, null, null, -1L);
                    }
                    String code = "http_" + status;
                    String message = "HTTP " + status;
                    JsonObject errorBody = parseObject(bodyText);
                    if (errorBody != null && errorBody.has("detail")) {
                        var detail = errorBody.get("detail");
                        if (detail.isJsonObject()) {
                            code = PingCodec.optString(detail.getAsJsonObject(), "code", code);
                            message = PingCodec.optString(detail.getAsJsonObject(), "message", message);
                        } else if (detail.isJsonPrimitive()) {
                            message = detail.getAsString();
                        }
                    }
                    long retryAfter = RetryPolicy.parseRetryAfterMillis(
                            response.headers().firstValue("Retry-After").orElse(null),
                            System.currentTimeMillis());
                    return new Attempt(status, null, code, message, retryAfter);
                })
                .thenCompose(attemptResult -> {
                    if (attemptResult.body != null) {
                        retryPolicy.recordSuccess();
                        return CompletableFuture.completedFuture(attemptResult.body);
                    }
                    int status = attemptResult.status;
                    String code = attemptResult.code;
                    if (retryable && attempt < retryPolicy.maxAttempts()
                            && RetryPolicy.isRetryable(status, code)) {
                        if (RetryPolicy.countsTowardBreaker(status, code)) {
                            retryPolicy.recordFailure(System.currentTimeMillis());
                        }
                        long delay = retryPolicy.nextDelayMillis(attempt, attemptResult.retryAfterMs);
                        return CompletableFuture.supplyAsync(
                                        () -> null,
                                        CompletableFuture.delayedExecutor(delay, java.util.concurrent.TimeUnit.MILLISECONDS))
                                .thenCompose(ignored -> sendAttempt(request, true, attempt + 1));
                    }
                    if (RetryPolicy.countsTowardBreaker(status, code)) {
                        retryPolicy.recordFailure(System.currentTimeMillis());
                    }
                    return CompletableFuture.failedFuture(
                            new ApiException(status, code, attemptResult.message));
                });
    }

    /** 单次请求的结果（成功时 body 非空，失败时 code/message 非空）。 */
    private record Attempt(int status, JsonObject body, String code, String message, long retryAfterMs) {
    }

    private static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            var element = JsonParser.parseString(text);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getSimpleName() : message;
    }

    /** 路径段编码：roomId 只允许 hex，这里仍然防御性编码，避免拼出越界 URL。 */
    private static String encodePath(String segment) {
        if (segment == null || segment.isBlank()) {
            return "";
        }
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 后端错误（HTTP 状态码 + 协议错误码 + 人类可读信息）。 */
    public static final class ApiException extends RuntimeException {
        private final int status;
        private final String code;

        public ApiException(int status, String code, String message) {
            super(message == null ? code : message);
            this.status = status;
            this.code = code == null ? "unknown" : code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        /** 是否属于"需要重新认证"的错误。 */
        public boolean needsReauth() {
            return status == 401 || "unauthorized".equals(code) || "bad_token".equals(code)
                    || "no_token".equals(code);
        }

        /** 是否属于"后端根本连不上/不可用"。 */
        public boolean backendUnavailable() {
            return status <= 0 || status >= 500 || "network_error".equals(code) || "circuit_open".equals(code);
        }
    }

    /**
     * 把 CompletableFuture 的包装异常（{@link CompletionException} / {@link ExecutionException}）
     * 拆成 ApiException（日志/文案用）。
     */
    public static ApiException unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ApiException api) {
                return api;
            }
            if ((current instanceof CompletionException || current instanceof ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            break;
        }
        return new ApiException(-1, "unknown", throwable == null ? "未知错误" : throwable.toString());
    }
}
