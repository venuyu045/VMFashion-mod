package com.venus.vmfashion.config;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.render.CosmeticAssets;
import com.venus.vmfashion.sync.AssetSyncService;
import com.venus.vmfashion.sync.SyncService;

/**
 * 配置热更新：不重启游戏就能让 {@code config.json} 的改动生效。
 *
 * <p>为什么需要：{@code SyncService#initialize} 只在启动时跑一次，改 {@code apiBaseUrl}
 * 必须重启游戏，玩家体验很差（见 {@code 开发路线图.md} §4「配置热更新」）。
 *
 * <p>执行顺序有讲究：
 * <ol>
 *   <li>重新读文件（失败则保留旧配置）；</li>
 *   <li>重建同步层（换地址 / 换开关）；</li>
 *   <li>重扫时装包 + 清渲染缓存（包目录可能被用户改过）。</li>
 * </ol>
 * 所有调用都在主线程，可以安全触碰 {@code CosmeticAssets}（贴图上传需要 GL 上下文）。
 */
public final class ConfigReloader {

    private ConfigReloader() {
    }

    /**
     * 重新加载配置并应用。
     *
     * @return 时装包数量（用于命令输出）
     */
    public static int reload() {
        VMFashionClient client = VMFashionClient.getInstance();
        if (client == null) {
            return 0;
        }
        ModConfig fresh = ModConfig.load();
        client.replaceConfig(fresh);

        SyncService.getInstance().initialize(fresh);
        AssetSyncService.getInstance().initialize(fresh);

        PackLoader.LoadResult result = PackLoader.reload();
        LoadoutService.getInstance().invalidate();
        CosmeticAssets.invalidate();
        return result.cosmeticCount();
    }
}
