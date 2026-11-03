package com.venus.vmfashion.gui;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ConfigReloader;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.gui.component.ValueSlider;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 时装设置界面（v2.0 新增）。
 *
 * <p>为什么现在才有：1.x 的配置项只能手改 {@code config/vmfashion/config.json}，
 * 玩家（尤其是从 Mod Menu 点「配置」进来的）没有可视化入口。v2.0 把标点功能并进来之后，
 * 配置项从 14 个涨到 29 个，再让玩家手改 JSON 不现实 —— 所以补一个统一设置界面，
 * 并在里面放<b>「标点设置…」</b>入口（标点配置有自己的二级界面 {@link PingSettingsScreen}）。
 *
 * <p>交互约定（与项目其它界面一致）：
 * <ul>
 *   <li><b>完成</b>：把控件上的值写回 {@link ModConfig} → 存盘 → {@link ConfigReloader#reload()}
 *       <b>热生效</b>（同步层 / 资产层 / 标点中继都会重建，不用重进游戏）；</li>
 *   <li><b>取消 / ESC</b>：丢弃本次改动（控件只改本界面内的副本，不动 {@code ModConfig} 单例）；</li>
 *   <li><b>恢复默认</b>：把控件重置成默认值（仍需点「完成」才生效）。</li>
 * </ul>
 *
 * <p>布局是<b>流式 + 两列</b>的：先按内容算出面板高度，再垂直居中；窗口大小 / GUI 缩放变化时
 * {@link #init()} 会被重新调用，控件按字段里的当前值重建，所以"改了一半再拖窗口"不会丢改动。
 * 面板配色沿用 {@link VanillaStyle} 的原版容器色板（与衣柜同一套）。
 *
 * <p><b>矮窗口（v2.0.1）</b>：内容（14 项 ≈ 233 逻辑像素）比屏幕高时，底部三个按钮
 * <b>钉在面板底部</b>（永远可点），中间内容区变成可滚动视口（滚轮），只创建<b>完整落在
 * 视口内</b>的控件 —— 详见 {@link SettingsLayout}。旧实现在 320×180 下会把
 * 「完成 / 取消 / 恢复默认」画到屏幕外（玩家能改设置但存不了）。
 */
public final class SettingsScreen extends Screen {

    // ==================== 布局常量（逻辑像素） ====================
    /** 两列之间的间距。 */
    private static final int COLUMN_GAP = 6;
    /** 行间距 / 分区间距 / 分区标题高度（基准值，窗口矮时会被 {@link SettingsLayout#pickMetrics} 压缩）。 */
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int SECTION_GAP = 8;
    private static final int LABEL_HEIGHT = 11;
    /** URL 输入框最大长度。 */
    private static final int MAX_URL_LENGTH = 300;

    /** 窗口矮时的候选度量（从宽松到紧凑）。 */
    private static final SettingsLayout.Metrics[] METRIC_CANDIDATES = {
            new SettingsLayout.Metrics(ROW_HEIGHT, ROW_GAP, SECTION_GAP, LABEL_HEIGHT),
            new SettingsLayout.Metrics(18, 3, 8, 10),
            new SettingsLayout.Metrics(16, 2, 6, 9),
            new SettingsLayout.Metrics(14, 2, 4, 9),
    };

    /** 返回用（ESC / 完成 / 取消都回到这里；{@code null} = 回游戏）。 */
    private final Screen parent;

    // ==================== 布局度量（init 时按窗口高度自适应选一组） ====================
    private int rowHeight = ROW_HEIGHT;
    private int rowGap = ROW_GAP;
    private int sectionGap = SECTION_GAP;
    private int labelHeight = LABEL_HEIGHT;

    // ==================== 面板几何（init 时算好，渲染直接用） ====================
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    /** 底部按钮行的 y（固定，不随滚动变化）。 */
    private int buttonRowY;
    /** 可滚动内容区的上下边界（屏幕坐标）。 */
    private int viewportTop;
    private int viewportBottom;
    /** 当前滚动量 / 滚动上限（0 = 内容放得下）。 */
    private int scroll;
    private int maxScroll;
    /** 三个分区标题的 y 坐标（渲染时画在对应控件组上方；-1 = 不在视口内，不画）。 */
    private int sectionDisplayY;
    private int sectionSyncY;
    private int sectionPingY;

    // ==================== 待保存的值（控件改这里，点"完成"才写回配置） ====================
    private float uiScale;
    private int animationDuration;
    private boolean toastEnabled;
    private boolean enabled;
    private boolean renderOtherPlayers;
    private int renderDistance;
    private float modelScale;
    private boolean syncEnabled;
    private boolean wardrobeFilterEnabled;
    private int refreshIntervalSeconds;
    private boolean downloadServerPacks;
    private int maxPackCacheMb;
    private String apiBaseUrl = "";
    private String authServer = "";

    public SettingsScreen(Screen parent) {
        super(Component.translatable("vmfashion.gui.settings.title"));
        this.parent = parent;
        loadFromConfig(currentConfig());
    }

    /** Mod Menu / 命令用的工厂方法。 */
    public static Screen createScreen(Screen parent) {
        return new SettingsScreen(parent);
    }

    /** 当前配置；预览器 / 初始化早期可能为 null，调用方按默认值兜底。 */
    private static ModConfig currentConfig() {
        VMFashionClient client = VMFashionClient.getInstance();
        return client == null ? null : client.getConfig();
    }

    /** 从配置读一遍待保存值。 */
    private void loadFromConfig(ModConfig config) {
        ModConfig source = config == null ? new ModConfig() : config;
        this.uiScale = source.getUiScale();
        this.animationDuration = source.getAnimationDuration();
        this.toastEnabled = source.isToastEnabled();
        this.enabled = source.isEnabled();
        this.renderOtherPlayers = source.isRenderOtherPlayers();
        this.renderDistance = (int) Math.round(source.getRenderDistance());
        this.modelScale = source.getScale();
        this.syncEnabled = source.isSyncEnabled();
        this.wardrobeFilterEnabled = source.isWardrobeFilterEnabled();
        this.refreshIntervalSeconds = source.getRefreshIntervalSeconds();
        this.downloadServerPacks = source.isDownloadServerPacks();
        this.maxPackCacheMb = source.getMaxPackCacheMb();
        this.apiBaseUrl = source.getApiBaseUrl();
        this.authServer = source.getAuthServer();
    }

    @Override
    protected void init() {
        this.clearWidgets();

        // 窗口太矮（GUI 缩放很大 / 小窗口）时自动压缩行高与间距；仍放不下就滚动（见 SettingsLayout）
        SettingsLayout.Metrics metrics = SettingsLayout.pickMetrics(
                this.height - 12, METRIC_CANDIDATES, SettingsScreen::layoutHeight);
        this.rowHeight = metrics.rowHeight();
        this.rowGap = metrics.rowGap();
        this.sectionGap = metrics.sectionGap();
        this.labelHeight = metrics.labelHeight();

        SettingsLayout.Layout layout = SettingsLayout.compute(
                this.width, this.height, layoutHeight(metrics), metrics);
        this.panelWidth = layout.panelWidth();
        this.panelHeight = layout.panelHeight();
        this.panelX = layout.panelX();
        this.panelY = layout.panelY();
        this.buttonRowY = layout.buttonY();
        this.viewportTop = layout.contentTop();
        this.viewportBottom = layout.viewportBottom();
        this.maxScroll = layout.maxScroll();
        this.scroll = SettingsLayout.clampScroll(this.scroll, this.maxScroll);

        int contentWidth = panelWidth - SettingsLayout.PADDING * 2;
        int columnWidth = Math.max(80, (contentWidth - COLUMN_GAP) / 2);
        int left = panelX + SettingsLayout.PADDING;
        int right = left + columnWidth + COLUMN_GAP;
        // 内容坐标原点：滚动量已经扣掉，控件直接按屏幕坐标摆放
        int cursorY = viewportTop - scroll;

        // ---------- 界面与渲染 ----------
        this.sectionDisplayY = cursorY;
        cursorY += labelHeight;
        addSlider(left, cursorY, columnWidth, "vmfashion.gui.settings.option.ui_scale",
                0.5, 2.0, this.uiScale, 0.05, 2, Component.literal("×"), value -> this.uiScale = (float) value);
        addSlider(right, cursorY, columnWidth, "vmfashion.gui.settings.option.animation",
                0, 1000, this.animationDuration, 50, 0,
                Component.translatable("vmfashion.gui.settings.unit.ms"), value -> this.animationDuration = (int) value);
        cursorY += rowHeight + rowGap;

        addSlider(left, cursorY, columnWidth, "vmfashion.gui.settings.option.render_distance",
                0, 512, this.renderDistance, 8, 0,
                Component.translatable("vmfashion.gui.settings.unit.blocks"), value -> this.renderDistance = (int) value);
        addSlider(right, cursorY, columnWidth, "vmfashion.gui.settings.option.model_scale",
                0.05, 8.0, this.modelScale, 0.05, 2, Component.literal("×"), value -> this.modelScale = (float) value);
        cursorY += rowHeight + rowGap;

        addToggle(left, cursorY, columnWidth, "vmfashion.gui.settings.option.toast",
                this.toastEnabled, value -> this.toastEnabled = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.gui.settings.option.enabled",
                this.enabled, value -> this.enabled = value);
        cursorY += rowHeight + rowGap;

        addToggle(left, cursorY, columnWidth, "vmfashion.gui.settings.option.render_others",
                this.renderOtherPlayers, value -> this.renderOtherPlayers = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.gui.settings.option.wardrobe_filter",
                this.wardrobeFilterEnabled, value -> this.wardrobeFilterEnabled = value);
        cursorY += rowHeight + sectionGap;

        // ---------- 同步与资产 ----------
        this.sectionSyncY = cursorY;
        cursorY += labelHeight;
        addToggle(left, cursorY, columnWidth, "vmfashion.gui.settings.option.sync",
                this.syncEnabled, value -> this.syncEnabled = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.gui.settings.option.download_packs",
                this.downloadServerPacks, value -> this.downloadServerPacks = value);
        cursorY += rowHeight + rowGap;

        addSlider(left, cursorY, columnWidth, "vmfashion.gui.settings.option.refresh_interval",
                0, 3600, this.refreshIntervalSeconds, 5, 0,
                Component.translatable("vmfashion.gui.settings.unit.seconds"), value -> this.refreshIntervalSeconds = (int) value);
        addSlider(right, cursorY, columnWidth, "vmfashion.gui.settings.option.cache_mb",
                0, 8192, this.maxPackCacheMb, 128, 0,
                Component.translatable("vmfashion.gui.settings.unit.mb"), value -> this.maxPackCacheMb = (int) value);
        cursorY += rowHeight + rowGap;

        addEditBox(left, cursorY, contentWidth, "vmfashion.gui.settings.option.api_url",
                "vmfashion.gui.settings.api_url.hint", this.apiBaseUrl, value -> this.apiBaseUrl = value);
        cursorY += rowHeight + rowGap;

        addEditBox(left, cursorY, contentWidth, "vmfashion.gui.settings.option.auth_server",
                "vmfashion.gui.settings.auth_server.hint", this.authServer, value -> this.authServer = value);
        cursorY += rowHeight + sectionGap;

        // ---------- 标点 ----------
        this.sectionPingY = cursorY;
        cursorY += labelHeight;
        if (rowVisible(cursorY, rowHeight)) {
            Button pingEntry = Button.builder(Component.translatable("vmfashion.gui.settings.ping_entry"),
                            button -> openPingSettings())
                    .bounds(left, cursorY, contentWidth, rowHeight)
                    .build();
            this.addRenderableWidget(pingEntry);
        }
        cursorY += rowHeight + SettingsLayout.BOTTOM_GAP;

        // ---------- 底部按钮（钉在面板底部，不随滚动；必须最后加，绘制在最上层） ----------
        int buttonGap = 6;
        int buttonWidth = Math.max(20, (contentWidth - buttonGap * 2) / 3);
        this.addRenderableWidget(Button.builder(Component.translatable("vmfashion.gui.settings.done"),
                        button -> saveAndClose())
                .bounds(left, buttonRowY, buttonWidth, rowHeight).build());
        this.addRenderableWidget(Button.builder(Component.translatable("vmfashion.gui.settings.cancel"),
                        button -> onClose())
                .bounds(left + buttonWidth + buttonGap, buttonRowY, buttonWidth, rowHeight).build());
        this.addRenderableWidget(Button.builder(Component.translatable("vmfashion.gui.settings.defaults"),
                        button -> resetToDefaults())
                .bounds(left + (buttonWidth + buttonGap) * 2, buttonRowY, buttonWidth, rowHeight).build());
    }

    // ==================== 控件工厂 ====================

    /** 控件是否完整落在可滚动视口内（不在视口内的控件不创建，避免"看不见但点得到"）。 */
    private boolean rowVisible(int y, int height) {
        return SettingsLayout.rowVisible(y, height, viewportTop, viewportBottom);
    }

    /** 数值滑条：值变化先写进本界面字段，点「完成」才落盘。 */
    private void addSlider(int x, int y, int width, String labelKey,
                           double min, double max, double initial, double step, int decimals,
                           Component suffix, java.util.function.DoubleConsumer setter) {
        if (!rowVisible(y, rowHeight)) {
            return;
        }
        ValueSlider slider = new ValueSlider(x, y, width, rowHeight, labelKey,
                min, max, initial, step, decimals, suffix, setter);
        this.addRenderableWidget(slider);
    }

    /**
     * 布尔开关：文字形如「显示 Toast：开」。
     *
     * <p>不用 {@code CycleButton}：它的「< 值 >」翻页样式对布尔值来说不如直接显示开/关直观。
     */
    private void addToggle(int x, int y, int width, String labelKey, boolean initial,
                           Consumer<Boolean> setter) {
        if (!rowVisible(y, rowHeight)) {
            return;
        }
        boolean[] state = {initial};
        Button[] holder = new Button[1];
        holder[0] = Button.builder(toggleLabel(labelKey, state[0]), button -> {
            state[0] = !state[0];
            setter.accept(state[0]);
            holder[0].setMessage(toggleLabel(labelKey, state[0]));
        }).bounds(x, y, width, rowHeight).build();
        this.addRenderableWidget(holder[0]);
    }

    /** 文本输入框（URL）；不在视口内就不创建。 */
    private void addEditBox(int x, int y, int width, String labelKey, String hintKey,
                            String initial, Consumer<String> setter) {
        if (!rowVisible(y, rowHeight)) {
            return;
        }
        EditBox box = new EditBox(this.font, x, y, width, rowHeight, Component.translatable(labelKey));
        box.setMaxLength(MAX_URL_LENGTH);
        box.setValue(initial == null ? "" : initial);
        box.setHint(Component.translatable(hintKey));
        box.setResponder(value -> setter.accept(value == null ? "" : value));
        this.addRenderableWidget(box);
    }

    private static Component toggleLabel(String labelKey, boolean on) {
        return Component.translatable("vmfashion.gui.settings.toggle",
                Component.translatable(labelKey),
                Component.translatable(on ? "vmfashion.gui.settings.on" : "vmfashion.gui.settings.off"));
    }

    // ==================== 布局度量 ====================

    /** 按给定度量算整个面板的高度（与 {@link #init()} 里的顺序必须一致）。 */
    static int layoutHeight(SettingsLayout.Metrics metrics) {
        int rowHeight = metrics.rowHeight();
        int rowGap = metrics.rowGap();
        int sectionGap = metrics.sectionGap();
        int labelHeight = metrics.labelHeight();
        // 界面与渲染：4 行（含 1 个分区标题）
        int display = labelHeight + 4 * rowHeight + 3 * rowGap;
        // 同步与资产：4 行（2 行双列 + 2 行输入框）+ 分区标题 + 分区前间距
        int sync = sectionGap + labelHeight + 4 * rowHeight + 3 * rowGap;
        // 标点：分区标题 + 1 行入口按钮 + 底部间距
        int ping = sectionGap + labelHeight + rowHeight + SettingsLayout.BOTTOM_GAP;
        // 底部按钮
        int bottom = rowHeight;
        return SettingsLayout.TITLE_HEIGHT + SettingsLayout.PADDING * 2 + display + sync + ping + bottom;
    }

    // ==================== 动作 ====================

    /** 打开标点设置（返回时回到本界面，改动仍需在本界面点「完成」才写盘）。 */
    private void openPingSettings() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(new PingSettingsScreen(this));
        }
    }

    /** 恢复默认值（只改本界面字段与控件，点「完成」才写回配置）。 */
    private void resetToDefaults() {
        ModConfig defaults = new ModConfig();
        this.uiScale = defaults.getUiScale();
        this.animationDuration = defaults.getAnimationDuration();
        this.toastEnabled = defaults.isToastEnabled();
        this.enabled = defaults.isEnabled();
        this.renderOtherPlayers = defaults.isRenderOtherPlayers();
        this.renderDistance = (int) Math.round(defaults.getRenderDistance());
        this.modelScale = defaults.getScale();
        this.syncEnabled = defaults.isSyncEnabled();
        this.wardrobeFilterEnabled = defaults.isWardrobeFilterEnabled();
        this.refreshIntervalSeconds = defaults.getRefreshIntervalSeconds();
        this.downloadServerPacks = defaults.isDownloadServerPacks();
        this.maxPackCacheMb = defaults.getMaxPackCacheMb();
        this.apiBaseUrl = defaults.getApiBaseUrl();
        this.authServer = defaults.getAuthServer();
        // 重建控件：init() 会按上面这些字段重新生成（滑条位置、开关文字、输入框内容）
        this.rebuildWidgets();
    }

    /** 写回配置 + 存盘 + 热生效 + 关闭。 */
    private void saveAndClose() {
        ModConfig config = currentConfig();
        if (config != null) {
            config.setUiScale(this.uiScale);
            config.setAnimationDuration(this.animationDuration);
            config.setToastEnabled(this.toastEnabled);
            config.setEnabled(this.enabled);
            config.setRenderOtherPlayers(this.renderOtherPlayers);
            config.setRenderDistance(this.renderDistance);
            config.setScale(this.modelScale);
            config.setSyncEnabled(this.syncEnabled);
            config.setWardrobeFilterEnabled(this.wardrobeFilterEnabled);
            config.setRefreshIntervalSeconds(this.refreshIntervalSeconds);
            config.setDownloadServerPacks(this.downloadServerPacks);
            config.setMaxPackCacheMb(this.maxPackCacheMb);
            config.setApiBaseUrl(this.apiBaseUrl);
            config.setAuthServer(this.authServer);
            config.save();
            // 重读 + 重建同步层 / 资产层 / 标点中继（与 /vmfashion config reload 同一条路径）
            ConfigReloader.reload();
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ==================== 输入 ====================

    /**
     * 滚轮滚动内容区（只有内容放不下时才响应）。
     *
     * <p>滚动后重建控件：视口外的控件本来就不该存在（否则会出现"看不见但点得到"的幽灵点击），
     * 而控件是按屏幕坐标创建的，所以滚动只能走 {@code rebuildWidgets()}。
     * 待保存的值在字段里，重建不会丢改动。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (maxScroll <= 0) {
            return false;
        }
        int next = SettingsLayout.scrollBy(scroll, (int) Math.round(scrollY), rowHeight + rowGap, maxScroll);
        if (next == scroll) {
            return false;
        }
        this.scroll = next;
        this.rebuildWidgets();
        return true;
    }

    // ==================== 渲染 ====================

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick) {
        // 1) 压暗背景（不自己调 extractBackground，避免"blur 两次"崩溃）
        context.fill(0, 0, this.width, this.height, ModConfig.UI.OVERLAY_COLOR);
        // 2) 原版容器面板
        VanillaStyle.panel(context, panelX, panelY, panelWidth, panelHeight, 1.0f);
        // 3) 居中标题 + 分隔线
        drawCentered(context, this.title, panelX + panelWidth / 2,
                panelY + (SettingsLayout.TITLE_HEIGHT - this.font.lineHeight) / 2 + 1, ModConfig.UI.TEXT_COLOR);
        VanillaStyle.separator(context, panelX + 1, panelX + panelWidth - 1,
                panelY + SettingsLayout.TITLE_HEIGHT, 1.0f);
        // 4) 分区小标题（滚出视口就不画）
        int labelX = panelX + SettingsLayout.PADDING;
        drawSectionLabel(context, "vmfashion.gui.settings.section.display", sectionDisplayY, labelX);
        drawSectionLabel(context, "vmfashion.gui.settings.section.sync", sectionSyncY, labelX);
        drawSectionLabel(context, "vmfashion.gui.settings.section.ping", sectionPingY, labelX);
        // 5) 控件（原版统一绘制，必须在自定义底板之后）
        super.extractRenderState(context, mouseX, mouseY, partialTick);
        // 6) 内容区上下边界各画一条细线：让玩家一眼看出"这里可以滚"
        drawScrollHint(context);
    }

    private void drawSectionLabel(GuiGraphicsExtractor context, String key, int y, int x) {
        if (!rowVisible(y, labelHeight)) {
            return;
        }
        context.text(this.font, Component.translatable(key), x, y, ModConfig.UI.ACCENT_COLOR, false);
    }

    /** 内容溢出时在视口上下沿画一条极淡的提示线（原版风格：1px 深灰）。 */
    private void drawScrollHint(GuiGraphicsExtractor context) {
        if (maxScroll <= 0) {
            return;
        }
        int x0 = panelX + SettingsLayout.PADDING;
        int x1 = panelX + panelWidth - SettingsLayout.PADDING;
        if (scroll > 0) {
            context.fill(x0, viewportTop - 2, x1, viewportTop - 1, 0x40000000);
        }
        if (scroll < maxScroll) {
            context.fill(x0, viewportBottom + 1, x1, viewportBottom + 2, 0x40000000);
        }
    }

    /** 浅底上的居中文字（不带阴影 —— 阴影在 #C6C6C6 上会糊成脏边）。 */
    private void drawCentered(GuiGraphicsExtractor context, Component text, int centerX, int y, int color) {
        String plain = text.getString();
        context.text(this.font, plain, centerX - this.font.width(plain) / 2, y, color, false);
    }
}
