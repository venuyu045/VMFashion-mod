package com.venus.vmfashion.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * 时装后端的身份认证。
 *
 * <p>目标 MC 服务器同时支持正版与皮肤站登录（authlib-injector / Yggdrasil），两条路径协议一致：
 * <ol>
 *   <li>{@code POST /auth/challenge} 拿一次性 serverId</li>
 *   <li>用当前 MC 会话向<b>会话服务器</b> join（正版 = Mojang，皮肤站 = 皮肤站的 Yggdrasil）</li>
 *   <li>{@code POST /auth/verify}，后端反查 hasJoined 后签发 token</li>
 * </ol>
 *
 * <p>皮肤站地址的探测顺序：JVM 参数里的 authlib-injector javaagent → 配置项。
 * 正版玩家两者都没有，留空即可。
 *
 * <p><b>i18n 约定</b>：这里抛出的 {@link FashionApi.ApiException} 的 message 是**开发者兜底文案**
 * （中文，只会进日志）。玩家看到的是 {@code sync/SyncService#describeApiError} 按错误码
 * （{@code no_session} / {@code no_profile} / {@code no_token} / {@code bad_challenge} /
 * {@code bad_verify} / {@code bad_session_url} / {@code join_failed}）翻译出来的文案
 * （语言文件 {@code vmfashion.error.*}）。<b>新增错误码时，必须同步在 SyncService 里补一条映射</b>，
 * 否则玩家会看到这条中文兜底。
 */
public final class FashionAuth {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(15);

    private static final String MOJANG_SESSION_SERVER = "https://sessionserver.mojang.com";

    private static final Path SESSION_FILE = FabricLoader.getInstance()
            .getConfigDir().resolve("vmfashion").resolve("session.json");

    private final FashionApi api;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile Session session;
    private volatile CompletableFuture<Session> inFlight;

    public FashionAuth(FashionApi api) {
        this.api = api;
    }

    /** 一次成功的认证结果。 */
    public record Session(String token, String mcUuid, String mcName, boolean verified,
                          String authType, long expiresAt, String apiBaseUrl) {

        public boolean expired() {
            return System.currentTimeMillis() > expiresAt - 60_000L;
        }
    }

    public FashionApi api() {
        return api;
    }

    /**
     * 释放本类自己的 {@link HttpClient}（会话服务器 join 用）。
     *
     * <p>和 {@link FashionApi#close()} 同理：配置热更新会重建同步层，旧实例不关就会
     * 每次重载泄漏一套线程。{@code shutdownNow} 不等待在飞请求，避免卡主线程。
     */
    public void close() {
        try {
            http.shutdownNow();
        } catch (Throwable t) {
            VMFashionClient.LOGGER.debug("关闭时装认证 HttpClient 失败（忽略）: {}", t.toString());
        }
    }

    public Session session() {
        return session;
    }

    // ==================== 认证主流程 ====================

    /**
     * 完成一次认证；并发调用会复用同一个 future。
     *
     * @param authServerOverride 配置里手填的皮肤站地址，空则自动探测
     */
    public CompletableFuture<Session> authenticate(String authServerOverride) {
        CompletableFuture<Session> existing = inFlight;
        if (existing != null && !existing.isDone()) {
            return existing;
        }
        CompletableFuture<Session> future = doAuthenticate(authServerOverride);
        inFlight = future;
        return future.whenComplete((ignored, throwable) -> inFlight = null);
    }

    private CompletableFuture<Session> doAuthenticate(String authServerOverride) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return CompletableFuture.failedFuture(
                    new FashionApi.ApiException(-1, "no_session", "Minecraft 客户端尚未初始化"));
        }
        User user = minecraft.getUser();
        if (user == null) {
            return CompletableFuture.failedFuture(
                    new FashionApi.ApiException(-1, "no_session", "尚未登录 Minecraft 账号"));
        }

        UUID uuid = user.getProfileId();
        String name = user.getName();
        String accessToken = user.getAccessToken();
        String authServer = detectAuthServer(authServerOverride);

        if (uuid == null || name == null || name.isBlank()) {
            return CompletableFuture.failedFuture(
                    new FashionApi.ApiException(-1, "no_profile", "无法读取当前角色信息"));
        }
        if (accessToken == null || accessToken.isBlank() || accessToken.length() < 8) {
            return CompletableFuture.failedFuture(new FashionApi.ApiException(-1, "no_token",
                    "当前启动器没有提供有效的登录令牌（离线登录无法同步时装）"));
        }

        VMFashionClient.LOGGER.info("开始时装后端认证：{} ({}) authServer={}", name, uuid,
                authServer.isBlank() ? "mojang" : authServer);

        return api.challenge(uuid.toString(), name, authServer)
                .thenCompose(challenge -> {
                    String serverId = optString(challenge, "serverId", "");
                    if (serverId.isBlank()) {
                        throw new CompletionException(new FashionApi.ApiException(-1, "bad_challenge",
                                "后端没有返回 serverId"));
                    }
                    String challengeAuthType = optString(challenge, "authType", "mojang");
                    return joinSession(authServer, accessToken, uuid, serverId)
                            .thenCompose(ignored -> api.verify(uuid.toString(), name, authServer, serverId))
                            .thenApply(verify -> buildSession(verify, uuid.toString(), name, challengeAuthType));
                });
    }

    private Session buildSession(JsonObject verify, String uuid, String name, String fallbackAuthType) {
        String token = optString(verify, "token", "");
        if (token.isBlank()) {
            throw new CompletionException(new FashionApi.ApiException(-1, "bad_verify",
                    "后端没有返回 token"));
        }
        String authType = optString(verify, "authType", fallbackAuthType);
        boolean verified = true;
        if (verify.has("identity") && verify.get("identity").isJsonObject()) {
            JsonObject identity = verify.getAsJsonObject("identity");
            try {
                verified = !identity.has("verified") || identity.get("verified").isJsonNull()
                        || identity.get("verified").getAsBoolean();
            } catch (Exception ignored) {
                verified = true;
            }
        }
        long expiresIn = 86400L;
        if (verify.has("expiresIn") && !verify.get("expiresIn").isJsonNull()) {
            try {
                expiresIn = verify.get("expiresIn").getAsLong();
            } catch (Exception ignored) {
                expiresIn = 86400L;
            }
        }
        if (expiresIn <= 0) {
            expiresIn = 86400L;
        }
        Session result = new Session(token, uuid, name, verified, authType,
                System.currentTimeMillis() + expiresIn * 1000L, api.baseUrl());

        api.setToken(token);
        this.session = result;
        saveSession(result);
        VMFashionClient.LOGGER.info("时装后端认证成功：{}（{}）", name, authType);
        return result;
    }

    /**
     * 向会话服务器 join，证明"这个 UUID 现在确实登录着"。
     *
     * <p>正版走 Mojang，皮肤站走 {@code {base}/sessionserver/session/minecraft/join}。
     */
    private CompletableFuture<Void> joinSession(String authServer, String accessToken, UUID uuid, String serverId) {
        // 防御性校验：accessToken 是玩家凭据，绝不能发给非 https（本机回环除外）的地址
        if (authServer != null && !authServer.isBlank() && validateAuthServer(authServer).isBlank()) {
            return CompletableFuture.failedFuture(new FashionApi.ApiException(-1, "bad_session_url",
                    "皮肤站地址必须是 https（本机回环除外）且不含用户信息"));
        }
        String url = authServer == null || authServer.isBlank()
                ? MOJANG_SESSION_SERVER + "/session/minecraft/join"
                : stripTrailingSlash(authServer) + "/sessionserver/session/minecraft/join";

        JsonObject body = new JsonObject();
        body.addProperty("accessToken", accessToken);
        body.addProperty("selectedProfile", uuid.toString().replace("-", ""));
        body.addProperty("serverId", serverId);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(JOIN_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "VMFashion/" + VMFashionClient.MOD_NAME)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new FashionApi.ApiException(-1, "bad_session_url", "会话服务器地址无效: " + url));
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenAccept(response -> {
                    int status = response.statusCode();
                    if (status / 100 == 2) {
                        return;
                    }
                    String message = "会话服务器拒绝了 join（HTTP " + status + "）";
                    if (status == 403) {
                        message = "会话服务器拒绝：登录令牌可能已过期，请重新登录游戏";
                    }
                    throw new CompletionException(new FashionApi.ApiException(status, "join_failed", message));
                });
    }

    // ==================== 皮肤站地址探测 ====================

    /**
     * 探测当前客户端使用的 Yggdrasil 皮肤站地址。
     *
     * <p>authlib-injector 以 {@code -javaagent:...authlib-injector.jar=<URL>} 的形式注入，
     * 从中可以直接读出皮肤站 base；没有就是正版。
     */
    public static String detectAuthServer(String override) {
        // 优先相信启动参数里的 authlib-injector：这是启动器实际在用的皮肤站，
        // 恶意 config 不能覆盖它去骗取 Minecraft accessToken。
        String fromAgent = detectAuthServerFromJavaagent();
        if (!fromAgent.isBlank()) {
            return fromAgent;
        }
        if (override != null && !override.isBlank()) {
            String validated = validateAuthServer(override);
            if (validated.isBlank()) {
                VMFashionClient.LOGGER.warn("配置的皮肤站地址非法（必须是 https 或本机回环 http）：{}", override);
            }
            return validated;
        }
        return "";
    }

    private static String detectAuthServerFromJavaagent() {
        try {
            List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String arg : args) {
                if (arg == null || !arg.startsWith("-javaagent:") || !arg.contains("authlib-injector")) {
                    continue;
                }
                int eq = arg.indexOf('=');
                if (eq > 0 && eq + 1 < arg.length()) {
                    String value = arg.substring(eq + 1).trim();
                    String validated = validateAuthServer(value);
                    if (!validated.isBlank()) {
                        return validated;
                    }
                }
            }
        } catch (Throwable t) {
            VMFashionClient.LOGGER.debug("探测 authlib-injector 参数失败: {}", t.toString());
        }
        return "";
    }

    /**
     * 校验皮肤站 base：只允许 https（本机回环允许 http），拒绝 userinfo/query/fragment。
     * 非法返回空串。皮肤站地址会接收 Minecraft accessToken，不能把凭据发给任意 URL。
     */
    private static String validateAuthServer(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getUserInfo() != null) {
                return "";
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                return "";
            }
            boolean loopback = "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host) || "::1".equals(host) || "[::1]".equals(host);
            if (!"https".equals(scheme) && !("http".equals(scheme) && loopback)) {
                return "";
            }
            return stripTrailingSlash(value);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 会话持久化 ====================

    /** 启动时尝试恢复上次的 token（未过期、且仍属于当前 MC 账号才用）。 */
    public Session restoreSession() {
        if (!Files.isRegularFile(SESSION_FILE)) {
            return null;
        }
        try {
            String json = Files.readString(SESSION_FILE);
            Session stored = GSON.fromJson(json, Session.class);
            if (stored == null || stored.token() == null || stored.token().isBlank() || stored.expired()) {
                deleteSessionFile();
                return null;
            }
            if (stored.apiBaseUrl() == null || !stored.apiBaseUrl().equalsIgnoreCase(api.baseUrl())) {
                // token 只属于签发它的后端；换后端地址后绝不能复用
                VMFashionClient.LOGGER.info("时装后端地址已变化，丢弃旧时装会话");
                deleteSessionFile();
                return null;
            }
            UUID current = currentProfileId();
            if (current != null && !matchesProfile(stored, current)) {
                // 换号后绝不复用上一个账号的 token：否则新账号的穿戴会被写进旧账号
                VMFashionClient.LOGGER.info("检测到 Minecraft 账号已切换，丢弃旧时装会话");
                deleteSessionFile();
                return null;
            }
            api.setToken(stored.token());
            this.session = stored;
            return stored;
        } catch (Exception e) {
            VMFashionClient.LOGGER.warn("恢复时装会话失败: {}", e.toString());
            return null;
        }
    }

    private static UUID currentProfileId() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return null;
            }
            User user = minecraft.getUser();
            return user == null ? null : user.getProfileId();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 复核当前 Minecraft 账号是否仍是签发这个 session 的账号。
     *
     * <p>旧实现只在 {@link #restoreSession()}（启动时）比对一次 UUID，运行中换号就会
     * 把 A 的穿戴写进 B、也会拿 B 的操作去改 A 的后端衣柜。所以进服和低频 tick 都要复核。
     *
     * @return {@code true} = 账号一致（或暂时判断不了）；{@code false} = 已清掉不匹配的 session
     */
    public boolean revalidateProfile() {
        Session current = session;
        if (current == null) {
            return true;
        }
        UUID profileId = currentProfileId();
        if (profileId == null) {
            // 拿不到账号信息（客户端还在初始化 / 离线登录）时不要贸然清会话
            return true;
        }
        if (!matchesProfile(current, profileId)) {
            VMFashionClient.LOGGER.info("检测到 Minecraft 账号已切换，丢弃旧时装会话");
            clearSession();
            return false;
        }
        return true;
    }

    /** session 是否属于给定账号。 */
    private static boolean matchesProfile(Session session, UUID currentProfileId) {
        if (session == null || session.mcUuid() == null || currentProfileId == null) {
            return false;
        }
        return currentProfileId.toString().equalsIgnoreCase(session.mcUuid().trim());
    }

    private void saveSession(Session session) {
        Path temp = null;
        try {
            Files.createDirectories(SESSION_FILE.getParent());
            // 先写临时文件并收紧权限，再原子替换，避免 session.json 出现
            // "默认 umask 可读" 的短暂窗口
            temp = Files.createTempFile(SESSION_FILE.getParent(), "session-", ".tmp");
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {
                // 非 POSIX 文件系统忽略
            }
            Files.writeString(temp, GSON.toJson(session), StandardCharsets.UTF_8);
            try {
                Files.move(temp, SESSION_FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, SESSION_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException e) {
            VMFashionClient.LOGGER.warn("保存时装会话失败: {}", e.toString());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响主流程
                }
            }
        }
    }

    private static void deleteSessionFile() {
        try {
            Files.deleteIfExists(SESSION_FILE);
        } catch (IOException ignored) {
            // 删不掉就算了，下次认证会覆盖
        }
    }

    /** 退出登录 / 切换账号时调用。 */
    public void clearSession() {
        this.session = null;
        api.setToken(null);
        deleteSessionFile();
    }

    // ==================== 工具 ====================

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String optString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
