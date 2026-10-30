package com.venus.vmfashion.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.net.FashionApi;
import com.venus.vmfashion.net.PackCatalog;
import com.venus.vmfashion.net.PackDownloader;
import com.venus.vmfashion.net.PackSignature;
import com.venus.vmfashion.net.SafeZip;
import com.venus.vmfashion.render.CosmeticAssets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 服务端时装包自动下发（「A 穿的时装，B 没装也能看见」）。
 *
 * <p>契约（后端需新增，见 {@code docs/fashion-api.md}）：
 * <pre>
 * GET  /packs                          → { packs: [ {id, version, sha256, bytes, url} ] }
 * GET  /packs/{id}/{version}/download  → zip 字节流（pack.json + cosmetics/...）
 * </pre>
 * {@code /loadouts/resolve} 与 {@code /loadout/me} 的每个 item 补 {@code packId} / {@code packVersion}。
 *
 * <p>客户端安全要求（自动下载 = 新增远程数据入口，必须全部做到）：
 * <ul>
 *   <li>下载流式落盘 + {@code DigestInputStream} 校验 sha256 与字节数（{@link PackDownloader}）；</li>
 *   <li>解压拒绝 {@code ..}、绝对路径、符号链接，限制条目数 / 单文件大小 / 总解压大小 / 压缩比（{@link SafeZip}）；</li>
 *   <li>解到 {@code packs/server/.tmp-*}，校验 {@code pack.json.id} 后再原子 move 到 {@code packs/server/<packId>/}；</li>
 *   <li>用户自己装的同名包优先，绝不覆盖；</li>
 *   <li>装完在主线程 {@code PackLoader.reload()} + {@code CosmeticAssets.invalidate()}（贴图上传要 GL 上下文）。</li>
 * </ul>
 *
 * <p><b>线程模型</b>：{@link #requestMissing(String)} 在渲染线程上被每帧调用，因此它<b>只做内存入队</b>；
 * 所有网络 / 解压 / 磁盘操作都在 {@code vmfashion-pack-*} 工作线程上跑；
 * 需要 GL 上下文的 {@code PackLoader.reload()} 与 {@code CosmeticAssets.invalidate()} 回到主线程执行。
 *
 * <p><b>优雅降级</b>（后端还没实现这些端点时）：
 * <ul>
 *   <li>{@code GET /packs} 返回 404/501/405 → 记<b>一次</b>日志并停止重试，本次会话内自动下发关闭；
 *       渲染、本地包、衣柜、同步全部不受影响，玩家只是看不到"别人穿的、自己没装的"时装；</li>
 *   <li>网络错误 → 每 {@value #CATALOG_RETRY_MS}ms 最多重试一次，不刷屏；</li>
 *   <li>单个包下载/校验/解压失败 → 该包冷却 {@value #FAILED_RETRY_MS}ms，其它包不受影响；</li>
 *   <li>配置 {@code downloadServerPacks=false} 或没配后端地址 → 整个服务空转，不产生任何请求与磁盘写入。</li>
 * </ul>
 *
 * <p>由 {@code VMFashionClient} 初始化、{@code SyncService} 在进服/离服时转发，
 * 渲染层解析不到时装 ID 时调用 {@link #requestMissing(String)}。
 */
public final class AssetSyncService {

    private static final AssetSyncService INSTANCE = new AssetSyncService();

    /** 同时下载/安装的包数量上限：2 个够快，又不至于把 1.2GB 内存的机器压垮。 */
    private static final int MAX_CONCURRENT_INSTALLS = 2;
    /** 每个包目录里的哈希标记文件（同时用作 LRU 的时间戳）。 */
    private static final String MARKER_FILE = ".sha256";
    /** 临时解压目录前缀，崩溃残留会在下次初始化时清掉。 */
    private static final String TEMP_PREFIX = ".tmp-";
    /** 下载/解压失败的包冷却多久再试（避免对坏包疯狂重试）。 */
    private static final long FAILED_RETRY_MS = 10 * 60 * 1000L;
    /** 清单里还没有这个包（例如刚上架、清单未刷新）时的重试间隔。 */
    private static final long CATALOG_MISS_RETRY_MS = 60 * 1000L;
    /** 清单拉取失败后的最小重试间隔。 */
    private static final long CATALOG_RETRY_MS = 30 * 1000L;
    /** LRU 时间戳落盘的间隔（别每帧 stat）。 */
    private static final long LRU_TOUCH_INTERVAL_MS = 60 * 1000L;
    /** 信任库（trusted_keys.json）的重新读取间隔：运维改文件后最多 1 分钟生效。 */
    private static final long TRUST_RELOAD_INTERVAL_MS = 60 * 1000L;

    private final AtomicInteger threadCounter = new AtomicInteger();

    private volatile ModConfig config;
    private volatile boolean enabled;

    private volatile HttpClient http;
    private volatile ExecutorService ioExecutor;
    private volatile PackDownloader downloader;
    private volatile PackCatalog catalog;
    private volatile String lastBaseUrl = "";

    /** 待安装（含在飞）的 packId（小写）；{@link #pendingCount()} 直接返回它的大小。 */
    private final Set<String> queued = ConcurrentHashMap.newKeySet();
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();
    /** 正在下载/解压的 packId（小写）。 */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    /** 每个 in-flight packId 的起始时间戳，看门狗据此判断"卡太久"。 */
    private final Map<String, Long> inFlightSince = new ConcurrentHashMap<>();
    /**
     * 正在读取的响应体流（packId → InputStream）。
     *
     * <p>用于看门狗：body 卡住时 {@code read} 会无限阻塞，只能从另一个线程 {@code close} 这个流
     * 来解阻塞。{@link PackDownloader} 在读之前把流登记进来、读完后移除。
     */
    private final Map<String, java.io.InputStream> activeStreams = new ConcurrentHashMap<>();
    /** 单次安装（下载 + 解压 + 落盘）的硬超时；超过就由看门狗强拆，避免永久占线程。 */
    private static final long INSTALL_TIMEOUT_MS = 5 * 60 * 1000L;
    /** 已安装的服务端包（小写 packId → 元数据）。 */
    private final Map<String, InstalledPack> installed = new ConcurrentHashMap<>();
    /** 用户自己装的包（小写 packId）：存在时永不下载/覆盖。 */
    private final Set<String> userPacks = ConcurrentHashMap.newKeySet();
    /** 失败冷却：packId → 允许重试的时间戳。 */
    private final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    /** 因"清单里还没有这个包"而失败的，清单刷新后立刻重试。 */
    private final Set<String> catalogMisses = ConcurrentHashMap.newKeySet();
    /** 最近被穿戴引用过的包，用于延迟刷新 LRU 时间戳（避免渲染线程做 IO）。 */
    private final Set<String> recentlyUsed = ConcurrentHashMap.newKeySet();

    /**
     * 验签失败 / 严格模式缺签名的包：<b>本会话永久跳过</b>，不走 {@code failedUntil} 的冷却重试
     * （签名错误重试一万次也还是错，只会刷日志）。{@code initialize} / {@code clearCache} 时清空。
     */
    private final Set<String> rejectedBySignature = ConcurrentHashMap.newKeySet();
    /** 信任库缓存 + 上次读取时间。 */
    private volatile PackSignature.TrustStore trustStore;
    private volatile long trustStoreLoadedAt;
    /** "后端没签名、按宽松模式放行"的 warn 只打一次，避免每个包刷一行。 */
    private final AtomicBoolean unsignedWarningLogged = new AtomicBoolean(false);
    /** 已经提示过"签名来自未知密钥"的 packId（每包只提醒一次，避免刷屏）。 */
    private final Set<String> unknownKeyWarned = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private final AtomicBoolean evicting = new AtomicBoolean(false);
    private final AtomicBoolean catalogInFlight = new AtomicBoolean(false);

    private volatile boolean catalogUnsupported;
    private volatile long lastCatalogAttemptAt;
    private volatile long lastLruTouchAt;
    /**
     * 已安装包的总字节数（内存维护，{@link #cacheBytes()} 不 stat 磁盘）。
     *
     * <p>用 {@link java.util.concurrent.atomic.AtomicLong} 而不是 {@code volatile long}：
     * 安装完成（worker 线程，最多 2 个并发）与 LRU 淘汰（另一个 worker）都会做
     * {@code += / -=} 这种读-改-写，plain volatile 会丢更新，导致缓存大小统计偏小、
     * 淘汰不及时（缓存可能长期超过上限）。
     */
    private final java.util.concurrent.atomic.AtomicLong cachedBytes =
            new java.util.concurrent.atomic.AtomicLong();
    /** 代次：离服 / 清缓存时 +1，在飞下载的结果据此作废。 */
    private volatile long epoch;

    /** 已安装包的元数据。{@code lastUsedAt} 用于 LRU 淘汰。 */
    private record InstalledPack(String packId, String version, String sha256, long bytes,
                                 long installedAt, long lastUsedAt) {
    }

    private AssetSyncService() {
    }

    public static AssetSyncService getInstance() {
        return INSTANCE;
    }

    // ==================== 生命周期 ====================

    /**
     * 按配置初始化（可在配置热更新后重复调用）。
     *
     * <p>{@code downloadServerPacks=false} 或未配置 {@code apiBaseUrl} 时整个服务空转：
     * 不建连接、不下载、不写盘，已有的服务端包仍然可离线使用（只是不再更新/淘汰）。
     */
    public void initialize(ModConfig config) {
        this.config = config;
        boolean canRun = config != null && config.isDownloadServerPacks()
                && !config.getApiBaseUrl().isBlank();
        if (!canRun) {
            enabled = false;
            queue.clear();
            queued.clear();
            failedUntil.clear();
            catalogMisses.clear();
            return;
        }

        String baseUrl = config.getApiBaseUrl();
        if (http == null) {
            http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    // 与 FashionApi 一致：不跟随重定向，避免 Authorization 被带到别的 host
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        if (ioExecutor == null) {
            // 线程数 = 并发安装数 + 2：安装任务会在 worker 上 join 下载 future，
            // 下载的流式读取回调也要用同一个池，多留两个线程避免互相等死
            ioExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_INSTALLS + 2, runnable -> {
                Thread thread = new Thread(runnable, "vmfashion-pack-" + threadCounter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        }
        if (downloader == null) {
            downloader = new PackDownloader(http, ioExecutor);
        }
        if (catalog == null) {
            catalog = new PackCatalog(catalogCacheFile());
        }
        if (!baseUrl.equalsIgnoreCase(lastBaseUrl)) {
            // 换了后端：旧清单与旧包的哈希都对不上了，重新来
            lastBaseUrl = baseUrl;
            catalog.clear();
            catalogUnsupported = false;
            lastCatalogAttemptAt = 0L;
        }
        enabled = true;
        rejectedBySignature.clear();
        trustStore = null;
        trustStoreLoadedAt = 0L;
        catalog.loadFromDiskIfNeeded(message -> VMFashionClient.LOGGER.debug("{}", message));
        rescanDisk();
        VMFashionClient.LOGGER.info("时装包自动下发已启用：{}（已缓存 {} 个包）", baseUrl, installed.size());
    }

    /**
     * 每客户端 tick 调用（由 {@code VMFashionClient} 注册，主线程）。
     *
     * <p>只做"内存判断 + 拉起 worker + 必要时回主线程 reload"，绝不在 tick 里做网络/磁盘重活。
     */
    public void tick() {
        if (!enabled) {
            return;
        }
        if (reloadPending.compareAndSet(true, false)) {
            reloadOnMainThread();
        }
        startQueuedInstalls();
        refreshCatalogIfDue(false);
        touchRecentlyUsedIfDue();
        evictIfOverBudget();
        sweepStuckInstalls();
    }

    /**
     * 看门狗：清理卡太久的 in-flight 安装。
     *
     * <p>为什么必须有：body 读取可能永久阻塞（后端返回 200 头却不发 body）。那种线程不会自己退出，
     * 会一直占着下载线程池，最终让整个自动下发停摆。这里做两件事：
     * <ol>
     *   <li>把该 key 正在读的流 {@code close} 掉 —— 阻塞的 {@code read} 会立刻抛异常、线程得以释放；</li>
     *   <li>把 key 从 inFlight/queued 摘掉并设冷却 —— 保证队列能继续前进、{@code pendingCount} 不会卡死。</li>
     * </ol>
     */
    private void sweepStuckInstalls() {
        if (inFlightSince.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> entry : inFlightSince.entrySet()) {
            String key = entry.getKey();
            Long since = entry.getValue();
            if (since == null || now - since < INSTALL_TIMEOUT_MS) {
                continue;
            }
            inFlightSince.remove(key);
            java.io.InputStream stream = activeStreams.remove(key);
            if (stream != null) {
                try {
                    stream.close(); // 解阻塞正在 read 的线程
                } catch (Throwable ignored) {
                    // 关不掉也无所谓，马上要把它踢出队列
                }
            }
            if (inFlight.remove(key)) {
                queued.remove(key);
                failedUntil.put(key, now + FAILED_RETRY_MS);
                VMFashionClient.LOGGER.warn("时装包 {} 安装超时（{} 分钟），已强制中止并稍后重试",
                        key, INSTALL_TIMEOUT_MS / 60_000L);
            }
        }
    }

    /** 进服：刷新 catalog，并预取自己穿戴涉及的包。 */
    public void onJoin() {
        if (!enabled) {
            return;
        }
        // 换服后同名 packId 的包可能是另一个服务器的（且合格）：清掉本会话的验签拒绝记录重新评估
        rejectedBySignature.clear();
        // 玩家可能刚手动装了包（用户包优先），进服重扫一次
        rescanDisk();
        Map<String, String> worn = LocalLoadout.getInstance().snapshot();
        for (String cosmeticId : worn.values()) {
            requestMissing(cosmeticId);
        }
        refreshCatalogIfDue(true);
    }

    /** 离服：取消在飞下载，保留已缓存文件（离线可用）。 */
    public void onDisconnect() {
        // 在飞下载会在下载器的取消回调里发现 epoch 变了并自行中止；已下载的文件保留
        epoch++;
        queue.clear();
        queued.clear();
        failedUntil.clear();
        catalogMisses.clear();
        abortActiveStreams();
    }

    /** 把所有正在读的响应体流关掉（离服 / 清缓存时立刻中止在飞下载，而不是等它读完）。 */
    private void abortActiveStreams() {
        for (java.io.InputStream stream : activeStreams.values()) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
        activeStreams.clear();
    }

    // ==================== 渲染层入口（热路径） ====================

    /**
     * 请求下发某件时装的包。
     *
     * <p><b>必须在渲染线程上零阻塞</b>：这里只做几次内存查表 + 入队，网络与磁盘全部在 worker 上。
     * 同一个 packId 只会入队一次（{@link #queued} 去重），失败有冷却，不会形成重试风暴。
     *
     * @param cosmeticId 形如 {@code packId:localId}；{@code null}/空/已存在本地包时直接忽略
     */
    public void requestMissing(String cosmeticId) {
        if (!enabled || cosmeticId == null || cosmeticId.isBlank()) {
            return;
        }
        int colon = cosmeticId.indexOf(':');
        if (colon <= 0 || colon == cosmeticId.length() - 1) {
            // 没有 packId 前缀：这件时装来自本地包（或脏数据），无需下发
            return;
        }
        String packId = cosmeticId.substring(0, colon).trim();
        if (!PackCatalog.isSafePackId(packId)) {
            return;
        }
        String key = packId.toLowerCase(Locale.ROOT);
        if (userPacks.contains(key)) {
            // 用户自己装的同名包优先，永不覆盖 / 永不更新
            return;
        }
        InstalledPack local = installed.get(key);
        if (local != null) {
            PackCatalog.PackInfo info = catalog == null ? null : catalog.find(key);
            if (info == null || !PackUpdatePolicy.needsUpdate(local.version(), local.sha256(),
                    info.version(), info.sha256())) {
                // 已经能解析到且是最新：顺手记一下"最近用过"，供 LRU 淘汰（不在这里做磁盘 IO）
                recentlyUsed.add(key);
                return;
            }
            // 清单里有更新的版本：不 return，继续走下面的入队逻辑（本会话内会重装成新版本）
        }
        if (rejectedBySignature.contains(key)) {
            return; // 验签失败过：本会话不再尝试
        }
        Long failedAt = failedUntil.get(key);
        if (failedAt != null && System.currentTimeMillis() < failedAt) {
            return;
        }
        if (!queued.add(key)) {
            return; // 已排队或在飞
        }
        queue.add(key);
    }

    /**
     * 标记某件时装"刚被渲染用到过"，用于 LRU 淘汰。
     *
     * <p>为什么需要：{@code LoadoutService.resolve()} 解析成功后会直接返回缓存，不会走
     * {@link #requestMissing}，所以"正在穿的老包"的最近使用时间不会推进 —— 缓存超限时会被
     * 当成最久未用淘汰掉，导致正在显示的时装被删、下一帧重新下载（闪现/抖动）。
     * 这里是纯内存操作（渲染线程安全），只在 60 秒节流后真正落盘。
     *
     * @param cosmeticId 形如 {@code packId:localId}；非法 / 本地包直接忽略
     */
    public void markUsed(String cosmeticId) {
        if (!enabled || cosmeticId == null || cosmeticId.isBlank()) {
            return;
        }
        int colon = cosmeticId.indexOf(':');
        if (colon <= 0) {
            return;
        }
        String key = cosmeticId.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        if (!key.isEmpty() && installed.containsKey(key)) {
            recentlyUsed.add(key);
        }
    }

    // ==================== 状态 / 缓存管理 ====================

    /** 清空服务端下发的包缓存（{@code /vmfashion cache clear}）。 */
    public void clearCache() {
        epoch++;
        queue.clear();
        queued.clear();
        failedUntil.clear();
        catalogMisses.clear();
        recentlyUsed.clear();
        abortActiveStreams();
        // 用户显式清了缓存：给之前验签失败的包一次重新验证的机会
        rejectedBySignature.clear();
        int removed = 0;
        Path serverRoot = PackLoader.serverPacksDir();
        if (Files.isDirectory(serverRoot)) {
            try (Stream<Path> children = Files.list(serverRoot)) {
                for (Path child : children.toList()) {
                    if (deleteRecursively(child)) {
                        removed++;
                    }
                }
            } catch (IOException e) {
                VMFashionClient.LOGGER.warn("清理服务端时装包缓存失败: {}", e.toString());
            }
        }
        installed.clear();
        cachedBytes.set(0L);
        // 包没了，注册表里还留着它们：让主线程下一 tick 重载
        reloadPending.set(true);
        VMFashionClient.LOGGER.info("已清理服务端时装包缓存：{} 个目录", removed);
    }

    /**
     * 状态摘要，给 {@code /vmfashion status} 与衣柜底部状态行用。
     *
     * <p>i18n：返回 {@link Component}，文案在语言文件 {@code vmfashion.asset.status.*}。
     * 这里只描述**状态本身**（"2 个包 / 3.5 MB"），"资产下发："这种标签由调用方
     * （命令的 {@code vmfashion.command.status.assets}、衣柜的 {@code vmfashion.gui.status.assets}）拼，
     * 否则会出现"资产下发：资产下发：…"的双前缀。
     */
    public Component describeStatus() {
        ModConfig current = config;
        if (current == null) {
            return Component.translatable("vmfashion.asset.status.not_initialized");
        }
        if (!current.isDownloadServerPacks()) {
            return Component.translatable("vmfashion.asset.status.disabled_in_config");
        }
        if (current.getApiBaseUrl().isBlank()) {
            return Component.translatable("vmfashion.asset.status.no_backend");
        }
        if (!enabled) {
            return Component.translatable("vmfashion.asset.status.not_enabled");
        }
        if (catalogUnsupported) {
            return Component.translatable("vmfashion.asset.status.unsupported");
        }
        MutableComponent status = Component.translatable("vmfashion.asset.status.active",
                installed.size(), humanBytes(cachedBytes.get()));
        int pending = pendingCount();
        if (pending > 0) {
            status.append(Component.translatable("vmfashion.asset.status.queue", pending));
        }
        int catalogSize = catalog == null ? 0 : catalog.size();
        if (catalogSize > 0) {
            status.append(Component.translatable("vmfashion.asset.status.catalog", catalogSize));
        }
        return status;
    }

    /** 已缓存的服务端包占用字节数，给诊断用（内存值，不做磁盘遍历）。 */
    public long cacheBytes() {
        return cachedBytes.get();
    }

    /** 在飞 + 排队中的下载数。 */
    public int pendingCount() {
        return queued.size();
    }

    // ==================== 队列驱动 ====================

    /**
     * 本地已装且与清单一致（或清单里还没有这个包）时返回 {@code true}。
     *
     * <p>M01 的判定入口：旧实现只看 {@code installed.containsKey}，于是"已装"等于"永远不动"，
     * 后端发新版本后全体老玩家静默停在旧包。现在队列出队、渲染请求、安装入口三处都走这里。
     */
    private boolean isUpToDate(String key) {
        InstalledPack local = installed.get(key);
        if (local == null) {
            return false;
        }
        PackCatalog currentCatalog = catalog;
        PackCatalog.PackInfo info = currentCatalog == null ? null : currentCatalog.find(key);
        if (info == null) {
            return true; // 清单里没有这个包：不动它，等清单刷新
        }
        return !PackUpdatePolicy.needsUpdate(local.version(), local.sha256(),
                info.version(), info.sha256());
    }

    private void startQueuedInstalls() {
        ExecutorService executor = ioExecutor;
        if (executor == null) {
            return;
        }
        while (inFlight.size() < MAX_CONCURRENT_INSTALLS) {
            String key = queue.poll();
            if (key == null) {
                return;
            }
            if (!queued.contains(key) || userPacks.contains(key) || isUpToDate(key)) {
                queued.remove(key);
                continue;
            }
            inFlight.add(key);
            long submittedEpoch = epoch;
            CompletableFuture.runAsync(() -> installPack(key, submittedEpoch), executor)
                    .whenComplete((ignored, error) -> {
                        inFlight.remove(key);
                        queued.remove(key);
                        if (error != null) {
                            VMFashionClient.LOGGER.debug("时装包安装任务异常 {}: {}", key, error.toString());
                        }
                    });
        }
    }

    /**
     * 下载 → 安全解压 → 校验 → 原子安装。全程在 worker 线程。
     *
     * <p>任何一步失败都只影响这一个包：清理临时目录 + 设置冷却，绝不抛给调用方。
     */
    private void installPack(String key, long submittedEpoch) {
        PackCatalog currentCatalog = catalog;
        PackDownloader currentDownloader = downloader;
        ModConfig currentConfig = config;
        if (currentCatalog == null || currentDownloader == null || currentConfig == null) {
            return;
        }
        Path tempDir = null;
        String packId = key;
        try {
            if (submittedEpoch != epoch) {
                return;
            }
            if (userPacks.contains(key)) {
                return;
            }
            PackCatalog.PackInfo info = currentCatalog.find(key);
            if (info == null) {
                // 清单还没拉到 / 还没有这个包：冷却一小会儿，等清单刷新
                catalogMisses.add(key);
                failedUntil.put(key, System.currentTimeMillis() + CATALOG_MISS_RETRY_MS);
                return;
            }
            packId = info.id();

            // 用户包优先：磁盘上有同名用户包时永不下载（内存集合可能过期，这里以磁盘为准）
            Path userDir = PackLoader.packsDir().resolve(info.id());
            if (Files.exists(userDir)) {
                userPacks.add(key);
                VMFashionClient.LOGGER.debug("本地已有同名用户包 {}，跳过服务端下发", info.id());
                return;
            }
            InstalledPack existing = installed.get(key);
            if (existing != null && !PackUpdatePolicy.needsUpdate(existing.version(), existing.sha256(),
                    info.version(), info.sha256())) {
                return; // 已是最新（M01：只有真的过期才重装）
            }

            Path serverRoot = PackLoader.serverPacksDir();
            Files.createDirectories(serverRoot);
            tempDir = serverRoot.resolve(TEMP_PREFIX + UUID.randomUUID());
            Files.createDirectories(tempDir);

            Path zip = tempDir.resolve("pack.zip");
            inFlightSince.put(key, System.currentTimeMillis());
            CompletableFuture<PackDownloader.DownloadResult> future = currentDownloader.download(
                    info.url(), currentConfig.getApiBaseUrl(), currentToken(), zip,
                    info.sha256(), info.bytes(), SafeZip.Limits.defaults().maxArchiveBytes(),
                    () -> submittedEpoch != epoch,
                    stream -> {
                        // 登记 / 注销正在读的流：看门狗靠 close 它来解阻塞永久 read
                        if (stream == null) {
                            activeStreams.remove(key);
                        } else {
                            activeStreams.put(key, stream);
                        }
                    });
            PackDownloader.DownloadResult download;
            try {
                // worker 线程阻塞等待是安全的，但必须带超时：否则 body 卡住时这里永远返回不了。
                // 超时后 finally 会删临时目录，看门狗负责释放 inFlight。
                download = future.get(INSTALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                throw new PackDownloader.DownloadException(PackDownloader.FailureReason.IO,
                        "下载超过 " + (INSTALL_TIMEOUT_MS / 60_000L) + " 分钟未完成");
            } catch (java.util.concurrent.ExecutionException execution) {
                Throwable cause = execution.getCause() == null ? execution : execution.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof java.io.IOException io) {
                    throw io;
                }
                throw new PackDownloader.DownloadException(PackDownloader.FailureReason.IO,
                        cause.getMessage() == null ? "下载失败" : cause.getMessage());
            } finally {
                activeStreams.remove(key);
            }
            if (submittedEpoch != epoch) {
                return;
            }

            // 解压前验签：签名覆盖 (packId, version, sha256)，而 sha256 刚在下载时校验过，
            // 所以这里通过 = "内容确实由受信密钥签发"。验签失败绝不落盘解压。
            PackSignature.Decision decision = verifySignature(info);
            if (!decision.allowed()) {
                rejectedBySignature.add(key);
                failedUntil.remove(key);
                VMFashionClient.LOGGER.warn("拒绝安装时装包 {}：{}（本会话不再重试）",
                        info.id(), describeSignatureDecision(decision));
                return;
            }

            Path unzipped = tempDir.resolve("unzipped");
            SafeZip.ExtractionResult extraction = SafeZip.extract(zip, unzipped, SafeZip.Limits.defaults());
            if (submittedEpoch != epoch) {
                // 解压期间玩家离服 / 清了缓存：不要再往磁盘上装
                return;
            }

            // 校验包里声明的 id 与清单一致：防止"清单说 A、包里其实是 B"的替换攻击
            String manifestId = readManifestId(unzipped);
            if (manifestId == null || !manifestId.equalsIgnoreCase(info.id())) {
                throw new SafeZip.SafeZipException(SafeZip.Reason.CORRUPT,
                        "pack.json 的 id 与清单不一致: " + manifestId + " != " + info.id());
            }

            Path target = serverRoot.resolve(info.id());
            // 目录可能已经存在：① 本地是旧版本（M01：要被替换）；② 上次崩溃留下的半成品；
            // ③ 极端的并发安装。做法统一成「先整体改名让位 → 装新的 → 成功后删旧的」，
            // 任一步失败都把旧目录移回去，绝不出现"新包没装好、旧包也没了"。
            Path backup = null;
            if (Files.exists(target)) {
                backup = serverRoot.resolve(TEMP_PREFIX + "old-" + UUID.randomUUID());
                moveIntoPlace(target, backup);
                VMFashionClient.LOGGER.debug("服务端包目录已存在，先移开再装新版本: {} → {}",
                        info.id(), backup.getFileName());
            }
            long now = System.currentTimeMillis();
            long bytes = extraction.totalBytes();
            long previousBytes = existing != null ? existing.bytes() : 0L;
            try {
                moveIntoPlace(unzipped, target);
                writeMarker(target, info, download.sha256(), bytes, now);
            } catch (Throwable installFailure) {
                // 目录可能已经移到位但 marker 写失败：删掉半成品，把旧目录移回去
                deleteRecursively(target);
                if (backup != null) {
                    try {
                        moveIntoPlace(backup, target);
                    } catch (IOException restoreFailure) {
                        VMFashionClient.LOGGER.warn("时装包 {} 回滚失败，旧版本已丢失（下次会重新下载）: {}",
                                info.id(), restoreFailure.toString());
                    }
                }
                throw installFailure;
            }
            if (backup != null) {
                deleteRecursively(backup);
            }
            installed.put(key, new InstalledPack(info.id(), info.version(), download.sha256(), bytes, now, now));
            // 替换旧版本时先减掉旧体积，避免缓存统计虚高、触发不必要的 LRU 淘汰
            cachedBytes.addAndGet(bytes - previousBytes);
            recentlyUsed.add(key);
            failedUntil.remove(key);
            catalogMisses.remove(key);
            reloadPending.set(true);
            if (existing != null && !existing.version().equalsIgnoreCase(info.version())) {
                VMFashionClient.LOGGER.info("已更新时装包 {} v{} → v{}（{}，{} 个文件）",
                        info.id(), existing.version().isBlank() ? "?" : existing.version(),
                        info.version(), humanBytes(bytes), extraction.fileCount());
            } else {
                VMFashionClient.LOGGER.info("已下发时装包 {} v{}（{}，{} 个文件）",
                        info.id(), info.version(), humanBytes(bytes), extraction.fileCount());
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null
                    ? t.getCause() : t;
            boolean aborted = submittedEpoch != epoch
                    || (cause instanceof PackDownloader.DownloadException de
                            && de.reason() == PackDownloader.FailureReason.ABORTED);
            if (aborted) {
                // 玩家离服 / 清了缓存导致的取消：不是失败，绝不能设冷却（否则重进服后
                // 这些包会在 10 分钟内不再下发，时装"消失 10 分钟"）
                VMFashionClient.LOGGER.debug("下发时装包 {} 已取消（换服/清缓存）", packId);
                return;
            }
            failedUntil.put(key, System.currentTimeMillis() + FAILED_RETRY_MS);
            VMFashionClient.LOGGER.warn("下发时装包 {} 失败（{} 分钟后再试）: {}",
                    packId, FAILED_RETRY_MS / 60_000L, cause.toString());
        } finally {
            inFlightSince.remove(key);
            activeStreams.remove(key);
            if (tempDir != null) {
                deleteRecursively(tempDir);
            }
        }
    }

    // ==================== 清单 ====================

    private void refreshCatalogIfDue(boolean force) {
        PackCatalog currentCatalog = catalog;
        ExecutorService executor = ioExecutor;
        ModConfig currentConfig = config;
        if (currentCatalog == null || executor == null || currentConfig == null || catalogUnsupported) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force) {
            if (currentCatalog.isFresh(now)) {
                return;
            }
            if (now - lastCatalogAttemptAt < CATALOG_RETRY_MS) {
                return;
            }
        }
        if (!catalogInFlight.compareAndSet(false, true)) {
            return;
        }
        lastCatalogAttemptAt = now;
        String baseUrl = currentConfig.getApiBaseUrl();
        currentCatalog.fetch(http, baseUrl, currentToken(), executor).whenComplete((packs, error) -> {
            catalogInFlight.set(false);
            if (error != null) {
                Throwable cause = unwrap(error);
                if (cause instanceof FashionApi.ApiException api
                        && (api.status() == 404 || api.status() == 501 || api.status() == 405)) {
                    if (!catalogUnsupported) {
                        catalogUnsupported = true;
                        VMFashionClient.LOGGER.info(
                                "后端未实现 GET /packs（HTTP {}），本次会话内关闭时装包自动下发；"
                                        + "本地已装的包照常可用", api.status());
                    }
                    return;
                }
                VMFashionClient.LOGGER.debug("拉取时装包清单失败（{}ms 后重试）: {}",
                        CATALOG_RETRY_MS, cause.toString());
                return;
            }
            currentCatalog.store(packs, System.currentTimeMillis());
            // 清单更新后，之前"清单里没有这个包"的冷却立刻解除
            for (String miss : catalogMisses) {
                failedUntil.remove(miss);
                if (queued.add(miss)) {
                    queue.add(miss);
                }
            }
            catalogMisses.clear();
            // M01：已经装过的包如果清单里有新版本，立刻排队更新。
            // 旧实现只清 catalogMisses，从不 diff 版本 → 后端发新版本后老玩家永远停在旧包。
            int updates = 0;
            for (Map.Entry<String, PackCatalog.PackInfo> entry : packs.entrySet()) {
                String key = entry.getKey();
                InstalledPack local = installed.get(key);
                if (local == null || userPacks.contains(key)) {
                    continue;
                }
                PackCatalog.PackInfo info = entry.getValue();
                if (!PackUpdatePolicy.needsUpdate(local.version(), local.sha256(),
                        info.version(), info.sha256())) {
                    continue;
                }
                if (queued.add(key)) {
                    queue.add(key);
                    updates++;
                }
            }
            if (updates > 0) {
                VMFashionClient.LOGGER.info("发现 {} 个时装包有新版本，已排队更新", updates);
            }
            VMFashionClient.LOGGER.debug("时装包清单已更新：{} 个包", packs.size());
        });
    }

    // ==================== 磁盘扫描 / 安装细节 ====================

    /** 扫描磁盘，重建"已安装 / 用户包"内存视图，并清掉上次崩溃留下的临时目录。 */
    private void rescanDisk() {
        installed.clear();
        userPacks.clear();
        long total = 0L;
        Path packsRoot = PackLoader.packsDir();
        if (Files.isDirectory(packsRoot)) {
            try (Stream<Path> children = Files.list(packsRoot)) {
                for (Path dir : children.toList()) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    if (PackLoader.SERVER_DIR_NAME.equals(dir.getFileName().toString())) {
                        continue; // server/ 是自动下发命名空间，不是用户包
                    }
                    String id = readManifestId(dir);
                    if (id != null) {
                        userPacks.add(id.toLowerCase(Locale.ROOT));
                    }
                }
            } catch (IOException e) {
                VMFashionClient.LOGGER.debug("扫描用户时装包失败: {}", e.toString());
            }
        }
        Path serverRoot = PackLoader.serverPacksDir();
        if (Files.isDirectory(serverRoot)) {
            try (Stream<Path> children = Files.list(serverRoot)) {
                for (Path dir : children.toList()) {
                    String name = dir.getFileName().toString();
                    if (name.startsWith(TEMP_PREFIX)) {
                        // 上次退出时留下的临时解压目录：直接清掉
                        deleteRecursively(dir);
                        continue;
                    }
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    InstalledPack pack = readMarker(dir, name);
                    if (pack != null) {
                        installed.put(pack.packId().toLowerCase(Locale.ROOT), pack);
                        total += pack.bytes();
                    }
                }
            } catch (IOException e) {
                VMFashionClient.LOGGER.debug("扫描服务端时装包失败: {}", e.toString());
            }
        }
        cachedBytes.set(total);
    }

    /** 读包目录里的 {@code pack.json}，返回 {@code id}（缺失时回退目录名，与 PackLoader 一致）。 */
    private static String readManifestId(Path packDir) {
        Path manifest = packDir.resolve("pack.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            String text = Files.readString(manifest, StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(text).getAsJsonObject();
            if (object.has("id") && !object.get("id").isJsonNull()) {
                String id = object.get("id").getAsString().trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }
            return packDir.getFileName().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 读 {@code .sha256} 标记；缺失时按目录名 + 目录大小兜底（保证缓存统计可用）。 */
    private static InstalledPack readMarker(Path packDir, String fallbackId) {
        Path marker = packDir.resolve(MARKER_FILE);
        if (Files.isRegularFile(marker)) {
            try {
                String text = Files.readString(marker, StandardCharsets.UTF_8);
                JsonObject object = JsonParser.parseString(text).getAsJsonObject();
                String packId = optString(object, "packId", fallbackId);
                String version = optString(object, "version", "");
                String sha256 = optString(object, "sha256", "");
                long bytes = optLong(object, "bytes", -1L);
                long installedAt = optLong(object, "installedAt", 0L);
                long lastUsedAt = optLong(object, "lastUsedAt", installedAt);
                if (bytes < 0) {
                    bytes = directorySize(packDir);
                }
                return new InstalledPack(packId, version, sha256, bytes, installedAt, lastUsedAt);
            } catch (Exception e) {
                // 标记坏了不影响包本身可用，退回兜底值
            }
        }
        if (!Files.isRegularFile(packDir.resolve("pack.json"))) {
            return null; // 不是包目录
        }
        long size = directorySize(packDir);
        long modified = 0L;
        try {
            modified = Files.getLastModifiedTime(packDir).toMillis();
        } catch (IOException ignored) {
            // 拿不到时间就当 0（最久没用，优先被淘汰）
        }
        return new InstalledPack(fallbackId, "", "", size, modified, modified);
    }

    /**
     * 只更新 marker 里已有的 JSON 字段的 {@code lastUsedAt}（保留其它字段原样）。
     *
     * <p>失败不影响功能（LRU 精度略降），所以只吞异常。
     */
    private static void rewriteMarkerLastUsed(Path marker, long lastUsedAt) throws IOException {
        try {
            String text = Files.readString(marker, StandardCharsets.UTF_8);
            JsonObject object = JsonParser.parseString(text).getAsJsonObject();
            object.addProperty("lastUsedAt", lastUsedAt);
            Files.writeString(marker, object.toString(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // JSON 坏掉：不影响包可用性，忽略
        }
    }

    private static void writeMarker(Path packDir, PackCatalog.PackInfo info, String sha256,
                                    long bytes, long now) throws IOException {
        JsonObject marker = new JsonObject();
        marker.addProperty("packId", info.id());
        marker.addProperty("version", info.version());
        marker.addProperty("sha256", sha256);
        marker.addProperty("bytes", bytes);
        marker.addProperty("installedAt", now);
        marker.addProperty("lastUsedAt", now);
        Files.writeString(packDir.resolve(MARKER_FILE), marker.toString(), StandardCharsets.UTF_8);
    }

    /** 原子安装：同一文件系统内 rename；不支持原子移动时退回普通 move（目标不存在，仍是原子的）。 */
    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    // ==================== LRU / 容量 ====================

    /** 把"最近用过"的包的时间戳刷到磁盘（低频、在 worker 线程做 IO）。 */
    private void touchRecentlyUsedIfDue() {
        ExecutorService executor = ioExecutor;
        if (executor == null || recentlyUsed.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLruTouchAt < LRU_TOUCH_INTERVAL_MS) {
            return;
        }
        lastLruTouchAt = now;
        List<String> keys = new ArrayList<>(recentlyUsed);
        recentlyUsed.clear();
        executor.execute(() -> {
            Path serverRoot = PackLoader.serverPacksDir();
            for (String key : keys) {
                InstalledPack pack = installed.get(key);
                if (pack == null) {
                    continue;
                }
                Path marker = serverRoot.resolve(pack.packId()).resolve(MARKER_FILE);
                try {
                    if (Files.isRegularFile(marker)) {
                        Files.setLastModifiedTime(marker, FileTime.fromMillis(now));
                        // 关键：readMarker 优先读 JSON 里的 lastUsedAt，只刷 mtime 的话
                        // 跨重启 LRU 顺序会退回安装时间。这里把 JSON 的 lastUsedAt 也更新掉。
                        rewriteMarkerLastUsed(marker, now);
                    }
                } catch (IOException ignored) {
                    // 刷时间戳失败只影响 LRU 精度，不影响功能
                }
                installed.computeIfPresent(key, (k, value) ->
                        new InstalledPack(value.packId(), value.version(), value.sha256(),
                                value.bytes(), value.installedAt(), now));
            }
        });
    }

    /** 缓存超限时按"最久没用"淘汰（在 worker 线程做删除，避免卡主线程）。 */
    private void evictIfOverBudget() {
        ModConfig currentConfig = config;
        ExecutorService executor = ioExecutor;
        if (currentConfig == null || executor == null) {
            return;
        }
        long budgetMb = currentConfig.getMaxPackCacheMb();
        if (budgetMb <= 0) {
            return; // 0 = 不限制
        }
        long budget = budgetMb * 1024L * 1024L;
        if (cachedBytes.get() <= budget) {
            return;
        }
        if (!evicting.compareAndSet(false, true)) {
            return;
        }
        executor.execute(() -> {
            try {
                List<InstalledPack> candidates = new ArrayList<>(installed.values());
                candidates.sort(Comparator.comparingLong(InstalledPack::lastUsedAt));
                for (InstalledPack pack : candidates) {
                    if (cachedBytes.get() <= budget) {
                        break;
                    }
                    if (deleteRecursively(PackLoader.serverPacksDir().resolve(pack.packId()))) {
                        installed.remove(pack.packId().toLowerCase(Locale.ROOT));
                        cachedBytes.addAndGet(-pack.bytes());
                        reloadPending.set(true);
                        VMFashionClient.LOGGER.info("缓存超过 {}MB，淘汰最久未用的包 {}",
                                budgetMb, pack.packId());
                    }
                }
            } finally {
                evicting.set(false);
            }
        });
    }

    // ==================== 验签 ====================

    /**
     * 按信任库策略验证一个包。
     *
     * <p>三种情况：
     * <ul>
     *   <li>带签名且通过 → 安装；</li>
     *   <li>没有签名 → <b>默认放行 + 记一次 warn</b>（否则所有自建后端 / 老后端全部失效），
     *       严格模式（{@code trusted_keys.json} 里 {@code requireSignature: true}）下拒绝；</li>
     *   <li>签名无效 / keyId 未知 → 拒绝，且本会话不再重试。</li>
     * </ul>
     */
    private PackSignature.Decision verifySignature(PackCatalog.PackInfo info) {
        PackSignature.TrustStore trust = trustStore();
        PackSignature.Decision decision = PackSignature.evaluate(trust, info.id(), info.version(),
                info.sha256(), info.signature(), info.signingKeyId());
        if (decision == PackSignature.Decision.SIGNED_OK) {
            VMFashionClient.LOGGER.debug("时装包 {} 验签通过（keyId={}）",
                    info.id(), info.signingKeyId() == null || info.signingKeyId().isBlank()
                            ? "任意受信密钥" : info.signingKeyId());
        } else if (decision == PackSignature.Decision.UNKNOWN_KEY) {
            // 每个包只提示一次：内置公钥是占位值、或后端刚换密钥时会走到这里
            if (unknownKeyWarned.add(info.id())) {
                VMFashionClient.LOGGER.warn("时装包 {} 带签名，但没有任何受信公钥能验证它"
                        + "（内置公钥可能是占位值，或后端换了密钥）。宽松模式已放行；"
                        + "要强制校验，请把后端公钥写进 config/vmfashion/trusted_keys.json 并设置 \"requireSignature\": true",
                        info.id());
            }
        } else if (decision == PackSignature.Decision.UNSIGNED_ALLOWED
                && unsignedWarningLogged.compareAndSet(false, true)) {
            VMFashionClient.LOGGER.warn("时装包 {} 没有 Ed25519 签名，已按宽松模式放行（仅记这一次）。"
                    + "要强制校验，请在 config/vmfashion/trusted_keys.json 里设置 \"requireSignature\": true",
                    info.id());
        }
        return decision;
    }

    /** 把验签结论翻译成日志文案。 */
    private static String describeSignatureDecision(PackSignature.Decision decision) {
        return switch (decision) {
            case SIGNED_OK -> "验签通过";
            case UNSIGNED_ALLOWED -> "宽松模式放行（无签名）";
            case UNKNOWN_KEY -> "宽松模式放行（签名来自未知密钥，请把对应公钥加入 trusted_keys.json）";
            case UNSIGNED_REJECTED -> "严格模式要求签名，但清单里没有 signature";
            case BAD_SIGNATURE -> "签名无效（base64/长度/keyId/内容不符）";
        };
    }

    /** 信任库（带 1 分钟缓存；文件改了就重建，不用重启游戏）。 */
    private PackSignature.TrustStore trustStore() {
        long now = System.currentTimeMillis();
        PackSignature.TrustStore current = trustStore;
        if (current != null && now - trustStoreLoadedAt < TRUST_RELOAD_INTERVAL_MS) {
            return current;
        }
        synchronized (this) {
            current = trustStore;
            if (current != null && now - trustStoreLoadedAt < TRUST_RELOAD_INTERVAL_MS) {
                return current;
            }
            PackSignature.TrustStore loaded = PackSignature.TrustStore.load(trustedKeysFile(),
                    message -> VMFashionClient.LOGGER.info("{}", message));
            if (loaded.builtinOnly()) {
                VMFashionClient.LOGGER.debug("未配置 {}，使用内置占位公钥（仅校验带签名的包会失败）",
                        PackSignature.TRUST_FILE_NAME);
            }
            trustStore = loaded;
            trustStoreLoadedAt = now;
            return loaded;
        }
    }

    /** {@code config/vmfashion/trusted_keys.json}。 */
    private static Path trustedKeysFile() {
        try {
            return FabricLoader.getInstance().getConfigDir()
                    .resolve("vmfashion").resolve(PackSignature.TRUST_FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 主线程重载 ====================

    /**
     * 装好包后重载资源。
     *
     * <p>{@code CosmeticAssets.invalidate()} 会释放贴图、下次渲染重新上传，<b>必须有 GL 上下文</b>，
     * 所以整段必须回主线程（{@code Minecraft#execute}）。
     */
    private void reloadOnMainThread() {
        Runnable task = () -> {
            try {
                PackLoader.LoadResult result = PackLoader.reload();
                CosmeticAssets.invalidate();
                // 穿戴缓存里可能留着"解析不到"的负结果，一并清掉
                LoadoutService.getInstance().invalidate();
                VMFashionClient.LOGGER.info("时装包重载完成：{} 个包 / {} 件时装",
                        result.packs().size(), result.cosmeticCount());
            } catch (Throwable t) {
                VMFashionClient.LOGGER.warn("重载时装包失败: {}", t.toString());
            }
        };
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            task.run();
            return;
        }
        minecraft.execute(task);
    }

    // ==================== 工具 ====================

    /** 包清单缓存文件：{@code config/vmfashion/cache/pack-catalog.json}。 */
    private static Path catalogCacheFile() {
        try {
            return FabricLoader.getInstance().getConfigDir()
                    .resolve("vmfashion").resolve("cache").resolve("pack-catalog.json");
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前 token（同步层未启用 / 未登录时为 {@code null}）。 */
    private static String currentToken() {
        FashionApi api = SyncService.getInstance().api();
        return api == null ? null : api.token();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause
                && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.io.UncheckedIOException)) {
            cause = cause.getCause();
        }
        return cause == null ? throwable : cause;
    }

    private static long directorySize(Path dir) {
        long[] total = {0L};
        try {
            Files.walkFileTree(dir, Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 统计失败就用 0，不影响功能
        }
        return total[0];
    }

    /** 递归删除；成功返回 {@code true}。 */
    private static boolean deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (IOException e) {
            VMFashionClient.LOGGER.debug("删除失败 {}: {}", path, e.toString());
            return false;
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return (bytes / 1024L) + " KB";
        }
        if (bytes < 1024L * 1024L * 1024L) {
            // 保留一位小数，避免 "0 MB" 这种误导
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private static String optString(JsonObject object, String key, String fallback) {
        try {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                return fallback;
            }
            return object.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long optLong(JsonObject object, String key, long fallback) {
        try {
            if (!object.has(key) || object.get(key).isJsonNull()) {
                return fallback;
            }
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }
}
