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

/**
 * 客户端命令 {@code /vmfashion ...}，只在本地执行，不会发给服务器。
 *
 * <p>新增文案一律走 {@link Component#translatable}（语言文件 {@code assets/vmfashion/lang/}），
 * 保留 {@code §} 颜色码的旧输出不动 —— i18n 统一迁移是后续单独一轮。
 */
public final class FashionCommand {

    /** 聊天栏前缀，和 {@code ChatUtil#showLocalMessage} 保持一致。 */
    private static final String PREFIX = "[VMFashion] ";
    /** 聊天栏里长路径 / 长状态行的截断长度（聊天栏宽度有限，太长会被折行看不清）。 */
    private static final int MAX_CHAT_TEXT = 160;

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
        ChatUtil.showLocalMessage("重新加载完成：" + result.packs().size() + " 个包，"
                + result.cosmeticCount() + " 件时装");
        for (String warning : result.warnings()) {
            ChatUtil.showLocalMessage("§e警告：" + warning);
        }
        return 1;
    }

    private static int list() {
        CosmeticRegistry registry = CosmeticRegistry.getInstance();
        if (registry.packs().isEmpty()) {
            ChatUtil.showLocalMessage("没有加载到任何时装包，把包放到 config/vmfashion/packs/ 下再 /vmfashion reload");
            return 0;
        }
        for (CosmeticPack pack : registry.packs()) {
            ChatUtil.showLocalMessage("§6" + pack.name() + " §7(" + pack.id() + " v" + pack.version()
                    + ", " + pack.cosmetics().size() + " 件)");
            for (CosmeticDefinition cosmetic : pack.cosmetics()) {
                ChatUtil.showLocalMessage("  §b" + cosmetic.id() + " §7[" + cosmetic.category().key() + "]");
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
        source.sendFeedback(feedback("vmfashion.command.status.sync", safeDescribe(sync::describeStatus)));
        source.sendFeedback(tokenLine(sync));

        AssetSyncService assets = AssetSyncService.getInstance();
        source.sendFeedback(feedback("vmfashion.command.status.assets",
                safeDescribe(assets::describeStatus),
                DiagExporter.humanBytes(safeCacheBytes(assets)),
                safePending(assets)));

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
            ChatUtil.showLocalMessage("§e未配置时装后端地址，当前是纯本地模式");
            return 0;
        }
        SyncService.getInstance().uploadLocalLoadout();
        ChatUtil.showLocalMessage("已提交穿戴同步请求（结果看日志）");
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
            source.sendFeedback(feedback("vmfashion.command.diag.done", String.valueOf(file)));
            VMFashionClient.LOGGER.info("诊断信息已导出: {}", file);
            return 1;
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("导出诊断信息失败: {}", t.toString());
            source.sendFeedback(feedback("vmfashion.command.diag.failed", String.valueOf(t)));
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

        ChatUtil.showLocalMessage("§6VMFashion 调试信息");
        ChatUtil.showLocalMessage("  总开关=" + config.isEnabled()
                + " 渲染他人=" + config.isRenderOtherPlayers()
                + " 渲染距离=" + config.getRenderDistance()
                + " 全局缩放=" + config.getScale());
        ChatUtil.showLocalMessage("  界面缩放=" + config.getUiScale()
                + " 动画时长=" + config.getAnimationDuration()
                + " Toast=" + config.isToastEnabled());
        ChatUtil.showLocalMessage("  已缓存资源=" + CosmeticAssets.cachedCount()
                + " 失败=" + CosmeticAssets.failureCount());

        if (minecraft.player == null) {
            ChatUtil.showLocalMessage("  §7当前没有玩家实体，无法自检");
            return 0;
        }

        java.util.UUID uuid = minecraft.player.getUUID();
        ChatUtil.showLocalMessage("  本地玩家 UUID=" + uuid);

        Map<String, String> loadout = LoadoutService.getInstance().loadoutFor(uuid);
        if (loadout.isEmpty()) {
            ChatUtil.showLocalMessage("  §7没有穿戴任何时装（/vmfashion equip <id>）");
            return 1;
        }

        for (Map.Entry<String, String> entry : loadout.entrySet()) {
            CosmeticDefinition definition = LoadoutService.getInstance().resolve(entry.getValue());
            if (definition == null) {
                ChatUtil.showLocalMessage("  §c" + entry.getValue() + " 找不到定义");
                continue;
            }
            CosmeticAssets.Entry assets = CosmeticAssets.get(definition);
            if (assets == null) {
                ChatUtil.showLocalMessage("  §c" + definition.id() + " 资源加载失败（看日志）");
                continue;
            }
            ChatUtil.showLocalMessage("  §a" + definition.id()
                    + " §7分类=" + definition.category().key()
                    + " 骨骼=" + definition.attachedBone()
                    + " 根骨骼=" + assets.model().topLevelBones().length
                    + " 四边形=" + assets.quadCount()
                    + " 贴图=" + assets.texture());
        }
        return 1;
    }

    private static int equip(String id) {
        CosmeticDefinition cosmetic = CosmeticRegistry.getInstance().find(id);
        if (cosmetic == null) {
            ChatUtil.showLocalMessage("§c找不到时装：" + id + "（先 /vmfashion list 看看有什么）");
            return 0;
        }
        LocalLoadout.getInstance().wear(cosmetic);
        ChatUtil.showLocalMessage("已穿上 §b" + cosmetic.name() + "§r（" + cosmetic.category().key() + "）");
        VMFashionClient.LOGGER.info("穿上时装 {} -> {}", cosmetic.category().key(), cosmetic.id());
        SyncService.getInstance().uploadLocalLoadout();
        return 1;
    }

    private static int unequip(String category) {
        String removed = LocalLoadout.getInstance().unwear(category.toLowerCase());
        if (removed == null) {
            ChatUtil.showLocalMessage("§7该分类本来就没有穿戴：" + category);
            return 0;
        }
        ChatUtil.showLocalMessage("已脱下 §b" + removed);
        SyncService.getInstance().uploadLocalLoadout();
        return 1;
    }

    // ==================== 工具 ====================

    /** 拼一条带前缀的可翻译反馈（前缀和旧命令的 {@code ChatUtil} 输出保持一致）。 */
    private static Component feedback(String key, Object... args) {
        return Component.literal(PREFIX).append(Component.translatable(key, args));
    }

    /** 本地时装包目录占用字节数（给 status / 诊断用）。 */
    private static long packBytes() {
        try {
            return DiagExporter.directorySize(PackLoader.packsDir());
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static long safeCacheBytes(AssetSyncService assets) {
        try {
            return assets.cacheBytes();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static int safePending(AssetSyncService assets) {
        try {
            return assets.pendingCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 别的模块的 describeStatus() 抛异常时不能把命令打断。 */
    private static String safeDescribe(java.util.function.Supplier<String> supplier) {
        try {
            String text = supplier.get();
            return text == null ? "?" : truncate(text);
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String truncate(String text) {
        return text.length() <= MAX_CHAT_TEXT ? text : text.substring(0, MAX_CHAT_TEXT) + "…";
    }
}
