package com.venus.vmfashion.gui;

import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticCategory;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.cosmetic.CosmeticPack;
import com.venus.vmfashion.cosmetic.CosmeticRegistry;
import com.venus.vmfashion.cosmetic.LocalLoadout;
import com.venus.vmfashion.cosmetic.WardrobeCollection;
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
import net.minecraft.network.chat.MutableComponent;
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
 * <p><b>数据来源（网站衣柜收藏）</b>：列表不再直接等于"本地装了哪些包"，而是网站
 * 「我的衣柜」里的收藏（{@link WardrobeCollection}，由同步层拉取）：
 * <ul>
 *   <li>收藏里本地<b>没装包</b>的时装也会列出来（名字 / 分类来自服务端），卡片显示「未下载」，
 *       点它就直接穿上并自动触发时装包下发；</li>
 *   <li>未绑定网站账号 / 收藏还没加载 / 配置里关了「只显示衣柜收藏」→ 退回"本地全部"，
 *       并在底部状态行说明原因（绝不让玩家面对一个莫名其妙的空衣柜）；</li>
 *   <li>打开界面时顺手做一次"过期就刷新"（{@link SyncService#refreshWardrobeIfStale(long)}），
 *       异步执行、异常吞掉 —— 同步层挂了不能连累界面。</li>
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
    /**
     * 底部状态行高度。
     *
     * <p>三行：同步状态 / 资产下发状态 / <b>衣柜数据来源</b>（收藏 N 件、未绑定、未加载、开关关闭）。
     * 来源说明是这次「衣柜只显示网站收藏」改动里最容易被玩家问"我东西去哪了"的地方，
     * 所以单独占一行常驻，而不是塞进 Tooltip。
     */
    private static final int BASE_FOOTER_HEIGHT = 34;
    private static final int BASE_TAB_WIDTH = 74;
    private static final int BASE_TAB_HEIGHT = 18;
    private static final int BASE_PREVIEW_WIDTH = 150;
    private static final int BASE_CARD_WIDTH = 64;
    private static final int BASE_CARD_HEIGHT = 74;
    private static final int BASE_CARD_GAP = 6;
    private static final int BASE_SEARCH_HEIGHT = 16;

    /**
     * 三栏布局的**最小**基准尺寸（base 单位，未乘缩放）。
     *
     * <p>分类栏 + 1 列卡片 + 预览栏 + 4 个 padding 是这套布局不可再压缩的下限；
     * {@code init()} 用它反推"实际能用的缩放"，保证面板被屏幕夹小 / uiScale 很大时
     * 三栏仍然放得下（否则网格会被画到预览栏上）。
     */
    private static final int BASE_MIN_WIDTH = BASE_TAB_WIDTH + BASE_CARD_WIDTH + BASE_PREVIEW_WIDTH
            + 4 * ModConfig.UI.PADDING;
    /** 高度方向的下限：头部 + 搜索框 + 1 行卡片 + 底部三行状态 + 3 个 padding。 */
    private static final int BASE_MIN_HEIGHT = BASE_HEADER_HEIGHT + BASE_SEARCH_HEIGHT + BASE_CARD_HEIGHT
            + BASE_FOOTER_HEIGHT + 3 * ModConfig.UI.PADDING;

    private static final int TAB_SPACING = 2;
    private static final int CARD_INNER_PADDING = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int PREVIEW_THUMB_MAX = 72;
    private static final int SCROLL_STEP = 24;
    private static final int MAX_SEARCH_LENGTH = 48;
    /**
     * 鼠标左键的按钮编号。
     *
     * <p>26.3 的鼠标事件由 SDL3 上报，按钮编号从 <b>1</b> 开始（左 1 / 中 2 / 右 3），
     * 不再是 GLFW 的 0/2/1。原版 {@code AbstractWidget#isValidClickButton} 同样只认 1。
     * 之前这里写 0，导致自定义绘制的分类 tab 和卡片把所有左键点击都判成"不是左键"直接吞掉
     * （原版 Button 不受影响，因为它们按 {@code isValidClickButton} 判定），表现就是
     * 左侧分类栏和中间卡片点了没反应。
     */
    private static final int LEFT_BUTTON = 1;

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

    /** 已解析出来的缩略图（时装 ID → 贴图 ID；失败不写这里，见 {@link #thumbFailures}）。 */
    private final Map<String, Identifier> thumbnails = new HashMap<>();

    /**
     * 缩略图加载失败的记录（时装 ID → 失败时间戳），用来显示"加载失败"角标并允许稍后重试
     * （audit v1.2 N08）。
     *
     * <p>为什么要带时间戳：{@link CosmeticAssets#get} 内部对失败有 5 分钟冷却，界面这里每
     * {@value #THUMB_RETRY_MS}ms 才重试一次，既不会每帧刷日志，也不至于"一次失败就永久占位"。
     */
    private final Map<String, Long> thumbFailures = new HashMap<>();
    /** 缩略图失败后多久允许重试（毫秒）。 */
    private static final long THUMB_RETRY_MS = 30_000L;
    /** 卡片角标：正在下载。 */
    private static final String PENDING_MARK = "…";
    /** 卡片角标：加载失败。 */
    private static final String FAILED_MARK = "!";
    /**
     * 卡片角标：收藏里有、本地没装包（文案走语言文件 {@value}，中英双语）。
     *
     * <p>和 {@link #PENDING_MARK}/{@link #FAILED_MARK} 是同一套角标机制：画在卡片左上角，
     * 只是这两个是单字符、这个是短词（"未下载"），所以宽度要截断。
     */
    private static final String NOT_INSTALLED_MARK = "vmfashion.gui.card.not_installed";
    /** 卡片角标：未下载 + 正在下载中（包已入队）。 */
    private static final String DOWNLOADING_MARK = "vmfashion.gui.card.downloading";
    /**
     * 卡片角标：已下架（服务端目录里删除 / 停用）。
     *
     * <p>语义和上面两个完全不同：{@link #NOT_INSTALLED_MARK} 是"本地没装包、点一下就下载"，
     * 这个角标是"后端已经不要它了，点了也不会有任何结果"——所以它必须盖掉"未下载"角标，
     * 并且点击不触发下载 / 穿戴。
     */
    private static final String MISSING_MARK = "vmfashion.gui.card.missing";
    /** 已下架卡片悬停时的解释文案。 */
    private static final String MISSING_HINT = "vmfashion.gui.card.missing_hint";
    /** 角标底色（压在半透明缩略图上，保证文字可读）。 */
    private static final int BADGE_BG_COLOR = 0xCC000000;

    /** 打开衣柜时，"收藏多久没刷新才重新拉一次"（毫秒）。 */
    private static final long WARDROBE_REFRESH_INTERVAL_MS = 60_000L;

    /**
     * 底部状态行的重算间隔（毫秒）。
     *
     * <p>状态行每帧都要画，但内容（同步状态 / 资产状态 / 数据来源）不是动画：
     * 250ms 一次既看不出延迟，又能把每帧 3 个 {@link Component} + 3 个字符串的分配压掉。
     */
    private static final long FOOTER_CACHE_MS = 250L;

    /** 底部三行已经算好的纯文本（见 {@link #rebuildFooterLinesIfStale()}）。 */
    private String footerSyncLine;
    private String footerAssetsLine;
    private String footerSourceLine;
    /** 上面三行是什么时候算的（毫秒）；时钟回拨时也会重算。 */
    private long footerBuiltAtMs;

    /** 左侧分类 tab（"全部"的 category 为 {@code null}）。 */
    private final List<TabEntry> tabs = new ArrayList<>();

    private SearchField searchField;
    private Button equipButton;
    private Button unequipButton;
    private Button closeButton;

    /** 当前分类（{@code null} = 全部）。 */
    private CosmeticCategory selectedCategory;
    /** 右侧预览中的条目（{@code definition == null} 表示本地没装包）。 */
    private WardrobeEntry selected;
    /** 搜索关键词（已转小写）。 */
    private String query = "";
    private int scroll;

    /** 过滤结果缓存：搜索 / 切分类 / 重载时置脏，避免每帧重扫整个注册表。 */
    private List<WardrobeEntry> filtered = List.of();
    private boolean filteredDirty = true;

    /**
     * 上一次构建条目时看到的数据源快照，用来发现"收藏在后台刷新完了"。
     *
     * <p>{@link WardrobeCollection#replaceAll} 是网络回调线程写的，界面不会收到通知；
     * 这里每帧比一下几个 volatile 字段 + 列表引用（都是内存读，零分配），变了才重建。
     */
    private List<WardrobeCollection.Entry> sourceEntriesRef;
    private boolean sourceLinked;
    private boolean sourceLoaded;
    private boolean sourceFilterEnabled;
    /**
     * 已加载时装包的快照。
     *
     * <p>为什么要盯它：未下载的收藏点穿上后会触发时装包下发，装完 {@code PackLoader.reload()}
     * 会整体替换注册表 —— 界面得跟着把那一件从「未下载」变成正常卡片（不用重开界面）。
     */
    private List<CosmeticPack> sourcePacksRef;
    /** 打开界面时只请求一次"过期就刷新"，窗口 resize 会再走 init，不能每次都拉。 */
    private boolean wardrobeRefreshRequested;

    /** 本帧剩余的烘焙预算。 */
    private int bakeBudget;

    /**
     * 预览栏可用的最小宽度（逻辑像素）。小于它就没法显示缩略图 + 文字，整栏隐藏（见 {@link #init()}）。
     *
     * <p>取 110：`BASE_PREVIEW_WIDTH=150` 的 2/3，再小下去缩略图（最大 72）+ 两行文字就挤成一团；
     * 实测 256×200 逻辑像素时预览栏仍有 116 宽（刚好够），220–240 宽时降到 ~104 → 走降级。
     */
    private static final int MIN_PREVIEW_WIDTH = 110;

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
    /**
     * 窗口太窄时预览栏被整栏隐藏（降级模式，见 {@link #init()}）。
     *
     * <p>此时 {@code previewWidth == 0}、网格吃满剩余宽度、穿上/脱下按钮搬到网格下方。
     */
    private boolean previewHidden;

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
        float wantedScale = resolveUiScale();

        // 面板尺寸先按屏幕夹住。旧写法是 max(240, min(width - 8, …))：窗口比 248 还窄时
        // 下限 240 会赢，面板左右各溢出屏幕一截（右半边的按钮永远点不到）。
        int maxPanelWidth = Math.max(1, this.width - 8);
        int maxPanelHeight = Math.max(1, this.height - 8);
        this.panelWidth = Math.min(maxPanelWidth, Math.max(240, Math.round(BASE_WIDTH * wantedScale)));
        this.panelHeight = Math.min(maxPanelHeight, Math.max(170, Math.round(BASE_HEIGHT * wantedScale)));
        this.panelX = (this.width - panelWidth) / 2;
        this.panelY = (this.height - panelHeight) / 2;

        // 内部三栏（分类 / 网格 / 预览）的尺寸必须跟着**实际面板**收缩，而不是只跟着 uiScale。
        // 面板被屏幕夹小之后仍按 uiScale 算，就会出现"网格和搜索框画到预览栏上、点击判定却
        // 仍然覆盖被遮住的那块区域"（幽灵点击）；uiScale = 2 配 480 宽的 GUI 必现。
        // 约束：三栏最小布局（分类 + 1 列卡片 + 预览 + 4 个 padding）必须放得下面板。
        float fitWidth = panelWidth / (float) BASE_MIN_WIDTH;
        float fitHeight = panelHeight / (float) BASE_MIN_HEIGHT;
        float scale = Math.min(wantedScale, Math.min(fitWidth, fitHeight));
        // 极端小窗口下也别把布局压成 0：下限 0.35 仍能画出一列卡片
        scale = Math.max(0.35f, scale);
        this.uiScale = scale;

        this.padding = Math.max(4, Math.round(ModConfig.UI.PADDING * scale));
        this.headerHeight = Math.max(20, Math.round(BASE_HEADER_HEIGHT * scale));
        // 底部固定三行状态文字（行高 LINE_HEIGHT 不随 uiScale 缩放，字体是固定像素），
        // 所以下限就是基准高度：小缩放时宁可让面板高一点，也不能把第三行画到面板外面
        this.footerHeight = Math.max(BASE_FOOTER_HEIGHT, Math.round(BASE_FOOTER_HEIGHT * scale));
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
        // 降级：面板窄到"分类 + 一列卡片 + 预览"都放不下时（缩放被压到 0.35–0.625 区间，
        // 卡片 / 预览的**下限**尺寸开始赢过可用空间），把预览栏整栏隐藏，宽度全给网格。
        //
        // 为什么必须这么做：预览面板在 drawGrid 之后绘制（不透明底色），会把越界的网格盖住；
        // 而点击判定用的仍是 gridX..gridX+gridWidth —— 于是"看得见的是预览区、点下去命中的是卡片"
        // （实测 200×150 逻辑像素时重叠 21px，点预览缩略图会选中 / 穿上被遮住的卡片）。
        // 少一栏只是信息少，点错是错误操作，必须消掉。
        int gridBudget = contentRight - tabsRight - 2 * padding;
        // 先试着把预览栏压到"刚好给网格留出一整列卡片"：能留就留（只压窄，不隐藏），
        // 压到 MIN_PREVIEW_WIDTH 还留不出来才降级整栏隐藏。
        int previewWanted = Math.min(previewWidth, Math.max(0, gridBudget - cardWidth));
        this.previewHidden = previewWanted < MIN_PREVIEW_WIDTH;
        this.previewWidth = previewHidden ? 0 : previewWanted;
        int previewLeft = contentRight - previewWidth;

        // 搜索框放在网格列顶部，宽度跟网格列一致，避免和标题 / 返回按钮抢空间
        this.searchHeight = Math.max(12, Math.round(BASE_SEARCH_HEIGHT * scale));
        this.searchX = tabsRight + padding;
        this.searchY = contentTop;
        // 小窗口下宁可让搜索框窄一点，也不能压到预览栏上：搜索框是控件，画在预览栏之后，
        // 越界的那几像素会盖住预览面板（而且点上去是控件命中，不是卡片命中）。
        int searchSpace = Math.max(0, previewLeft - padding - searchX);
        this.searchWidth = Math.max(24, Math.min(Math.max(60, searchSpace), searchSpace));

        this.gridX = searchX;
        this.gridY = contentTop + searchHeight + padding;
        // 网格宽度**永远不越过** previewLeft：不加 max(cardWidth, …)，否则极小窗口下
        // 网格会伸到预览栏底下（看得见的是预览、点得中的是卡片）。宁可一列只露出半张卡片。
        this.gridWidth = Math.max(1, previewLeft - padding - gridX);
        // 预览栏隐藏时按钮搬到网格下方，网格底部要给按钮留出高度，否则按钮会压住卡片
        int gridBottom = previewHidden ? contentBottom - buttonHeight - padding : contentBottom;
        this.gridHeight = Math.max(cardHeight, gridBottom - gridY);
        this.columns = Math.max(1, (gridWidth + cardGap) / (cardWidth + cardGap));

        buildTabs(contentLeft, contentTop);
        restoreLastCategory();
        buildWidgets();
        // 打开界面时顺手刷新一次收藏（异步、过期才拉、异常吞掉）
        requestWardrobeRefresh();
        ensureFiltered();
        refreshButtons();
    }

    /**
     * 打开界面时请求一次"收藏过期就刷新"。
     *
     * <p>三点要求，缺一不可：
     * <ul>
     *   <li><b>异步</b>：{@link SyncService#refreshWardrobeIfStale(long)} 自己丢 worker，界面不等它；</li>
     *   <li><b>非阻塞</b>：拿到新数据后由 {@link #ensureFiltered()} 的签名比对自动重建，不需要回调；</li>
     *   <li><b>异常吞掉</b>：同步层是另一个模块（甚至可能是旧版本），它抛什么都不能让衣柜打不开。</li>
     * </ul>
     */
    private void requestWardrobeRefresh() {
        if (wardrobeRefreshRequested) {
            return;
        }
        wardrobeRefreshRequested = true;
        try {
            SyncService.getInstance().refreshWardrobeIfStale(WARDROBE_REFRESH_INTERVAL_MS);
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("刷新衣柜收藏失败（界面继续用当前数据）: {}", t.toString());
        }
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

        int buttonsTop = contentBottom - buttonHeight;
        int buttonsLeft;
        int buttonWidth;
        if (previewHidden) {
            // 降级模式：没有预览栏，按钮均分网格宽度放在网格下方（init() 已给按钮留出高度）
            buttonsLeft = gridX;
            buttonWidth = Math.max(30, (gridWidth - padding) / 2);
        } else {
            buttonsLeft = panelX + panelWidth - padding - previewWidth + padding;
            buttonWidth = Math.max(40, (previewWidth - 3 * padding) / 2);
        }
        this.equipButton = Button.builder(Component.translatable("vmfashion.gui.equip"),
                        button -> equipSelected())
                .bounds(buttonsLeft, buttonsTop, buttonWidth, buttonHeight)
                .build();
        this.unequipButton = Button.builder(Component.translatable("vmfashion.gui.unequip"),
                        button -> unequipSelected())
                .bounds(buttonsLeft + buttonWidth + padding, buttonsTop, buttonWidth, buttonHeight)
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

    private void drawCard(GuiGraphicsExtractor context, WardrobeEntry entry,
                          int x, int y, int mouseX, int mouseY, float alpha) {
        boolean worn = isWorn(entry);
        boolean hovered = isInside(mouseX, mouseY, x, y, cardWidth, cardHeight);
        boolean chosen = entry == this.selected;
        boolean missing = entry.missing();

        // 已下架（服务端目录里删了 / 停用了）的卡片要一眼看出"不可用"：
        // 底色压暗一档 + 名称走弱化色 + 左上角红色「已下架」角标；选中/悬停时仍然高亮，
        // 否则玩家会以为卡片点不动是界面卡了。
        int background = missing
                ? (hovered || chosen ? ModConfig.UI.HOVER_COLOR : ModConfig.UI.BG_COLOR)
                : ((hovered || chosen) ? ModConfig.UI.HOVER_COLOR : ModConfig.UI.PANEL_COLOR);
        context.fill(x, y, x + cardWidth, y + cardHeight, withAlpha(background, alpha));
        int border = worn ? ModConfig.UI.SUCCESS_COLOR
                : (chosen ? ModConfig.UI.ACCENT_COLOR
                : (missing ? ModConfig.UI.DANGER_COLOR : ModConfig.UI.BORDER_COLOR));
        context.outline(x, y, cardWidth, cardHeight, withAlpha(border, alpha));

        // 缩略图：已安装的走 CosmeticAssets 注册好的 Identifier；未安装的没有贴图，只能画占位
        int thumbSize = Math.max(8, cardWidth - 2 * CARD_INNER_PADDING);
        int thumbX = x + CARD_INNER_PADDING;
        int thumbY = y + CARD_INNER_PADDING;
        Identifier texture = entry.installed() ? thumbnailFor(entry.definition()) : null;
        if (texture != null) {
            // javap 核对：blit(Identifier, int x0, int y0, int x1, int y1, float u0, float v0, float u1, float v1)
            // （u/v 是 0..1 归一化坐标，见 LoomScreen 的调用点；所以不需要贴图尺寸）
            context.blit(texture, thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    0.0f, 0.0f, 1.0f, 1.0f);
        } else {
            // 未安装 / 加载失败 / 本帧预算用完：占位底色 + 分类名（至少能看出这是哪一类）
            context.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    withAlpha(THUMB_PLACEHOLDER_COLOR, alpha));
            context.centeredText(this.font, categoryLabel(entry.category()),
                    thumbX + thumbSize / 2,
                    thumbY + (thumbSize - this.font.lineHeight) / 2,
                    withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
        }

        // 名称（超出卡片宽度就截断）；已下架用弱化色
        drawCenteredTruncated(context, entry.name(), x + cardWidth / 2,
                thumbY + thumbSize + 2, thumbSize,
                withAlpha(missing ? ModConfig.UI.MUTED_COLOR : ModConfig.UI.TEXT_COLOR, alpha));

        // 状态角标（左上角）
        if (missing) {
            // 已下架优先级最高：它既不"未下载"也不"下载中"（后端一定会拒绝下发），
            // 所以绝不能被下面两个分支的文案盖掉。
            int reserved = worn ? this.font.width(WORN_MARK) + 4 : 0;
            drawCornerBadge(context, Component.translatable(MISSING_MARK),
                    x + 2, y + 2, Math.max(0, cardWidth - 4 - reserved),
                    ModConfig.UI.DANGER_COLOR, alpha);
            if (hovered) {
                // 悬停时补一句解释（原版 widget 也是在这个阶段排队 tooltip 的）
                context.setTooltipForNextFrame(Component.translatable(MISSING_HINT), mouseX, mouseY);
            }
        } else if (!entry.installed()) {
            // 收藏里有、本地没装包：优先显示"下载中…"，否则"未下载"（点卡片就会开始下载）
            boolean downloading = isPackPending(packIdOf(entry));
            int reserved = worn ? this.font.width(WORN_MARK) + 4 : 0;
            drawCornerBadge(context,
                    Component.translatable(downloading ? DOWNLOADING_MARK : NOT_INSTALLED_MARK),
                    x + 2, y + 2, Math.max(0, cardWidth - 4 - reserved),
                    downloading ? ModConfig.UI.MUTED_COLOR : ModConfig.UI.ACCENT_COLOR, alpha);
        } else if (thumbFailures.containsKey(entry.id())) {
            context.text(this.font, FAILED_MARK, x + 3, y + 3,
                    withAlpha(ModConfig.UI.DANGER_COLOR, alpha));
        } else if (isPackPending(packIdOf(entry))) {
            context.text(this.font, PENDING_MARK, x + 3, y + 3,
                    withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
        }

        // 已穿标记（右上角）
        if (worn) {
            context.text(this.font, WORN_MARK,
                    x + cardWidth - this.font.width(WORN_MARK) - 3, y + 3,
                    withAlpha(ModConfig.UI.SUCCESS_COLOR, alpha));
        }
    }

    /** 左上角小角标：带底色的一块短文字（单字符角标仍走 {@code context.text}，不浪费底色）。 */
    private void drawCornerBadge(GuiGraphicsExtractor context, Component text, int x, int y,
                                 int maxWidth, int color, float alpha) {
        if (maxWidth <= 0) {
            return;
        }
        String shown = text.getString();
        if (this.font.width(shown) > maxWidth) {
            shown = this.font.plainSubstrByWidth(shown,
                    Math.max(1, maxWidth - this.font.width("…"))) + "…";
        }
        int width = Math.min(maxWidth, this.font.width(shown));
        context.fill(x, y, x + width + 3, y + this.font.lineHeight,
                withAlpha(BADGE_BG_COLOR, alpha));
        context.text(this.font, shown, x + 1, y, withAlpha(color, alpha));
    }

    /** 条目所属包 ID：服务端没给 packId 时从全局 ID（{@code packId:localId}）里拆。 */
    private static String packIdOf(WardrobeEntry entry) {
        String packId = entry.packId();
        if (packId != null && !packId.isBlank()) {
            return packId;
        }
        String id = entry.id();
        int colon = id == null ? -1 : id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : "";
    }

    /** 这个包是否正在自动下发（"下载中"角标）。别的模块抛异常不能让界面崩。 */
    private static boolean isPackPending(String packId) {
        try {
            return AssetSyncService.getInstance().isPackPending(packId);
        } catch (Throwable t) {
            return false;
        }
    }

    private void drawPreview(GuiGraphicsExtractor context, float alpha) {
        if (previewHidden || previewWidth <= 0) {
            // 降级模式：窗口太窄，预览栏整栏隐藏（按钮已搬到网格下方，见 buildWidgets()）
            return;
        }
        int left = panelX + panelWidth - padding - previewWidth;
        int right = panelX + panelWidth - padding;
        int top = contentTop;
        int bottom = contentBottom - buttonHeight - padding;
        context.fill(left, top, right, bottom, withAlpha(ModConfig.UI.PANEL_COLOR, alpha));
        context.outline(left, top, previewWidth, Math.max(0, bottom - top),
                withAlpha(ModConfig.UI.BORDER_COLOR, alpha));

        WardrobeEntry entry = this.selected;
        if (entry == null) {
            context.textWithWordWrap(this.font, Component.translatable("vmfashion.gui.preview.hint"),
                    left + padding, top + padding, Math.max(40, previewWidth - 2 * padding),
                    withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
            return;
        }

        int thumbSize = Math.max(16, Math.min(PREVIEW_THUMB_MAX, previewWidth - 2 * padding));
        int thumbX = left + (previewWidth - thumbSize) / 2;
        int thumbY = top + padding;
        Identifier texture = entry.installed() ? thumbnailFor(entry.definition()) : null;
        if (texture != null) {
            context.blit(texture, thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    0.0f, 0.0f, 1.0f, 1.0f);
        } else {
            // 未安装（或缩略图失败）：占位图 + 分类名，右侧信息仍然完整
            context.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
                    withAlpha(THUMB_PLACEHOLDER_COLOR, alpha));
            context.centeredText(this.font, categoryLabel(entry.category()),
                    thumbX + thumbSize / 2, thumbY + (thumbSize - this.font.lineHeight) / 2,
                    withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
        }

        int textX = left + padding;
        int textWidth = Math.max(40, previewWidth - 2 * padding);
        int y = thumbY + thumbSize + padding;
        y = drawLabelValue(context, "vmfashion.gui.preview.name", entry.name(),
                textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.id", entry.id(),
                textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.category",
                categoryLabel(entry.category()).getString(), textX, y, textWidth, alpha);

        CosmeticDefinition definition = entry.definition();
        if (definition == null) {
            // 本地没装包：没有骨骼 / 隐藏部件 / 缩放可显示，改成"所属包" + 一行说明
            y = drawLabelValue(context, "vmfashion.gui.preview.pack", valueOrDash(packIdOf(entry)),
                    textX, y, textWidth, alpha);
            // 已下架优先：不能再显示"穿上后会自动下载"——后端根本不会再下发这件时装
            boolean missing = entry.missing();
            context.textWithWordWrap(this.font,
                    Component.translatable(missing
                            ? "vmfashion.gui.preview.missing"
                            : "vmfashion.gui.preview.not_installed"),
                    textX, y + 2, textWidth,
                    withAlpha(missing ? ModConfig.UI.DANGER_COLOR : ModConfig.UI.ACCENT_COLOR, alpha));
            return;
        }
        y = drawLabelValue(context, "vmfashion.gui.preview.bone", definition.attachedBone(),
                textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.hide_parts", hidePartsText(definition),
                textX, y, textWidth, alpha);
        y = drawLabelValue(context, "vmfashion.gui.preview.scale", String.valueOf(definition.scale()),
                textX, y, textWidth, alpha);
        if (entry.missing()) {
            // 已安装但已下架：预览里必须说明"不能穿"，否则玩家只会看到一堆正常信息
            context.textWithWordWrap(this.font, Component.translatable("vmfashion.gui.preview.missing"),
                    textX, y + 2, textWidth, withAlpha(ModConfig.UI.DANGER_COLOR, alpha));
        }
    }

    private static String valueOrDash(String value) {
        return value == null || value.isBlank() ? EMPTY_VALUE : value;
    }

    private void drawFooter(GuiGraphicsExtractor context, float alpha) {
        int top = panelY + panelHeight - footerHeight;
        context.fill(panelX, top, panelX + panelWidth, panelY + panelHeight,
                withAlpha(ModConfig.UI.PANEL_COLOR, alpha));
        context.fill(panelX, top, panelX + panelWidth, top + 1,
                withAlpha(ModConfig.UI.BORDER_COLOR, alpha));

        int textX = panelX + padding;
        int maxWidth = Math.max(40, panelWidth - 2 * padding);
        rebuildFooterLinesIfStale();
        drawTruncated(context, footerSyncLine,
                textX, top + 3, maxWidth, withAlpha(ModConfig.UI.TEXT_COLOR, alpha));
        drawTruncated(context, footerAssetsLine,
                textX, top + 3 + LINE_HEIGHT, maxWidth, withAlpha(ModConfig.UI.MUTED_COLOR, alpha));
        // 第三行：这份列表到底从哪来的（收藏 N 件 / 未绑定 / 未加载 / 开关关闭）
        drawTruncated(context, footerSourceLine,
                textX, top + 3 + 2 * LINE_HEIGHT, maxWidth,
                withAlpha(ModConfig.UI.ACCENT_COLOR, alpha));
    }

    /**
     * 重算底部三行文字（{@value #FOOTER_CACHE_MS}ms 一次）。
     *
     * <p>为什么要缓存：这三行每帧要调两个跨模块的 {@code describeStatus()}、拼 3 个
     * {@link Component} 再各取一次 {@code getString()}（60Hz 下约 10 个字符串/秒·帧的垃圾）。
     * 状态行不是动画，250ms 刷新一次肉眼看不出来，但把每帧的分配压到 0。
     *
     * <p>时钟回拨（{@code now < builtAt}）时也重算，避免永远停在旧文案上。
     */
    private void rebuildFooterLinesIfStale() {
        long now = System.currentTimeMillis();
        boolean stale = footerSourceLine == null || now < footerBuiltAtMs
                || now - footerBuiltAtMs >= FOOTER_CACHE_MS;
        if (!stale) {
            return;
        }
        footerBuiltAtMs = now;
        // 两个 describeStatus 都不属于本模组界面（其它成员在并行改）；抛异常不能连累整个界面。
        // 两者现在都返回 Component，文案跟着客户端语言走。
        Component syncStatus = safeComponent(() -> SyncService.getInstance().describeStatus());
        Component assetStatus = safeComponent(() -> AssetSyncService.getInstance().describeStatus());
        // 加载失败的资源数也放进状态行：玩家在界面里就能看到"有几件是坏的"（audit v1.2 N08）
        MutableComponent assetsLine = Component.translatable("vmfashion.gui.status.assets", assetStatus);
        int failed = safeFailureCount();
        if (failed > 0) {
            assetsLine.append(Component.translatable("vmfashion.gui.status.assets_failed", failed));
        }
        footerSyncLine = Component.translatable("vmfashion.gui.status.sync", syncStatus).getString();
        footerAssetsLine = assetsLine.getString();
        footerSourceLine = sourceStatusText().getString();
    }

    /**
     * 底部状态行第三行：当前衣柜列表的数据来源。
     *
     * <p>这段文案是这次改动的"解释器"：玩家发现衣柜里的东西变少了 / 变多了，一眼就能看出原因，
     * 而不是以为模组坏了。四种状态对应 {@link Source}。
     */
    private Component sourceStatusText() {
        return switch (wardrobeSource()) {
            case FILTERED -> {
                MutableComponent text = Component.translatable("vmfashion.gui.source.wardrobe",
                        safeCollectionSize());
                // 已下架的收藏也还在列表里（灰掉 + 「已下架」角标），状态行里给个总数，
                // 玩家才不会以为"这几件怎么点都穿不上"是模组坏了
                int removed = safeMissingCount();
                if (removed > 0) {
                    text.append(Component.translatable("vmfashion.gui.source.removed", removed));
                }
                yield text;
            }
            case UNLINKED -> Component.translatable("vmfashion.gui.source.unlinked");
            case NOT_LOADED -> Component.translatable("vmfashion.gui.source.not_loaded");
            case DISABLED -> Component.translatable("vmfashion.gui.source.disabled");
        };
    }

    /** 收藏件数；同步层抛异常时按 0 算（界面不能因为别的模块崩）。 */
    private static int safeCollectionSize() {
        try {
            return WardrobeCollection.getInstance().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 收藏里"已下架"的件数；同步层抛异常时按 0 算。
     *
     * <p>用 {@link WardrobeCollection#entries()} 一次遍历而不是对每件调 {@code isMissing(id)}：
     * 后者每次都从头线性查一遍，列表大时是 O(n²)。
     */
    private static int safeMissingCount() {
        try {
            List<WardrobeCollection.Entry> entries = WardrobeCollection.getInstance().entries();
            int count = 0;
            for (WardrobeCollection.Entry entry : entries) {
                if (entry != null && entry.missing()) {
                    count++;
                }
            }
            return count;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 资源加载失败件数（诊断用）；拿不到就按 0，界面不能因此崩。 */
    private static int safeFailureCount() {
        try {
            return CosmeticAssets.failureCount();
        } catch (Throwable t) {
            return 0;
        }
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
                    refreshButtons();
                    persistCategory();
                }
                return true;
            }
        }

        if (isInside(mouseX, mouseY, gridX, gridY, gridWidth, gridHeight)) {
            int index = cardIndexAt(mouseX, mouseY);
            if (index >= 0) {
                WardrobeEntry entry = filtered.get(index);
                this.selected = entry;
                // 收藏里有、本地没装包：点一下就直接穿上（同时触发时装包下发），
                // 不用先选中再点"穿上" —— 玩家点它的意图就是"我要穿这个"。
                // 已下架的条目只选中（右侧预览会说明原因），绝不触发下载 / 穿戴：
                // 后端对已下架时装一定拒绝，点了只会让玩家以为界面坏了。
                if (entry.equipable() && !entry.installed() && !isWorn(entry)) {
                    equipEntry(entry);
                }
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
        // 点在卡片之间的空隙上不算命中。
        // 用 >=：卡片占 [0, cardWidth - 1]，从 cardWidth 起就是空隙（旧写法用 >，
        // 卡片右沿 / 下沿那一列像素会被当成命中，点空隙也会选中隔壁卡片）。
        if (relativeX % cellWidth >= cardWidth || relativeY % cellHeight >= cardHeight) {
            return -1;
        }
        int index = row * columns + column;
        return index >= 0 && index < filtered.size() ? index : -1;
    }

    // ==================== 换装（和命令同一条路径） ====================

    private void equipSelected() {
        equipEntry(this.selected);
    }

    /**
     * 穿上一件条目。
     *
     * <p>两条路径：
     * <ul>
     *   <li><b>已安装</b>：{@link LocalLoadout#wear(CosmeticDefinition)}，和 {@code /vmfashion equip}
     *       完全一致（本地状态 + 上报后端）；</li>
     *   <li><b>未安装</b>：{@link LocalLoadout#wear(String, String)} 只记 ID，再请求时装包下发；
     *       包到了之后注册表刷新，渲染层自然就能画出来（界面里那件会从"未下载"变成正常卡片）。</li>
     * </ul>
     *
     * <p><b>已下架（{@code missing}）的条目一律拒绝</b>：后端目录里已经没有它了，
     * 硬写进穿戴表只会在上报时被拒、身上也画不出东西。这里给一条明确的错误提示，
     * 而不是让玩家点了"穿上"什么反应都没有。
     */
    private void equipEntry(WardrobeEntry entry) {
        if (entry == null) {
            return;
        }
        if (!entry.equipable()) {
            ToastWidget.showError(Component.translatable("vmfashion.gui.toast.equip_missing",
                    entry.name()));
            return;
        }
        CosmeticDefinition definition = entry.definition();
        if (definition != null) {
            LocalLoadout.getInstance().wear(definition);
            // 与 /vmfashion equip 完全一致：本地状态 + 上报后端
            SyncService.getInstance().uploadLocalLoadout();
            // 资源加载失败时不能只说"已穿上"：玩家会以为成功了却什么也看不到（audit v1.2 N08）
            if (assetReady(definition)) {
                ToastWidget.showSuccess(Component.translatable("vmfashion.gui.toast.equipped",
                        entry.name()));
            } else {
                ToastWidget.showError(Component.translatable("vmfashion.gui.toast.equip_no_asset",
                        entry.name()));
            }
        } else {
            LocalLoadout.getInstance().wear(entry.category().key(), entry.id());
            // 上报在触发下载之前：就算下载排队很久，网站侧"我穿了什么"也已经是新的
            SyncService.getInstance().uploadLocalLoadout();
            requestMissingPack(entry);
            // 只有真的进了下发队列才说"正在下载"：纯本地模式（没配后端 / 关了自动下发）、
            // 用户自己装的同名包、验签失败过、失败退避窗口里，requestMissing 都是静默返回，
            // 旧代码照样弹"正在下载…"，玩家会一直等一个永远不会来的包。
            if (isPackPending(packIdOf(entry))) {
                ToastWidget.showSuccess(Component.translatable("vmfashion.gui.toast.equipped_downloading",
                        entry.name()));
            } else {
                ToastWidget.showWarning(Component.translatable("vmfashion.gui.toast.equip_no_download",
                        entry.name()));
            }
        }
        refreshButtons();
    }

    /** 请求下发某件未安装时装的包；参数非法 / 同步层抛异常都不能让界面崩。 */
    private static void requestMissingPack(WardrobeEntry entry) {
        try {
            AssetSyncService.getInstance().requestMissing(entry.id());
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("请求时装包下发失败 {}: {}", entry.id(), t.toString());
        }
    }

    /** 这件时装的模型/贴图是否已经能加载（会触发首次烘焙，和缩略图同一条路径）。 */
    private static boolean assetReady(CosmeticDefinition cosmetic) {
        try {
            return CosmeticAssets.get(cosmetic) != null;
        } catch (Throwable t) {
            return false;
        }
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
            // 已下架的条目不能穿：按钮直接置灰（点卡片也不会自动穿，见 mouseClicked）
            equipButton.active = selected != null && selected.equipable() && !isWorn(selected);
        }
        if (unequipButton != null) {
            CosmeticCategory category = selected != null ? selected.category() : selectedCategory;
            unequipButton.active = category != null
                    && LocalLoadout.getInstance().get(category.key()) != null;
        }
    }

    /**
     * 这件条目是不是当前穿着的。
     *
     * <p>两个方向都判：服务端收藏里的 ID 可能是不带包名的短 ID（{@code angel}），
     * 而本地装上包之后条目 ID 会变成全局 ID（{@code lw_angel:angel}）—— 只比一边会出现
     * "明明穿着却显示没穿"。{@code entry.id()} 与本地定义 ID 任一命中都算穿着。
     *
     * <p><b>还要比"短名"</b>（v1.3.1）：未下载的收藏点穿上时写进 {@link LocalLoadout} 的是
     * <em>当时</em>的 ID（短 ID {@code angel}）；包下载完、注册表重建之后同一个条目的
     * {@code entry.id()} 变成全局 ID {@code lw_angel:angel}，而穿戴表里仍然是 {@code angel}。
     * 只比全名的话，卡片会在下载完成的瞬间从"已穿"退回"未穿"（角标消失、穿上按钮重新亮起）。
     */
    private static boolean isWorn(WardrobeEntry entry) {
        if (entry == null) {
            return false;
        }
        String wornId = LocalLoadout.getInstance().get(entry.category().key());
        if (wornId == null) {
            return false;
        }
        if (CosmeticIdMatch.same(wornId, entry.id())) {
            return true;
        }
        CosmeticDefinition definition = entry.definition();
        return definition != null && CosmeticIdMatch.same(wornId, definition.id());
    }

    // ==================== 过滤 / 选择 ====================

    private void onSearchChanged(String text) {
        this.query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        this.scroll = 0;
        this.filteredDirty = true;
    }

    /**
     * 衣柜列表当前的数据来源。
     *
     * <p>优先级：配置开关 &gt; 是否加载过 &gt; 是否绑定。先判"加载过"再判"绑定"：
     * 没加载过时 {@code linked} 一定是 {@code false}，不能把"还没拉到"误报成"未绑定"。
     */
    private Source wardrobeSource() {
        if (!isFilterEnabled()) {
            return Source.DISABLED;
        }
        WardrobeCollection collection = collectionOrNull();
        if (collection == null || !collection.loaded()) {
            return Source.NOT_LOADED;
        }
        return collection.linked() ? Source.FILTERED : Source.UNLINKED;
    }

    /** 配置里的「只显示衣柜收藏」开关；读不到配置时按开启处理（默认值就是 {@code true}）。 */
    private static boolean isFilterEnabled() {
        ModConfig config = configOrNull();
        return config == null || config.isWardrobeFilterEnabled();
    }

    /** 收藏镜像；同步层抛异常时返回 {@code null}（界面退回"本地全部"，绝不崩）。 */
    private static WardrobeCollection collectionOrNull() {
        try {
            return WardrobeCollection.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private void ensureFiltered() {
        markDirtyIfSourceChanged();
        if (!filteredDirty) {
            return;
        }
        filteredDirty = false;
        List<WardrobeEntry> all = buildEntries();
        List<WardrobeEntry> result = new ArrayList<>();
        for (WardrobeEntry entry : all) {
            if (entry == null) {
                continue;
            }
            if (selectedCategory != null && entry.category() != selectedCategory) {
                continue;
            }
            if (!query.isEmpty() && !matchesQuery(entry)) {
                continue;
            }
            result.add(entry);
        }
        this.filtered = result;
        ensureSelectedValid(all);
    }

    /**
     * 数据源（收藏快照 / 绑定状态 / 配置开关 / 已装包）变了就置脏。
     *
     * <p>全是内存读，每帧调也不心疼 —— 收藏是网络回调线程整体替换的、注册表是装包线程整体替换的，
     * 界面都收不到回调，只能靠"快照引用变了"来发现。
     */
    private void markDirtyIfSourceChanged() {
        WardrobeCollection collection = collectionOrNull();
        List<WardrobeCollection.Entry> entriesRef = collection == null ? null : collection.entries();
        boolean linked = collection != null && collection.linked();
        boolean loaded = collection != null && collection.loaded();
        boolean filterEnabled = isFilterEnabled();
        List<CosmeticPack> packsRef = loadedPacksOrNull();
        if (entriesRef != sourceEntriesRef || linked != sourceLinked
                || loaded != sourceLoaded || filterEnabled != sourceFilterEnabled
                || packsRef != sourcePacksRef) {
            sourceEntriesRef = entriesRef;
            sourceLinked = linked;
            sourceLoaded = loaded;
            sourceFilterEnabled = filterEnabled;
            sourcePacksRef = packsRef;
            filteredDirty = true;
        }
    }

    /** 已加载的包列表快照（重载时整体替换，引用变了 = 注册表变了）；拿不到返回 {@code null}。 */
    private static List<CosmeticPack> loadedPacksOrNull() {
        try {
            return CosmeticRegistry.getInstance().packs();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 未过滤的全部条目：过滤生效时来自网站收藏，否则退回"本地装了哪些包"。 */
    private List<WardrobeEntry> buildEntries() {
        if (wardrobeSource() == Source.FILTERED) {
            return buildWardrobeEntries();
        }
        List<WardrobeEntry> result = new ArrayList<>();
        for (CosmeticDefinition definition : CosmeticRegistry.getInstance().all()) {
            if (definition == null) {
                continue;
            }
            result.add(new WardrobeEntry(definition.id(), definition.name(),
                    definition.category(), definition.packId(), definition, false));
        }
        return result;
    }

    /**
     * 网站收藏 → 条目。
     *
     * <p>本地装了包的用本地定义（缩略图 / 预览 / 穿戴全部走老路径，名字也优先本地名，
     * 这样多语言时装包的名字才能跟着客户端语言走）；没装包的 {@code definition = null}，
     * 名字 / 分类全部来自服务端，点穿上会自动触发下载。
     */
    private List<WardrobeEntry> buildWardrobeEntries() {
        WardrobeCollection collection = collectionOrNull();
        if (collection == null) {
            return List.of();
        }
        CosmeticRegistry registry = CosmeticRegistry.getInstance();
        List<WardrobeEntry> result = new ArrayList<>();
        for (WardrobeCollection.Entry entry : collection.entries()) {
            if (entry == null || entry.cosmeticId() == null || entry.cosmeticId().isBlank()) {
                continue;
            }
            CosmeticDefinition definition = registry.find(entry.cosmeticId());
            if (definition != null) {
                String name = definition.name() == null || definition.name().isBlank()
                        ? serverName(entry)
                        : definition.name();
                result.add(new WardrobeEntry(definition.id(), name, definition.category(),
                        definition.packId(), definition, entry.missing()));
            } else {
                result.add(new WardrobeEntry(entry.cosmeticId(), serverName(entry),
                        CosmeticCategory.byKey(entry.categoryKey()),
                        entry.packId() == null ? "" : entry.packId(), null, entry.missing()));
            }
        }
        return result;
    }

    /** 服务端名字（可能为空串）→ 回落到时装 ID。 */
    private static String serverName(WardrobeCollection.Entry entry) {
        String name = entry.name();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return entry.cosmeticId();
    }

    private boolean matchesQuery(WardrobeEntry entry) {
        String name = entry.name();
        if (name != null && name.toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return entry.id().toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * 选中的条目失效就清掉 / 换成新对象。
     *
     * <p>两种失效：{@code /vmfashion reload} 之后收藏里的时装没了；或者未安装的条目刚下载完，
     * 条目从 {@code definition == null} 变成有定义 —— 后者要换成本次构建的新对象，
     * 否则预览面板会一直停在"本地未安装"。
     *
     * <p>匹配用 {@link CosmeticIdMatch#same}（而不是全名相等）：短 ID 收藏在包下载完之后
     * {@code id()} 会从 {@code angel} 变成 {@code lw_angel:angel}，全名比较会让选中的条目
     * 在"下载完成"的瞬间被判为失效、预览面板被清空（玩家正盯着它等下载结果）。
     */
    private void ensureSelectedValid(List<WardrobeEntry> all) {
        if (selected == null) {
            return;
        }
        for (WardrobeEntry entry : all) {
            if (CosmeticIdMatch.same(entry.id(), selected.id())) {
                this.selected = entry;
                return;
            }
        }
        this.selected = null;
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
        // 先判搜索：有搜索词时"没有匹配"永远是最准确的解释。
        // 旧顺序把"衣柜为空"放在最前面，于是"衣柜空 + 正在搜索"会显示"去网站加收藏"，
        // 玩家会以为是收藏同步出了问题（其实只是关键词没命中）。
        if (!query.isEmpty()) {
            return Component.translatable("vmfashion.gui.empty.no_match");
        }
        // 过滤生效但收藏为空：这是"衣柜里还没加东西"，必须引导去网站商店，
        // 而不是沿用"没装包"的文案（玩家会去翻 config/vmfashion/packs 找不存在的问题）
        if (wardrobeSource() == Source.FILTERED && safeCollectionSize() == 0) {
            return Component.translatable("vmfashion.gui.empty.wardrobe_empty");
        }
        if (selectedCategory != null) {
            return Component.translatable("vmfashion.gui.empty.no_category");
        }
        if (CosmeticRegistry.getInstance().size() == 0) {
            return Component.translatable("vmfashion.gui.empty.no_packs");
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
     * 失败<b>不</b>写进 {@link #thumbnails}（否则一次失败就永久占位），而是记进
     * {@link #thumbFailures}：界面显示"!"角标，{@value #THUMB_RETRY_MS}ms 后自动重试一次
     * （audit v1.2 N08）。
     */
    private Identifier thumbnailFor(CosmeticDefinition cosmetic) {
        if (cosmetic == null) {
            return null;
        }
        String key = cosmetic.id();
        Identifier cached = thumbnails.get(key);
        if (cached != null) {
            return cached;
        }
        Long failedAt = thumbFailures.get(key);
        if (failedAt != null) {
            if (System.currentTimeMillis() - failedAt < THUMB_RETRY_MS) {
                return null; // 冷却中：画占位图 + "!" 角标
            }
            thumbFailures.remove(key);
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
        if (texture == null) {
            // 失败（含 CosmeticAssets 内部已吞掉异常返回 null 的情况）：记时间戳，稍后重试
            thumbFailures.put(key, System.currentTimeMillis());
            return null;
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
     * 调别的模块的状态摘要（{@link Component} 版）。
     *
     * <p>界面渲染不能被别的模块的异常打断：抛了就显示占位符，日志留给它们自己。
     */
    private static Component safeComponent(Supplier<Component> supplier) {
        try {
            Component text = supplier.get();
            return text == null ? Component.literal("?") : text;
        } catch (Throwable t) {
            return Component.literal("?");
        }
    }


    /**
     * 衣柜里的一条。
     *
     * <p>{@code definition == null} 表示"网站衣柜里有、本地还没装包"：缩略图 / 预览 / 穿戴
     * 全部要走分支，绝不能解引用 {@code definition}（这是这次改动最容易崩的地方）。
     *
     * @param id         全局 ID（{@code packId:localId}）；未安装时来自服务端收藏
     * @param name       展示名（已安装优先本地名，未安装用服务端名，空则回落 ID）
     * @param category   分类（未安装时由服务端 categoryKey 解析，未知按 {@code other}）
     * @param packId     所属包 ID（未安装时用来判"下载中"角标，可能为空串）
     * @param definition 本地定义；{@code null} = 本地没装包
     * @param missing    {@code true} = 这件时装在网站目录里<b>已删除 / 已停用</b>：
     *                   界面要显示（玩家会问"我衣柜里的东西呢"）但<b>禁止穿戴、禁止触发下发</b>。
     *                   注意它和"本地没装包"是两件事：{@code missing} 来自服务端，
     *                   {@code definition == null} 来自本地注册表。
     */
    private record WardrobeEntry(String id, String name, CosmeticCategory category,
                                 String packId, CosmeticDefinition definition, boolean missing) {
        boolean installed() {
            return definition != null;
        }

        /** 可穿戴 = 没下架。下架的时装后端一定会拒绝，界面不该给玩家"点了没反应"的错觉。 */
        boolean equipable() {
            return !missing;
        }
    }

    /**
     * 衣柜列表的数据来源，决定底部状态行文案与空状态文案。
     *
     * <p>这个枚举存在的意义：让"为什么我的衣柜变了"这件事在界面上自己解释清楚，
     * 而不是让玩家去猜 / 去翻日志。
     */
    private enum Source {
        /** 已绑定 + 已加载：只显示网站衣柜收藏。 */
        FILTERED,
        /** 服务端明确说该身份没绑定网站账号 → 退回本地全部。 */
        UNLINKED,
        /** 收藏还没成功加载过（断网 / 纯本地模式）→ 退回本地全部。 */
        NOT_LOADED,
        /** 配置里关掉了「只显示衣柜收藏」→ 显示本地全部。 */
        DISABLED
    }

    /** 左侧分类 tab。{@code category == null} 表示「全部」。 */
    private record TabEntry(CosmeticCategory category, Component label,
                            int x, int y, int width, int height) {
    }
}
