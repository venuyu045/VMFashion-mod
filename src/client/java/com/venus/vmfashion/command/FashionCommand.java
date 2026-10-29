package com.venus.vmfashion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ConfigReloader;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticPack;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.gui.KeyBindings;
import com.venus.vmfashion.gui.WardrobeScreen;
import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.loadout.WornCosmetic;
import com.venus.vmfashion.net.FashionAuth;
import com.venus.vmfashion.render.CosmeticAssets;
import com.venus.vmfashion.sync.AssetSyncService;
import com.venus.vmfashion.sync.SyncService;
import com.venus.vmfashion.util.ChatUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 客户端命令 {@code /vmfashion ...}，只在本地执行，不会发给服务器。
 *
 * <p>i18n：玩家可见文案全部走 {@link Component#translatable}（key 前缀 {@code vmfashion.command.*}），
 * 颜色码写在语言文件的译文里（和原版 lang 的写法一致）；带参数的文案用 {@code %s} 占位，
 * 不在 Java 里拼字符串。日志（{@code VMFashionClient.LOGGER}）仍然用中文，那是开发者看的。
 */
public final class FashionCommand {

    private FashionCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register(FashionCommand::build);
    }

    private static void build(CommandDispatcher<FabricClientCommandSource> dispatcher,
                              CommandBuildContext registryAccess) {
        dispatcher.register(ClientCommands.literal("vmfashion")
                .then(ClientCommands.literal("reload").executes(ctx -> reload()))
                .then(ClientCommands.literal("list").executes(ctx -> list()))
                .then(ClientCommands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(ClientCommands.literal("debug").executes(ctx -> debug()))
                .then(ClientCommands.literal("login").executes(ctx -> login()))
                .then(ClientCommands.literal("sync").executes(ctx -> sync()))
                .then(ClientCommands.literal("bind").executes(ctx -> bind()))
                // 打开衣柜界面（gui 是 open 的别名，玩家更可能先猜 gui）
                .then(ClientCommands.literal("open").executes(ctx -> open(ctx.getSource())))
                .then(ClientCommands.literal("gui").executes(ctx -> open(ctx.getSource())))
                // 配置热更新：/vmfashion config reload（等价于旧的 /vmfashion reload，但会重读 config.json）
                .then(ClientCommands.literal("config")
                        .then(ClientCommands.literal("reload")
                                .executes(ctx -> configReload(ctx.getSource()))))
                // 清空服务端下发包的缓存
                .then(ClientCommands.literal("cache")
                        .then(ClientCommands.literal("clear")
                                .executes(ctx -> cacheClear(ctx.getSource()))))
                // 导出诊断信息到 config/vmfashion/diag/
                .then(ClientCommands.literal("diag").executes(ctx -> diag(ctx.getSource())))
                .then(ClientCommands.literal("equip")
                        .then(ClientCommands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> equip(StringArgumentType.getString(ctx, "id")))))
                .then(ClientCommands.literal("unequip")
                        .then(ClientCommands.argument("category", StringArgumentType.word())
                                .executes(ctx -> unequip(StringArgumentType.getString(ctx, "category"))))));
    }

    private static int reload() {
        PackLoader.LoadResult result = PackLoader.reload();
        // 包换了之后，烘焙好的模型 / 注册过的贴图 / ID 解析缓存都要作废
        LoadoutService.getInstance().invalidate();
        CosmeticAssets.invalidate();
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.reload_done",
                result.packs().size(), result.cosmeticCount()));
        for (String warning : result.warnings()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.reload_warning", warning));
        }
        return 1;
    }

    private static int list() {
        CosmeticRegistry registry = CosmeticRegistry.getInstance();
        if (registry.packs().isEmpty()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.list_empty"));
            return 0;
        }
        for (CosmeticPack pack : registry.packs()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.list_pack",
                    pack.name(), pack.id(), pack.version(), pack.cosmetics().size()));
            for (CosmeticDefinition cosmetic : pack.cosmetics()) {
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.list_cosmetic",
                        cosmetic.id(), cosmetic.category().key()));
            }
        }
        return 1;
    }

    /**
     * 状态总览：同步 / token 过期时间 / 资产下发 / 本地包与缓存占用 / 当前穿戴。
     *
     * <p>路线图 §10 #10：{@code Session.verified} 从未被使用，{@code /vmfashion status}
     * 也不显示 token 过期时间与资产状态 —— 这里补齐。
     */
    private static int status(FabricClientCommandSource source) {
        SyncService sync = SyncService.getInstance();
        source.sendFeedback(feedback("vmfashion.command.status.header"));
        source.sendFeedback(feedback("vmfashion.command.status.sync", safeComponent(sync::describeStatus)));
        source.sendFeedback(tokenLine(sync));

        // 资产行只留 describeStatus 本身：它已经带包数 / 占用 / 队列 / 清单，再拼一遍缓存与在飞就重复了
        AssetSyncService assets = AssetSyncService.getInstance();
        source.sendFeedback(feedback("vmfashion.command.status.assets",
                safeComponent(assets::describeStatus)));

        CosmeticRegistry registry = CosmeticRegistry.getInstance();
        source.sendFeedback(feedback("vmfashion.command.status.packs",
                registry.packs().size(),
                registry.size(),
                DiagExporter.humanBytes(packBytes())));

        Map<String, String> worn = LocalLoadout.getInstance().snapshot();
        if (worn.isEmpty()) {
            source.sendFeedback(feedback("vmfashion.command.status.loadout_empty"));
            return 1;
        }
        source.sendFeedback(feedback("vmfashion.command.status.worn_header"));
        worn.forEach((category, id) -> source.sendFeedback(
                feedback("vmfashion.command.status.worn", category, id)));
        return 1;
    }

    /** token 过期时间：没登录 / 已过期 / 剩余分钟数三种情况。 */
    private static Component tokenLine(SyncService sync) {
        FashionAuth auth = sync.auth();
        FashionAuth.Session session = auth == null ? null : auth.session();
        if (session == null) {
            return feedback("vmfashion.command.status.token_none");
        }
        if (session.expired()) {
            return feedback("vmfashion.command.status.token_expired");
        }
        long remainingMinutes = Math.max(0L, (session.expiresAt() - System.currentTimeMillis()) / 60_000L);
        return feedback("vmfashion.command.status.token_valid", remainingMinutes, session.authType());
    }

    /** 手动重新认证（换账号 / token 过期后）。 */
    private static int login() {
        SyncService.getInstance().authenticateAsync(true);
        return 1;
    }

    /** 手动把当前穿戴推送到后端。 */
    private static int sync() {
        if (!SyncService.getInstance().isEnabled()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.local_only"));
            return 0;
        }
        SyncService.getInstance().uploadLocalLoadout();
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.sync_submitted"));
        return 1;
    }

    /** 绑定到网站：mod 显示 6 位绑定码，玩家在网页「时装绑定」页输入。 */
    private static int bind() {
        SyncService.getInstance().bindToWebsite(true);
        return 1;
    }

    /** 打开可视化衣柜界面（普通玩家的主入口，快捷键默认 {@code K}）。 */
    private static int open(FabricClientCommandSource source) {
        WardrobeScreen.open();
        source.sendFeedback(feedback("vmfashion.command.open", KeyBindings.keyName()));
        return 1;
    }

    /**
     * 配置热更新：重读 {@code config.json} + 重建同步层 + 重扫时装包。
     *
     * <p>和 {@code /vmfashion reload} 的区别：那个只重扫包，这个连配置一起重读
     * （改 {@code apiBaseUrl} 不用重启游戏）。
     */
    private static int configReload(FabricClientCommandSource source) {
        int cosmeticCount = ConfigReloader.reload();
        int packCount = CosmeticRegistry.getInstance().packs().size();
        source.sendFeedback(feedback("vmfashion.command.config_reloaded", packCount, cosmeticCount));
        return 1;
    }

    /** 清空服务端下发的时装包缓存（用户自己装的包不受影响）。 */
    private static int cacheClear(FabricClientCommandSource source) {
        AssetSyncService assets = AssetSyncService.getInstance();
        long before = safeCacheBytes(assets);
        try {
            assets.clearCache();
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("清理时装包缓存失败: {}", t.toString());
            source.sendFeedback(feedback("vmfashion.command.cache_clear_failed", String.valueOf(t)));
            return 0;
        }
        source.sendFeedback(feedback("vmfashion.command.cache_cleared", DiagExporter.humanBytes(before)));
        return 1;
    }

    /** 导出诊断信息到 {@code config/vmfashion/diag/}，聊天栏给出路径。 */
    private static int diag(FabricClientCommandSource source) {
        try {
            Path file = DiagExporter.export();
            source.sendFeedback(feedback("vmfashion.command.diag_done", String.valueOf(file)));
            VMFashionClient.LOGGER.info("诊断信息已导出: {}", file);
            return 1;
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("导出诊断信息失败: {}", t.toString());
            source.sendFeedback(feedback("vmfashion.command.diag_failed", String.valueOf(t)));
            return 0;
        }
    }

    /**
     * 渲染链路自检：把配置、UUID、穿戴、烘焙结果全部打到聊天栏和日志里。
     *
     * <p>没有图形环境时也能用它确认"包 → 模型烘焙 → 贴图注册"整条链路是通的。
     */
    private static int debug() {
        Minecraft minecraft = Minecraft.getInstance();
        ModConfig config = VMFashionClient.getInstance().getConfig();

        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_header"));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_render",
                config.isEnabled(), config.isRenderOtherPlayers(),
                config.getRenderDistance(), config.getScale()));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_ui",
                config.getUiScale(), config.getAnimationDuration(), config.isToastEnabled()));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_cache",
                CosmeticAssets.cachedCount(), CosmeticAssets.failureCount()));

        if (minecraft.player == null) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_no_player"));
            return 0;
        }

        java.util.UUID uuid = minecraft.player.getUUID();
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_uuid", uuid));

        // v1.0：穿戴数据带上后端 scale（WornCosmetic），debug 输出也把 scale 一起打出来
        Map<String, WornCosmetic> loadout = LoadoutService.getInstance().loadoutFor(uuid);
        if (loadout.isEmpty()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_empty"));
            return 1;
        }

        for (Map.Entry<String, WornCosmetic> entry : loadout.entrySet()) {
            String cosmeticId = entry.getValue().cosmeticId();
            CosmeticDefinition definition = LoadoutService.getInstance().resolve(cosmeticId);
            if (definition == null) {
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_missing",
                        cosmeticId));
                continue;
            }
            CosmeticAssets.Entry assets = CosmeticAssets.get(definition);
            if (assets == null) {
                ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_asset_failed",
                        definition.id()));
                continue;
            }
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.debug_entry",
                    definition.id(), definition.category().key(), definition.attachedBone(),
                    assets.model().topLevelBones().length, assets.quadCount(), assets.texture()));
            VMFashionClient.LOGGER.debug("穿戴槽位 {}: {}（包 scale={} × 后端 scale={}）",
                    entry.getKey(), definition.id(), definition.scale(), entry.getValue().scale());
        }
        return 1;
    }

    private static int equip(String id) {
        CosmeticDefinition cosmetic = CosmeticRegistry.getInstance().find(id);
        if (cosmetic == null) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.equip_not_found", id));
            return 0;
        }
        LocalLoadout.getInstance().wear(cosmetic);
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.equipped",
                cosmetic.name(), cosmetic.category().key()));
        VMFashionClient.LOGGER.info("穿上时装 {} -> {}", cosmetic.category().key(), cosmetic.id());
        SyncService.getInstance().uploadLocalLoadout();
        return 1;
    }

    private static int unequip(String category) {
        String removed = LocalLoadout.getInstance().unwear(category.toLowerCase());
        if (removed == null) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.unequip_none", category));
            return 0;
        }
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.command.unequipped", removed));
        SyncService.getInstance().uploadLocalLoadout();
        return 1;
    }

    // ==================== 工具 ====================

    /**
     * 命令反馈：带 {@code [VMFashion] } 前缀的可翻译文案。
     *
     * <p>前缀由 {@link ChatUtil#prefixed} 统一拼装 —— 聊天栏和命令反馈必须是同一个前缀。
     */
    private static Component feedback(String key, Object... args) {
        return ChatUtil.prefixed(Component.translatable(key, args));
    }

    /** 服务端包缓存占用（清理缓存时用来告诉玩家释放了多少）；别的模块抛异常也不能中断命令。 */
    private static long safeCacheBytes(AssetSyncService assets) {
        try {
            return assets.cacheBytes();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /** 本地时装包目录占用字节数（给 status / 诊断用）。 */
    private static long packBytes() {
        try {
            return DiagExporter.directorySize(PackLoader.packsDir());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 别的模块的状态摘要（Component 版）。
     *
     * <p>命令不能因为别的模块抛异常就中断，失败时给一个占位符。
     */
    private static Component safeComponent(Supplier<Component> supplier) {
        try {
            Component text = supplier.get();
            return text == null ? Component.literal("?") : text;
        } catch (Throwable t) {
            return Component.literal("?");
        }
    }
}
