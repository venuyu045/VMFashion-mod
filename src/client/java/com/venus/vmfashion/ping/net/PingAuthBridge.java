package com.venus.vmfashion.ping.net;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.net.FashionAuth;
import com.venus.vmfashion.net.PackDownloader;
import com.venus.vmfashion.sync.SyncService;

import java.util.concurrent.CompletableFuture;

/**
 * 标点中继与<b>时装认证体系</b>之间的桥。
 *
 * <p>合订版（VMFashion 2.0）不再有独立的 ping 认证流程：标点直接复用
 * {@link SyncService} 持有的 {@link FashionAuth} 会话 ——
 * 同一个 Minecraft 账号、同一份 24h token、同一个后端地址，玩家只需要登录一次。
 * 本类把「同步层是否可用 / 标点后端地址是什么 / 怎么拿到 token」收敛成一个小接口，
 * 让 {@link PingRelay} 不直接依赖同步层的内部字段（也方便单测替换）。
 *
 * <p>后端地址推导见 {@link PingSettings#resolveApiBaseUrl(String)}：默认跟随时装后端
 * （{@code /api/fashion} → {@code /api/ping}），玩家可以在标点设置里单独覆盖。
 *
 * <p>线程约定：本类只读配置 / 转调异步方法，不阻塞；{@link #ensureSession()} 返回的
 * future 在 JDK 公共线程池完成，回调里碰界面必须切回主线程。
 */
public final class PingAuthBridge {

    /** 当前标点后端 base（已去结尾斜杠）；空串 = 没有可用后端（纯本地模式）。 */
    public String apiBaseUrl() {
        ModConfig config = currentConfig();
        if (config == null) {
            return "";
        }
        return config.getPing().resolveApiBaseUrl(config.getApiBaseUrl());
    }

    /** 时装后端 base（诊断 / 状态栏展示用；可能是空串）。 */
    public String fashionApiBaseUrl() {
        ModConfig config = currentConfig();
        return config == null ? "" : config.getApiBaseUrl();
    }

    /** 当前生效的标点配置（永不返回 null）。 */
    public PingSettings settings() {
        ModConfig config = currentConfig();
        return config == null ? new PingSettings() : config.getPing();
    }

    /** 同步层是否启用（没配后端 / 配置里关了同步 → 标点只能本地）。 */
    public boolean syncEnabled() {
        return SyncService.getInstance().isEnabled();
    }

    /** 标点后端地址是否是 https（或本机回环 http）—— 凭据只发给这种地址。 */
    public boolean secure() {
        return PackDownloader.isSecureUrl(apiBaseUrl());
    }

    /** 标点中继是否具备启动条件：同步层可用 + 后端地址安全。 */
    public boolean available() {
        return syncEnabled() && !apiBaseUrl().isBlank() && secure();
    }

    /**
     * 确保有一个可用的时装会话（复用 {@link SyncService#ensureSession()}）。
     *
     * <p>已有未过期会话直接返回，不会每次进服都重新认证；token 过期 / 被 401 拒绝后
     * 调 {@link #invalidateSession()} 再调本方法即可重新认证。
     */
    public CompletableFuture<FashionAuth.Session> ensureSession() {
        return SyncService.getInstance().ensureSession();
    }

    /** 丢弃当前时装会话（401 后调用；下一次 {@link #ensureSession()} 会重新认证）。 */
    public void invalidateSession() {
        SyncService.getInstance().invalidateSession();
    }

    private static ModConfig currentConfig() {
        VMFashionClient client = VMFashionClient.getInstance();
        return client == null ? null : client.getConfig();
    }
}
