package com.venus.vmfashion.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.loadout.LoadoutProvider;

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
 *   <li>本地缓存 5 分钟，未命中才发请求，且一次最多 {@value #MAX_BATCH} 个 UUID；</li>
 *   <li>只拿 {@code slot → cosmeticId}，资产由别处单独下载（后端明确不在 resolve 里内联资产）；</li>
 *   <li>任何失败都退化为"没有数据"，绝不影响渲染线程。</li>
 * </ul>
 */
public final class HttpLoadoutProvider implements LoadoutProvider {

    /** 后端建议 5s、客户端 5 分钟；这里取 5 分钟，玩家换装会在下一次刷新时体现。 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_BATCH = 200;

    private final FashionApi api;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();

    private record Entry(Map<String, String> loadout, long fetchedAt) {

        boolean fresh(long now) {
            return now - fetchedAt < CACHE_TTL_MS;
        }
    }

    public HttpLoadoutProvider(FashionApi api) {
        this.api = api;
    }

    @Override
    public CompletableFuture<Map<UUID, Map<String, String>>> resolve(Collection<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        long now = System.currentTimeMillis();

        Map<UUID, Map<String, String>> result = new HashMap<>();
        List<UUID> missing = new ArrayList<>();
        for (UUID uuid : uuids) {
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
        final List<UUID> requested = missing.size() > MAX_BATCH
                ? new ArrayList<>(missing.subList(0, MAX_BATCH))
                : missing;

        List<String> requestUuids = new ArrayList<>(requested.size());
        for (UUID uuid : requested) {
            requestUuids.add(uuid.toString());
        }

        return api.resolve(requestUuids).handle((response, throwable) -> {
            if (throwable != null) {
                VMFashionClient.LOGGER.warn("解析玩家穿戴失败: {}", throwable.toString());
                return result;
            }
            try {
                parse(response, requested, result);
            } catch (Exception e) {
                VMFashionClient.LOGGER.warn("解析穿戴响应失败: {}", e.toString());
            }
            return result;
        });
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
}
