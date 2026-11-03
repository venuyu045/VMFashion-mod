package com.venus.vmfashion.config;

/**
 * 标点（原 VMPing 独立模组）配置 —— VMFashion 2.0 合订版新增，序列化在
 * {@code config/vmfashion/config.json} 的 {@code "ping"} 对象下。
 *
 * <p>设计原则与 {@link ModConfig} 一致：
 * <ul>
 *   <li><b>字段缺失 = 默认值</b>：老版本 config.json 没有 {@code ping} 对象，读出来就是默认配置；</li>
 *   <li><b>越界值在 {@link #sanitize()} 里拉回</b>：玩家手改 JSON 写错也不会让界面/渲染炸掉；</li>
 *   <li><b>后端地址默认留空</b>：留空表示"跟随时装后端"——由
 *       {@link #resolveApiBaseUrl(String)} 从时装地址推导出 {@code /api/ping}，
 *       这样合订版只需要维护一个后端地址（认证、同步、标点共用同一套账号体系）。</li>
 * </ul>
 *
 * <p>为什么单独一个类而不是继续塞进 {@link ModConfig}：标点有 14 个配置项，
 * 平铺进 ModConfig 会让那个类再胖一圈，而且 Gson 的嵌套对象天然给配置文件分组，
 * 玩家手改时一眼能看出哪些是标点的配置。
 */
public final class PingSettings {

    /**
     * 标点后端地址（默认留空 = 从时装后端地址推导）。
     *
     * <p>推导规则见 {@link #resolveApiBaseUrl(String)}：时装地址以 {@code /fashion} 结尾时
     * 把最后一段换成 {@code /ping}（官方实例 {@code https://www.venusyu045.cn/api/fashion}
     * → {@code https://www.venusyu045.cn/api/ping}）；其它形状则在末尾追加 {@code /ping}。
     */
    public static final String DEFAULT_API_BASE_URL = "";

    // ==================== 配置字段（Gson 直接读写） ====================

    /** 总开关：关掉后不显示轮盘、不发标点、不渲染任何标点。 */
    private boolean enabled = true;

    /** 标点后端地址；留空 = 跟随时装后端（见 {@link #DEFAULT_API_BASE_URL}）。 */
    private String apiBaseUrl = DEFAULT_API_BASE_URL;

    /** 是否显示自己的标点。 */
    private boolean showOwnPings = true;

    /** 是否显示别人的标点。 */
    private boolean showOthersPings = true;

    /** 是否显示距离数字。 */
    private boolean showDistance = true;

    /** 是否显示标点者名字（按住 Tab 玩家列表时强制显示）。 */
    private boolean showPlayerNames = false;

    /** 是否播放提示音。 */
    private boolean playSound = true;

    /** 提示音音量（0~1）。 */
    private float soundVolume = 0.7f;

    /** 超过这个距离（格）不再画图标，只画屏幕边缘箭头。 */
    private int maxIconDistance = 96;

    /** 标点本地显示时长（秒）；服务端 TTL 是 20s，客户端不会显示得更久。 */
    private int pingTtlSeconds = 20;

    /** 长轮询等待时长（秒），服务端上限 25。 */
    private int pollTimeoutSeconds = 20;

    /** 是否显示屏幕边缘方向箭头。 */
    private boolean showEdgeIndicator = true;

    /** 是否在 HUD 上显示连接状态（认证失败/降级时给个提示）。 */
    private boolean showStatus = true;

    /** 中继不可用时是否自动降级为纯本地标点。 */
    private boolean degradeToLocal = true;

    /** 调试日志：把每次请求/响应摘要打进日志（排查中继问题时用）。 */
    private boolean debugLogging = false;

    // ==================== 读取 / 保存（由 ModConfig 统一负责） ====================

    /** 把越界值拉回合法区间（手改配置写错了也不至于让界面炸掉）。 */
    public void sanitize() {
        apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        while (apiBaseUrl.endsWith("/")) {
            apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
        }
        if (!Float.isFinite(soundVolume)) {
            soundVolume = 0.7f;
        } else {
            soundVolume = Math.max(0f, Math.min(1f, soundVolume));
        }
        maxIconDistance = Math.max(8, Math.min(512, maxIconDistance));
        pingTtlSeconds = Math.max(3, Math.min(20, pingTtlSeconds));
        pollTimeoutSeconds = Math.max(5, Math.min(25, pollTimeoutSeconds));
    }

    /**
     * 计算实际使用的标点后端地址。
     *
     * @param fashionApiBaseUrl 时装后端地址（{@link ModConfig#getApiBaseUrl()}，可能为空）
     * @return 标点后端 base（不含结尾斜杠）；空串表示没有可用的后端（纯本地模式）
     */
    public String resolveApiBaseUrl(String fashionApiBaseUrl) {
        String override = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        while (override.endsWith("/")) {
            override = override.substring(0, override.length() - 1);
        }
        if (!override.isEmpty()) {
            return override;
        }
        return deriveApiBaseUrl(fashionApiBaseUrl);
    }

    /**
     * 从时装后端地址推导标点后端地址（纯函数，便于单测）。
     *
     * <p>规则：
     * <ul>
     *   <li>空地址 → 空串（纯本地模式）；</li>
     *   <li>以 {@code /fashion} 结尾 → 替换成 {@code /ping}
     *       （官方实例与自建同构后端都适用）；</li>
     *   <li>其它形状 → 末尾追加 {@code /ping}（例如后端把两组路由挂在同一个前缀下）。</li>
     * </ul>
     */
    public static String deriveApiBaseUrl(String fashionApiBaseUrl) {
        String base = fashionApiBaseUrl == null ? "" : fashionApiBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            return "";
        }
        if (base.endsWith("/fashion")) {
            return base.substring(0, base.length() - "/fashion".length()) + "/ping";
        }
        return base + "/ping";
    }

    /** 标点中继是否具备启动条件（总开关打开 + 有可用的后端地址）。 */
    public boolean relayEnabled(String fashionApiBaseUrl) {
        return enabled && !resolveApiBaseUrl(fashionApiBaseUrl).isBlank();
    }

    // ==================== Getters / Setters ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl == null ? "" : apiBaseUrl.trim();
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
    }

    public boolean isShowOwnPings() {
        return showOwnPings;
    }

    public void setShowOwnPings(boolean showOwnPings) {
        this.showOwnPings = showOwnPings;
    }

    public boolean isShowOthersPings() {
        return showOthersPings;
    }

    public void setShowOthersPings(boolean showOthersPings) {
        this.showOthersPings = showOthersPings;
    }

    public boolean isShowDistance() {
        return showDistance;
    }

    public void setShowDistance(boolean showDistance) {
        this.showDistance = showDistance;
    }

    public boolean isShowPlayerNames() {
        return showPlayerNames;
    }

    public void setShowPlayerNames(boolean showPlayerNames) {
        this.showPlayerNames = showPlayerNames;
    }

    public boolean isPlaySound() {
        return playSound;
    }

    public void setPlaySound(boolean playSound) {
        this.playSound = playSound;
    }

    public float getSoundVolume() {
        return soundVolume;
    }

    public void setSoundVolume(float soundVolume) {
        this.soundVolume = Float.isFinite(soundVolume)
                ? Math.max(0f, Math.min(1f, soundVolume)) : 0.7f;
    }

    public int getMaxIconDistance() {
        return maxIconDistance;
    }

    public void setMaxIconDistance(int maxIconDistance) {
        this.maxIconDistance = Math.max(8, Math.min(512, maxIconDistance));
    }

    public int getPingTtlSeconds() {
        return pingTtlSeconds;
    }

    public void setPingTtlSeconds(int pingTtlSeconds) {
        this.pingTtlSeconds = Math.max(3, Math.min(20, pingTtlSeconds));
    }

    public int getPollTimeoutSeconds() {
        return pollTimeoutSeconds;
    }

    public void setPollTimeoutSeconds(int pollTimeoutSeconds) {
        this.pollTimeoutSeconds = Math.max(5, Math.min(25, pollTimeoutSeconds));
    }

    public boolean isShowEdgeIndicator() {
        return showEdgeIndicator;
    }

    public void setShowEdgeIndicator(boolean showEdgeIndicator) {
        this.showEdgeIndicator = showEdgeIndicator;
    }

    public boolean isShowStatus() {
        return showStatus;
    }

    public void setShowStatus(boolean showStatus) {
        this.showStatus = showStatus;
    }

    public boolean isDegradeToLocal() {
        return degradeToLocal;
    }

    public void setDegradeToLocal(boolean degradeToLocal) {
        this.degradeToLocal = degradeToLocal;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public void setDebugLogging(boolean debugLogging) {
        this.debugLogging = debugLogging;
    }

    // ==================== 界面常量（轮盘 / HUD / 边缘指示共用） ====================

    /**
     * 界面常量 —— 轮盘与 HUD 共用，改一处即可。
     *
     * <p>从原 VMPing 的 {@code ModConfig.UI} 平移过来；合订版不再有独立的 ping 配置类，
     * 但轮盘半径 / 配色这类"不是玩家配置"的常量仍然集中在这里。
     */
    public static final class UI {
        /** 轮盘外半径（逻辑像素）。 */
        public static final int WHEEL_RADIUS = 78;
        /** 轮盘内半径（中心死区，鼠标在里面算"未选中"）。 */
        public static final int WHEEL_INNER_RADIUS = 22;
        /** 扇区之间的间隙（角度，度）。 */
        public static final float SEGMENT_GAP_DEGREES = 3f;
        /** 轮盘背景色（半透明黑）。 */
        public static final int WHEEL_BG = 0xB4000000;
        /** 轮盘边框色。 */
        public static final int WHEEL_BORDER = 0x66FFFFFF;
        /** 选中扇区的高亮色（白色半透明）。 */
        public static final int WHEEL_HIGHLIGHT = 0x40FFFFFF;
        /** 中心提示文字色。 */
        public static final int WHEEL_TEXT = 0xFFFFFFFF;
        /** 轮盘中心圆半径。 */
        public static final int WHEEL_CENTER_RADIUS = 16;
        /** 世界标记的图标边长（格）。 */
        public static final double MARKER_SIZE = 0.9d;
        /** 标记从地面抬起的高度（格）。 */
        public static final double MARKER_LIFT = 0.05d;
        /** 信标光柱高度（格）。 */
        public static final double BEAM_HEIGHT = 24d;
        /** 屏幕边缘指示箭头的边距（像素）。 */
        public static final int EDGE_INDICATOR_MARGIN = 24;
        /** 边缘箭头的尺寸（像素）。 */
        public static final int EDGE_INDICATOR_SIZE = 10;
    }
}
