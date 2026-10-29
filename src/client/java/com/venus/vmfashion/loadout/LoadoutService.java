package com.venus.vmfashion.loadout;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.sync.AssetSyncService;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 渲染层与数据层之间的唯一入口。
 *
 * <p>职责：
 * <ul>
 *   <li>本地玩家直接读 {@link LocalLoadout}（零延迟，穿上立刻可见）；</li>
 *   <li>其他玩家先查 {@link LoadoutProvider} 的异步缓存，没有数据时回退到本地穿戴，
 *       保证在同步层接入前也能看到别人身上的时装；</li>
 *   <li>缓存 ID → {@link CosmeticDefinition} 的解析结果，避免每帧做线性搜索。</li>
 * </ul>
 */
public final class LoadoutService {

    private static final long REMOTE_REQUEST_INTERVAL_MS = 1000L;
    /** 远端穿戴缓存时长；到期后先返回旧值并后台刷新，避免每次渲染都查网络。 */
    private static final long REMOTE_CACHE_TTL_MS = 5 * 60 * 1000L;
    /** 同一个解析不到的时装 ID，最短多久才再次请求资产下发（渲染热路径上每帧都会查一次）。 */
    private static final long MISSING_ASSET_RETRY_MS = 60_000L;
    /** 解析失败 ID 的请求记录上限，防止脏数据把内存撑大。 */
    private static final int MAX_MISSING_ASSET_ENTRIES = 512;

    private static final LoadoutService INSTANCE = new LoadoutService();

    private volatile LoadoutProvider provider = new LocalOnlyProvider();
    private volatile boolean fallbackToLocal = true;

    private final Map<UUID, Map<String, String>> remoteLoadouts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> remoteFetchedAt = new ConcurrentHashMap<>();
    private final Map<String, CosmeticDefinition> resolved = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRequests = ConcurrentHashMap.newKeySet();
    /** 本帧发现、等下一次节流窗口批量提交的玩家 UUID（避免一帧一个请求）。 */
    private final Set<UUID> queuedRequests = ConcurrentHashMap.newKeySet();
    /** 最近一次请求资产下发的时间戳，按时装 ID 记录（见 {@link #MISSING_ASSET_RETRY_MS}）。 */
    private final Map<String, Long> missingAssetRequests = new ConcurrentHashMap<>();

    /**
     * 缓存代次：换服 / 换 provider / 重载时 +1。
     *
     * <p>在飞的 {@link LoadoutProvider#resolve} 无法取消（{@code CompletableFuture} 没有强取消语义，
     * 底层 HTTP 请求照样跑完），所以回调里必须比对代次：旧代次的结果直接丢弃，
     * 绝不能写回新服务器的缓存 —— 否则上个服的时装会出现在新服的人身上。
     */
    private final AtomicLong generation = new AtomicLong();

    private volatile long lastRequestAt;

    private LoadoutService() {
    }

    public static LoadoutService getInstance() {
        return INSTANCE;
    }

    // ==================== Provider ====================

    public LoadoutProvider provider() {
        return provider;
    }

    /** 后端接入后调用这里替换实现（例如 {@code new HttpLoadoutProvider(...)}）。 */
    public void setProvider(LoadoutProvider provider) {
        // 先换代次再换 provider：所有在飞响应在回调里都会被判定为旧代次并丢弃
        generation.incrementAndGet();
        this.provider = provider == null ? new LocalOnlyProvider() : provider;
        this.remoteLoadouts.clear();
        this.remoteFetchedAt.clear();
        this.pendingRequests.clear();
        this.queuedRequests.clear();
    }

    /** 当前缓存代次（诊断 / 单测用）。 */
    public long generation() {
        return generation.get();
    }

    /** 没有远端数据时是否回退到本地穿戴（同步层接入后可关掉）。 */
    public boolean isFallbackToLocal() {
        return fallbackToLocal;
    }

    public void setFallbackToLocal(boolean fallbackToLocal) {
        this.fallbackToLocal = fallbackToLocal;
    }

    // ==================== 查询 ====================

    /** 是否为本地玩家（第三人称里的"自己"）。 */
    public boolean isLocalPlayer(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.player != null && uuid.equals(minecraft.player.getUUID());
    }

    /**
     * 取某个玩家当前穿戴（分类 → 时装 ID）。渲染线程调用，绝不阻塞。
     */
    public Map<String, String> loadoutFor(UUID uuid) {
        if (uuid == null) {
            return Map.of();
        }
        if (isLocalPlayer(uuid)) {
            return LocalLoadout.getInstance().snapshot();
        }
        Map<String, String> remote = remoteLoadouts.get(uuid);
        if (remote != null) {
            Long fetchedAt = remoteFetchedAt.get(uuid);
            if (fetchedAt == null || System.currentTimeMillis() - fetchedAt >= REMOTE_CACHE_TTL_MS) {
                // 过期：返回旧值保证本帧有内容，同时触发后台刷新
                requestRemote(List.of(uuid));
            }
            return remote;
        }
        requestRemote(List.of(uuid));
        return fallbackToLocal ? LocalLoadout.getInstance().snapshot() : Map.of();
    }

    /** 某个玩家身上所有时装要隐藏的原版部件并集。 */
    public Set<String> hiddenParts(UUID uuid) {
        Map<String, String> loadout = loadoutFor(uuid);
        if (loadout.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String id : loadout.values()) {
            CosmeticDefinition definition = resolve(id);
            if (definition != null) {
                result.addAll(definition.hideParts());
            }
        }
        return result;
    }

    /** ID → 定义，带缓存；时装包重载后调用 {@link #invalidate()}。 */
    public CosmeticDefinition resolve(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        CosmeticDefinition cached = resolved.get(id);
        if (cached != null) {
            return cached;
        }
        CosmeticDefinition definition = CosmeticRegistry.getInstance().find(id);
        if (definition != null) {
            resolved.put(id, definition);
            return definition;
        }
        // 本地解析不到：可能是"别人穿了服务端下发的包，而我没装"。请求资产下发，
        // 装好之后 PackLoader.reload() + invalidate() 会让下一帧解析成功。
        requestMissingAsset(id);
        return null;
    }

    /**
     * 请求资产下发（同一 ID 有节流）。
     *
     * <p>调用点在渲染热路径上（每帧、每个玩家、每个槽位），所以这里只做一次
     * {@code ConcurrentHashMap} 查询 + 写入，失败也不能影响渲染。
     */
    private void requestMissingAsset(String id) {
        long now = System.currentTimeMillis();
        Long last = missingAssetRequests.get(id);
        if (last != null && now - last < MISSING_ASSET_RETRY_MS) {
            return;
        }
        if (missingAssetRequests.size() >= MAX_MISSING_ASSET_ENTRIES) {
            // 脏数据（大量不存在的 ID）时整表清掉，比按 LRU 淘汰更省事
            missingAssetRequests.clear();
        }
        missingAssetRequests.put(id, now);
        try {
            AssetSyncService.getInstance().requestMissing(id);
        } catch (Throwable t) {
            // 资产下发层还没接好 / 正在重载，都不该让渲染崩
            VMFashionClient.LOGGER.debug("请求资产下发失败 {}: {}", id, t.toString());
        }
    }

    /** 时装包重载 / 换服后清空所有缓存。 */
    public void invalidate() {
        // 先换代次：在飞响应回来后一律丢弃（换服竞态的根因）
        generation.incrementAndGet();
        resolved.clear();
        remoteLoadouts.clear();
        remoteFetchedAt.clear();
        pendingRequests.clear();
        queuedRequests.clear();
        missingAssetRequests.clear();
    }

    // ==================== 异步同步 ====================

    /**
     * 触发一次远端查询（节流）。结果到达后写入缓存，下一帧渲染即可见。
     */
    public void requestRemote(Collection<UUID> uuids) {
        requestRemote(uuids, false);
    }

    /**
     * 触发一次远端查询。
     *
     * @param force {@code true} = 跳过 1 秒节流窗口立刻提交。给"定时刷新"用：
     *              节流是为了拦住渲染热路径的每帧请求，而周期刷新本来就低频，
     *              若被节流吞掉只能等下一次请求来冲刷队列，等于刷新被延后一个周期。
     */
    public void requestRemote(Collection<UUID> uuids, boolean force) {
        if (uuids != null) {
            for (UUID uuid : uuids) {
                if (uuid != null && !pendingRequests.contains(uuid)) {
                    queuedRequests.add(uuid);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastRequestAt < REMOTE_REQUEST_INTERVAL_MS) {
            return; // 本帧新发现的 UUID 已进队列，下个窗口一起批量提交
        }
        lastRequestAt = now;

        List<UUID> wanted = new ArrayList<>();
        for (UUID uuid : queuedRequests) {
            if (pendingRequests.add(uuid)) {
                wanted.add(uuid);
            }
        }
        queuedRequests.clear();
        if (wanted.isEmpty()) {
            return;
        }

        try {
            final long requestGeneration = generation.get();
            provider.resolve(wanted).whenComplete((result, error) -> {
                if (requestGeneration != generation.get()) {
                    // 换服 / 重载已发生：这份响应属于旧服务器，直接丢弃。
                    // 注意不能在这里动 pendingRequests —— invalidate() 已经把它清空了，
                    // 再删一次会误删新代次刚加进去的"正在请求"标记。
                    return;
                }
                pendingRequests.removeAll(wanted);
                if (error != null) {
                    VMFashionClient.LOGGER.warn("查询玩家穿戴失败: {}", error.toString());
                    return;
                }
                if (result == null || result.isEmpty()) {
                    return;
                }
                long fetchedAt = System.currentTimeMillis();
                result.forEach((uuid, loadout) -> {
                    if (uuid != null && loadout != null) {
                        // 空表也要缓存：代表"该玩家确认没有穿戴"，否则每帧都会重新请求
                        remoteLoadouts.put(uuid, new LinkedHashMap<>(loadout));
                        remoteFetchedAt.put(uuid, fetchedAt);
                    }
                });
            });
        } catch (Exception e) {
            pendingRequests.removeAll(wanted);
            VMFashionClient.LOGGER.warn("提交穿戴查询失败: {}", e.toString());
        }
    }
}
