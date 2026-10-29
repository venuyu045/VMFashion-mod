package com.venus.vmfashion.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.loadout.LoadoutProvider;
import com.venus.vmfashion.loadout.RequestBatching;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过 VMTools 后端解析其他玩家的穿戴。
 *
 * <p>热路径设计（对齐后端 {@code /loadouts/resolve}）：
 * <ul>
 *   <li>本地缓存 5 分钟，未命中才发请求；</li>
 *   <li>一次请求最多 {@value #MAX_BATCH} 个 UUID，超出部分<b>分块串行提交再合并</b>
 *       （旧实现直接丢弃剩余 UUID，玩家多了以后后半批人永远看不到时装）；</li>
 *   <li>只拿 {@code slot → cosmeticId}，资产由别处单独下载（后端明确不在 resolve 里内联资产）；</li>
 *   <li>token 中途过期（401）时通过注入的 {@link ReauthHook} 重新认证一次并重放本批，
 *       而不是像以前那样只打一条 warn 让别人身上的时装永久消失；</li>
 *   <li>任何失败都退化为"没有数据"，绝不影响渲染线程。</li>
 * </ul>
 */
public final class HttpLoadoutProvider implements LoadoutProvider {

    /** 后端建议 5s、客户端 5 分钟；这里取 5 分钟，玩家换装会在下一次刷新时体现。 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    /** 后端单次请求接受的 UUID 上限。 */
    private static final int MAX_BATCH = 200;

    private final FashionApi api;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private volatile ReauthHook reauthHook;

    /**
     * 401 时的重认证钩子。
     *
     * <p>为什么不直接调 {@code SyncService}：{@code SyncService} 已经依赖本类（持有 provider），
     * 反向依赖会形成循环，也让本类无法单独测试。钩子由 {@code SyncService} 在初始化时注入。
     */
    public interface ReauthHook {

        /**
         * 触发一次重新认证。
         *
         * @return 完成时 {@code true} 表示已拿到可用的新 token，调用方可以重放请求
         */
        CompletableFuture<Boolean> reauthenticate();
    }

    private record Entry(Map<String, String> loadout, long fetchedAt) {

        boolean fresh(long now) {
            return now - fetchedAt < CACHE_TTL_MS;
        }
    }

    public HttpLoadoutProvider(FashionApi api) {
        this.api = api;
    }

    /** 注入 401 重认证钩子（由 {@code SyncService} 提供）。 */
    public void setReauthHook(ReauthHook hook) {
        this.reauthHook = hook;
    }

    @Override
    public CompletableFuture<Map<UUID, Map<String, String>>> resolve(Collection<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long now = System.currentTimeMillis();

        Map<UUID, Map<String, String>> result = new ConcurrentHashMap<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID uuid : uuids) {
            if (uuid == null) {
                continue;
            }
            Entry entry = cache.get(uuid);
            if (entry != null && entry.fresh(now)) {
                result.put(uuid, entry.loadout());
            } else {
                missing.add(uuid);
            }
        }
        if (missing.isEmpty()) {
            return CompletableFuture.completedFuture(result);
        }

        List<List<UUID>> batches = RequestBatching.chunk(missing, MAX_BATCH);
        // 串行提交：分块数量通常很少（1~3），串行比并发更容易控制后端压力，也避免
        // 一次刷新同时打几十个请求把弱网彻底堵死
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<UUID> batch : batches) {
            chain = chain.thenCompose(ignored -> resolveBatch(batch, result, false));
        }
        return chain.thenApply(ignored -> result);
    }

    /** 提交一批（≤ {@value #MAX_BATCH}）UUID；单批失败只丢这一批，不影响其它批。 */
    private CompletableFuture<Void> resolveBatch(List<UUID> batch,
                                                 Map<UUID, Map<String, String>> result,
                                                 boolean replayed) {
        List<String> requestUuids = new ArrayList<>(batch.size());
        for (UUID uuid : batch) {
            requestUuids.add(uuid.toString());
        }
        return api.resolve(requestUuids)
                .handle((response, throwable) -> {
                    if (throwable == null) {
                        try {
                            parse(response, batch, result);
                        } catch (Exception e) {
                            VMFashionClient.LOGGER.warn("解析穿戴响应失败: {}", e.toString());
                        }
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    Throwable cause = unwrap(throwable);
                    if (!replayed && isUnauthorized(cause)) {
                        // token 中途过期：重认证一次后重放本批。replayed 保证每批最多重放一次，
                        // 否则 token 一直无效会变成无限重试风暴。
                        return reauthenticateAndReplay(batch, result);
                    }
                    VMFashionClient.LOGGER.warn("解析玩家穿戴失败: {}", cause.toString());
                    return CompletableFuture.<Void>completedFuture(null);
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<Void> reauthenticateAndReplay(List<UUID> batch,
                                                            Map<UUID, Map<String, String>> result) {
        ReauthHook hook = reauthHook;
        if (hook == null) {
            VMFashionClient.LOGGER.warn("穿戴查询返回 401，但没有可用的重认证钩子，本批跳过");
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Boolean> reauthenticate;
        try {
            reauthenticate = hook.reauthenticate();
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("触发重认证失败: {}", t.toString());
            return CompletableFuture.completedFuture(null);
        }
        if (reauthenticate == null) {
            return CompletableFuture.completedFuture(null);
        }
        return reauthenticate
                .handle((ok, error) -> error == null && Boolean.TRUE.equals(ok))
                .thenCompose(ok -> ok
                        ? resolveBatch(batch, result, true)
                        : CompletableFuture.<Void>completedFuture(null));
    }

    private void parse(JsonObject response, List<UUID> requested, Map<UUID, Map<String, String>> result) {
        long now = System.currentTimeMillis();
        Map<String, UUID> byKey = new HashMap<>();
        for (UUID uuid : requested) {
            byKey.put(uuid.toString().toLowerCase(Locale.ROOT), uuid);
        }

        if (response.has("loadouts") && response.get("loadouts").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : response.getAsJsonObject("loadouts").entrySet()) {
                UUID uuid = byKey.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (uuid == null) {
                    continue;
                }
                try {
                    Map<String, String> loadout = parseItems(entry.getValue());
                    cache.put(uuid, new Entry(loadout, now));
                    result.put(uuid, loadout);
                } catch (Exception e) {
                    VMFashionClient.LOGGER.debug("跳过无法解析的穿戴条目 {}: {}", uuid, e.toString());
                }
            }
        }

        if (response.has("missing") && response.get("missing").isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray("missing")) {
                try {
                    UUID uuid = byKey.get(element.getAsString().toLowerCase(Locale.ROOT));
                    if (uuid != null) {
                        // 未认证的玩家当作"没有穿戴"，同样缓存，避免每帧重复请求
                        Map<String, String> empty = Map.of();
                        cache.put(uuid, new Entry(empty, now));
                        result.put(uuid, empty);
                    }
                } catch (Exception ignored) {
                    // 忽略非法条目
                }
            }
        }
    }

    private static Map<String, String> parseItems(JsonElement element) {
        Map<String, String> loadout = new LinkedHashMap<>();
        if (element == null || !element.isJsonObject()) {
            return loadout;
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("items") || !object.get("items").isJsonArray()) {
            return loadout;
        }
        JsonArray items = object.getAsJsonArray("items");
        for (JsonElement item : items) {
            if (!item.isJsonObject()) {
                continue;
            }
            try {
                JsonObject entry = item.getAsJsonObject();
                String slot = optString(entry, "slot", "");
                String cosmeticId = optString(entry, "cosmeticId", "");
                boolean missing = entry.has("missing") && !entry.get("missing").isJsonNull()
                        && entry.get("missing").getAsBoolean();
                if (!slot.isBlank() && !cosmeticId.isBlank() && !missing) {
                    loadout.put(slot.toLowerCase(Locale.ROOT), cosmeticId);
                }
            } catch (Exception ignored) {
                // 单条脏数据不影响同批其他玩家
            }
        }
        return loadout;
    }

    /**
     * 把本地穿戴上报到后端（全量覆盖，没出现的槽位会被卸下）。
     *
     * @param loadout 分类 key → 时装 ID
     */
    public CompletableFuture<Void> upload(Map<String, String> loadout) {
        JsonObject body = new JsonObject();
        JsonArray items = new JsonArray();
        if (loadout != null) {
            loadout.forEach((slot, cosmeticId) -> {
                if (slot == null || cosmeticId == null || slot.isBlank() || cosmeticId.isBlank()) {
                    return;
                }
                JsonObject item = new JsonObject();
                item.addProperty("slot", slot);
                item.addProperty("cosmeticId", cosmeticId);
                // 刻意不传 scale：mod 当前没有本地缩放状态，交给后端保留网页衣柜里
                // 已保存的 scale，避免每次换装把网页设置全部重置为 1.0。
                items.add(item);
            });
        }
        body.add("items", items);
        return api.putLoadout(body).thenAccept(response -> {
            VMFashionClient.LOGGER.debug("穿戴已上报: {}", items.size());
            invalidateAll();
        });
    }

    /** 清空缓存（换服 / 重新登录时调用）。 */
    public void invalidateAll() {
        cache.clear();
    }

    public void invalidate(UUID uuid) {
        if (uuid != null) {
            cache.remove(uuid);
        }
    }

    private static String optString(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 异常链里是否 401（token 失效）。 */
    private static boolean isUnauthorized(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null) {
            if (cause instanceof FashionApi.ApiException api && api.status() == 401) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /** 剥掉 CompletableFuture 包的壳，拿到真正的业务异常。 */
    private static Throwable unwrap(Throwable throwable) {
        Throwable cause = throwable;
        while (cause != null && cause.getCause() != null && cause.getCause() != cause
                && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        return cause == null ? throwable : cause;
    }
}
