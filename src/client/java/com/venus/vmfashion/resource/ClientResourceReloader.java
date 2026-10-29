package com.venus.vmfashion.resource;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.render.CosmeticAssets;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.SimpleReloadListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;

/**
 * 让 {@code F3+T}（原版资源重载）也能刷新时装资源。
 *
 * <p>为什么需要：{@code CosmeticAssets} 的缓存原本只在 {@code /vmfashion reload} 里作废，
 * 玩家改了贴图后按 F3+T 看不到变化，只能猜"是不是没生效"（见开发路线图 §10 #16）。
 *
 * <p>用的是 Fabric API 0.165 起的新资源重载 API（{@code fabric-resource-loader-v1}）：
 * 旧的 {@code ResourceManagerHelper#registerReloadListener} 整个接口已 {@code @Deprecated}。
 * {@link SimpleReloadListener#apply} 在主线程执行 —— 正是我们要的，因为
 * {@link CosmeticAssets#invalidate()} 会 {@code release} 动态贴图，需要 GL 上下文。
 *
 * <p>监听器只做"作废缓存 + 重扫磁盘包"，不做重新烘焙（烘焙仍懒加载到第一次渲染）。
 */
public final class ClientResourceReloader extends SimpleReloadListener<Void> {

    private static final Identifier ID = Identifier.fromNamespaceAndPath(VMFashionClient.MOD_ID, "cosmetics");

    private ClientResourceReloader() {
    }

    /** 在客户端初始化时注册（{@code VMFashionClient#onInitializeClient}）。 */
    public static void register() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(ID, new ClientResourceReloader());
    }

    @Override
    protected Void prepare(PreparableReloadListener.SharedState sharedState) {
        // 磁盘上的时装包不归资源包管，重载前不需要准备任何数据
        return null;
    }

    @Override
    protected void apply(Void unused, PreparableReloadListener.SharedState sharedState) {
        try {
            // 重载后模型/贴图缓存必须作废，否则玩家改了 packs/ 里的文件按 F3+T 看不到变化
            CosmeticAssets.invalidate();
            LoadoutService.getInstance().invalidate();
            PackLoader.LoadResult result = PackLoader.reload();
            VMFashionClient.LOGGER.info("资源重载：已刷新 {} 件时装", result.cosmeticCount());
        } catch (Throwable t) {
            // 资源重载失败绝不能把客户端带崩（原版会在重载后直接进游戏）
            VMFashionClient.LOGGER.warn("资源重载时刷新时装失败: {}", t.toString());
        }
    }
}
