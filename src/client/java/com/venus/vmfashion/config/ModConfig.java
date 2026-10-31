package com.venus.vmfashion.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.venus.vmfashion.VMFashionClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 模组配置管理
 */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("vmfashion").resolve("config.json");

    // ==================== UI 常量 ====================
    public static class UI {
        // 面板尺寸
        public static final int PANEL_WIDTH = 220;
        public static final int PANEL_HEIGHT = 180;
        public static final int BUTTON_HEIGHT = 20;
        public static final int PADDING = 8;

        // 颜色定义
        public static final int BG_COLOR = 0xFF1E1E2E;
        public static final int PANEL_COLOR = 0xFF2D2D44;
        public static final int HEADER_COLOR = 0xFF3D3D5C;
        public static final int TEXT_COLOR = 0xFFE0E0E0;
        public static final int ACCENT_COLOR = 0xFF7C3AED;
        public static final int HOVER_COLOR = 0xFF4A4A6A;
        public static final int SUCCESS_COLOR = 0xFF4ADE80;
        public static final int DANGER_COLOR = 0xFFF87171;
        public static final int BORDER_COLOR = 0xFF4A4A6A;
        public static final int MUTED_COLOR = 0xFF888888;

        // Toast 通知
        public static final int TOAST_WIDTH = 200;
        public static final int TOAST_HEIGHT = 30;
        public static final int TOAST_MARGIN = 10;
        public static final long TOAST_DURATION = 3000;
        public static final int TOAST_FADE_DURATION = 500;
    }

    // ==================== 配置项 ====================

    /**
     * 官方时装后端地址（部署在项目主服务器上，填到 {@code /api/fashion} 这一级）。
     *
     * <p>v1.2.1 起作为默认值：装好 mod 不用改任何配置就能用云端衣柜。
     * 想用自建后端改 {@code config.json} 的 {@code apiBaseUrl}；想纯本地就把地址清空。
     */
    public static final String DEFAULT_API_BASE_URL = "https://www.venusyu045.cn/api/fashion";

    /** 配置结构版本；老配置没有这个字段（Gson 保留默认值 1），用于一次性迁移。 */
    private int configVersion = 1;
    /** 当前配置结构版本：2 = 默认后端地址从「留空」改成官方实例。 */
    private static final int CURRENT_CONFIG_VERSION = 2;

    // UI 缩放比例 (0.5 - 2.0)
    private float uiScale = 1.0f;

    // 界面打开动画时长 (毫秒，0 - 1000)
    private int animationDuration = 200;

    // 是否显示 Toast 通知
    private boolean toastEnabled = true;

    // ==================== 渲染配置 ====================

    // 总开关：关闭后不再往渲染管线提交任何时装几何（时装包仍会加载）
    private boolean enabled = true;

    // 是否渲染其他玩家（本地玩家（第三人称）始终渲染）
    private boolean renderOtherPlayers = true;

    // 渲染距离（方块），<= 0 表示不限制
    private double renderDistance = 64.0;

    // 全局模型缩放倍率（与每件时装自己的 scale 相乘）
    private float scale = 1.0f;

    // ==================== 同步配置 ====================

    // 时装后端地址，默认官方实例；留空 = 纯本地模式
    private String apiBaseUrl = DEFAULT_API_BASE_URL;

    // 是否启用云端同步（需要 apiBaseUrl 且认证成功；关闭后完全离线）
    private boolean syncEnabled = true;

    // 皮肤站 Yggdrasil 地址；留空则从 authlib-injector 的 javaagent 参数自动探测
    private String authServer = "";

    // 其他玩家穿戴的定时刷新间隔（秒），<= 0 关闭；用于"别人换装后不用重进游戏"
    private int refreshIntervalSeconds = 45;

    // ==================== 资产下发配置 ====================

    // 是否自动下载服务端下发的时装包（关闭后只渲染本地已装的包）
    private boolean downloadServerPacks = true;

    // 服务端时装包缓存上限（MB），超过后按最久未用淘汰
    private int maxPackCacheMb = 512;

    /**
     * 从文件加载配置
     *
     * <p>兼容旧字段：Gson 用无参构造创建实例（字段初始值生效），
     * 文件里缺失的字段会保留默认值，因此老 config.json 可以直接升级。
     */
    public static ModConfig load() {
        if (Files.exists(CONFIG_PATH)) {
            try {
                String json = Files.readString(CONFIG_PATH);
                ModConfig config = GSON.fromJson(json, ModConfig.class);
                // Gson 对空文件或 "null" 内容会返回 null，必须回退到默认配置，
                // 否则 getConfig() 返回 null 会导致后续所有调用点 NPE
                if (config == null) {
                    VMFashionClient.LOGGER.warn("配置文件内容为空，使用默认配置");
                    return new ModConfig();
                }
                // 手改坏的 JSON 会抛 JsonSyntaxException（RuntimeException），
                // 这里必须一并兜住，不能让客户端在初始化阶段直接崩溃
                config.sanitize();
                VMFashionClient.LOGGER.info("配置文件已加载");
                return config;
            } catch (Exception e) {
                VMFashionClient.LOGGER.error("加载配置文件失败，使用默认配置", e);
            }
        }
        return new ModConfig();
    }

    /** 把从文件读入的数值收敛到安全范围（Gson 反序列化会绕过 setter 的 clamp）。 */
    private void sanitize() {
        if (!Float.isFinite(uiScale)) {
            uiScale = 1.0f;
        } else {
            uiScale = Math.max(0.5f, Math.min(2.0f, uiScale));
        }
        animationDuration = Math.max(0, Math.min(1000, animationDuration));
        if (!Float.isFinite(scale) || scale <= 0.0f) {
            scale = 1.0f;
        } else {
            scale = Math.max(0.05f, Math.min(8.0f, scale));
        }
        if (!Double.isFinite(renderDistance) || renderDistance < 0.0) {
            renderDistance = 64.0;
        } else {
            renderDistance = Math.min(512.0, renderDistance);
        }
        apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        authServer = authServer == null ? "" : authServer.trim();
        refreshIntervalSeconds = Math.max(0, Math.min(3600, refreshIntervalSeconds));
        maxPackCacheMb = Math.max(0, Math.min(8192, maxPackCacheMb));
        migrateLegacyDefaults();
    }

    /**
     * 老配置一次性迁移到新的默认后端地址。
     *
     * <p>v1.2.0 及更早的默认值是"留空 = 纯本地"，而 {@link #save()} 会把所有字段写进
     * {@code config.json}，所以老玩家的文件里是一个显式的 {@code "apiBaseUrl": ""}。
     * 只改字段初始值的话，这些文件读出来仍然是空地址、永远连不上官方后端。
     *
     * <p>迁移只做一次（靠 {@code configVersion} 判定）：补完地址后写入版本 2，
     * 玩家之后主动清空地址就会保持纯本地，不会被反复填回来。
     */
    private void migrateLegacyDefaults() {
        if (configVersion >= CURRENT_CONFIG_VERSION) {
            return;
        }
        if (apiBaseUrl.isEmpty()) {
            apiBaseUrl = DEFAULT_API_BASE_URL;
            VMFashionClient.LOGGER.info("旧配置未设置后端地址，已迁移到官方实例 {}", DEFAULT_API_BASE_URL);
        }
        configVersion = CURRENT_CONFIG_VERSION;
    }

    /**
     * 保存配置到文件
     */
    public void save() {
        try {
            // 落盘时写当前结构版本：新装用户第一次保存就是 v2，不会在下次启动时再走迁移分支
            configVersion = CURRENT_CONFIG_VERSION;
            Files.createDirectories(CONFIG_PATH.getParent());
            Files.writeString(CONFIG_PATH, GSON.toJson(this));
        } catch (IOException e) {
            VMFashionClient.LOGGER.error("保存配置文件失败", e);
        }
    }

    // Getters and Setters

    public float getUiScale() {
        return uiScale;
    }

    public void setUiScale(float uiScale) {
        this.uiScale = Math.max(0.5f, Math.min(2.0f, uiScale));
    }

    public int getAnimationDuration() {
        return animationDuration;
    }

    public void setAnimationDuration(int animationDuration) {
        this.animationDuration = Math.max(0, Math.min(1000, animationDuration));
    }

    public boolean isToastEnabled() {
        return toastEnabled;
    }

    public void setToastEnabled(boolean toastEnabled) {
        this.toastEnabled = toastEnabled;
    }

    // ==================== 渲染配置 ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isRenderOtherPlayers() {
        return renderOtherPlayers;
    }

    public void setRenderOtherPlayers(boolean renderOtherPlayers) {
        this.renderOtherPlayers = renderOtherPlayers;
    }

    public double getRenderDistance() {
        return renderDistance;
    }

    public void setRenderDistance(double renderDistance) {
        // 0 / 负数表示不限制，其余限制在 1 ~ 512 之间，避免误填导致什么都看不到
        this.renderDistance = renderDistance <= 0 ? 0 : Math.min(512, renderDistance);
    }

    public float getScale() {
        return scale;
    }

    public void setScale(float scale) {
        this.scale = Math.max(0.05f, Math.min(8.0f, scale));
    }

    // ==================== 同步配置 ====================

    public String getApiBaseUrl() {
        return apiBaseUrl == null ? "" : apiBaseUrl.trim();
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public String getAuthServer() {
        return authServer == null ? "" : authServer.trim();
    }

    public void setAuthServer(String authServer) {
        this.authServer = authServer == null ? "" : authServer.trim();
    }

    /** 其他玩家穿戴的定时刷新间隔（秒），{@code <= 0} 表示关闭。 */
    public int getRefreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    public void setRefreshIntervalSeconds(int refreshIntervalSeconds) {
        this.refreshIntervalSeconds = Math.max(0, Math.min(3600, refreshIntervalSeconds));
    }

    // ==================== 资产下发配置 ====================

    public boolean isDownloadServerPacks() {
        return downloadServerPacks;
    }

    public void setDownloadServerPacks(boolean downloadServerPacks) {
        this.downloadServerPacks = downloadServerPacks;
    }

    public int getMaxPackCacheMb() {
        return maxPackCacheMb;
    }

    public void setMaxPackCacheMb(int maxPackCacheMb) {
        this.maxPackCacheMb = Math.max(0, Math.min(8192, maxPackCacheMb));
    }

    /** 是否具备云端同步的前提条件（有地址 + 开关打开）。 */
    public boolean canSync() {
        return syncEnabled && !getApiBaseUrl().isBlank();
    }

    /** 是否处于"纯本地模式"（完全没配后端地址）。 */
    public boolean isLocalOnly() {
        return getApiBaseUrl().isBlank();
    }
}
