package com.venus.vmfashion.loadout;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 没有后端时的占位实现：永远返回空表。
 *
 * <p>渲染层因此只会回退到本地穿戴（见 {@link LoadoutService}），
 * 这样即使同步层还没接，也能验证"本地玩家 / 其他玩家身上出现 3D 时装"的渲染链路。
 */
public final class LocalOnlyProvider implements LoadoutProvider {

    private static final CompletableFuture<Map<UUID, Map<String, WornCosmetic>>> EMPTY =
            CompletableFuture.completedFuture(Map.of());

    @Override
    public CompletableFuture<Map<UUID, Map<String, WornCosmetic>>> resolve(Collection<UUID> uuids) {
        return EMPTY;
    }
}
