package com.venus.vmfashion.ping.render;

import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.ping.PingService;
import com.venus.vmfashion.ping.core.ClientContext;
import com.venus.vmfashion.ping.core.PingData;
import com.venus.vmfashion.ping.core.PingKind;
import com.venus.vmfashion.ping.core.PingStore;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 世界标点渲染器 —— 把 {@link PingStore} 里的标点画成屏幕空间标记。
 *
 * <p><b>为什么是屏幕空间而不是世界空间</b>：26.3 删掉了 {@code WorldRenderEvents}，
 * 而标点本质上是"UI 语义"的东西 —— 不需要被方块遮挡、不需要深度测试、不需要光照。
 * 所以照抄上游 26.3 的做法：{@code GameRendererMixin} 每帧抓一份相机矩阵（{@link CameraCapture}），
 * 这里用 {@link WorldProjector} 把世界坐标投影成屏幕坐标，再用 {@code GuiGraphicsExtractor}
 * 画 2D 标记。整套逻辑只有一次 4×4 矩阵乘法，跨版本稳定。
 *
 * <p>绘制规则：
 * <ul>
 *   <li>只画**当前维度**的标点；</li>
 *   <li>自己的标点更淡（{@link #OWN_ALPHA}），避免挡视线；</li>
 *   <li>距离越远越小、越淡（{@link WorldProjector#scaleForDistance} / {@code distanceAlpha}）；</li>
 *   <li>最后 3 秒线性淡出（{@link PingData#fadeAlpha}）；</li>
 *   <li>超过 {@code maxIconDistance} 不画图标，交给 {@link EdgeIndicator} 画边缘箭头；</li>
 *   <li>屏幕外 / 相机背后的标点也只画边缘箭头。</li>
 * </ul>
 *
 * <p><b>性能</b>：投影器、标记池、距离文字缓存等每帧要用的对象都复用；但
 * {@code store.snapshot()} 与边缘指示的少量临时数组仍会每帧分配 —— 标点数量有上限
 * （{@link #MAX_MARKERS}），这点分配量可以接受，不追求"零分配"的营销说法。
 *
 * <p>合订版适配：配置从 {@link PingSettings} 读（原 ping {@code ModConfig} 的替代），
 * 中继从 {@link PingService#relay()} 取，UI 常量走 {@link PingSettings.UI}，语言 key 改成
 * {@code vmfashion.ping.*}；渲染管线与独立版保持一致。
 */
public final class PingRenderer {

    /** 最后多少毫秒开始淡出。 */
    public static final long FADE_OUT_MS = 3_000L;
    /** 自己的标点透明度系数（更淡，少挡视线）。 */
    public static final float OWN_ALPHA = 0.65f;
    /** 一帧最多画多少个图标（超出时只画最近的）。 */
    public static final int MAX_MARKERS = 64;
    /** 标记菱形的基础半径（像素，会再乘距离缩放）。 */
    private static final int MARKER_RADIUS = 5;
    /** 标记的最小/最大缩放（防止极远/极近时大小失控）。 */
    private static final float MIN_SCALE = 0.45f;
    private static final float MAX_SCALE = 1.15f;
    /** 文字底衬色。 */
    private static final int LABEL_BACKDROP = 0x78000000;

    /** 复用的投影器（渲染线程独占）。 */
    private static final WorldProjector PROJECTOR = new WorldProjector();
    /** 标记对象池（复用，避免每帧给每个标点 new 对象）。 */
    private static final List<Marker> MARKER_POOL = new ArrayList<>();
    /** 边缘指示器对象池。 */
    private static final List<EdgeItem> EDGE_POOL = new ArrayList<>();
    /** 距离文字缓存：ping id → 上一次算好的文字（距离变化 < 0.1 格不重算）。 */
    private static final Map<String, DistanceLabel> LABEL_CACHE = new HashMap<>();
    /** 类型名缓存（{@code Component.translatable} 只建一次）。 */
    private static final Component[] KIND_LABELS = new Component[PingKind.values().length];
    /** 距离单位（"格"/"m"），首次渲染时按语言确定。 */
    private static String distanceUnit;

    private PingRenderer() {
    }

    /** 一个待绘制的屏幕标记（可变，池化复用）。 */
    private static final class Marker {
        String pingId;
        float screenX;
        float screenY;
        double distance;
        float scale;
        float alpha;
        int color;
        PingKind kind;
        String playerName;
        boolean own;
        boolean onScreen;
        boolean behind;
    }

    /** 一个待绘制的边缘指示（可变，池化复用）。 */
    private static final class EdgeItem {
        String pingId;
        float directionX;
        float directionY;
        float angle;
        int side;
        float coordinate;
        int color;
        float alpha;
        double distance;
    }

    /** 距离文字缓存项。 */
    private static final class DistanceLabel {
        int bucket = Integer.MIN_VALUE;
        String text = "";
    }

    /**
     * 渲染入口：由 HUD 元素（{@code HudElementRegistry}）每帧调用一次。
     *
     * @param graphics    HUD 绘制上下文
     * @param deltaTracker 帧增量（本实现只用矩阵，不需要 partialTick，保留参数便于以后做动画）
     */
    public static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (minecraft.gui == null || minecraft.gui.hud.isHidden()) {
            return;
        }
        PingSettings config = PingService.getInstance().settings();
        if (config == null || !config.isEnabled()) {
            return;
        }
        CameraCapture.Snapshot snapshot = CameraCapture.current();
        if (snapshot == null || !snapshot.valid) {
            return;
        }
        String dimension = ClientContext.dimensionId();
        if (dimension.isBlank()) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        PROJECTOR.setView(snapshot.modelView, snapshot.projection,
                snapshot.cameraX, snapshot.cameraY, snapshot.cameraZ, width, height);

        PingStore store = PingService.getInstance().relay().store();
        long now = store.now();
        List<PingData> pings = store.snapshot(dimension, now);
        if (pings.isEmpty()) {
            return;
        }

        if (distanceUnit == null) {
            distanceUnit = Component.translatable("vmfashion.ping.hud.distance.unit").getString();
        }

        UUID self = ClientContext.selfUuid();
        boolean showNames = config.isShowPlayerNames() || ClientContext.isPlayerListKeyDown();
        double maxIconDistance = Math.max(8, config.getMaxIconDistance());
        float edgeMargin = Math.min(PingSettings.UI.EDGE_INDICATOR_MARGIN,
                Math.max(2, Math.min(width, height) / 8));

        int markerCount = 0;
        int edgeCount = 0;

        for (PingData ping : pings) {
            boolean own = self != null && ping.isOwn(self);
            if (own && !config.isShowOwnPings()) {
                continue;
            }
            if (!own && !config.isShowOthersPings()) {
                continue;
            }
            WorldProjector.Projected projected = PROJECTOR.project(ping.x(), ping.y(), ping.z());
            if (!projected.projected) {
                continue;
            }
            double distance = projected.distance;
            float fadeAlpha = ping.fadeAlpha(now, FADE_OUT_MS);
            float alpha = fadeAlpha
                    * WorldProjector.distanceAlpha(distance, maxIconDistance)
                    * (own ? OWN_ALPHA : 1f);
            alpha = WorldProjector.clamp01(alpha);
            if (alpha <= 0.02f) {
                continue;
            }
            boolean onScreen = !projected.behindCamera
                    && projected.x >= 0f && projected.x <= width
                    && projected.y >= 0f && projected.y <= height;
            boolean withinIconDistance = distance <= maxIconDistance;

            if (onScreen && withinIconDistance) {
                Marker marker = marker(markerCount++);
                marker.pingId = ping.id();
                marker.screenX = projected.x;
                marker.screenY = projected.y;
                marker.distance = distance;
                marker.scale = clampScale(WorldProjector.scaleForDistance(distance, 100f));
                marker.alpha = alpha;
                marker.color = ping.kind().color();
                marker.kind = ping.kind();
                marker.playerName = ping.playerName();
                marker.own = own;
                marker.onScreen = true;
                marker.behind = false;
            } else if (config.isShowEdgeIndicator()) {
                // 屏幕外 / 相机背后 / 太远：交给边缘指示器
                float directionX = projected.x - width * 0.5f;
                float directionY = projected.y - height * 0.5f;
                if (projected.behindCamera) {
                    directionX = -directionX;
                    directionY = -directionY;
                }
                if (Math.abs(directionX) < 1e-3f && Math.abs(directionY) < 1e-3f) {
                    directionY = 1f; // 正中心（贴脸）时给个默认方向，避免 NaN
                }
                EdgeItem item = edge(edgeCount++);
                item.pingId = ping.id();
                item.directionX = directionX;
                item.directionY = directionY;
                item.angle = EdgeIndicatorMath.angleOf(directionX, directionY);
                item.side = EdgeIndicatorMath.sideOf(item.angle);
                item.color = ping.kind().color();
                item.alpha = Math.max(alpha, 0.35f); // 屏幕外的指示不能太淡，否则等于没有
                item.distance = distance;
                item.coordinate = edgeCoordinate(item.angle, width, height, edgeMargin);
            }
        }

        Font font = minecraft.font;
        drawMarkers(graphics, font, markerCount, config, showNames);
        if (config.isShowEdgeIndicator() && edgeCount > 0) {
            drawEdgeIndicators(graphics, font, edgeCount, config);
        }
        trimLabelCache(pings.size());
    }

    // ==================== 屏幕内标记 ====================

    private static void drawMarkers(GuiGraphicsExtractor graphics, Font font, int count,
                                    PingSettings config, boolean showNames) {
        if (count <= 0) {
            return;
        }
        // 按距离升序排序（插入排序：count 通常是个位数，且对象是池化的，不产生分配）
        for (int i = 1; i < count; i++) {
            Marker current = MARKER_POOL.get(i);
            int j = i - 1;
            while (j >= 0 && MARKER_POOL.get(j).distance > current.distance) {
                MARKER_POOL.set(j + 1, MARKER_POOL.get(j));
                j--;
            }
            MARKER_POOL.set(j + 1, current);
        }
        // 只画最近的 MAX_MARKERS 个；从远到近画，近的压在上面
        int start = Math.max(0, count - MAX_MARKERS);
        for (int i = count - 1; i >= start; i--) {
            drawMarker(graphics, font, MARKER_POOL.get(i), config, showNames);
        }
    }

    private static void drawMarker(GuiGraphicsExtractor graphics, Font font, Marker marker,
                                   PingSettings config, boolean showNames) {
        int x = Math.round(marker.screenX);
        int y = Math.round(marker.screenY);
        int radius = Math.max(3, Math.round(MARKER_RADIUS * marker.scale));
        int color = WorldProjector.withAlpha(marker.color, marker.alpha);
        int outline = WorldProjector.withAlpha(0xFF101010, marker.alpha * 0.8f);

        // 菱形：逐行画水平线段（GuiGraphicsExtractor 只有 fill，没有多边形）
        for (int dy = -radius - 1; dy <= radius + 1; dy++) {
            int half = radius + 1 - Math.abs(dy);
            if (half > 0) {
                graphics.fill(x - half, y + dy, x + half, y + dy + 1, outline);
            }
        }
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            if (half > 0) {
                graphics.fill(x - half, y + dy, x + half, y + dy + 1, color);
            }
        }

        int textColor = WorldProjector.withAlpha(0xFFFFFFFF, marker.alpha);
        int shadowColor = WorldProjector.withAlpha(0xFF000000, marker.alpha * 0.75f);
        int lineHeight = font.lineHeight;

        // 距离：图标上方
        if (config.isShowDistance()) {
            DistanceLabel label = labelFor(marker.pingId, marker.distance);
            drawCenteredLabel(graphics, font, label.text, x, y - radius - lineHeight - 3, textColor, shadowColor);
        }
        // 类型名：图标下方
        Component kindLabel = kindLabel(marker.kind);
        drawCenteredLabel(graphics, font, kindLabel, x, y + radius + 3, textColor, shadowColor);

        // 标点者名字：类型名下方（配置开启或按住 Tab 玩家列表）
        if (showNames && !marker.own) {
            String name = marker.playerName;
            if (name != null && !name.isEmpty()) {
                drawCenteredLabel(graphics, font, name, x, y + radius + 3 + lineHeight + 1,
                        WorldProjector.withAlpha(0xFFD0D0D0, marker.alpha), shadowColor);
            }
        }
    }

    private static void drawCenteredLabel(GuiGraphicsExtractor graphics, Font font, String text,
                                          int centerX, int topY, int color, int backdrop) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int textWidth = font.width(text);
        int x = centerX - textWidth / 2;
        graphics.fill(x - 2, topY - 1, x + textWidth + 2, topY + font.lineHeight, LABEL_BACKDROP);
        graphics.text(font, text, x, topY, color, false);
    }

    private static void drawCenteredLabel(GuiGraphicsExtractor graphics, Font font, Component text,
                                          int centerX, int topY, int color, int backdrop) {
        if (text == null) {
            return;
        }
        int textWidth = font.width(text);
        int x = centerX - textWidth / 2;
        graphics.fill(x - 2, topY - 1, x + textWidth + 2, topY + font.lineHeight, LABEL_BACKDROP);
        graphics.text(font, text, x, topY, color, false);
    }

    // ==================== 屏幕外边缘指示 ====================

    private static void drawEdgeIndicators(GuiGraphicsExtractor graphics, Font font, int count,
                                           PingSettings config) {
        EdgeIndicator.prepare(graphics.guiWidth(), graphics.guiHeight());
        // 同一侧边、沿边坐标接近的箭头分到同一簇，交错错开
        float[] coordinates = new float[count];
        int[] sides = new int[count];
        for (int i = 0; i < count; i++) {
            EdgeItem item = EDGE_POOL.get(i);
            coordinates[i] = item.coordinate;
            sides[i] = item.side;
        }
        int[] slots = EdgeIndicatorMath.assignSlots(coordinates, sides,
                PingSettings.UI.EDGE_INDICATOR_SIZE + 8f);

        for (int i = 0; i < count; i++) {
            EdgeItem item = EDGE_POOL.get(i);
            int color = WorldProjector.withAlpha(item.color, item.alpha);
            int textColor = WorldProjector.withAlpha(0xFFFFFFFF, item.alpha);
            String distance = config.isShowDistance()
                    ? labelFor(item.pingId, item.distance).text
                    : null;
            EdgeIndicator.draw(graphics, font, item.directionX, item.directionY,
                    color, distance, textColor, slots[i]);
        }
    }

    /** 箭头在"边"上的坐标：左右边用 y，上下边用 x（堆叠分组排序用）。 */
    private static float edgeCoordinate(float angle, int width, int height, float margin) {
        float[] edge = EdgeIndicatorMath.rectIntersection(angle, margin, margin,
                width - margin, height - margin);
        int side = EdgeIndicatorMath.sideOf(angle);
        return (side == EdgeIndicatorMath.SIDE_LEFT || side == EdgeIndicatorMath.SIDE_RIGHT)
                ? edge[1]
                : edge[0];
    }

    // ==================== 工具 ====================

    private static Marker marker(int index) {
        while (MARKER_POOL.size() <= index) {
            MARKER_POOL.add(new Marker());
        }
        return MARKER_POOL.get(index);
    }

    private static EdgeItem edge(int index) {
        while (EDGE_POOL.size() <= index) {
            EDGE_POOL.add(new EdgeItem());
        }
        return EDGE_POOL.get(index);
    }

    private static float clampScale(float scale) {
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    private static Component kindLabel(PingKind kind) {
        int index = kind.ordinal();
        Component cached = KIND_LABELS[index];
        if (cached == null) {
            cached = Component.translatable(kind.translationKey());
            KIND_LABELS[index] = cached;
        }
        return cached;
    }

    /** 距离文字（0.1 格粒度缓存，距离没变就不重新格式化）。 */
    private static DistanceLabel labelFor(String pingId, double distance) {
        DistanceLabel label = LABEL_CACHE.get(pingId);
        if (label == null) {
            label = new DistanceLabel();
            LABEL_CACHE.put(pingId, label);
        }
        int bucket = (int) Math.round(distance * 10d);
        if (bucket != label.bucket) {
            label.bucket = bucket;
            String value = String.format(Locale.ROOT, "%.1f", distance);
            label.text = value + " " + distanceUnit;
        }
        return label;
    }

    /** 缓存清理：标点数量骤降时（换服/过期）把陈旧的文字清掉，避免无限增长。 */
    private static void trimLabelCache(int aliveCount) {
        if (LABEL_CACHE.size() > Math.max(64, aliveCount * 2)) {
            LABEL_CACHE.clear();
        }
    }
}
