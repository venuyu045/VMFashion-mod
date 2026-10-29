package com.venus.vmfashion.loadout;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.LocalLoadout;
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

    private static final LoadoutService INSTANCE = new LoadoutService();

    private volatile LoadoutProvider provider = new LocalOnlyProvider();
    private volatile boolean fallbackToLocal = true;

    private final Map<UUID, Map<String, String>> remoteLoadouts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> remoteFetchedAt = new ConcurrentHashMap<>();
    private final Map<String, CosmeticDefinition> resolved = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRequests = ConcurrentHashMap.newKeySet();
    /** 本帧发现、等下一次节流窗口批量提交的玩家 UUID（避免一帧一个请求）。 */
    private final Set<UUID> queuedRequests = ConcurrentHashMap.newKeySet();

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
        this.provider = provider == null ? new LocalOnlyProvider() : provider;
        this.remoteLoadouts.clear();
        this.remoteFetchedAt.clear();
        this.pendingRequests.clear();
        this.queuedRequests.clear();
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
        }
        return definition;
    }

    /** 时装包重载后清空所有缓存。 */
    public void invalidate() {
        resolved.clear();
        remoteLoadouts.clear();
        remoteFetchedAt.clear();
        pendingRequests.clear();
        queuedRequests.clear();
    }

    // ==================== 异步同步 ====================

    /**
     * 触发一次远端查询（节流）。结果到达后写入缓存，下一帧渲染即可见。
     */
    public void requestRemote(Collection<UUID> uuids) {
        if (uuids != null) {
            for (UUID uuid : uuids) {
                if (uuid != null && !pendingRequests.contains(uuid)) {
                    queuedRequests.add(uuid);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastRequestAt < REMOTE_REQUEST_INTERVAL_MS) {
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
            provider.resolve(wanted).whenComplete((result, error) -> {
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
