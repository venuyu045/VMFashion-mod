package com.venus.vmfashion.gui;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticCategory;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.gui.component.SearchField;
import com.venus.vmfashion.gui.component.ToastWidget;
import com.venus.vmfashion.render.CosmeticAssets;
import com.venus.vmfashion.sync.AssetSyncService;
import com.venus.vmfashion.sync.SyncService;
import com.venus.vmfashion.ui.UIState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 可视化衣柜界面。
 *
 * <p>设计（见 {@code 开发路线图.md} §2.2）：
 * <ul>
 *   <li>左侧分类 tab（全部 / 披风 / 翅膀 / 帽子 / 背饰 / 宠物 / 光环 / 其他）；</li>
 *   <li>中间网格卡片（每件时装一个，带贴图缩略图 + 名称 + 已穿标记，可滚动）；</li>
 *   <li>右侧预览 + 穿上 / 脱下按钮；</li>
 *   <li>顶部搜索框复用 {@code gui/component/SearchField}；</li>
 *   <li>底部状态行显示同步状态与资产下发状态；</li>
 *   <li>换装走 {@code LocalLoadout} + {@code SyncService.uploadLocalLoadout()}，
 *       和 {@code /vmfashion equip} 完全同一条路径（命令与界面不会出现两套状态）。</li>
 * </ul>
 *
 * <p>配色 / 内边距 / 按钮高度取 {@code config/ModConfig.UI} 常量，整体缩放取
 * {@code ModConfig#getUiScale()}，打开动画时长取 {@code ModConfig#getAnimationDuration()}
 * （0 表示不做动画）。这三个配置项之前是全仓库 0 引用的死字段（路线图 §10 #2）。
 *
 * <p><b>渲染 API</b>：26.3 是"提取式"绘制，{@link Screen#extractRenderState} 里拿到的
 * {@link GuiGraphicsExtractor} 只往渲染状态里写命令，没有旧的 {@code GuiGraphics#drawString}。
 * 用到的每个方法都用官方 jar 的 {@code javap} 核对过签名（见各调用点注释）。
 *
 * <p><b>性能</b>：{@link CosmeticAssets#get} 会烘焙模型 + 上传贴图，几十件时装一次性全烘焙会卡住一帧，
 * 因此只对"真正画到屏幕上的卡片"做懒加载，并且每帧限制新烘焙数量（{@link #MAX_BAKES_PER_FRAME}）。
 */
public final class WardrobeScreen extends Screen {

    // ==================== 布局常量（逻辑像素，运行时按 uiScale 缩放） ====================

    /**
     * 面板基准宽度。
     *
     * <p>{@code ModConfig.UI.PANEL_WIDTH} 是单栏设置面板的宽度，衣柜要放下
     * 「分类 + 网格 + 预览」三栏，取 2 倍再加两栏间距。
     */
    private static final int BASE_WIDTH = ModConfig.UI.PANEL_WIDTH * 2 + 80;
    /** 面板基准高度（两倍单栏面板高度减去头部/底部的富余量）。 */
    private static final int BASE_HEIGHT = ModConfig.UI.PANEL_HEIGHT * 2 - 60;
    private static final int BASE_HEADER_HEIGHT = 26;
    private static final int BASE_FOOTER_HEIGHT = 24;
    private static final int BASE_TAB_WIDTH = 74;
    private static final int BASE_TAB_HEIGHT = 18;
    private static final int BASE_PREVIEW_WIDTH = 150;
    private static final int BASE_CARD_WIDTH = 64;
    private static final int BASE_CARD_HEIGHT = 74;
    private static final int BASE_CARD_GAP = 6;
    private static final int BASE_SEARCH_HEIGHT = 16;

    private static final int TAB_SPACING = 2;
    private static final int CARD_INNER_PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int PREVIEW_THUMB_MAX = 72;
    private static final int SCROLL_STEP = 24;
    private static final int MAX_SEARCH_LENGTH = 48;
    private static final int LEFT_BUTTON = 0;

    /** 每帧最多新烘焙多少件时装的资源（模型 + 贴图）。 */
    private static final int MAX_BAKES_PER_FRAME = 2;

    /** 全屏遮罩颜色（0x99 = 60% 不透明）。 */
    private static final int OVERLAY_COLOR = 0x99000000;
    /** 缩略图占位底色。 */
    private static final int THUMB_PLACEHOLDER_COLOR = 0xFF15151F;
    /** 已穿标记。 */
    private static final String WORN_MARK = "✓";
    private static final String EMPTY_VALUE = "—";

    // ==================== 状态 ====================

    /** 打开本界面之前的界面；{@code null} 表示回到游戏（ESC / 关闭按钮都走它）。 */
    private final Screen parent;

    /** 打开时间，用于 {@code animationDuration} 淡入。 */
    private final long openedAtMs = System.currentTimeMillis();

    /** 已解析出来的缩略图（时装 ID → 贴图 ID，失败存 {@code null}，避免每帧重试）。 */
    private final Map<String, Identifier> thumbnails = new HashMap<>();

    /** 左侧分类 tab（"全部"的 category 为 {@code null}）。 */
    private final List<TabEntry> tabs = new ArrayList<>();

    private SearchField searchField;
    private Button equipButton;
    private Button unequipButton;
    private Button closeButton;

    /** 当前分类（{@code null} = 全部）。 */
    private CosmeticCategory selectedCategory;
    /** 右侧预览中的时装。 */
    private CosmeticDefinition selected;
    /** 搜索关键词（已转小写）。 */
    private String query = "";
    private int scroll;

    /** 过滤结果缓存：搜索 / 切分类 / 重载时置脏，避免每帧重扫整个注册表。 */
    private List<CosmeticDefinition> filtered = List.of();
    private boolean filteredDirty = true;

    /** 本帧剩余的烘焙预算。 */
    private int bakeBudget;

    /** 预览面板里 hideParts 文本的缓存（拼接会产生垃圾，选中的时装不变就不重算）。 */
    private CosmeticDefinition hidePartsOwner;
    private String hidePartsCache = EMPTY_VALUE;

    /** 界面状态持久化（记住上次选中的分类）；读不到就退化成不记忆。 */
    private UIState uiState;
    private boolean categoryRestored;

    // ==================== 布局（init 里算好） ====================

    private float uiScale = 1.0f;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int padding;
    private int headerHeight;
    private int footerHeight;
    private int tabWidth;
    private int tabHeight;
    private int previewWidth;
    private int buttonHeight;
    private int contentTop;
    private int contentBottom;
    private int gridX;
    private int gridY;
    private int gridWidth;
    private int gridHeight;
    private int searchX;
    private int searchY;
    private int searchWidth;
    private int searchHeight;
    private int cardWidth;
    private int cardHeight;
    private int cardGap;
    private int columns;

    public WardrobeScreen(Screen parent) {
        super(Component.translatable("vmfashion.gui.title"));
        this.parent = parent;
    }

    /** 打开衣柜界面（当前已在其它界面时会替换掉它）。 */
    public static void open() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        // 从游戏内打开：parent 传 null，ESC 直接回到游戏（命令执行时聊天界面还没关，
        // 拿当前 screen 当 parent 会把聊天框又弹回来）
        minecraft.gui.setScreen(new WardrobeScreen(null));
    }

    /**
     * 创建一个带「返回」行为的衣柜界面，供 Mod Menu 的「配置」按钮等外部入口使用。
     *
     * @param parent 关闭后回到的界面；{@code null} 表示回到游戏
     */
    public static Screen createScreen(Screen parent) {
        return new WardrobeScreen(parent);
    }

    /** 关闭后要回到的界面（{@code null} = 游戏）。 */
    public Screen parentScreen() {
        return parent;
    }

    // ==================== 生命周期 / 布局 ====================

    @Override
    protected void init() {
        this.uiScale = resolveUiScale();
        float scale = this.uiScale;

        this.panelWidth = Math.max(240, Math.min(this.width - 8, Math.round(BASE_WIDTH * scale)));
        this.panelHeight = Math.max(170, Math.min(this.height - 8, Math.round(BASE_HEIGHT * scale)));
        this.panelX = (this.width - panelWidth) / 2;
        this.panelY = (this.height - panelHeight) / 2;

        this.padding = Math.max(4, Math.round(ModConfig.UI.PADDING * scale));
        this.headerHeight = Math.max(20, Math.round(BASE_HEADER_HEIGHT * scale));
        this.footerHeight = Math.max(20, Math.round(BASE_FOOTER_HEIGHT * scale));
        this.tabWidth = Math.max(54, Math.round(BASE_TAB_WIDTH * scale));
        this.previewWidth = Math.max(104, Math.round(BASE_PREVIEW_WIDTH * scale));
        this.buttonHeight = Math.max(14, Math.round(ModConfig.UI.BUTTON_HEIGHT * scale));
        this.cardGap = Math.max(4, Math.round(BASE_CARD_GAP * scale));
        this.cardWidth = Math.max(40, Math.round(BASE_CARD_WIDTH * scale));
        this.cardHeight = Math.max(52, Math.round(BASE_CARD_HEIGHT * scale));

        this.contentTop = panelY + headerHeight + padding;
        this.contentBottom = panelY + panelHeight - footerHeight - padding;

        int contentLeft = panelX + padding;
        int contentRight = panelX + panelWidth - padding;
        int tabsRight = contentLeft + tabWidth;
        int previewLeft = contentRight - previewWidth;

        // 搜索框放在网格列顶部，宽度跟网格列一致，避免和标题 / 返回按钮抢空间
        this.searchHeight = Math.max(12, Math.round(BASE_SEARCH_HEIGHT * scale));
        this.searchX = tabsRight + padding;
        this.searchY = contentTop;
        this.searchWidth = Math.max(60, previewLeft - padding - searchX);

        this.gridX = searchX;
        this.gridY = contentTop + searchHeight + padding;
        this.gridWidth = Math.max(cardWidth, previewLeft - padding - gridX);
        this.gridHeight = Math.max(cardHeight, contentBottom - gridY);
        this.columns = Math.max(1, (gridWidth + cardGap) / (cardWidth + cardGap));

        buildTabs(contentLeft, contentTop);
        restoreLastCategory();
        buildWidgets();
        ensureSelectedValid();
        refreshButtons();
    }

    /**
     * 恢复上次选中的分类（{@code ui/UIState} 落盘）。
     *
     * <p>只在第一次 {@code init()} 时恢复：窗口 resize 也会再走 init，
     * 那时不能把玩家刚点的分类冲掉。
     */
    private void restoreLastCategory() {
        if (categoryRestored) {
            return;
        }
        categoryRestored = true;
        try {
            this.uiState = UIState.load();
            this.selectedCategory = parseCategory(uiState.getWardrobeCategory());
        } catch (Throwable t) {
            // 状态文件坏了不该影响衣柜可用性
            this.uiState = null;
            VMFashionClient.LOGGER.warn("读取界面状态失败: {}", t.toString());
        }
    }

    /** 分类 key → 枚举；{@code "all"} / 未知值都按「全部」处理。 */
    private static CosmeticCategory parseCategory(String key) {
        if (key == null || key.isBlank() || "all".equalsIgnoreCase(key)) {
            return null;
        }
        for (CosmeticCategory category : CosmeticCategory.values()) {
            if (category.key().equalsIgnoreCase(key)) {
                return category;
            }
        }
        return null;
    }

    /** 记住当前分类（切 tab 时调用，失败只记日志）。 */
    private void persistCategory() {
        UIState state = this.uiState;
        if (state == null) {
            return;
        }
        try {
            state.setWardrobeCategory(selectedCategory == null ? "all" : selectedCategory.key());
            state.save();
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("保存界面状态失败: {}", t.toString());
        }
    }

    /** 左侧 8 个分类 tab 的位置（数量固定，尺寸随可用高度收缩）。 */
    private void buildTabs(int left, int top) {
        this.tabs.clear();
        int count = 1 + CosmeticCategory.values().length;
        int available = Math.max(0, contentBottom - top);
        int spacing = Math.max(1, Math.round(TAB_SPACING * uiScale));
        int preferred = Math.max(12, Math.round(BASE_TAB_HEIGHT * uiScale));
        // 小屏 / 小缩放时 8 个 tab 会超出内容高度，按可用高度均分
        this.tabHeight = Math.max(10, Math.min(preferred,
                (available - (count - 1) * spacing) / count));

        int y = top;
        tabs.add(new TabEntry(null, Component.translatable("vmfashion.gui.tab.all"),
                left, y, tabWidth, tabHeight));
        y += tabHeight + spacing;
        for (CosmeticCategory category : CosmeticCategory.values()) {
            tabs.add(new TabEntry(category, Component.translatable("vmfashion.gui.tab." + category.key()),
                    left, y, tabWidth, tabHeight));
            y += tabHeight + spacing;
        }
    }

    private void buildWidgets() {
        // 搜索框：复用 gui/component/SearchField（内部包了 EditBox）
        this.searchField = new SearchField(this.font, searchX, searchY, searchWidth, searchHeight);
        this.searchField.setOnSearchChanged(this::onSearchChanged);
        this.searchField.getWidget().setMaxLength(MAX_SEARCH_LENGTH);
        this.addRenderableWidget(this.searchField.getWidget());

        int previewLeft = panelX + panelWidth - padding - previewWidth;
        int buttonsTop = contentBottom - buttonHeight;
        int buttonWidth = Math.max(40, (previewWidth - 3 * padding) / 2);
        this.equipButton = Button.builder(Component.translatable("vmfashion.gui.equip"),
                        button -> equipSelected())
                .bounds(previewLeft + padding, buttonsTop, buttonWidth, buttonHeight)
                .build();
        this.unequipButton = Button.builder(Component.translatable("vmfashion.gui.unequip"),
                        button -> unequipSelected())
                .bounds(previewLeft + padding + buttonWidth + padding, buttonsTop, buttonWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.equipButton);
        this.addRenderableWidget(this.unequipButton);

        // 返回 / 关闭：右上角，位置固定在头部
        int closeWidth = Math.max(38, Math.round(52 * uiScale));
        this.closeButton = Button.builder(Component.translatable(
                        parent == null ? "vmfashion.gui.close" : "vmfashion.gui.back"),
                        button -> onClose())
                .bounds(panelX + panelWidth - padding - closeWidth,
                        panelY + (headerHeight - buttonHeight) / 2,
                        closeWidth, buttonHeight)
                .build();
        this.addRenderableWidget(this.closeButton);
    }

    @Override
    public void onClose() {
        // parent 为 null 时 setScreen(null) 就是"回到游戏"，这正是 Screen#onClose 的默认行为
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        // 衣柜是游戏内叠加界面，单机时不应该暂停世界
        return false;
    }

    @Override
    public boolean isInGameUi() {
        // 在游戏内 → extractTransparentBackground（半透明遮罩，不画全景图 / 泥土背景）；
        // 在标题界面（例如 Mod Menu 的「配置」按钮）→ 保持原版菜单背景
        return this.minecraft != null && this.minecraft.level != null;
    }

    // ==================== 渲染 ====================

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        float alpha = animationAlpha();
        // 打开动画：控件跟着面板一起淡入（Screen#fadeWidgets 会遍历子控件 setAlpha）
        this.fadeWidgets(alpha);
        this.bakeBudget = MAX_BAKES_PER_FRAME;
        ensureFiltered();
        // 穿戴状态可能被命令 / 云端同步改掉，按钮可用状态每帧跟一次，避免显示成过期的
        refreshButtons();

        // 1) 全屏遮罩
        context.fill(0, 0, this.width, this.height, withAlpha(OVERLAY_COLOR, alpha));
        // 2) 面板底板 + 头部
        drawPanel(context, alpha);
        // 3) 左侧分类
        drawTabs(context, mouseX, mouseY, alpha);
        // 4) 中间网格（含空状态）
        drawGrid(context, mouseX, mouseY, alpha);
        // 5) 右侧预览
        drawPreview(context, alpha);
        // 6) 底部状态行
        drawFooter(context, alpha);

        // 控件（搜索框 / 三个按钮）由 Screen 统一绘制
        super.extractRenderState(context, mouseX, mouseY, delta);

        // 全局 Toast：换装 / 脱下的反馈（ToastWidget 内部按 toastEnabled 决定是否显示）
        ToastWidget.render(context);
    }

    private void drawPanel(GuiGraphicsExtractor context, float alpha) {
        context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight,
                withAlpha(ModConfig.UI.BG_COLOR, alpha));
        context.fill(panelX, panelY, panelX + panelWidth, panelY + headerHeight,
                withAlpha(ModConfig.UI.HEADER_COLOR, alpha));
        // 头部下沿分隔线
        context.fill(panelX, panelY + headerHeight, panelX + panelWidth, panelY + headerHeight + 1,
                withAlpha(ModConfig.UI.BORDER_COLOR, alpha));
        context.outline(panelX, panelY, panelWidth, panelHeight,
                withAlpha(ModConfig.UI.BORDER_COLOR, alpha));
        context.text(this.font, this.title, panelX + padding,
                panelY + (headerHeight - this.font.lineHeight) / 2,
                withAlpha(ModConfig.UI.ACCENT_COLOR, alpha));
    }

    private void drawTabs(GuiGraphicsExtractor context, int mouseX, int mouseY, float alpha) {
        for (TabEntry tab : tabs) {
            boolean active = tab.category() == selectedCategory;
            boolean hovered = isInside(mouseX, mouseY, tab.x(), tab.y(), tab.width(), tab.height());
            int background = active ? ModConfig.UI.ACCENT_COLOR
                    : (hovered ? ModConfig.UI.HOVER_COLOR : ModConfig.UI.PANEL_COLOR);
            context.fill(tab.x(), tab.y(), tab.x() + tab.width(), tab.y() + tab.height(),
                    withAlpha(background, alpha));
            context.outline(tab.x(), tab.y(), tab.width(), tab.height(),
                    withAlpha(active ? ModConfig.UI.ACCENT_COLOR : ModConfig.UI.BORDER_COLOR, alpha));
            int textColor = active ? 0xFFFFFFFF : ModConfig.UI.TEXT_COLOR;
            context.centeredText(this.font, tab.label(), tab.x() + tab.width() / 2,
                    tab.y() + (tab.height() - this.font.lineHeight) / 2,
                    withAlpha(textColor, alpha));
        }
    }

    private void drawGrid(GuiGraphicsExtractor context, int mouseX, int mouseY, float alpha) {
        this.scroll = clamp(this.scroll, 0, maxScroll());
        // 裁剪：卡片滚出网格区域时不能画到分类栏 / 预览栏上
        context.enableScissor(gridX, gridY, gridX + gridWidth, gridY + gridHeight);
        try {
            if (filtered.isEmpty()) {
                drawEmptyState(context, alpha);
                return;
            }
            int cellWidth = cardWidth + cardGap;
            int cellHeight = cardHeight + cardGap;
            for (int index = 0; index < filtered.size(); index++) {
                int column = index % columns;
                int row = index / columns;
                int x = gridX + column * cellWidth;
                int y = gridY + row * cellHeight - scroll;
                // 可见区域裁剪：屏幕外的卡片连资源都不碰（这是"懒加载"的关键一步）
                if (y + cardHeight <= gridY || y >= gridY + gridHeight) {
                    continue;
                }
                drawCard(context, filtered.get(index), x, y, mouseX, mouseY, alpha);
            }
        } finally {
            context.disableScissor();
        }
    }

    private void drawEmptyState(GuiGraphicsExtractor context, float alpha) {
        Component message = emptyMessage();
        int textWidth = Math.max(60, gridWidth - 2 * padding);
        int height = this.font.wordWrapHeight(message, textWidth);
        int y = gridY + Math.max(0, (gridHeight - height) / 2);
        context.textWithWordWrap(this.font, message, gridX + padding, y, textWidth,
                withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
    }

    private void drawCard(GuiGraphicsExtractor context, CosmeticDefinition cosmetic,
                          int x, int y, int mouseX, int mouseY, float alpha) {
        boolean worn = isWorn(cosmetic);
        boolean hovered = isInside(mouseX, mouseY, x, y, cardWidth, cardHeight);
        boolean chosen = cosmetic == this.selected;

        int background = (hovered || chosen) ? ModConfig.UI.HOVER_COLOR : ModConfig.UI.PANEL_COLOR;
        context.fill(x, y, x + cardWidth, y + cardHeight, withAlpha(background, alpha));
        int border = worn ? ModConfig.UI.SUCCESS_COLOR
                : (chosen ? ModConfig.UI.ACCENT_COLOR : ModConfig.UI.BORDER_COLOR);
        context.outline(x, y, cardWidth, cardHeight, withAlpha(border, alpha));

        // 缩略图：直接画 CosmeticAssets 注册好的 Identifier
        int thumbSize = Math.max(8, cardWidth - 2 * CARD_INNER_PADDING);
        int thumbX = x + CARD_INNER_PADDING;
        int thumbY = y + CARD_INNER_PADDING;
        Identifier texture = thumbnailFor(cosmetic);
        if (texture != null) {
            // javap 核对：blit(Identifier, int x0, int y0, int x1, int y1, float u0, float v0, float u1, float v1)
            // （u/v 是 0..1 归一化坐标，见 LoomScreen 的调用点；所以不需要贴图尺寸）
            context.blit(texture, thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    0.0f, 0.0f, 1.0f, 1.0f);
        } else {
            context.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    withAlpha(THUMB_PLACEHOLDER_COLOR, alpha));
            context.centeredText(this.font, categoryLabel(cosmetic.category()),
                    thumbX + thumbSize / 2,
                    thumbY + (thumbSize - this.font.lineHeight) / 2,
                    withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
        }

        // 名称（超出卡片宽度就截断）
        drawCenteredTruncated(context, cosmetic.name(), x + cardWidth / 2,
                thumbY + thumbSize + 2, thumbSize, withAlpha(ModConfig.UI.TEXT_COLOR, alpha));

        // 已穿标记（右上角）
        if (worn) {
            context.text(this.font, WORN_MARK,
                    x + cardWidth - this.font.width(WORN_MARK) - 3, y + 3,
                    withAlpha(ModConfig.UI.SUCCESS_COLOR, alpha));
        }
    }

    private void drawPreview(GuiGraphicsExtractor context, float alpha) {
        int left = panelX + panelWidth - padding - previewWidth;
        int right = panelX + panelWidth - padding;
        int top = contentTop;
        int bottom = contentBottom - buttonHeight - padding;
        context.fill(left, top, right, bottom, withAlpha(ModConfig.UI.PANEL_COLOR, alpha));
        context.outline(left, top, previewWidth, Math.max(0, bottom - top),
                withAlpha(ModConfig.UI.BORDER_COLOR, alpha));

        CosmeticDefinition cosmetic = this.selected;
        if (cosmetic == null) {
            context.textWithWordWrap(this.font, Component.translatable("vmfashion.gui.preview.hint"),
                    left + padding, top + padding, Math.max(40, previewWidth - 2 * padding),
                    withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
            return;
        }

        int thumbSize = Math.max(16, Math.min(PREVIEW_THUMB_MAX, previewWidth - 2 * padding));
        int thumbX = left + (previewWidth - thumbSize) / 2;
        int thumbY = top + padding;
        Identifier texture = thumbnailFor(cosmetic);
        if (texture != null) {
            context.blit(texture, thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    0.0f, 0.0f, 1.0f, 1.0f);
        } else {
            context.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    withAlpha(THUMB_PLACEHOLDER_COLOR, alpha));
        }

        int textX = left + padding;
        int textWidth = Math.max(40, previewWidth - 2 * padding);
        int y = thumbY + thumbSize + padding;
        y = drawLabelValue(context, "vmfashion.gui.preview.name", cosmetic.name(),
                textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.id", cosmetic.id(),
                textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.category",
                categoryLabel(cosmetic.category()).getString(), textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.bone", cosmetic.attachedBone(),
                textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.hide_parts", hidePartsText(cosmetic),
                textX, y, textWidth, alpha);
        drawLabelValue(context, "vmfashion.gui.preview.scale", String.valueOf(cosmetic.scale()),
                textX, y, textWidth, alpha);
    }

    private void drawFooter(GuiGraphicsExtractor context, float alpha) {
        int top = panelY + panelHeight - footerHeight;
        context.fill(panelX, top, panelX + panelWidth, panelY + panelHeight,
                withAlpha(ModConfig.UI.PANEL_COLOR, alpha));
        context.fill(panelX, top, panelX + panelWidth, top + 1,
                withAlpha(ModConfig.UI.BORDER_COLOR, alpha));

        int textX = panelX + padding;
        int maxWidth = Math.max(40, panelWidth - 2 * padding);
        // 两个 describeStatus 都不属于本模组界面，别的模块正在并行改；抛异常不能连累整个界面
        String syncStatus = safeDescribe(() -> SyncService.getInstance().describeStatus());
        String assetStatus = safeDescribe(() -> AssetSyncService.getInstance().describeStatus());
        drawTruncated(context, Component.translatable("vmfashion.gui.status.sync", syncStatus).getString(),
                textX, top + 3, maxWidth, withAlpha(ModConfig.UI.TEXT_COLOR, alpha));
        drawTruncated(context, Component.translatable("vmfashion.gui.status.assets", assetStatus).getString(),
                textX, top + 3 + LINE_HEIGHT, maxWidth, withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
    }

    // ==================== 交互 ====================

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // 先让控件（搜索框 / 按钮）处理，它们命中就不再做卡片判定
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() != LEFT_BUTTON) {
            return false;
        }
        // 搜索词可能在上一帧刚变（EditBox 的回调先于渲染），命中判定前先同步过滤结果
        ensureFiltered();
        double mouseX = event.x();
        double mouseY = event.y();

        for (TabEntry tab : tabs) {
            if (isInside(mouseX, mouseY, tab.x(), tab.y(), tab.width(), tab.height())) {
                if (tab.category() != selectedCategory) {
                    selectedCategory = tab.category();
                    scroll = 0;
                    filteredDirty = true;
                    ensureSelectedValid();
                    refreshButtons();
                    persistCategory();
                }
                return true;
            }
        }

        if (isInside(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight)) {
            int index = cardIndexAt(mouseX, mouseY);
            if (index >= 0) {
                this.selected = filtered.get(index);
                refreshButtons();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) {
            return true;
        }
        if (!isInside(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight)) {
            return false;
        }
        int delta = (int) Math.round(scrollY * SCROLL_STEP);
        if (delta == 0) {
            return false;
        }
        this.scroll = clamp(this.scroll - delta, 0, maxScroll());
        return true;
    }

    /** 点在网格的第几件上；不在卡片上返回 {@code -1}。 */
    private int cardIndexAt(double mouseX, double mouseY) {
        if (columns <= 0 || filtered.isEmpty()) {
            return -1;
        }
        int cellWidth = cardWidth + cardGap;
        int cellHeight = cardHeight + cardGap;
        int relativeX = (int) (mouseX - gridX);
        int relativeY = (int) (mouseY - gridY) + scroll;
        if (relativeX < 0 || relativeY < 0) {
            return -1;
        }
        int column = relativeX / cellWidth;
        int row = relativeY / cellHeight;
        if (column >= columns) {
            return -1;
        }
        // 点在卡片之间的空隙上不算命中
        if (relativeX % cellWidth > cardWidth || relativeY % cellHeight > cardHeight) {
            return -1;
        }
        int index = row * columns + column;
        return index >= 0 && index < filtered.size() ? index : -1;
    }

    // ==================== 换装（和命令同一条路径） ====================

    private void equipSelected() {
        CosmeticDefinition cosmetic = this.selected;
        if (cosmetic == null) {
            return;
        }
        LocalLoadout.getInstance().wear(cosmetic);
        // 与 /vmfashion equip 完全一致：本地状态 + 上报后端
        SyncService.getInstance().uploadLocalLoadout();
        ToastWidget.showSuccess(Component.translatable("vmfashion.gui.toast.equipped", cosmetic.name()));
        refreshButtons();
    }

    private void unequipSelected() {
        CosmeticCategory category = selected != null ? selected.category() : selectedCategory;
        if (category == null) {
            return;
        }
        String removed = LocalLoadout.getInstance().unwear(category.key());
        if (removed == null) {
            return;
        }
        SyncService.getInstance().uploadLocalLoadout();
        ToastWidget.showSuccess(Component.translatable("vmfashion.gui.toast.unequipped", removed));
        refreshButtons();
    }

    private void refreshButtons() {
        if (equipButton != null) {
            equipButton.active = selected != null && !isWorn(selected);
        }
        if (unequipButton != null) {
            CosmeticCategory category = selected != null ? selected.category() : selectedCategory;
            unequipButton.active = category != null
                    && LocalLoadout.getInstance().get(category.key()) != null;
        }
    }

    private static boolean isWorn(CosmeticDefinition cosmetic) {
        if (cosmetic == null) {
            return false;
        }
        String wornId = LocalLoadout.getInstance().get(cosmetic.category().key());
        return cosmetic.id().equals(wornId);
    }

    // ==================== 过滤 / 选择 ====================

    private void onSearchChanged(String text) {
        this.query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        this.scroll = 0;
        this.filteredDirty = true;
    }

    private void ensureFiltered() {
        if (!filteredDirty) {
            return;
        }
        filteredDirty = false;
        List<CosmeticDefinition> result = new ArrayList<>();
        for (CosmeticDefinition cosmetic : CosmeticRegistry.getInstance().all()) {
            if (cosmetic == null) {
                continue;
            }
            if (selectedCategory != null && cosmetic.category() != selectedCategory) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(cosmetic)) {
                continue;
            }
            result.add(cosmetic);
        }
        this.filtered = result;
    }

    private boolean matchesQuery(CosmeticDefinition cosmetic) {
        String name = cosmetic.name();
        if (name != null && name.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return cosmetic.id().toLowerCase(Locale.ROOT).contains(query);
    }

    /** 选中的时装可能因为 {@code /vmfashion reload} 已经不存在了，重开界面时要清掉。 */
    private void ensureSelectedValid() {
        if (selected == null) {
            return;
        }
        if (CosmeticRegistry.getInstance().find(selected.id()) == null) {
            this.selected = null;
        }
    }

    private int maxScroll() {
        ensureFiltered();
        if (filtered.isEmpty() || columns <= 0) {
            return 0;
        }
        int rows = (filtered.size() + columns - 1) / columns;
        int contentHeight = rows * (cardHeight + cardGap) - cardGap;
        return Math.max(0, contentHeight - gridHeight);
    }

    private Component emptyMessage() {
        if (CosmeticRegistry.getInstance().size() == 0) {
            return Component.translatable("vmfashion.gui.empty.no_packs");
        }
        if (!query.isEmpty()) {
            return Component.translatable("vmfashion.gui.empty.no_match");
        }
        return Component.translatable("vmfashion.gui.empty.no_category");
    }

    private static Component categoryLabel(CosmeticCategory category) {
        return category == null
                ? Component.translatable("vmfashion.gui.tab.all")
                : Component.translatable("vmfashion.gui.tab." + category.key());
    }

    private String hidePartsText(CosmeticDefinition cosmetic) {
        if (cosmetic != hidePartsOwner) {
            hidePartsOwner = cosmetic;
            Set<String> parts = cosmetic.hideParts();
            hidePartsCache = (parts == null || parts.isEmpty()) ? EMPTY_VALUE : String.join(",", parts);
        }
        return hidePartsCache;
    }

    // ==================== 资源（懒加载） ====================

    /**
     * 取一件时装的缩略图；没烘焙过就现烘焙，但受每帧预算限制。
     *
     * <p>{@link CosmeticAssets#get} 会烘焙模型并上传贴图，是"第一次看到才付钱"的操作。
     * 失败结果也缓存（值为 {@code null}），否则一件坏资源会每帧重试、日志刷屏。
     */
    private Identifier thumbnailFor(CosmeticDefinition cosmetic) {
        if (cosmetic == null) {
            return null;
        }
        String key = cosmetic.id();
        if (thumbnails.containsKey(key)) {
            return thumbnails.get(key);
        }
        if (bakeBudget <= 0) {
            // 本帧预算用完：先画占位图，下一帧继续
            return null;
        }
        bakeBudget--;
        Identifier texture = null;
        try {
            CosmeticAssets.Entry entry = CosmeticAssets.get(cosmetic);
            texture = entry == null ? null : entry.texture();
        } catch (Throwable t) {
            // 单件资源坏掉只影响这一张卡片
            VMFashionClient.LOGGER.warn("衣柜缩略图加载失败 {}: {}", key, t.toString());
        }
        thumbnails.put(key, texture);
        return texture;
    }

    // ==================== 配置 ====================

    private static ModConfig configOrNull() {
        VMFashionClient client = VMFashionClient.getInstance();
        return client == null ? null : client.getConfig();
    }

    private static float resolveUiScale() {
        ModConfig config = configOrNull();
        if (config == null) {
            return 1.0f;
        }
        float scale = config.getUiScale();
        // 配置已经从文件反序列化过一遍，这里再兜一次底：非法值不能让布局算成 0 尺寸
        return Float.isFinite(scale) && scale > 0.0f ? scale : 1.0f;
    }

    /** 打开动画进度 0..1；{@code animationDuration <= 0} 表示不做动画。 */
    private float animationAlpha() {
        ModConfig config = configOrNull();
        int duration = config == null ? 0 : config.getAnimationDuration();
        if (duration <= 0) {
            return 1.0f;
        }
        long elapsed = System.currentTimeMillis() - openedAtMs;
        if (elapsed <= 0) {
            return 0.0f;
        }
        if (elapsed >= duration) {
            return 1.0f;
        }
        return (float) elapsed / (float) duration;
    }

    // ==================== 绘制工具 ====================

    /** 给颜色乘一个透明度系数（用于打开动画淡入）。 */
    private static int withAlpha(int color, float factor) {
        if (factor >= 1.0f) {
            return color;
        }
        if (factor <= 0.0f) {
            return color & 0x00FFFFFF;
        }
        int a = (int) (((color >>> 24) & 0xFF) * factor);
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** 单行"标签：值"，返回下一行的 y。 */
    private int drawLabelValue(GuiGraphicsExtractor context, String labelKey, String value,
                               int x, int y, int maxWidth, float alpha) {
        Component label = Component.translatable(labelKey);
        context.text(this.font, label, x, y, withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
        int labelWidth = this.font.width(label) + 4;
        drawTruncated(context, value, x + labelWidth, y, Math.max(10, maxWidth - labelWidth),
                withAlpha(ModConfig.UI.TEXT_COLOR, alpha));
        return y + LINE_HEIGHT;
    }

    private void drawTruncated(GuiGraphicsExtractor context, String text, int x, int y,
                               int maxWidth, int color) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return;
        }
        String shown = this.font.width(text) <= maxWidth
                ? text
                : this.font.plainSubstrByWidth(text, Math.max(1, maxWidth - this.font.width("…"))) + "…";
        context.text(this.font, shown, x, y, color);
    }

    private void drawCenteredTruncated(GuiGraphicsExtractor context, String text, int centerX, int y,
                                       int maxWidth, int color) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return;
        }
        String shown = this.font.width(text) <= maxWidth
                ? text
                : this.font.plainSubstrByWidth(text, Math.max(1, maxWidth - this.font.width("…"))) + "…";
        context.text(this.font, shown, centerX - this.font.width(shown) / 2, y, color);
    }

    /**
     * 调别的模块的 {@code describeStatus()}。
     *
     * <p>界面渲染不能被别的模块的异常打断：抛了就显示占位符，日志留给它们自己。
     */
    private static String safeDescribe(Supplier<String> supplier) {
        try {
            String text = supplier.get();
            return text == null ? EMPTY_VALUE : text;
        } catch (Throwable t) {
            return EMPTY_VALUE;
        }
    }

    /** 左侧分类 tab。{@code category == null} 表示「全部」。 */
    private record TabEntry(CosmeticCategory category, Component label,
                            int x, int y, int width, int height) {
    }
}
