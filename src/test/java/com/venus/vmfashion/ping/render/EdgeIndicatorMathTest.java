package com.venus.vmfashion.ping.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EdgeIndicatorMath} 的单元测试：方向角、贴边求交、堆叠错开。
 *
 * <p>边缘指示器是"看得见但摸不着"的东西 —— 画错了玩家只会觉得"箭头位置怪怪的"，
 * 却说不出哪里不对。所以这里把三件事都钉死：
 * <ol>
 *   <li>方向角：0°=右、90°=下、180°=左、-90°=上（<b>屏幕 y 向下</b>，别按数学课本的 y 向上写）；</li>
 *   <li>贴边点：四个正方向必须落在四条边的中点；斜方向必须落在边上（不能跑到矩形外）；</li>
 *   <li>堆叠槽位：同一侧、位置接近的箭头按 0、+1、-1、+2、-2 交替摊开；不同侧边互不干扰。</li>
 * </ol>
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.render}，用例与被测行为不变。
 */
class EdgeIndicatorMathTest {

    private static final float LEFT = 20f;
    private static final float TOP = 10f;
    private static final float RIGHT = 220f;
    private static final float BOTTOM = 110f;
    private static final float CENTER_X = (LEFT + RIGHT) / 2f;
    private static final float CENTER_Y = (TOP + BOTTOM) / 2f;

    @Test
    void angleOfFourCardinalDirections() {
        assertEquals(0f, EdgeIndicatorMath.angleOf(1f, 0f), 0.0001f, "正右 = 0°");
        assertEquals((float) Math.PI / 2f, EdgeIndicatorMath.angleOf(0f, 1f), 0.0001f, "正下 = +90°（y 向下）");
        assertEquals((float) Math.PI, Math.abs(EdgeIndicatorMath.angleOf(-1f, 0f)), 0.0001f, "正左 = 180°");
        assertEquals(-(float) Math.PI / 2f, EdgeIndicatorMath.angleOf(0f, -1f), 0.0001f, "正上 = -90°");
    }

    @Test
    void angleOfZeroVectorIsFinite() {
        float angle = EdgeIndicatorMath.angleOf(0f, 0f);
        assertTrue(Float.isFinite(angle), "零向量必须给出一个有限的角度");
    }

    @Test
    void rectIntersectionCardinalDirectionsHitEdgeMidpoints() {
        assertPointEquals(RIGHT, CENTER_Y, EdgeIndicatorMath.rectIntersection(0f, LEFT, TOP, RIGHT, BOTTOM));
        assertPointEquals(CENTER_X, BOTTOM,
                EdgeIndicatorMath.rectIntersection((float) Math.PI / 2f, LEFT, TOP, RIGHT, BOTTOM));
        assertPointEquals(LEFT, CENTER_Y,
                EdgeIndicatorMath.rectIntersection((float) Math.PI, LEFT, TOP, RIGHT, BOTTOM));
        assertPointEquals(CENTER_X, TOP,
                EdgeIndicatorMath.rectIntersection(-(float) Math.PI / 2f, LEFT, TOP, RIGHT, BOTTOM));
    }

    @Test
    void rectIntersectionDiagonalStaysOnBorder() {
        float[] point = EdgeIndicatorMath.rectIntersection((float) Math.toRadians(45f),
                LEFT, TOP, RIGHT, BOTTOM);
        // 45° 方向：半宽 100 > 半高 50，先撞下边
        assertPointEquals(CENTER_X + (BOTTOM - CENTER_Y), BOTTOM, point);
        assertTrue(point[0] >= LEFT && point[0] <= RIGHT, "交点不能跑出矩形");
        assertTrue(point[1] >= TOP && point[1] <= BOTTOM, "交点不能跑出矩形");
    }

    @Test
    void rectIntersectionDegenerateRectReturnsCenter() {
        float[] point = EdgeIndicatorMath.rectIntersection(0.3f, 10f, 10f, 10f, 10f);
        assertPointEquals(10f, 10f, point);
    }

    @Test
    void sideOfDirection() {
        assertEquals(EdgeIndicatorMath.SIDE_RIGHT, EdgeIndicatorMath.sideOf(0f));
        assertEquals(EdgeIndicatorMath.SIDE_BOTTOM, EdgeIndicatorMath.sideOf((float) Math.PI / 2f));
        assertEquals(EdgeIndicatorMath.SIDE_LEFT, EdgeIndicatorMath.sideOf((float) Math.PI));
        assertEquals(EdgeIndicatorMath.SIDE_TOP, EdgeIndicatorMath.sideOf(-(float) Math.PI / 2f));
        // 边界：44.9° 还算右侧，45° 起算下侧
        assertEquals(EdgeIndicatorMath.SIDE_RIGHT, EdgeIndicatorMath.sideOf((float) Math.toRadians(44.9f)));
        assertEquals(EdgeIndicatorMath.SIDE_BOTTOM, EdgeIndicatorMath.sideOf((float) Math.toRadians(45f)));
        // 跨越 ±180° 的等价角度应该落在同一条边
        assertEquals(EdgeIndicatorMath.SIDE_LEFT, EdgeIndicatorMath.sideOf((float) Math.toRadians(-180f)));
        assertEquals(EdgeIndicatorMath.SIDE_LEFT, EdgeIndicatorMath.sideOf((float) Math.toRadians(180f)));
    }

    @Test
    void assignSlotsSpreadsCluster() {
        float[] coordinates = {100f, 101f, 102f};
        int[] sides = {EdgeIndicatorMath.SIDE_RIGHT, EdgeIndicatorMath.SIDE_RIGHT, EdgeIndicatorMath.SIDE_RIGHT};

        int[] slots = EdgeIndicatorMath.assignSlots(coordinates, sides, 20f);

        assertArrayEquals(new int[]{0, 1, -1}, slots, "三个挤在一起的箭头应该按 0/+1/-1 摊开");
    }

    @Test
    void assignSlotsKeepsFarApartItemsAlone() {
        float[] coordinates = {10f, 100f, 200f};
        int[] sides = {EdgeIndicatorMath.SIDE_BOTTOM, EdgeIndicatorMath.SIDE_BOTTOM, EdgeIndicatorMath.SIDE_BOTTOM};

        int[] slots = EdgeIndicatorMath.assignSlots(coordinates, sides, 20f);

        assertArrayEquals(new int[]{0, 0, 0}, slots);
    }

    @Test
    void assignSlotsSeparatesBySide() {
        // 坐标一样，但一条在右边、一条在上边 → 不该被当成重叠
        float[] coordinates = {100f, 100f};
        int[] sides = {EdgeIndicatorMath.SIDE_RIGHT, EdgeIndicatorMath.SIDE_TOP};

        int[] slots = EdgeIndicatorMath.assignSlots(coordinates, sides, 20f);

        assertArrayEquals(new int[]{0, 0}, slots);
    }

    @Test
    void assignSlotsFiveItemsAlternate() {
        float[] coordinates = {50f, 51f, 52f, 53f, 54f};
        int[] sides = new int[]{EdgeIndicatorMath.SIDE_LEFT, EdgeIndicatorMath.SIDE_LEFT,
                EdgeIndicatorMath.SIDE_LEFT, EdgeIndicatorMath.SIDE_LEFT, EdgeIndicatorMath.SIDE_LEFT};

        int[] slots = EdgeIndicatorMath.assignSlots(coordinates, sides, 100f);

        // 0、+1、-1、+2、-2 —— 交替摊开，视觉重心仍指向真实方向
        assertArrayEquals(new int[]{0, 1, -1, 2, -2}, slots);
    }

    @Test
    void assignSlotsHandlesEmptyInput() {
        assertArrayEquals(new int[0], EdgeIndicatorMath.assignSlots(new float[0], new int[0], 10f));
        assertArrayEquals(new int[0], EdgeIndicatorMath.assignSlots(null, null, 10f));
    }

    @Test
    void tangentOffsetSlidesAlongEdge() {
        assertEquals(0f, EdgeIndicatorMath.slotOffset(0, 18f), 0.0001f);
        assertEquals(18f, EdgeIndicatorMath.slotOffset(1, 18f), 0.0001f);
        assertEquals(-18f, EdgeIndicatorMath.slotOffset(-1, 18f), 0.0001f);
        // 切线是方向角的 +90°：0°（向右）的切线指向屏幕下方
        assertEquals(-0f, EdgeIndicatorMath.tangentX(0f), 0.0001f);
        assertEquals(1f, EdgeIndicatorMath.tangentY(0f), 0.0001f);
        // 90°（向下）的切线指向屏幕左侧
        assertEquals(-1f, EdgeIndicatorMath.tangentX((float) Math.PI / 2f), 0.0001f);
        assertEquals(0f, EdgeIndicatorMath.tangentY((float) Math.PI / 2f), 0.0001f);
    }

    private static void assertPointEquals(float expectedX, float expectedY, float[] point) {
        assertEquals(expectedX, point[0], 0.01f, "x 坐标不符");
        assertEquals(expectedY, point[1], 0.01f, "y 坐标不符");
    }
}
