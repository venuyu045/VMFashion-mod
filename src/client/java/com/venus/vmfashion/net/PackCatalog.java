package com.venus.vmfashion.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.util.HashUtil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 服务端时装包目录（{@code GET /packs}）的拉取、解析与本地缓存。
 *
 * <p>为什么需要"包级"目录：后端现有 {@code /loadouts/resolve} 只回单件时装的
 * {@code assetKey}（单文件），而运行时单位是<b>目录</b>（model + texture + emissive + anim）。
 * 这里按 {@code packId → (version, sha256, bytes, url)} 维护清单，下载器据此整包下载。
 *
 * <p>缓存分两层：
 * <ul>
 *   <li>内存：{@link #find(String)} 是渲染/命令路径上的查询，必须零 IO；</li>
 *   <li>磁盘：{@code config/vmfashion/cache/pack-catalog.json}，TTL {@value #DEFAULT_TTL_MS}ms，
 *       让"下次启动时后端暂时不可达"也能沿用上次的清单（离线可用）。</li>
 * </ul>
 *
 * <p><b>优雅降级</b>：后端没有实现 {@code /packs} 时（404/501），{@link #fetch} 抛出带状态码的
 * {@link FashionApi.ApiException}，由 {@code AssetSyncService} 判定为"不支持"后记一次日志并停止重试，
 * 渲染链路完全不受影响（玩家只是看不到别人穿的、自己没装的时装）。
 *
 * <p>JSON 解析全部防御式：缺字段、类型不对、条目数异常都只跳过该条，绝不让客户端崩溃。
 */
public final class PackCatalog {

    /** 目录缓存有效期：10 分钟。太短会让每次进服都打后端，太长会让新包迟迟不生效。 */
    public static final long DEFAULT_TTL_MS = 10 * 60 * 1000L;

    /** 后端清单端点（相对 {@code apiBaseUrl}）。 */
    public static final String PACKS_PATH = "/packs";

    /** 清单最多接受的条目数：后端就算返回 10 万条也不会把内存吃爆。 */
    private static final int MAX_PACKS = 2048;

    /** packId 会直接拼进文件系统路径，只允许安全字符，长度也有上限。 */
    private static final Pattern SAFE_PACK_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 一条包清单。
     *
     * @param id          packId（会作为 {@code packs/server/<packId>} 目录名，必须通过 {@link #isSafePackId}）
     * @param version     包版本，用于升级判断
     * @param sha256      整包 zip 的 sha256（小写 hex），下载后必须校验
     * @param bytes       zip 字节数
     * @param url         下载地址，可绝对（{@code https://…}）也可相对（{@code /packs/{id}/{ver}/download}）
     * @param publishedAt 发布时间戳（毫秒，可 0）
     * @param signature   Ed25519 签名（base64），覆盖 {@code packId + "\n" + version + "\n" + sha256}；
     *                    老后端没有这个字段时为空串，由 {@code PackSignature} 按策略决定放行还是拒绝
     * @param signingKeyId 签发密钥的 keyId（可选），对应客户端 {@code trusted_keys.json} 里的 {@code id}
     */
    public record PackInfo(String id, String version, String sha256, long bytes, String url, long publishedAt,
                           String signature, String signingKeyId) {

        /**
         * 兼容旧构造（没有签名）：老后端 / 既有单测继续可用。
         */
        public PackInfo(String id, String version, String sha256, long bytes, String url, long publishedAt) {
            this(id, version, sha256, bytes, url, publishedAt, "", "");
        }

        /** 是否带签名。 */
        public boolean hasSignature() {
            return signature != null && !signature.isBlank();
        }
    }

    private final Path cacheFile;

    private volatile Map<String, PackInfo> packs = Map.of();
    private volatile long fetchedAtMs;
    private volatile boolean loadedFromDisk;

    public PackCatalog(Path cacheFile) {
        this.cacheFile = cacheFile;
    }

    /** 包清单缓存文件路径（可为 {@code null}，表示纯内存）。 */
    public Path cacheFile() {
        return cacheFile;
    }

    // ==================== 查询 ====================

    /** 按 packId 查（大小写不敏感）；没拉到清单时返回 {@code null}。 */
    public PackInfo find(String packId) {
        if (packId == null || packId.isBlank()) {
            return null;
        }
        return packs.get(packId.trim().toLowerCase(Locale.ROOT));
    }

    /** 全部包（key 为小写 packId，只读）。 */
    public Map<String, PackInfo> packs() {
        return packs;
    }

    public int size() {
        return packs.size();
    }

    public long fetchedAtMs() {
        return fetchedAtMs;
    }

    /** 清单是否还在有效期内。 */
    public boolean isFresh(long nowMs) {
        return fetchedAtMs > 0 && nowMs - fetchedAtMs < DEFAULT_TTL_MS;
    }

    /** 是否已经拿到过清单（无论是否过期）。 */
    public boolean hasData() {
        return !packs.isEmpty();
    }

    // ==================== 缓存读写 ====================

    /**
     * 懒加载磁盘缓存：只在第一次查询前调用一次，失败只记日志（离线也要能启动）。
     *
     * @param logger 日志回调，可为 {@code null}
     */
    public synchronized void loadFromDiskIfNeeded(java.util.function.Consumer<String> logger) {
        if (loadedFromDisk) {
            return;
        }
        loadedFromDisk = true;
        if (cacheFile == null || !Files.isRegularFile(cacheFile)) {
            return;
        }
        try {
            String json = Files.readString(cacheFile, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return;
            }
            JsonObject object = root.getAsJsonObject();
            Map<String, PackInfo> parsed = parsePacks(object.get("packs"));
            long fetchedAt = optLong(object, "fetchedAt", 0L);
            if (!parsed.isEmpty()) {
                packs = parsed;
                fetchedAtMs = fetchedAt;
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.accept("读取包清单缓存失败（忽略）: " + e);
            }
        }
    }

    /** 更新内存缓存并异步落盘（原子写，写失败只记日志）。 */
    public void store(Map<String, PackInfo> newPacks, long nowMs) {
        Map<String, PackInfo> copy = newPacks == null ? Map.of() : Map.copyOf(newPacks);
        // 后端瞬时异常可能返回空数组：不要用空清单覆盖已有的磁盘缓存（否则离线可用清单被清空、
        // 且 10 分钟内不会再刷新，依赖清单的重新下发全部失效）。只更新 fetchedAt 让它稍后再拉。
        if (copy.isEmpty() && !this.packs.isEmpty()) {
            this.fetchedAtMs = nowMs;
            return;
        }
        this.packs = copy;
        this.fetchedAtMs = nowMs;
        if (cacheFile == null) {
            return;
        }
        try {
            // 磁盘缓存与线上格式保持一致（数组），这样 loadFromDiskIfNeeded 可以复用同一个解析器
            JsonArray packsArray = new JsonArray();
            for (PackInfo info : copy.values()) {
                JsonObject item = new JsonObject();
                item.addProperty("id", info.id());
                item.addProperty("version", info.version());
                item.addProperty("sha256", info.sha256());
                item.addProperty("bytes", info.bytes());
                item.addProperty("url", info.url());
                item.addProperty("publishedAt", info.publishedAt());
                if (info.hasSignature()) {
                    item.addProperty("signature", info.signature());
                    if (info.signingKeyId() != null && !info.signingKeyId().isBlank()) {
                        item.addProperty("signingKeyId", info.signingKeyId());
                    }
                }
                packsArray.add(item);
            }
            JsonObject root = new JsonObject();
            root.addProperty("fetchedAt", nowMs);
            root.add("packs", packsArray);
            writeAtomically(cacheFile, root.toString());
        } catch (Exception e) {
            // 缓存写不进去不影响本次会话，只是下次启动要重新拉
        }
    }

    /** 清空（{@code /vmfashion cache clear} 或切换后端地址时调用）。 */
    public void clear() {
        packs = Map.of();
        fetchedAtMs = 0L;
        loadedFromDisk = true;
        if (cacheFile != null) {
            try {
                Files.deleteIfExists(cacheFile);
            } catch (IOException ignored) {
                // 删不掉就留着，下次 store 会覆盖
            }
        }
    }

    // ==================== 网络 ====================

    /**
     * 拉一次 {@code GET /packs}。
     *
     * <p>不重试：重试由 {@code AssetSyncService} 按 tick 节奏控制（避免多实例叠加成风暴）。
     *
     * @param http     共享的 HttpClient（不跟随重定向）
     * @param baseUrl  后端地址，例如 {@code https://vmtools.example.com/api/fashion}
     * @param token    鉴权 token，可为 {@code null}（清单一般是公开的）
     * @param executor 解析/回调线程池
     * @return 解析后的清单（可能为空 map，但不会是 {@code null}）
     */
    public CompletableFuture<Map<String, PackInfo>> fetch(HttpClient http, String baseUrl, String token,
                                                          Executor executor) {
        if (http == null || baseUrl == null || baseUrl.isBlank()) {
            return CompletableFuture.failedFuture(new FashionApi.ApiException(-1, "no_base_url", "未配置后端地址"));
        }
        URI uri;
        try {
            uri = URI.create(stripTrailingSlash(baseUrl.trim()) + PACKS_PATH);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new FashionApi.ApiException(-1, "bad_base_url", "后端地址非法: " + baseUrl));
        }
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .header("User-Agent", "VMFashion")
                    .GET();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(
                    new FashionApi.ApiException(-1, "bad_base_url", "后端地址非法: " + baseUrl));
        }
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApplyAsync(response -> {
                    int status = response.statusCode();
                    if (status / 100 != 2) {
                        // 复用 FashionApi 的错误体解析，错误码/信息与其它端点保持一致
                        throw FashionApi.parseApiError(status, response.body());
                    }
                    return parse(response.body());
                }, executor);
    }

    // ==================== 解析（纯逻辑，可单测） ====================

    /**
     * 解析 {@code /packs} 响应体。
     *
     * <p>接受两种形状：{@code {"packs":[…]}} 与直接给数组 {@code […]}（后端同学容易写成后者）。
     *
     * @param json 响应体原文
     * @return 小写 packId → 清单项；任何解析失败都返回空 map，绝不抛异常
     */
    public static Map<String, PackInfo> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonElement arrayElement = root;
            if (root.isJsonObject()) {
                arrayElement = root.getAsJsonObject().get("packs");
            }
            return parsePacks(arrayElement);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static Map<String, PackInfo> parsePacks(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return Map.of();
        }
        JsonArray array = element.getAsJsonArray();
        int limit = Math.min(array.size(), MAX_PACKS);
        Map<String, PackInfo> result = new LinkedHashMap<>(Math.max(4, limit));
        for (int i = 0; i < limit; i++) {
            JsonElement entry = array.get(i);
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            try {
                PackInfo info = parsePack(entry.getAsJsonObject());
                if (info != null) {
                    // 同一个 packId 出现多次：以先出现的为准（后端排序通常是"最新在前"）
                    result.putIfAbsent(info.id().toLowerCase(Locale.ROOT), info);
                }
            } catch (Exception ignored) {
                // 单条脏数据不影响其它包
            }
        }
        return Map.copyOf(result);
    }

    private static PackInfo parsePack(JsonObject object) {
        String id = optString(object, "id");
        if (!isSafePackId(id)) {
            // packId 会拼进文件系统路径，不安全的 id 直接丢弃
            return null;
        }
        String version = optString(object, "version");
        if (version.isBlank() || version.length() > 64) {
            return null;
        }
        String sha256 = optString(object, "sha256").toLowerCase(Locale.ROOT);
        if (!HashUtil.isSha256Hex(sha256)) {
            // 没有可信哈希就不下载：无法校验完整性的包等于任意代码/数据
            return null;
        }
        long bytes = optLong(object, "bytes", -1L);
        if (bytes <= 0) {
            return null;
        }
        String url = optString(object, "url");
        if (url.isBlank()) {
            url = PACKS_PATH + "/" + encodePathSegment(id) + "/" + encodePathSegment(version) + "/download";
        }
        long publishedAt = optLong(object, "publishedAt", 0L);
        // 签名是可选字段：老后端没有 → 空串，由 PackSignature 的策略决定"放行 + warn"还是"严格模式拒绝"
        String signature = optString(object, "signature");
        String signingKeyId = optString(object, "signingKeyId");
        return new PackInfo(id, version, sha256, bytes, url, publishedAt, signature, signingKeyId);
    }

    /** packId 是否可安全用作目录名。 */
    public static boolean isSafePackId(String packId) {
        if (packId == null) {
            return false;
        }
        String trimmed = packId.trim();
        if (!SAFE_PACK_ID.matcher(trimmed).matches()) {
            return false;
        }
        // "." / ".." 虽然能过正则，但绝不能当目录名
        if (".".equals(trimmed) || "..".equals(trimmed)) {
            return false;
        }
        // 以 "." 开头的名字（尤其 ".tmp-"）会和 AssetSyncService 的临时目录前缀撞车 ——
        // rescanDisk 会把 ".tmp-*" 当崩溃残留删掉，导致这个包每次初始化都被删、永不持久。
        if (trimmed.startsWith(".")) {
            return false;
        }
        // "server" 是自动下发命名空间目录名，不能当 packId（否则磁盘结构冲突）
        if ("server".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return true;
    }

    // ==================== 工具 ====================

    private static void writeAtomically(Path file, String content) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = null;
        try {
            temp = Files.createTempFile(parent, "pack-catalog-", ".tmp");
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
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

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String encodePathSegment(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String optString(JsonObject object, String key) {
        try {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                return "";
            }
            return element.getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        try {
            JsonElement element = object.get(key);
            if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
                return fallback;
            }
            return element.getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }
}
