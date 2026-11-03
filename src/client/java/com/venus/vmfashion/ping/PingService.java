package com.venus.vmfashion.ping;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.ping.core.ClientContext;
import com.venus.vmfashion.ping.core.PingData;
import com.venus.vmfashion.ping.hud.StatusOverlay;
import com.venus.vmfashion.ping.net.PingAuthBridge;
import com.venus.vmfashion.ping.net.PingRelay;
import com.venus.vmfashion.ping.render.CameraCapture;
import com.venus.vmfashion.ping.render.PingRenderer;
import com.venus.vmfashion.ping.util.SoundPlayer;
import com.venus.vmfashion.util.ChatUtil;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 合订版标点功能（原独立模组 VMPing）的生命周期入口。
 *
 * <p>职责：
 * <ul>
 *   <li>持有 {@link PingRelay}（中继状态机）与 {@link PingAuthBridge}（复用时装认证）；</li>
 *   <li>注册 HUD 元素（世界标点 + 连接状态角标）；</li>
 *   <li>进服启动 / 退服停止 / 每 tick 驱动，配置热更新时重启中继；</li>
 *   <li>播放标点音效（自己发的 / 别人发的）；</li>
 *   <li>检测到玩家同时装了独立 VMPing 时提示移除（避免快捷键与 HUD 重复）。</li>
 * </ul>
 *
 * <p><b>认证</b>：不再有自己的 challenge/verify —— 直接复用
 * {@link com.venus.vmfashion.sync.SyncService} 的时装会话（同一个 MC 账号、同一份 token）。
 * 玩家没登录时装后端 / 关掉同步 / 后端不可达时，标点自动降级为「本地可见」，
 * 功能照用、只是别人看不见，界面上会说明原因。
 *
 * <p><b>渲染</b>：标点画在 HUD 最上层（{@code HudElementRegistry.addLast}）。26.3 没有
 * WorldRenderEvents，这是唯一稳定的每帧回调；相机矩阵由
 * {@link com.venus.vmfashion.ping.mixin.GameRendererMixin} 在 GUI 提取前抓取。
 */
public final class PingService {

    private static final PingService INSTANCE = new PingService();

    private final PingAuthBridge authBridge = new PingAuthBridge();
    private final PingRelay relay = new PingRelay(authBridge);

    private volatile ModConfig config;
    /** HUD 元素只允许注册一次（配置热更新 / 开发环境重复初始化都不能重复注册）。 */
    private boolean hudRegistered;
    /** "检测到独立 VMPing"的提示只发一次，避免每次 tick 刷屏。 */
    private boolean standaloneWarningSent;

    private PingService() {
    }

    public static PingService getInstance() {
        return INSTANCE;
    }

    /** 全局中继（渲染 / 命令 / 界面都从这里取状态）。 */
    public PingRelay relay() {
        return relay;
    }

    /** 认证桥（状态栏 / 诊断用）。 */
    public PingAuthBridge authBridge() {
        return authBridge;
    }

    /** 当前生效的标点配置（配置还没加载时返回默认值，不返回 null）。 */
    public PingSettings settings() {
        ModConfig current = config;
        return current == null ? new PingSettings() : current.getPing();
    }

    /**
     * 初始化：保存配置引用 + 注册 HUD 元素。
     *
     * <p>由 {@link VMFashionClient#onInitializeClient()} 在同步层初始化之后调用一次。
     */
    public void initialize(ModConfig activeConfig) {
        this.config = activeConfig;
        registerHud();
    }

    /** 注册 HUD：世界标点 + 连接状态角标。任何异常都不能把客户端搞崩。 */
    private void registerHud() {
        if (hudRegistered) {
            return;
        }
        hudRegistered = true;
        try {
            HudElementRegistry.addLast(
                    Identifier.fromNamespaceAndPath(VMFashionClient.MOD_ID, "pings"),
                    (graphics, deltaTracker) -> {
                        try {
                            PingRenderer.render(graphics, deltaTracker);
                        } catch (Throwable t) {
                            // 渲染路径异常只影响这一帧，绝不能让游戏崩
                            VMFashionClient.LOGGER.debug("标点渲染失败（忽略本帧）: {}", t.toString());
                        }
                        try {
                            StatusOverlay.render(graphics);
                        } catch (Throwable t) {
                            VMFashionClient.LOGGER.debug("标点状态角标渲染失败（忽略本帧）: {}", t.toString());
                        }
                    });
            VMFashionClient.LOGGER.info("标点 HUD 已注册（合订版）");
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("注册标点 HUD 失败（标点将不显示）: {}", t.toString());
        }
    }

    /** 进服：启动中继（内部会判断单机 / 局域网 / 未配置后端并自动降级本地）。 */
    public void onJoin() {
        try {
            relay.start(settings());
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("启动标点中继失败（降级为本地标点）: {}", t.toString());
        }
    }

    /** 退服：停轮询、清标点、丢掉上一局的相机快照与状态角标。 */
    public void onDisconnect() {
        try {
            relay.stop();
        } catch (Throwable t) {
            VMFashionClient.LOGGER.debug("停止标点中继失败（忽略）: {}", t.toString());
        }
        CameraCapture.invalidate();
        StatusOverlay.reset();
    }

    /**
     * 每客户端 tick（20 次/秒）。
     *
     * <p>兜底：JOIN 事件在极少数环境（回放 / 某些代理）不会触发，这里在"已经进世界
     * 但中继还没启动"时补一次；{@code start()} 自己会做单机/局域网判断，不会误发请求。
     */
    public void tick() {
        ModConfig current = config;
        if (current == null) {
            return;
        }
        PingSettings settings = current.getPing();
        if (!relay.isStarted() && settings.isEnabled()
                && Minecraft.getInstance().player != null
                && !ClientContext.roomId().isBlank()) {
            relay.start(settings);
        }
        relay.tick();
        warnIfStandaloneModInstalled();
    }

    /**
     * 配置热更新：换配置引用并重启中继（{@code /vmfashion config reload} 与设置界面保存都会走这里）。
     */
    public void applyConfig(ModConfig newConfig) {
        this.config = newConfig;
        try {
            relay.reload(settings());
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("标点配置热更新失败（保留旧中继状态）: {}", t.toString());
        }
    }

    /** 自己发了一个标点：播音效（界面/命令共用同一条路径）。 */
    public void onLocalPing(PingData ping) {
        if (!settings().isPlaySound()) {
            return;
        }
        SoundPlayer.playOwnPing(ping == null ? null : ping.kind());
    }

    /** 收到别人新标点（长轮询回调，网络线程）：只对最新一条响一声。 */
    public void onRemotePings(List<PingData> received) {
        if (received == null || received.isEmpty() || !settings().isShowOthersPings()
                || !settings().isPlaySound()) {
            return;
        }
        PingData latest = received.get(received.size() - 1);
        SoundPlayer.playRemotePing(latest.kind());
    }

    /** 是否装了独立的 VMPing 模组（同时装会重复注册快捷键 / HUD，提示玩家移除）。 */
    public static boolean standaloneVmpingInstalled() {
        try {
            return FabricLoader.getInstance().isModLoaded("vmping");
        } catch (Throwable t) {
            return false;
        }
    }

    private void warnIfStandaloneModInstalled() {
        if (standaloneWarningSent || !standaloneVmpingInstalled()) {
            return;
        }
        standaloneWarningSent = true;
        VMFashionClient.LOGGER.warn("检测到独立 VMPing 模组：标点功能已内置在 VMFashion，"
                + "同时安装会重复注册快捷键与 HUD，建议移除 vmping-*.jar");
        if (Minecraft.getInstance().player != null) {
            ChatUtil.showLocalMessage(Component.translatable("vmfashion.ping.warning.standalone_mod"));
        }
    }
}
