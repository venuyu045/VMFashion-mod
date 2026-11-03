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
    /**
     * 界面配色与尺寸 —— <b>全部取自 Minecraft 原版容器界面的色板</b>。
     *
     * <p>取色方式不是"看着像"，而是从客户端 jar 的
     * {@code assets/minecraft/textures/gui/container/inventory.png} 里逐像素量出来的
     * （见 {@code tools/uipreview/} 的调色过程）：
     * <ul>
     *   <li>面板底 {@code #C6C6C6}（198）—— 原版物品栏/箱子界面的主色；</li>
     *   <li>槽位内底 {@code #8B8B8B}（139）、槽位边框 {@code #373737}（55）—— 原版物品格；</li>
     *   <li>面板外框 {@code #000000}、内侧高光 {@code #FFFFFF}、右下阴影 {@code #555555}（85）。</li>
     * </ul>
     *
     * <p>文字色遵循原版规则：<b>浅底用深灰</b>（{@code #404040}），不要用白字 ——
     * 白字在 {@code #C6C6C6} 上对比度只有 1.6:1，游戏里几乎看不清。
     *
     * <p>强调色沿用原版的"凸起/凹陷"语言而不是彩色描边：选中用凹陷 + 深色文字，
     * 悬停用略亮的底色。只有"已穿"（绿）和"已下架"（红）保留颜色，因为那是状态而不是装饰。
     */
    public static class UI {
        // 面板尺寸
        public static final int PANEL_WIDTH = 220;
        public static final int PANEL_HEIGHT = 180;
        public static final int BUTTON_HEIGHT = 20;
        public static final int PADDING = 8;

        // ---- 原版色板（数值来自 inventory.png 逐像素采样）----
        /** 面板主底色：原版物品栏的 #C6C6C6。 */
        public static final int PANEL_COLOR = 0xFFC6C6C6;
        /** 面板外框（1px 纯黑）。 */
        public static final int PANEL_BORDER = 0xFF000000;
        /** 面板内侧高光（左/上 2px 白）。 */
        public static final int PANEL_HIGHLIGHT = 0xFFFFFFFF;
        /** 面板右下阴影（2px #555555）。 */
        public static final int PANEL_SHADOW = 0xFF555555;
        /** 槽位内底：原版物品格的 #8B8B8B。 */
        public static final int SLOT_COLOR = 0xFF8B8B8B;
        /** 槽位边框：原版物品格的 #373737。 */
        public static final int SLOT_BORDER = 0xFF373737;

        /** 全屏遮罩：原版打开容器界面时的半透明黑（不遮死，能看见背后的世界）。 */
        public static final int OVERLAY_COLOR = 0xB0101010;
        /** 浅底上的正文色（原版 {@code #404040}）。 */
        public static final int TEXT_COLOR = 0xFF404040;
        /**
         * 浅底上的次要文字。
         *
         * <p>原版提示色是 {@code #6B6B6B}，但那个值只在 {@code #C6C6C6} 面板上够用；
         * 槽位底是更深的 {@code #8B8B8B}，压 {@code #6B6B6B} 对比度只剩 1.35:1（几乎看不见）。
         * 所以统一压到 {@code #5A5A5A}：在面板上 3.5:1、在槽位上 2.3:1，两处都清楚。
         */
        public static final int MUTED_COLOR = 0xFF5A5A5A;
        /** 悬停时略亮的底色（原版 hover 就是整体提亮一档）。 */
        public static final int HOVER_COLOR = 0xFFD6D6D6;
        /** 选中/强调：原版没有彩色描边，用"更深一档"表示选中。 */
        public static final int ACCENT_COLOR = 0xFF373737;
        /** 已穿/成功：深绿（浅底上要够暗才看得清）。 */
        public static final int SUCCESS_COLOR = 0xFF2E7D32;
        /** 已下架/失败：深红。 */
        public static final int DANGER_COLOR = 0xFFB71C1C;
        /** 角标底色：原版 tooltip 的深紫黑 {@code #100010}。 */
        public static final int BADGE_BG_COLOR = 0xF0100010;

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

    /**
     * 衣柜是否只显示网站衣柜里收藏的时装（默认开）。
     *
     * <p>开启后游戏内衣柜只列出玩家在网站「时装商店 → 加入衣柜」的时装；关掉就退回旧行为
     * （列出本地已装的全部时装）。未绑定网站账号 / 后端不可达时也会自动退回旧行为，
     * 所以这个开关只是给"我就想看到本地全部"的玩家一个显式出口。
     */
    private boolean wardrobeFilterEnabled = true;

    // ==================== 标点（VMPing 合订版）配置 ====================

    /**
     * 标点功能配置（原独立模组 VMPing 的全部可配置项）。
     *
     * <p>v2.0 起标点功能并入本模组，配置跟着时装配置走同一个 {@code config.json}：
     * 认证复用时装账号体系（同一份 token），后端地址默认从 {@link #apiBaseUrl} 推导
     * （{@code /api/fashion} → {@code /api/ping}），所以玩家只需要配一次后端。
     * 细节见 {@link PingSettings}。
     */
    private PingSettings ping = new PingSettings();

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
        // 手改坏的 JSON 可能把 ping 写成 null / 越界值：兜住，不能让标点层 NPE
        if (ping == null) {
            ping = new PingSettings();
        }
        ping.sanitize();
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

    /**
     * 衣柜是否只显示网站衣柜里收藏的时装。
     *
     * <p>默认 {@code true}：游戏内衣柜 = 网站「我的衣柜」的收藏列表。
     * 未绑定网站账号 / 后端不可达 / 关闭同步时，界面会自动退回"本地全部"，
     * 所以这个开关不会让玩家"衣柜突然空了"。
     */
    public boolean isWardrobeFilterEnabled() {
        return wardrobeFilterEnabled;
    }

    public void setWardrobeFilterEnabled(boolean wardrobeFilterEnabled) {
        this.wardrobeFilterEnabled = wardrobeFilterEnabled;
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

    // ==================== 标点（VMPing 合订版）配置 ====================

    /** 标点配置（永不返回 {@code null}；老配置文件没有该对象时是默认值）。 */
    public PingSettings getPing() {
        if (ping == null) {
            ping = new PingSettings();
        }
        return ping;
    }

    /** 替换标点配置（GUI「恢复默认」用；传 null 等价于恢复默认）。 */
    public void setPing(PingSettings ping) {
        this.ping = ping == null ? new PingSettings() : ping;
    }
}
