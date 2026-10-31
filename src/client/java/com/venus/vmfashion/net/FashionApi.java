package com.venus.vmfashion.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * VMTools 时装后端（{@code /api/fashion}）的 HTTP 客户端。
 *
 * <p>只负责"发请求 + 解析 JSON"，不关心业务状态；鉴权 token 由 {@link FashionAuth} 设置。
 * 所有方法都是异步的，调用方（渲染/命令）不会阻塞游戏主线程。
 *
 * <p>接口契约见仓库外文档 {@code docs/fashion-api.md}。
 */
public final class FashionApi {

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    /** 批量查询穿戴的端点：唯一允许重试的 POST（查询语义、幂等）。 */
    private static final String RESOLVE_PATH = "/loadouts/resolve";
    /** "明文后端已停止携带 token" 的 warn 只打一次（每次 config reload 会重建实例，最多各一次）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean INSECURE_WARNED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 不跟随重定向：Authorization: Bearer 不能被 302 带到另一个 host
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;
    private volatile String token;
    /** 弱网重试 + 熔断；整个客户端共用一个实例，熔断状态才能跨请求生效。 */
    private final RetryPolicy retryPolicy = new RetryPolicy();

    public FashionApi(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        this.baseUrl = trimmed;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 释放底层 {@link HttpClient}（配置热更新换后端地址时调用）。
     *
     * <p>为什么要显式关：{@code HttpClient} 一旦用过就会持有选择器线程与线程池，
     * 不关的话每次 {@code /vmfashion config reload} 都会多泄漏一套（JDK 21+ 还会在
     * 回收时打 "HttpClient was not closed" 的警告）。用 {@code shutdownNow} 而不是
     * {@code close}：后者会等所有在飞请求结束，在渲染/命令线程上调用可能卡住主线程。
     *
     * <p>失败只记 debug：关不掉最多是泄漏一个线程，绝不能让重载流程失败。
     */
    public void close() {
        try {
            http.shutdownNow();
        } catch (Throwable t) {
            VMFashionClient.LOGGER.debug("关闭时装后端 HttpClient 失败（忽略）: {}", t.toString());
        }
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

    // ==================== 业务端点 ====================

    /** 认证第一步：申请一次性 serverId。 */
    public CompletableFuture<JsonObject> challenge(String mcUuid, String mcName, String authServer) {
        JsonObject body = new JsonObject();
        body.addProperty("mcUuid", mcUuid);
        body.addProperty("mcName", mcName);
        body.addProperty("authServer", authServer == null ? "" : authServer);
        return post("/auth/challenge", body, false);
    }

    /** 认证第三步：带着 serverId 让后端反查会话服务器。 */
    public CompletableFuture<JsonObject> verify(String mcUuid, String mcName, String authServer, String serverId) {
        JsonObject body = new JsonObject();
        body.addProperty("mcUuid", mcUuid);
        body.addProperty("mcName", mcName);
        body.addProperty("authServer", authServer == null ? "" : authServer);
        body.addProperty("serverId", serverId);
        return post("/auth/verify", body, false);
    }

    /** 时装目录。 */
    public CompletableFuture<JsonObject> catalog() {
        return get("/catalog", true);
    }

    /** 我的穿戴配置。 */
    public CompletableFuture<JsonObject> myLoadout() {
        return get("/loadout/me", true);
    }

    /** 全量覆盖我的穿戴配置（没出现的槽位 = 卸下）。 */
    public CompletableFuture<JsonObject> putLoadout(JsonObject items) {
        JsonObject body = new JsonObject();
        body.add("items", items.getAsJsonArray("items"));
        return put("/loadout", body, true);
    }

    /** 热路径：批量查询其他玩家的穿戴（≤200 个 UUID）。 */
    public CompletableFuture<JsonObject> resolve(java.util.List<String> mcUuids) {
        JsonObject body = new JsonObject();
        body.add("mcUuids", GSON.toJsonTree(mcUuids));
        return post(RESOLVE_PATH, body, true);
    }

    /**
     * 绑定第一步：申请 6 位绑定码（mod 显示给玩家，玩家在网站「时装绑定」页输入）。
     *
     * <p>返回 {@code {userCode, deviceCode, expiresIn, interval}}；之后用
     * {@link #deviceStatus(String)} 轮询结果。
     */
    public CompletableFuture<JsonObject> startDeviceFlow() {
        return post("/device/start", new JsonObject(), true);
    }

    /** 绑定轮询：{@code status} = {@code pending} / {@code approved} / {@code expired}。 */
    public CompletableFuture<JsonObject> deviceStatus(String deviceCode) {
        String encoded = java.net.URLEncoder.encode(
                deviceCode == null ? "" : deviceCode, StandardCharsets.UTF_8);
        return get("/device/status?deviceCode=" + encoded, true);
    }

    /** 下载资产（公开接口，不需要 token）。 */
    public CompletableFuture<byte[]> downloadAsset(String assetKey) {
        String encoded = encodeAssetPath(assetKey);
        if (encoded == null) {
            return CompletableFuture.failedFuture(new ApiException(-1, "bad_asset_key", "资产路径非法"));
        }
        URI uri;
        try {
            uri = URI.create(baseUrl + "/assets/" + encoded);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new ApiException(-1, "bad_asset_key", "资产路径非法: " + assetKey));
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "VMFashion/" + VMFashionClient.MOD_NAME)
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() / 100 != 2) {
                        throw new ApiException(response.statusCode(), "asset_http_" + response.statusCode(),
                                "下载资产失败: " + assetKey);
                    }
                    return response.body();
                });
    }

    /**
     * 把 assetKey 按路径段编码（空格/中文等），并拒绝 {@code ..} / 空段，
     * 避免客户端拼出 {@code /assets/../admin} 这种越界 URL。
     */
    private static String encodeAssetPath(String assetKey) {
        if (assetKey == null || assetKey.isBlank()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String part : assetKey.replace('\\', '/').split("/")) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                return null;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(java.net.URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.length() == 0 ? null : out.toString();
    }

    // ==================== 底层 ====================

    /**
     * 包一层，保证所有异常都以 failedFuture 的形式返回。
     *
     * <p>没有这层的话，{@code request()} 里的同步异常（例如未登录时缺 token）会直接从
     * {@code post/get/put} 抛到调用方线程 —— 而调用方是渲染线程。
     */
    private CompletableFuture<JsonObject> guarded(java.util.function.Supplier<CompletableFuture<JsonObject>> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }

    public CompletableFuture<JsonObject> get(String path, boolean withAuth) {
        return guarded(() -> {
            HttpRequest.Builder builder = request(path, withAuth);
            if (builder == null) {
                return CompletableFuture.failedFuture(new ApiException(-1, "no_base_url", "未配置后端地址"));
            }
            // GET 天然幂等，弱网下可以放心重试
            return send(builder.GET().build(), RetryMode.SAFE);
        });
    }

    public CompletableFuture<JsonObject> post(String path, JsonObject body, boolean withAuth) {
        return guarded(() -> {
            HttpRequest.Builder builder = request(path, withAuth);
            if (builder == null) {
                return CompletableFuture.failedFuture(new ApiException(-1, "no_base_url", "未配置后端地址"));
            }
            // 只有 /loadouts/resolve 是"查询语义"的 POST，可以重试；
            // 认证（serverId 一次性）和绑定（deviceCode）重试会造成脏状态，绝不重试。
            RetryMode mode = RESOLVE_PATH.equals(path) ? RetryMode.SAFE : RetryMode.NONE;
            return send(builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .build(), mode);
        });
    }

    public CompletableFuture<JsonObject> put(String path, JsonObject body, boolean withAuth) {
        return guarded(() -> {
            HttpRequest.Builder builder = request(path, withAuth);
            if (builder == null) {
                return CompletableFuture.failedFuture(new ApiException(-1, "no_base_url", "未配置后端地址"));
            }
            // 写操作（全量覆盖穿戴）重试可能把旧状态又盖回去，只发一次
            return send(builder.PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .build(), RetryMode.NONE);
        });
    }

    private HttpRequest.Builder request(String path, boolean withAuth) {
        if (baseUrl.isBlank()) {
            return null;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "VMFashion/" + VMFashionClient.MOD_NAME);
        if (withAuth) {
            String current = token;
            if (current == null || current.isBlank()) {
                throw new ApiException(401, "no_token", "尚未登录时装后端");
            }
            if (!PackDownloader.isSecureUrl(baseUrl)) {
                // 明文 http（非本机回环）绝不带 Bearer：宁可直接失败，也不把登录凭据发到网上。
                // 用 400 而不是 -1：400 不计入熔断、也不触发重试/重新认证（否则会每 5 秒认证一次）。
                if (INSECURE_WARNED.compareAndSet(false, true)) {
                    VMFashionClient.LOGGER.warn("时装后端地址不是 https（{}），出于安全已停止发送登录凭据；"
                            + "请把 apiBaseUrl 改成 https 地址（本机回环除外）", baseUrl);
                }
                throw new ApiException(400, "insecure_backend", "后端地址不是 https，出于安全已停止发送登录凭据");
            }
            builder.header("Authorization", "Bearer " + current);
        }
        return builder;
    }

    /** 请求是否允许重试。写操作一律 {@link #NONE}，避免重复提交。 */
    private enum RetryMode {
        SAFE,
        NONE
    }

    private CompletableFuture<JsonObject> send(HttpRequest request, RetryMode mode) {
        return sendAttempt(request, mode, 1);
    }

    /**
     * 发一次请求，失败时按 {@link RetryPolicy} 决定"是否重试 / 等多久"。
     *
     * <p>重试在同一个 future 链里完成，调用方无感知；重试期间不会阻塞任何线程
     * （用 {@code delayedExecutor} 定时，不是 {@code Thread.sleep}）。
     */
    private CompletableFuture<JsonObject> sendAttempt(HttpRequest request, RetryMode mode, int attempt) {
        if (!retryPolicy.tryAcquire(System.currentTimeMillis())) {
            // 熔断中：直接失败，避免每次渲染刷新都去戳一个已经挂掉的后端
            return CompletableFuture.failedFuture(new ApiException(-1, "circuit_open",
                    "时装后端连续失败，已暂停请求，稍后自动恢复"));
        }
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    JsonObject json = parseResponse(response);
                    retryPolicy.recordSuccess();
                    return json;
                })
                .exceptionallyCompose(throwable -> handleFailure(request, mode, attempt, throwable));
    }

    /** 失败收口：归一化成 {@link ApiException}，再决定重试还是把错误抛给调用方。 */
    private CompletableFuture<JsonObject> handleFailure(HttpRequest request, RetryMode mode,
                                                        int attempt, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        ApiException failure = cause instanceof ApiException api ? api
                : new ApiException(-1, "network_error",
                        cause.getMessage() == null ? "网络错误" : cause.getMessage());

        if (!RetryPolicy.countsTowardBreaker(failure.status(), failure.code())) {
            // 4xx 是确定性错误（参数 / 权限 / 不存在），不计入熔断，否则一个坏请求就拉黑整个后端
            return CompletableFuture.failedFuture(failure);
        }
        retryPolicy.recordFailure(System.currentTimeMillis());

        boolean retryable = mode == RetryMode.SAFE
                && RetryPolicy.isRetryable(failure.status(), failure.code())
                && attempt < retryPolicy.maxAttempts();
        if (!retryable) {
            VMFashionClient.LOGGER.warn("时装后端请求失败: {}", failure.toString());
            return CompletableFuture.failedFuture(failure);
        }

        long delay = retryPolicy.nextDelayMillis(attempt, failure.retryAfterMillis());
        VMFashionClient.LOGGER.debug("时装后端请求失败（{}），{}ms 后重试第 {} 次",
                failure, delay, attempt + 1);
        return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> sendAttempt(request, mode, attempt + 1));
    }

    /** 剥掉 CompletableFuture 包的几层壳，拿到真正的业务异常。 */
    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while (cause instanceof CompletionException || cause instanceof ExecutionException) {
            if (cause.getCause() == null || cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return cause;
    }

    /** 2xx → JSON；非 2xx → {@link ApiException}（带 {@code Retry-After} 换算的等待时间）。 */
    private static JsonObject parseResponse(HttpResponse<String> response) {
        int status = response.statusCode();
        String bodyText = response.body();
        if (status / 100 != 2) {
            throw parseApiError(status, bodyText, response.headers());
        }
        if (bodyText == null || bodyText.isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(bodyText).getAsJsonObject();
        } catch (Exception e) {
            throw new ApiException(status, "bad_json", "后端返回了无法解析的内容");
        }
    }

    /**
     * 解析后端错误体，抽出 {@code code} / {@code message}。
     *
     * <p>抽成公开静态方法是为了单测：直接喂 JSON 字符串断言解析结果，不需要起 HTTP 服务。
     * 支持后端两种格式：
     * <pre>
     * {"code":"unknown_cosmetic","message":"..."}
     * {"detail":{"code":"unknown_cosmetic","message":"..."}}   // 后端统一错误格式
     * </pre>
     *
     * @param status   HTTP 状态码
     * @param bodyText 响应体原文，可能是非 JSON
     */
    public static ApiException parseApiError(int status, String bodyText) {
        return parseApiError(status, bodyText, null);
    }

    private static ApiException parseApiError(int status, String bodyText, HttpHeaders headers) {
        String code = "http_" + status;
        String message = bodyText == null ? "" : bodyText;
        long retryAfterMillis = headers == null ? -1L
                : RetryPolicy.parseRetryAfterMillis(
                        headers.firstValue("Retry-After").orElse(null), System.currentTimeMillis());
        try {
            JsonObject error = JsonParser.parseString(bodyText == null ? "" : bodyText).getAsJsonObject();
            if (error.has("code") && !error.get("code").isJsonNull()) {
                code = error.get("code").getAsString();
            }
            if (error.has("message") && !error.get("message").isJsonNull()) {
                message = error.get("message").getAsString();
            } else if (error.has("detail") && !error.get("detail").isJsonNull()) {
                // 后端统一格式：{"detail":{"code":"...","message":"..."}}
                JsonElement detail = error.get("detail");
                if (detail.isJsonObject()) {
                    JsonObject detailObject = detail.getAsJsonObject();
                    if (detailObject.has("code") && !detailObject.get("code").isJsonNull()) {
                        code = detailObject.get("code").getAsString();
                    }
                    if (detailObject.has("message") && !detailObject.get("message").isJsonNull()) {
                        message = detailObject.get("message").getAsString();
                    }
                } else if (detail.isJsonPrimitive()) {
                    message = detail.getAsString();
                }
            }
        } catch (Exception ignored) {
            // 非 JSON 错误体，保留原文
        }
        return new ApiException(status, code, message, retryAfterMillis);
    }

    /** 后端返回的非 2xx 响应。 */
    public static final class ApiException extends RuntimeException {

        private final int status;
        private final String code;
        /** 服务端 {@code Retry-After} 换算的等待毫秒数；{@code -1} 表示没有该响应头。 */
        private final long retryAfterMillis;

        public ApiException(int status, String code, String message) {
            this(status, code, message, -1L);
        }

        public ApiException(int status, String code, String message, long retryAfterMillis) {
            super(message);
            this.status = status;
            this.code = code;
            this.retryAfterMillis = retryAfterMillis;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public long retryAfterMillis() {
            return retryAfterMillis;
        }

        @Override
        public String toString() {
            return "ApiException[" + status + " " + code + ": " + getMessage() + "]";
        }
    }
}
