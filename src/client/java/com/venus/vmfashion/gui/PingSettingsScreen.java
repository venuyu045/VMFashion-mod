package com.venus.vmfashion.gui;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ConfigReloader;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.gui.component.ValueSlider;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 标点设置界面（原独立模组 VMPing 的设置界面，v2.0 合订版并入时装设置二级页）。
 *
 * <p>入口：衣柜 → 设置 → 标点设置…（{@link SettingsScreen#openPingSettings()}）。
 * 覆盖 {@link PingSettings} 的全部 15 个配置项，并额外显示"实际使用的后端地址"
 * （默认跟随时装后端，{@code /api/fashion} → {@code /api/ping}），让玩家不用猜。
 *
 * <p>交互与 {@link SettingsScreen} 一致：控件只改本界面内的副本，点「完成」才写回
 * {@code config.json} 并调用 {@link ConfigReloader#reload()} 热生效（标点中继会重启）。
 *
 * <p><b>矮窗口（v2.0.1）</b>：与 {@link SettingsScreen} 同一套
 * {@link SettingsLayout} 布局 —— 底部按钮钉住、内容可滚动、视口外的控件不创建。
 */
public final class PingSettingsScreen extends Screen {

    // ==================== 布局常量（逻辑像素） ====================
    private static final int COLUMN_GAP = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_GAP = 4;
    private static final int SECTION_GAP = 8;
    private static final int LABEL_HEIGHT = 11;
    /** URL 输入框最大长度。 */
    private static final int MAX_URL_LENGTH = 300;

    private static final SettingsLayout.Metrics[] METRIC_CANDIDATES = {
            new SettingsLayout.Metrics(ROW_HEIGHT, ROW_GAP, SECTION_GAP, LABEL_HEIGHT),
            new SettingsLayout.Metrics(18, 3, 8, 10),
            new SettingsLayout.Metrics(16, 2, 6, 9),
            new SettingsLayout.Metrics(14, 2, 4, 9),
    };

    /** 返回用（ESC / 完成 / 取消都回到这里；{@code null} = 回游戏）。 */
    private final Screen parent;

    // ==================== 布局度量 ====================
    private int rowHeight = ROW_HEIGHT;
    private int rowGap = ROW_GAP;
    private int sectionGap = SECTION_GAP;
    private int labelHeight = LABEL_HEIGHT;

    // ==================== 面板几何 ====================
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int buttonRowY;
    private int viewportTop;
    private int viewportBottom;
    private int scroll;
    private int maxScroll;
    private int sectionDisplayY;
    private int sectionAdvancedY;
    private int sectionNetworkY;
    /** "实际使用的后端地址"那一行的 y（-1 = 不在视口内）。 */
    private int effectiveUrlY;

    // ==================== 待保存的值 ====================
    private boolean enabled;
    private boolean showOwnPings;
    private boolean showOthersPings;
    private boolean showDistance;
    private boolean showPlayerNames;
    private boolean playSound;
    private boolean showEdgeIndicator;
    private boolean showStatus;
    private boolean degradeToLocal;
    private boolean debugLogging;
    private int volumePercent;
    private int maxIconDistance;
    private int pingTtlSeconds;
    private int pollTimeoutSeconds;
    private String apiBaseUrl = "";
    /** 当前实际会用的标点后端地址（init / 输入框变化时算一次，渲染时直接用）。 */
    private String effectiveUrl = "";

    public PingSettingsScreen(Screen parent) {
        super(Component.translatable("vmfashion.ping.settings.title"));
        this.parent = parent;
        loadFromConfig(currentConfig());
    }

    /** 工厂方法（命令 / 外部入口用）。 */
    public static Screen createScreen(Screen parent) {
        return new PingSettingsScreen(parent);
    }

    private static ModConfig currentConfig() {
        VMFashionClient client = VMFashionClient.getInstance();
        return client == null ? null : client.getConfig();
    }

    /** 从配置读一遍待保存值。 */
    private void loadFromConfig(ModConfig config) {
        PingSettings source = config == null ? new PingSettings() : config.getPing();
        this.enabled = source.isEnabled();
        this.showOwnPings = source.isShowOwnPings();
        this.showOthersPings = source.isShowOthersPings();
        this.showDistance = source.isShowDistance();
        this.showPlayerNames = source.isShowPlayerNames();
        this.playSound = source.isPlaySound();
        this.showEdgeIndicator = source.isShowEdgeIndicator();
        this.showStatus = source.isShowStatus();
        this.degradeToLocal = source.isDegradeToLocal();
        this.debugLogging = source.isDebugLogging();
        this.volumePercent = Math.round(source.getSoundVolume() * 100f);
        this.maxIconDistance = source.getMaxIconDistance();
        this.pingTtlSeconds = source.getPingTtlSeconds();
        this.pollTimeoutSeconds = source.getPollTimeoutSeconds();
        this.apiBaseUrl = source.getApiBaseUrl();
    }

    @Override
    protected void init() {
        this.clearWidgets();

        SettingsLayout.Metrics metrics = SettingsLayout.pickMetrics(
                this.height - 12, METRIC_CANDIDATES, PingSettingsScreen::layoutHeight);
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
        int cursorY = viewportTop - scroll;

        // ---------- 显示 ----------
        this.sectionDisplayY = cursorY;
        cursorY += labelHeight;
        addToggle(left, cursorY, columnWidth, "vmfashion.ping.settings.enabled",
                this.enabled, value -> this.enabled = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.ping.settings.play_sound",
                this.playSound, value -> this.playSound = value);
        cursorY += rowHeight + rowGap;

        addToggle(left, cursorY, columnWidth, "vmfashion.ping.settings.show_own",
                this.showOwnPings, value -> this.showOwnPings = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.ping.settings.show_others",
                this.showOthersPings, value -> this.showOthersPings = value);
        cursorY += rowHeight + rowGap;

        addToggle(left, cursorY, columnWidth, "vmfashion.ping.settings.show_distance",
                this.showDistance, value -> this.showDistance = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.ping.settings.show_names",
                this.showPlayerNames, value -> this.showPlayerNames = value);
        cursorY += rowHeight + rowGap;

        addToggle(left, cursorY, columnWidth, "vmfashion.ping.settings.show_edge",
                this.showEdgeIndicator, value -> this.showEdgeIndicator = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.ping.settings.show_status",
                this.showStatus, value -> this.showStatus = value);
        cursorY += rowHeight + sectionGap;

        // ---------- 高级 ----------
        this.sectionAdvancedY = cursorY;
        cursorY += labelHeight;
        addToggle(left, cursorY, columnWidth, "vmfashion.ping.settings.degrade_local",
                this.degradeToLocal, value -> this.degradeToLocal = value);
        addToggle(right, cursorY, columnWidth, "vmfashion.ping.settings.debug_logging",
                this.debugLogging, value -> this.debugLogging = value);
        cursorY += rowHeight + rowGap;

        addSlider(left, cursorY, columnWidth, "vmfashion.ping.settings.volume",
                0, 100, this.volumePercent, 1, 0, Component.literal("%"), value -> this.volumePercent = (int) value);
        addSlider(right, cursorY, columnWidth, "vmfashion.ping.settings.max_distance",
                8, 512, this.maxIconDistance, 8, 0,
                Component.translatable("vmfashion.gui.settings.unit.blocks"), value -> this.maxIconDistance = (int) value);
        cursorY += rowHeight + rowGap;

        addSlider(left, cursorY, columnWidth, "vmfashion.ping.settings.ttl",
                3, 20, this.pingTtlSeconds, 1, 0,
                Component.translatable("vmfashion.gui.settings.unit.seconds"), value -> this.pingTtlSeconds = (int) value);
        addSlider(right, cursorY, columnWidth, "vmfashion.ping.settings.poll_timeout",
                5, 25, this.pollTimeoutSeconds, 1, 0,
                Component.translatable("vmfashion.gui.settings.unit.seconds"), value -> this.pollTimeoutSeconds = (int) value);
        cursorY += rowHeight + sectionGap;

        // ---------- 网络 ----------
        this.sectionNetworkY = cursorY;
        cursorY += labelHeight;
        if (rowVisible(cursorY, rowHeight)) {
            EditBox urlBox = new EditBox(this.font, left, cursorY, contentWidth, rowHeight,
                    Component.translatable("vmfashion.ping.settings.api_url"));
            urlBox.setMaxLength(MAX_URL_LENGTH);
            urlBox.setValue(this.apiBaseUrl);
            urlBox.setHint(Component.translatable("vmfashion.ping.settings.api_url.hint"));
            urlBox.setResponder(value -> {
                this.apiBaseUrl = value == null ? "" : value;
                this.effectiveUrl = computeEffectiveApiBaseUrl();
            });
            this.addRenderableWidget(urlBox);
        }
        this.effectiveUrl = computeEffectiveApiBaseUrl();
        cursorY += rowHeight + rowGap;
        this.effectiveUrlY = cursorY;
        cursorY += labelHeight + SettingsLayout.BOTTOM_GAP;

        // ---------- 底部按钮（钉在面板底部，不随滚动；必须最后加，绘制在最上层） ----------
        int buttonGap = 6;
        int buttonWidth = Math.max(20, (contentWidth - buttonGap * 2) / 3);
        this.addRenderableWidget(Button.builder(Component.translatable("vmfashion.ping.settings.done"),
                        button -> saveAndClose())
                .bounds(left, buttonRowY, buttonWidth, rowHeight).build());
        this.addRenderableWidget(Button.builder(Component.translatable("vmfashion.ping.settings.cancel"),
                        button -> onClose())
                .bounds(left + buttonWidth + buttonGap, buttonRowY, buttonWidth, rowHeight).build());
        this.addRenderableWidget(Button.builder(Component.translatable("vmfashion.ping.settings.defaults"),
                        button -> resetToDefaults())
                .bounds(left + (buttonWidth + buttonGap) * 2, buttonRowY, buttonWidth, rowHeight).build());
    }

    // ==================== 控件工厂 ====================

    private boolean rowVisible(int y, int height) {
        return SettingsLayout.rowVisible(y, height, viewportTop, viewportBottom);
    }

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

    private static Component toggleLabel(String labelKey, boolean on) {
        return Component.translatable("vmfashion.gui.settings.toggle",
                Component.translatable(labelKey),
                Component.translatable(on ? "vmfashion.gui.settings.on" : "vmfashion.gui.settings.off"));
    }

    // ==================== 布局度量 ====================

    static int layoutHeight(SettingsLayout.Metrics metrics) {
        int rowHeight = metrics.rowHeight();
        int rowGap = metrics.rowGap();
        int sectionGap = metrics.sectionGap();
        int labelHeight = metrics.labelHeight();
        // 显示：4 行 + 分区标题
        int display = labelHeight + 4 * rowHeight + 3 * rowGap;
        // 高级：3 行 + 分区标题 + 分区前间距
        int advanced = sectionGap + labelHeight + 3 * rowHeight + 2 * rowGap;
        // 网络：URL 输入框 + 实际地址说明行 + 分区标题 + 分区前间距
        int network = sectionGap + labelHeight + rowHeight + rowGap + labelHeight + SettingsLayout.BOTTOM_GAP;
        // 底部按钮
        int bottom = rowHeight;
        return SettingsLayout.TITLE_HEIGHT + SettingsLayout.PADDING * 2 + display + advanced + network + bottom;
    }

    // ==================== 动作 ====================

    private void resetToDefaults() {
        PingSettings defaults = new PingSettings();
        this.enabled = defaults.isEnabled();
        this.showOwnPings = defaults.isShowOwnPings();
        this.showOthersPings = defaults.isShowOthersPings();
        this.showDistance = defaults.isShowDistance();
        this.showPlayerNames = defaults.isShowPlayerNames();
        this.playSound = defaults.isPlaySound();
        this.showEdgeIndicator = defaults.isShowEdgeIndicator();
        this.showStatus = defaults.isShowStatus();
        this.degradeToLocal = defaults.isDegradeToLocal();
        this.debugLogging = defaults.isDebugLogging();
        this.volumePercent = Math.round(defaults.getSoundVolume() * 100f);
        this.maxIconDistance = defaults.getMaxIconDistance();
        this.pingTtlSeconds = defaults.getPingTtlSeconds();
        this.pollTimeoutSeconds = defaults.getPollTimeoutSeconds();
        this.apiBaseUrl = defaults.getApiBaseUrl();
        this.rebuildWidgets();
    }

    /** 写回配置 + 存盘 + 热生效 + 关闭。 */
    private void saveAndClose() {
        ModConfig config = currentConfig();
        if (config != null) {
            PingSettings ping = config.getPing();
            ping.setEnabled(this.enabled);
            ping.setShowOwnPings(this.showOwnPings);
            ping.setShowOthersPings(this.showOthersPings);
            ping.setShowDistance(this.showDistance);
            ping.setShowPlayerNames(this.showPlayerNames);
            ping.setPlaySound(this.playSound);
            ping.setShowEdgeIndicator(this.showEdgeIndicator);
            ping.setShowStatus(this.showStatus);
            ping.setDegradeToLocal(this.degradeToLocal);
            ping.setDebugLogging(this.debugLogging);
            ping.setSoundVolume(this.volumePercent / 100f);
            ping.setMaxIconDistance(this.maxIconDistance);
            ping.setPingTtlSeconds(this.pingTtlSeconds);
            ping.setPollTimeoutSeconds(this.pollTimeoutSeconds);
            ping.setApiBaseUrl(this.apiBaseUrl);
            config.save();
            // 重读 + 重启标点中继（与 /vmfashion config reload 同一条路径）
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
        context.fill(0, 0, this.width, this.height, ModConfig.UI.OVERLAY_COLOR);
        VanillaStyle.panel(context, panelX, panelY, panelWidth, panelHeight, 1.0f);
        drawCentered(context, this.title, panelX + panelWidth / 2,
                panelY + (SettingsLayout.TITLE_HEIGHT - this.font.lineHeight) / 2 + 1, ModConfig.UI.TEXT_COLOR);
        VanillaStyle.separator(context, panelX + 1, panelX + panelWidth - 1,
                panelY + SettingsLayout.TITLE_HEIGHT, 1.0f);

        int labelX = panelX + SettingsLayout.PADDING;
        drawSectionLabel(context, "vmfashion.ping.settings.section.display", sectionDisplayY, labelX);
        drawSectionLabel(context, "vmfashion.ping.settings.section.advanced", sectionAdvancedY, labelX);
        drawSectionLabel(context, "vmfashion.ping.settings.section.network", sectionNetworkY, labelX);

        // "实际使用的后端地址"：玩家不用猜 /api/ping 是从哪来的
        if (rowVisible(effectiveUrlY, labelHeight)) {
            Component line = effectiveUrl.isBlank()
                    ? Component.translatable("vmfashion.ping.settings.api_url.local_only")
                    : Component.translatable("vmfashion.ping.settings.api_url.effective", effectiveUrl);
            context.text(this.font, line, labelX, effectiveUrlY, ModConfig.UI.MUTED_COLOR, false);
        }

        super.extractRenderState(context, mouseX, mouseY, partialTick);
        drawScrollHint(context);
    }

    private void drawSectionLabel(GuiGraphicsExtractor context, String key, int y, int x) {
        if (!rowVisible(y, labelHeight)) {
            return;
        }
        context.text(this.font, Component.translatable(key), x, y, ModConfig.UI.ACCENT_COLOR, false);
    }

    /** 内容溢出时在视口上下沿画一条极淡的提示线。 */
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

    /**
     * 按当前输入框 + 时装后端地址算"实际会用的标点后端地址"（空串 = 纯本地）。
     *
     * <p>只在 init / 输入框变化时算，不在渲染循环里每帧 new 对象。
     */
    private String computeEffectiveApiBaseUrl() {
        try {
            PingSettings probe = new PingSettings();
            probe.setApiBaseUrl(this.apiBaseUrl);
            ModConfig config = currentConfig();
            return probe.resolveApiBaseUrl(config == null ? "" : config.getApiBaseUrl());
        } catch (Throwable t) {
            return "";
        }
    }

    private void drawCentered(GuiGraphicsExtractor context, Component text, int centerX, int y, int color) {
        String plain = text.getString();
        context.text(this.font, plain, centerX - this.font.width(plain) / 2, y, color, false);
    }
}
