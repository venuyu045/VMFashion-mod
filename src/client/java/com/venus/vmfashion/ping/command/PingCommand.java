package com.venus.vmfashion.ping.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.venus.vmfashion.config.ConfigReloader;
import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.gui.KeyBindings;
import com.venus.vmfashion.gui.PingSettingsScreen;
import com.venus.vmfashion.ping.PingService;
import com.venus.vmfashion.ping.core.ClientContext;
import com.venus.vmfashion.ping.core.PingData;
import com.venus.vmfashion.ping.core.PingKind;
import com.venus.vmfashion.ping.core.RoomKey;
import com.venus.vmfashion.ping.gui.PingWheelScreen;
import com.venus.vmfashion.ping.net.PingRelay;
import com.venus.vmfashion.util.ChatUtil;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

/**
 * 标点子命令 —— 挂在 {@code /vmfashion} 下（{@code /vmfashion ping ...}），只在本地执行，不会发给服务器。
 *
 * <p>子命令：
 * <ul>
 *   <li>{@code status} —— 中继状态 / 房间 / 维度 / token / 标点数；</li>
 *   <li>{@code ping [kind]} —— 不打开轮盘，直接按准星方向标一个点（kind 缺省 default）；</li>
 *   <li>{@code open} —— 打开轮盘界面（等同按键）；</li>
 *   <li>{@code settings} —— 打开标点设置界面；</li>
 *   <li>{@code config reload} —— 重读 config.json 并重启中继；</li>
 *   <li>{@code debug} —— 打印诊断信息（房间键、后端地址、错误详情）；</li>
 *   <li>{@code help} —— 帮助。</li>
 * </ul>
 *
 * <p>合订版适配：独立版 VMPing 注册的是顶层 {@code /vmping}，合订版改由
 * {@link com.venus.vmfashion.command.FashionCommand} 调用 {@link #attach} 挂到
 * {@code /vmfashion ping} 下，避免与仍装着独立版 VMPing 的玩家冲突；配置/中继/按键/设置界面
 * 全部换成合订版的实现，语言 key 改成 {@code vmfashion.ping.*}。
 */
public final class PingCommand {

    private PingCommand() {
    }

    /**
     * 把 {@code ping} 子命令挂到 {@code /vmfashion} 的 literal 上（由 {@code FashionCommand#build} 调用）。
     *
     * @param parent 已经构造好 {@code /vmfashion} 的 literal builder
     */
    public static void attach(LiteralArgumentBuilder<FabricClientCommandSource> parent) {
        parent.then(ClientCommands.literal("ping")
                // 不带参数 = 按准星方向标一个「注意」；带类型 = /vmfashion ping danger 这种
                .executes(ctx -> ping(PingKind.DEFAULT))
                .then(ClientCommands.argument("kind", StringArgumentType.word())
                        .executes(ctx -> ping(PingKind.fromId(
                                StringArgumentType.getString(ctx, "kind")))))
                // 子命令用 literal 挂：Brigadier 的 literal 匹配优先于 argument，
                // 所以 status/open/settings/... 不会被当成类型名吃掉
                .then(ClientCommands.literal("status").executes(ctx -> status()))
                .then(ClientCommands.literal("open").executes(ctx -> open()))
                .then(ClientCommands.literal("settings").executes(ctx -> settings()))
                .then(ClientCommands.literal("help").executes(ctx -> help()))
                .then(ClientCommands.literal("debug").executes(ctx -> debug()))
                .then(ClientCommands.literal("config")
                        .then(ClientCommands.literal("reload").executes(ctx -> configReload()))));
    }

    // ==================== 子命令 ====================

    private static int help() {
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.help.header"));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.help.status"));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.help.ping"));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.help.open",
                KeyBindings.pingKeyName()));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.help.settings"));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.help.config"));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.help.debug"));
        return 1;
    }

    private static int status() {
        PingRelay relay = PingService.getInstance().relay();
        String backend = PingService.getInstance().authBridge().apiBaseUrl();
        String room = relay.roomId();
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.status.state",
                Component.translatable(stateKey(relay.state()))));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.status.room",
                room.isBlank()
                        ? Component.translatable("vmfashion.ping.command.status.room.none")
                        : Component.literal(room)));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.status.dimension",
                Component.literal(relay.dimension().isBlank() ? "-" : relay.dimension())));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.status.pings",
                Component.literal(String.valueOf(relay.store().size()))));
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.status.backend",
                Component.literal(backend.isBlank()
                        ? Component.translatable("vmfashion.ping.command.status.backend.off").getString()
                        : backend)));
        if (!relay.lastErrorKey().isBlank()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.status.last_error",
                    Component.translatable(relay.lastErrorKey())));
        }
        return 1;
    }

    private static int open() {
        PingSettings settings = PingService.getInstance().settings();
        if (!settings.isEnabled()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.disabled"));
            return 0;
        }
        PingWheelScreen.open();
        return 1;
    }

    /** 打开标点设置界面（按键/命令都能到，不依赖 Mod Menu）。 */
    private static int settings() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return 0;
        }
        // 命令是在聊天界面里同步执行的：把 ChatScreen 当 parent 会导致关设置后弹回聊天框。
        // 与 WardrobeScreen.open() 同口径 —— 聊天界面 / 未知来源一律回游戏。
        var current = minecraft.gui.screen();
        var parent = current instanceof ChatScreen ? null : current;
        minecraft.gui.setScreen(new PingSettingsScreen(parent));
        return 1;
    }

    private static int ping(PingKind kind) {
        PingRelay relay = PingService.getInstance().relay();
        PingSettings settings = PingService.getInstance().settings();
        if (!relay.online() && !settings.isDegradeToLocal()) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.status.local_only"));
            return 0;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return 0;
        }
        // 用准星方向做一次拾取（与轮盘相同的逻辑：方块/实体/视线终点）
        var hit = minecraft.player.pick(settings.getMaxIconDistance(), 1.0f, false);
        double x;
        double y;
        double z;
        java.util.UUID targetUuid = null;
        String targetName = null;
        if (hit != null && hit.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY
                && hit instanceof net.minecraft.world.phys.EntityHitResult entityHit) {
            var entity = entityHit.getEntity();
            var pos = entity.position();
            x = pos.x;
            y = pos.y + entity.getBbHeight() * 0.5d;
            z = pos.z;
            targetUuid = entity.getUUID();
            targetName = entity.getName().getString();
        } else if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            var location = hit.getLocation();
            x = location.x;
            y = location.y;
            z = location.z;
        } else {
            var eye = minecraft.player.getEyePosition();
            var direction = minecraft.player.getViewVector(1.0f);
            var point = eye.add(direction.scale(Math.min(32d, settings.getMaxIconDistance())));
            x = point.x;
            y = point.y;
            z = point.z;
        }
        PingData ping = relay.sendPing(x, y, z, kind, targetUuid, targetName);
        if (ping != null) {
            PingService.getInstance().onLocalPing(ping);
        }
        return 1;
    }

    private static int configReload() {
        // 合订版：走统一的配置热更新（重读 config.json → 重建同步层 → 重启标点中继）
        ConfigReloader.reload();
        java.nio.file.Path path = FabricLoader.getInstance().getConfigDir()
                .resolve("vmfashion").resolve("config.json");
        ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.command.config.reloaded",
                Component.literal(path.toString())));
        return 1;
    }

    private static int debug() {
        PingRelay relay = PingService.getInstance().relay();
        ChatUtil.showLocalMessage(Component.literal("state=" + relay.state()
                + " room=" + relay.roomId()
                + " dim=" + relay.dimension()
                + " pings=" + relay.store().size()
                + " lastSeq=" + relay.store().lastSeq()
                + " clockOffset=" + relay.store().clock().offsetMs() + "ms"
                + " errorKey=" + relay.lastErrorKey()
                + " error=" + relay.lastErrorDetail()));
        ChatUtil.showLocalMessage(Component.literal("rawServer=" + ClientContext.rawServerAddress()
                + " normalized=" + RoomKey.normalize(ClientContext.rawServerAddress())
                + " secure=" + PingService.getInstance().authBridge().secure()
                + " backend=" + PingService.getInstance().authBridge().apiBaseUrl()));
        return 1;
    }

    private static String stateKey(PingRelay.State state) {
        return switch (state) {
            case OFF -> "vmfashion.ping.state.off";
            case AUTHENTICATING -> "vmfashion.ping.state.authenticating";
            case ONLINE -> "vmfashion.ping.state.online";
            case DEGRADED -> "vmfashion.ping.state.degraded";
        };
    }
}
