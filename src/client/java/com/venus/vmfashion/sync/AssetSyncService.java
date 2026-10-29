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
import com.venus.vmfashion.net.SafeZip;
import com.venus.vmfashion.render.CosmeticAssets;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

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

    private final AtomicBoolean reloadPending = new AtomicBoolean(false);
    private final AtomicBoolean evicting = new AtomicBoolean(false);
    private final AtomicBoolean catalogInFlight = new AtomicBoolean(false);

    private volatile boolean catalogUnsupported;
    private volatile long lastCatalogAttemptAt;
    private volatile long lastLruTouchAt;
    /** 已安装包的总字节数（内存维护，{@link #cacheBytes()} 不 stat 磁盘）。 */
    private volatile long cachedBytes;
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
    }

    /** 进服：刷新 catalog，并预取自己穿戴涉及的包。 */
    public void onJoin() {
        if (!enabled) {
            return;
        }
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
        if (installed.containsKey(key) || userPacks.contains(key)) {
            // 已经能解析到：顺手记一下"最近用过"，供 LRU 淘汰（不在这里做磁盘 IO）
            recentlyUsed.add(key);
            return;
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

    // ==================== 状态 / 缓存管理 ====================

    /** 清空服务端下发的包缓存（{@code /vmfashion cache clear}）。 */
    public void clearCache() {
        epoch++;
        queue.clear();
        queued.clear();
        failedUntil.clear();
        catalogMisses.clear();
        recentlyUsed.clear();
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
        cachedBytes = 0L;
        // 包没了，注册表里还留着它们：让主线程下一 tick 重载
        reloadPending.set(true);
        VMFashionClient.LOGGER.info("已清理服务端时装包缓存：{} 个目录", removed);
    }

    /** 状态摘要，给 {@code /vmfashion status} 用。 */
    public String describeStatus() {
        ModConfig current = config;
        if (current == null) {
            return "资产下发：未初始化";
        }
        if (!current.isDownloadServerPacks()) {
            return "资产下发：已在配置中关闭（downloadServerPacks=false）";
        }
        if (current.getApiBaseUrl().isBlank()) {
            return "资产下发：未配置后端地址";
        }
        if (!enabled) {
            return "资产下发：未启用";
        }
        if (catalogUnsupported) {
            return "资产下发：后端未提供 /packs，已禁用（只能看到本地已装的包）";
        }
        StringBuilder builder = new StringBuilder("资产下发：")
                .append(installed.size()).append(" 个包 / ")
                .append(humanBytes(cachedBytes));
        int pending = pendingCount();
        if (pending > 0) {
            builder.append(" · 队列 ").append(pending);
        }
        int catalogSize = catalog == null ? 0 : catalog.size();
        if (catalogSize > 0) {
            builder.append(" · 清单 ").append(catalogSize);
        }
        return builder.toString();
    }

    /** 已缓存的服务端包占用字节数，给诊断用（内存值，不做磁盘遍历）。 */
    public long cacheBytes() {
        return cachedBytes;
    }

    /** 在飞 + 排队中的下载数。 */
    public int pendingCount() {
        return queued.size();
    }

    // ==================== 队列驱动 ====================

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
            if (!queued.contains(key) || installed.containsKey(key) || userPacks.contains(key)) {
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
            if (installed.containsKey(key) || userPacks.contains(key)) {
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
            if (existing != null && existing.sha256().equalsIgnoreCase(info.sha256())) {
                return; // 已是最新
            }

            Path serverRoot = PackLoader.serverPacksDir();
            Files.createDirectories(serverRoot);
            tempDir = serverRoot.resolve(TEMP_PREFIX + UUID.randomUUID());
            Files.createDirectories(tempDir);

            Path zip = tempDir.resolve("pack.zip");
            PackDownloader.DownloadResult download = currentDownloader.download(
                            info.url(), currentConfig.getApiBaseUrl(), currentToken(), zip,
                            info.sha256(), info.bytes(), SafeZip.Limits.defaults().maxArchiveBytes(),
                            () -> submittedEpoch != epoch)
                    .join(); // worker 线程阻塞等待是安全的
            if (submittedEpoch != epoch) {
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
            if (Files.exists(target)) {
                // 目标已存在（并发安装 / 玩家手动放的）：绝不覆盖
                VMFashionClient.LOGGER.debug("服务端包目录已存在，跳过安装: {}", info.id());
                return;
            }
            moveIntoPlace(unzipped, target);

            long now = System.currentTimeMillis();
            long bytes = extraction.totalBytes();
            writeMarker(target, info, download.sha256(), bytes, now);
            installed.put(key, new InstalledPack(info.id(), info.version(), download.sha256(), bytes, now, now));
            cachedBytes += bytes;
            recentlyUsed.add(key);
            failedUntil.remove(key);
            catalogMisses.remove(key);
            reloadPending.set(true);
            VMFashionClient.LOGGER.info("已下发时装包 {} v{}（{}，{} 个文件）",
                    info.id(), info.version(), humanBytes(bytes), extraction.fileCount());
        } catch (Throwable t) {
            Throwable cause = t instanceof java.util.concurrent.CompletionException && t.getCause() != null
                    ? t.getCause() : t;
            failedUntil.put(key, System.currentTimeMillis() + FAILED_RETRY_MS);
            VMFashionClient.LOGGER.warn("下发时装包 {} 失败（{} 分钟后再试）: {}",
                    packId, FAILED_RETRY_MS / 60_000L, cause.toString());
        } finally {
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
        cachedBytes = total;
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
        if (cachedBytes <= budget) {
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
                    if (cachedBytes <= budget) {
                        break;
                    }
                    if (deleteRecursively(PackLoader.serverPacksDir().resolve(pack.packId()))) {
                        installed.remove(pack.packId().toLowerCase(Locale.ROOT));
                        cachedBytes -= pack.bytes();
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
