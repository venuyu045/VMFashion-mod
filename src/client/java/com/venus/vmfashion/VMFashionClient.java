package com.venus.vmfashion;

import com.venus.vmfashion.command.FashionCommand;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.LoadoutStore;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.gui.KeyBindings;
import com.venus.vmfashion.render.CosmeticAssets;
import com.venus.vmfashion.resource.ClientResourceReloader;
import com.venus.vmfashion.resource.DefaultPackInstaller;
import com.venus.vmfashion.sync.AssetSyncService;
import com.venus.vmfashion.sync.SyncService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Venus Mc Fashion - 客户端模组入口
 *
 * <p>启动顺序：配置 → 释放内置示例包 → 加载本地穿戴 → 扫描时装包 → 同步层 → 按键/命令。
 * 渲染资源（BakedGeoModel / 贴图）是懒加载的，第一次真正渲染时才烘焙。
 */
public class VMFashionClient implements ClientModInitializer {

    public static final String MOD_ID = "vmfashion";
    public static final String MOD_NAME = "Venus Mc Fashion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static VMFashionClient instance;
    private volatile ModConfig config;

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("[{}] 正在初始化...", MOD_NAME);

        // 初始化配置（缺失字段自动用默认值，兼容旧 config.json）
        config = ModConfig.load();

        // 首次启动时把 jar 里的示例时装包释放到 config/vmfashion/packs/example/
        DefaultPackInstaller.installIfMissing();

        // 加载时装包（config/vmfashion/packs/）
        PackLoader.LoadResult loadResult = PackLoader.reload();
        LOGGER.info("已加载 {} 个时装包，共 {} 件时装", loadResult.packs().size(), loadResult.cosmeticCount());
        for (String warning : loadResult.warnings()) {
            LOGGER.warn("时装包: {}", warning);
        }

        // 本地穿戴持久化：先读磁盘（重启不丢），随后进服时若云端有数据会覆盖它
        LoadoutStore.loadInto(LocalLoadout.getInstance());

        // 同步层：配置了后端地址才启用；否则保持纯本地（provider = LocalOnlyProvider）
        CosmeticAssets.invalidate();
        SyncService.getInstance().initialize(config);
        AssetSyncService.getInstance().initialize(config);

        // 进服时认证并刷新远端缓存，离服时清缓存
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> SyncService.getInstance().onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> SyncService.getInstance().onDisconnect());

        // 每 tick：快捷键、周期性刷新、资产下发队列
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            KeyBindings.tick();
            SyncService.getInstance().tick();
            AssetSyncService.getInstance().tick();
        });

        // 客户端命令 /vmfashion + 快捷键
        FashionCommand.register();
        KeyBindings.register();

        // F3+T 资源重载也刷新时装缓存（路线图 §10 #16）
        ClientResourceReloader.register();

        // 注册关闭事件，保存配置与本地穿戴
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> saveAll());

        LOGGER.info("[{}] 初始化完成！", MOD_NAME);
    }

    /**
     * 保存所有数据
     */
    public void saveAll() {
        LOGGER.info("[{}] 正在保存数据...", MOD_NAME);
        config.save();
        LoadoutStore.saveNow();
        LOGGER.info("[{}] 数据保存完成", MOD_NAME);
    }

    /**
     * 获取模组配置
     */
    public ModConfig getConfig() {
        return config;
    }

    /** 配置热更新时整体替换（{@code /vmfashion config reload}）。 */
    public void replaceConfig(ModConfig newConfig) {
        if (newConfig != null) {
            this.config = newConfig;
        }
    }

    /**
     * 获取模组实例
     */
    public static VMFashionClient getInstance() {
        return instance;
    }
}
