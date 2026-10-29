package com.venus.vmfashion.net;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 不跟随重定向：Authorization: Bearer 不能被 302 带到另一个 host
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final String baseUrl;
    private volatile String token;

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
        return post("/loadouts/resolve", body, true);
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
            return send(builder.GET().build());
        });
    }

    public CompletableFuture<JsonObject> post(String path, JsonObject body, boolean withAuth) {
        return guarded(() -> {
            HttpRequest.Builder builder = request(path, withAuth);
            if (builder == null) {
                return CompletableFuture.failedFuture(new ApiException(-1, "no_base_url", "未配置后端地址"));
            }
            return send(builder.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .build());
        });
    }

    public CompletableFuture<JsonObject> put(String path, JsonObject body, boolean withAuth) {
        return guarded(() -> {
            HttpRequest.Builder builder = request(path, withAuth);
            if (builder == null) {
                return CompletableFuture.failedFuture(new ApiException(-1, "no_base_url", "未配置后端地址"));
            }
            return send(builder.PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .header("Content-Type", "application/json")
                    .build());
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
            builder.header("Authorization", "Bearer " + current);
        }
        return builder;
    }

    private CompletableFuture<JsonObject> send(HttpRequest request) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    String bodyText = response.body();
                    int status = response.statusCode();
                    if (status / 100 != 2) {
                        String code = "http_" + status;
                        String message = bodyText;
                        try {
                            JsonObject error = JsonParser.parseString(bodyText).getAsJsonObject();
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
                        throw new ApiException(status, code, message);
                    }
                    if (bodyText == null || bodyText.isBlank()) {
                        return new JsonObject();
                    }
                    try {
                        return JsonParser.parseString(bodyText).getAsJsonObject();
                    } catch (Exception e) {
                        throw new ApiException(status, "bad_json", "后端返回了无法解析的内容");
                    }
                })
                .exceptionallyCompose(throwable -> {
                    Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                            ? throwable.getCause()
                            : throwable;
                    if (cause instanceof ApiException apiException) {
                        return CompletableFuture.failedFuture(apiException);
                    }
                    VMFashionClient.LOGGER.warn("时装后端请求失败: {}", cause.toString());
                    return CompletableFuture.failedFuture(
                            new ApiException(-1, "network_error", cause.getMessage() == null ? "网络错误" : cause.getMessage()));
                });
    }

    /** 后端返回的非 2xx 响应。 */
    public static final class ApiException extends RuntimeException {

        private final int status;
        private final String code;

        public ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        @Override
        public String toString() {
            return "ApiException[" + status + " " + code + ": " + getMessage() + "]";
        }
    }
}
