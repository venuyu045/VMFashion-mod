package com.venus.vmfashion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticPack;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.PackLoader;
import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.render.CosmeticAssets;
import com.venus.vmfashion.sync.SyncService;
import com.venus.vmfashion.util.ChatUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandBuildContext;

import java.util.Map;

/**
 * 客户端命令 {@code /vmfashion ...}，只在本地执行，不会发给服务器。
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
                .then(ClientCommands.literal("status").executes(ctx -> status()))
                .then(ClientCommands.literal("debug").executes(ctx -> debug()))
                .then(ClientCommands.literal("login").executes(ctx -> login()))
                .then(ClientCommands.literal("sync").executes(ctx -> sync()))
                .then(ClientCommands.literal("bind").executes(ctx -> bind()))
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

    private static int status() {
        ChatUtil.showLocalMessage("§6同步状态：" + SyncService.getInstance().describeStatus());
        Map<String, String> worn = LocalLoadout.getInstance().snapshot();
        if (worn.isEmpty()) {
            ChatUtil.showLocalMessage("当前没有穿戴任何时装");
            return 1;
        }
        worn.forEach((category, id) -> ChatUtil.showLocalMessage("§a" + category + " §7→ §b" + id));
        return 1;
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
}
