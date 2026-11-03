package com.venus.vmfashion.ping.gui;

import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.gui.KeyBindings;
import com.venus.vmfashion.ping.PingService;
import com.venus.vmfashion.ping.core.PingData;
import com.venus.vmfashion.ping.core.PingKind;
import com.venus.vmfashion.ping.net.PingRelay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 标点轮盘 —— 按住标点键（默认鼠标中键）打开，移动鼠标选类型，松开即标点。
 *
 * <p>交互设计：
 * <ul>
 *   <li>轮盘固定画在屏幕中心，**不暂停游戏**（{@code isPauseScreen=false}），世界照常渲染在后面；</li>
 *   <li>5 个扇区 = 5 种标点类型（{@link PingKind} 的枚举顺序）；</li>
 *   <li>鼠标落在中心死区 = 不选（松开就取消，不会误标）；</li>
 *   <li>松开鼠标 → 从相机方向做一次射线拾取（最远 {@code maxIconDistance} 格），
 *       点到实体就带上实体信息，否则用命中点/视线终点；</li>
 *   <li>Esc 或切出界面 → 取消。</li>
 * </ul>
 *
 * <p>为什么打开时不要"点击"事件：这个界面是被**按键**打开的，鼠标按键在打开瞬间可能已经按下，
 * 所以真正的触发点是 {@link #mouseReleased}，而不是 {@code mouseClicked}。
 *
 * <p>轮盘的扇形是预计算的线段（见 {@link WheelGeometry}），每帧只做平移 + fill。
 *
 * <p>合订版适配：配置从 {@link PingSettings} 读、UI 常量走 {@link PingSettings.UI}、
 * 按键改从 {@link KeyBindings#pingKey()} 取、中继从 {@link PingService#relay()} 取，
 * 语言 key 改成 {@code vmfashion.ping.*}；交互与绘制逻辑不变。
 */
public final class PingWheelScreen extends Screen {

    /** 轮盘中心（逻辑像素）。 */
    private int centerX;
    private int centerY;
    /** 各扇区的预计算线段。 */
    private final List<WheelGeometry.Span>[] sectorSpans;
    /** 当前选中的扇区下标；{@code -1} = 未选中。 */
    private int selected = -1;
    /** 打开时的 tick 计数：前 2 tick 不响应"按键松开"兜底，避免开界面的那一下被误判。 */
    private int ticksOpen;
    /** 是否已经完成标点（防止 mouseReleased + tick 兜底重复发送）。 */
    private boolean finished;
    /**
     * 是否由**按住按键**打开（决定要不要做"按键松开"兜底）。
     *
     * <p>命令 {@code /vmfashion ping open} 打开时玩家并没有按住标点键，如果仍然做
     * "按键没按下就自动结算"的兜底，界面会在 2 tick 后自己关掉 —— 这是独立版 VMPing
     * 就存在的小 bug，合订版顺手修掉：只有按键打开才启用兜底。
     */
    private final boolean expectKeyRelease;

    public PingWheelScreen() {
        this(false);
    }

    @SuppressWarnings("unchecked")
    private PingWheelScreen(boolean expectKeyRelease) {
        super(Component.translatable("vmfashion.ping.wheel.title"));
        this.expectKeyRelease = expectKeyRelease;
        int outer = PingSettings.UI.WHEEL_RADIUS;
        int inner = PingSettings.UI.WHEEL_INNER_RADIUS;
        float gap = PingSettings.UI.SEGMENT_GAP_DEGREES;
        this.sectorSpans = new List[WheelGeometry.SECTOR_COUNT];
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            float start = WheelGeometry.sectorStart(i) + gap / 2f;
            float end = WheelGeometry.sectorEnd(i) - gap / 2f;
            sectorSpans[i] = WheelGeometry.sectorSpans(outer, inner, start, end);
        }
    }

    /**
     * 打开轮盘（命令 / 外部入口用）。
     *
     * <p><b>不能</b>检查 {@code gui.screen() != null}：命令是在聊天界面的
     * {@code keyPressed → handleChatInput} 里**同步**执行的，此刻 screen 仍是 ChatScreen；
     * 有守卫的话 {@code /vmfashion ping open} 会静默失效（独立版 VMPing 就有这个 bug）。
     */
    public static void open() {
        openWith(false, false);
    }

    /**
     * 按键打开轮盘（按住标点键）：松开按键即结算，含"收不到 mouseReleased"的兜底。
     *
     * <p>按键路径仍然要求当前没有其它界面，避免和聊天 / 其它模组界面抢焦点。
     */
    public static void openFromKey() {
        openWith(true, true);
    }

    private static void openWith(boolean expectKeyRelease, boolean requireNoScreen) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (requireNoScreen && minecraft.gui.screen() != null) {
            return;
        }
        minecraft.gui.setScreen(new PingWheelScreen(expectKeyRelease));
    }

    /** 当前是否有轮盘界面打开（mixin 抑制"选取方块"用）。 */
    public static boolean isOpen() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft != null && minecraft.gui.screen() instanceof PingWheelScreen;
    }

    @Override
    protected void init() {
        this.centerX = this.width / 2;
        this.centerY = this.height / 2;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        ticksOpen++;
        // 兜底：某些环境（窗口失焦 / 鼠标被系统抢走）收不到 mouseReleased，
        // 这里检测"标点键已经松开"并当作松开处理，避免轮盘卡死。
        if (expectKeyRelease && !finished && ticksOpen > 2) {
            var mapping = KeyBindings.pingKey();
            if (mapping != null && !mapping.isDown()) {
                finish();
            }
        }
    }

    // ==================== 渲染 ====================

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick) {
        // 先算选中扇区（渲染和命中用同一套几何）
        selected = WheelGeometry.hitTest(mouseX, mouseY, centerX, centerY,
                PingSettings.UI.WHEEL_INNER_RADIUS, PingSettings.UI.WHEEL_RADIUS);

        // 1. 扇区
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            PingKind kind = PingKind.values()[i];
            boolean active = i == selected;
            int color = active ? withAlpha(kind.color(), 0xE6) : withAlpha(kind.color(), 0x8C);
            drawSpans(context, sectorSpans[i], centerX, centerY, color);
        }

        // 2. 扇区之间的分隔描边：把每个扇区的中心线方向画一条短线，视觉上更像"轮盘"
        drawSectorDividers(context);

        // 3. 中心圆
        fillCircle(context, centerX, centerY, PingSettings.UI.WHEEL_CENTER_RADIUS, PingSettings.UI.WHEEL_BG);
        drawCircleOutline(context, centerX, centerY, PingSettings.UI.WHEEL_CENTER_RADIUS,
                PingSettings.UI.WHEEL_BORDER);

        // 4. 中心文字：当前选中类型 / 提示
        Component centerText = selected >= 0
                ? Component.translatable(PingKind.values()[selected].translationKey())
                : Component.translatable("vmfashion.ping.wheel.title");
        int textWidth = this.font.width(centerText);
        context.text(this.font, centerText, centerX - textWidth / 2, centerY - 4, PingSettings.UI.WHEEL_TEXT);

        // 5. 每个扇区外侧的图标 + 名称
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            PingKind kind = PingKind.values()[i];
            boolean active = i == selected;
            double midRad = Math.toRadians(WheelGeometry.sectorStart(i) + WheelGeometry.SECTOR_DEGREES / 2f);
            int labelRadius = PingSettings.UI.WHEEL_RADIUS - 16;
            int lx = centerX + (int) Math.round(Math.sin(midRad) * labelRadius);
            int ly = centerY - (int) Math.round(Math.cos(midRad) * labelRadius);

            // 图标：一个 10x10 的实心方块（后续可替换成贴图）
            int iconSize = active ? 12 : 9;
            context.fill(lx - iconSize / 2, ly - iconSize / 2 - 6,
                    lx + iconSize / 2, ly + iconSize / 2 - 6, kind.color());

            Component label = Component.translatable(kind.translationKey());
            int labelWidth = this.font.width(label);
            int labelColor = active ? 0xFFFFFFFF : 0xFFD0D0D0;
            context.text(this.font, label, lx - labelWidth / 2, ly + 4, labelColor);
        }

        // 6. 底部操作提示
        Component hint = Component.translatable("vmfashion.ping.wheel.hint");
        int hintWidth = this.font.width(hint);
        context.text(this.font, hint, centerX - hintWidth / 2,
                centerY + PingSettings.UI.WHEEL_RADIUS + 10, 0xFFA0A0A0);
    }

    /** 把预计算的水平线段画到屏幕上。 */
    private void drawSpans(GuiGraphicsExtractor context, List<WheelGeometry.Span> spans,
                           int originX, int originY, int color) {
        for (WheelGeometry.Span span : spans) {
            context.fill(originX + span.x1(), originY + span.dy(),
                    originX + span.x2() + 1, originY + span.dy() + 1, color);
        }
    }

    /** 扇区分隔线（从内圈到外圈，沿扇区边界方向）。 */
    private void drawSectorDividers(GuiGraphicsExtractor context) {
        int inner = PingSettings.UI.WHEEL_INNER_RADIUS;
        int outer = PingSettings.UI.WHEEL_RADIUS;
        for (int i = 0; i < WheelGeometry.SECTOR_COUNT; i++) {
            double angle = Math.toRadians(WheelGeometry.sectorStart(i));
            for (int r = inner; r <= outer; r++) {
                int x = centerX + (int) Math.round(Math.sin(angle) * r);
                int y = centerY - (int) Math.round(Math.cos(angle) * r);
                context.fill(x, y, x + 1, y + 1, 0x33FFFFFF);
            }
        }
    }

    /** 实心圆（逐行扫描线）。 */
    private void fillCircle(GuiGraphicsExtractor context, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.floor(Math.sqrt((double) radius * radius - (double) dy * dy));
            context.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    /** 空心圆。 */
    private void drawCircleOutline(GuiGraphicsExtractor context, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.floor(Math.sqrt((double) radius * radius - (double) dy * dy));
            context.fill(cx - dx, cy + dy, cx - dx + 1, cy + dy + 1, color);
            context.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
        for (int dx = -radius; dx <= radius; dx++) {
            int dy = (int) Math.floor(Math.sqrt((double) radius * radius - (double) dx * dx));
            context.fill(cx + dx, cy - dy, cx + dx + 1, cy - dy + 1, color);
            context.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // 只有"标点键"松开才结算（其它按键的 release 不理会）。
        // 用 KeyMapping#matchesMouse 判断而不是比较按钮编号：26.3 的鼠标编号是 SDL 的 1-based，
        // 硬编码数字很容易在不同平台/版本上错位。
        var mapping = KeyBindings.pingKey();
        if (mapping != null && mapping.matchesMouse(event)) {
            finish();
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Esc 取消。26.3 的 KeyEvent 是 SDL 键码（Esc = 41，不是 GLFW 的 256），
        // 必须用 InputWithModifiers#isEscape() 判断，不能硬编码数字。
        if (event.isEscape()) {
            cancel();
            return true;
        }
        return super.keyPressed(event);
    }

    /** 结算：选中了就发标点，然后关界面。 */
    private void finish() {
        if (finished) {
            return;
        }
        finished = true;
        int kindIndex = selected;
        Minecraft minecraft = Minecraft.getInstance();
        if (kindIndex >= 0 && minecraft.player != null) {
            PingKind kind = PingKind.values()[kindIndex];
            PingTarget target = resolveTarget(minecraft);
            if (target != null) {
                PingRelay relay = PingService.getInstance().relay();
                PingData ping = relay.sendPing(target.x, target.y, target.z, kind,
                        target.entityUuid, target.entityName);
                if (ping != null) {
                    PingService.getInstance().onLocalPing(ping);
                }
            }
        }
        closeScreen();
    }

    /** 取消：不发标点。 */
    private void cancel() {
        if (finished) {
            return;
        }
        finished = true;
        closeScreen();
    }

    private void closeScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        // 不调用 onClose 的默认逻辑（那会打开暂停菜单），直接切回游戏
        minecraft.gui.setScreen(null);
    }

    // ==================== 射线拾取 ====================

    /** 标点目标点。 */
    private record PingTarget(double x, double y, double z, UUID entityUuid, String entityName) {
    }

    /**
     * 从相机方向做一次射线拾取：
     * <ul>
     *   <li>点到方块 → 用命中点（贴着方块表面）；</li>
     *   <li>点到实体 → 用实体位置，并带上 UUID / 名字；</li>
     *   <li>什么都没点到 → 用视线前方 {@code min(32, maxIconDistance)} 格处的点（"标向远方"）。</li>
     * </ul>
     */
    private PingTarget resolveTarget(Minecraft minecraft) {
        PingSettings config = PingService.getInstance().settings();
        double maxDistance = Math.max(8, config.getMaxIconDistance());
        var player = minecraft.player;
        if (player == null) {
            return null;
        }
        HitResult hit = player.pick(maxDistance, 1.0f, false);
        if (hit != null && hit.getType() == HitResult.Type.ENTITY && hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity != null) {
                Vec3 pos = entity.position();
                return new PingTarget(pos.x, pos.y + entity.getBbHeight() * 0.5d, pos.z,
                        entity.getUUID(), entity.getName().getString());
            }
        }
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            Vec3 location = hit.getLocation();
            return new PingTarget(location.x, location.y, location.z, null, null);
        }
        // 没点到任何东西：视线前方取一个点
        Vec3 eye = player.getEyePosition();
        Vec3 direction = player.getViewVector(1.0f);
        double distance = Math.min(32d, maxDistance);
        Vec3 point = eye.add(direction.scale(distance));
        return new PingTarget(point.x, point.y, point.z, null, null);
    }

    // ==================== 杂项 ====================

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        cancel();
    }

    /** 供 UI 预览器/测试读取：当前选中的类型（-1 = 未选）。 */
    public int selectedSector() {
        return selected;
    }

    /** 供 UI 预览器/测试读取：各扇区线段（不可变视图）。 */
    public List<WheelGeometry.Span> spansOf(int sector) {
        return sectorSpans[sector];
    }

    /** 供测试：把枚举映射成扇区下标。 */
    public static Map<PingKind, Integer> kindToSector() {
        Map<PingKind, Integer> map = new EnumMap<>(PingKind.class);
        PingKind[] kinds = PingKind.values();
        for (int i = 0; i < kinds.length; i++) {
            map.put(kinds[i], i);
        }
        return map;
    }
}
