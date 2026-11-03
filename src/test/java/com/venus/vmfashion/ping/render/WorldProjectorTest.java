package com.venus.vmfashion.ping.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldProjector} 的单元测试：投影、背向判定、视口裁剪、距离缩放。
 *
 * <p>为什么这些测试特别重要：世界 → 屏幕的数学一旦错了，表现是"标点飘在半空"
 * 或"背对着也显示"，而在游戏里很难靠肉眼判断到底是矩阵问题还是数据问题。
 * 这里用<b>手工构造的矩阵</b>把每一步钉死：
 * <ul>
 *   <li>视图矩阵用单位阵 → 世界坐标直接等于"相对相机的坐标"；</li>
 *   <li>投影矩阵用手写的标准透视矩阵（OpenGL 约定，相机看向 -z）→
 *       {@code w = -z}，所以"在相机前方"⇔ {@code w > 0}。</li>
 * </ul>
 *
 * <p><b>注意</b>：{@link WorldProjector#project} 返回的是<b>复用的</b>结果对象，
 * 所以每次调用后要立刻把需要的数值取出来，不能存两个引用再比较（会被后一次覆盖）。
 * 方法名用 ASCII 是刻意的：Gradle 的测试报告会拿方法名当文件名，
 * 非 ASCII 名字在非 UTF-8 环境下会写出乱码文件、导致报告生成失败。
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.render}，用例与被测行为不变。
 */
class WorldProjectorTest {

    private static final int VIEWPORT_WIDTH = 200;
    private static final int VIEWPORT_HEIGHT = 100;

    /** 4×4 单位阵（列主序）。 */
    private static float[] identity() {
        float[] m = new float[16];
        m[0] = 1f;
        m[5] = 1f;
        m[10] = 1f;
        m[15] = 1f;
        return m;
    }

    /**
     * 标准透视矩阵（列主序，OpenGL 约定：相机看向 -z，近平面 0.05，远平面 1000）。
     *
     * <p>关键在 {@code m[11] = -1}：它让裁剪空间的 {@code w = -z_view}，
     * 于是"点在相机前方"就等价于 {@code w > 0}。
     */
    private static float[] perspective(float fovDegrees, float aspect, float near, float far) {
        float f = (float) (1.0d / Math.tan(Math.toRadians(fovDegrees) / 2.0d));
        float[] m = new float[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = 2f * far * near / (near - far);
        return m;
    }

    private static WorldProjector projector(float[] modelView, float[] projection,
                                            double camX, double camY, double camZ) {
        WorldProjector projector = new WorldProjector();
        projector.setView(modelView, projection, camX, camY, camZ, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);
        return projector;
    }

    private static WorldProjector standardProjector(double camX, double camY, double camZ) {
        return projector(identity(), perspective(70f, 2f, 0.05f, 1000f), camX, camY, camZ);
    }

    @Test
    void pointInFrontIsProjectedToScreenCenter() {
        WorldProjector projector = standardProjector(0, 0, 0);

        WorldProjector.Projected projected = projector.project(0d, 0d, -5d);

        assertTrue(projected.projected, "应该得到有效投影");
        assertFalse(projected.behindCamera, "正前方的点不应该被判成背后");
        assertEquals(VIEWPORT_WIDTH / 2f, projected.x, 0.001f);
        assertEquals(VIEWPORT_HEIGHT / 2f, projected.y, 0.001f);
        assertEquals(5d, projected.distance, 0.001d);
    }

    @Test
    void pointBehindCameraIsMarkedBehind() {
        WorldProjector projector = standardProjector(0, 0, 0);

        WorldProjector.Projected behind = projector.project(0d, 0d, 5d); // 相机看向 -z，+z 在背后

        assertTrue(behind.projected);
        assertTrue(behind.behindCamera, "相机背后的点必须被判成 behindCamera");
        assertTrue(behind.depth <= 0f, "背向时裁剪空间 w 必须 <= 0");
    }

    @Test
    void screenYAxisPointsDown() {
        WorldProjector projector = standardProjector(0, 0, 0);

        // 世界 y 向上、屏幕 y 向下：y 更大的点应该画在更小的屏幕 y 上
        float upY = projector.project(0d, 1d, -5d).y;
        float downY = projector.project(0d, -1d, -5d).y;

        assertTrue(upY < VIEWPORT_HEIGHT / 2f, "世界 y 越大，屏幕 y 越小");
        assertTrue(downY > VIEWPORT_HEIGHT / 2f, "世界 y 越小，屏幕 y 越大");
    }

    @Test
    void cameraPositionIsSubtracted() {
        float[] projection = perspective(70f, 2f, 0.05f, 1000f);
        // 相机在 (10,64,10)，点也在正前方 5 格 → 应该和"相机在原点、点在 (0,0,-5)"一样
        WorldProjector atCamera = projector(identity(), projection, 10d, 64d, 10d);
        WorldProjector atOrigin = projector(identity(), projection, 0d, 0d, 0d);

        float aX = atCamera.project(10d, 64d, 5d).x;
        float aY = atCamera.project(10d, 64d, 5d).y;
        double aDistance = atCamera.project(10d, 64d, 5d).distance;

        WorldProjector.Projected b = atOrigin.project(0d, 0d, -5d);

        assertEquals(b.x, aX, 0.001f);
        assertEquals(b.y, aY, 0.001f);
        assertEquals(b.distance, aDistance, 0.001d);
    }

    @Test
    void viewportClippingOnScreenAndOffScreen() {
        WorldProjector projector = standardProjector(0, 0, 0);

        boolean centerInside = projector.project(0d, 0d, -5d)
                .inViewport(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, 0f);
        assertTrue(centerInside);

        // 视野边缘外：x 很大 → 屏幕坐标超出视口
        boolean farInside = projector.project(1000d, 0d, -5d)
                .inViewport(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, 0f);
        assertFalse(farInside, "远处的点应该被判为屏幕外");

        // 相机背后的点永远不算"在视口内"，即使投影出来的数字看着在范围内
        boolean behindInside = projector.project(0d, 0d, 5d)
                .inViewport(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, 0f);
        assertFalse(behindInside);
    }

    @Test
    void viewportClippingRespectsMargin() {
        WorldProjector projector = standardProjector(0, 0, 0);

        // 构造一个落在屏幕左边缘附近的点
        boolean withoutMargin = projector.project(-4.5d, 0d, -5d)
                .inViewport(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, 0f);
        assertTrue(withoutMargin, "贴边应该算在视口内");

        boolean withMargin = projector.project(-4.5d, 0d, -5d)
                .inViewport(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, 40f);
        assertFalse(withMargin, "加了边距就应该算视口外");
    }

    @Test
    void projectionWithoutMatricesIsInvalid() {
        WorldProjector projector = new WorldProjector();
        WorldProjector.Projected projected = projector.project(0d, 0d, 0d);
        assertFalse(projected.projected);
        assertFalse(projector.ready());
    }

    @Test
    void distanceScaleShrinksWithDistance() {
        // 1 格：2 / 1^0.3 = 2，max(1, 2) = 2 → ×0.5 = 1.0（贴脸时最大）
        assertEquals(1.0f, WorldProjector.scaleForDistance(1d, 100f), 0.001f);
        // 100 格：2 / 100^0.3 ≈ 0.5 < 1 → 取 1 → ×0.5
        assertEquals(0.5f, WorldProjector.scaleForDistance(100d, 100f), 0.001f);
        // 百分比翻倍 → 缩放翻倍
        assertEquals(1.0f, WorldProjector.scaleForDistance(100d, 200f), 0.001f);
        // 距离不会让缩放变成 0 或负数
        assertTrue(WorldProjector.scaleForDistance(1.0e9d, 100f) > 0f);
    }

    @Test
    void distanceAlphaFadesWithDistance() {
        assertEquals(1.0f, WorldProjector.distanceAlpha(0d, 96d), 0.001f);
        assertEquals(1.0f - 0.55f, WorldProjector.distanceAlpha(96d, 96d), 0.001f);
        assertEquals(0.45f, WorldProjector.distanceAlpha(1.0e9d, 96d), 0.001f);
        // maxDistance <= 0 表示不做距离淡化
        assertEquals(1.0f, WorldProjector.distanceAlpha(500d, 0d), 0.001f);
    }

    @Test
    void colorAlphaMultiplication() {
        assertEquals(0xFFFFFFFF, WorldProjector.withAlpha(0xFFFFFFFF, 1f));
        assertEquals(0x00FFFFFF, WorldProjector.withAlpha(0xFFFFFFFF, 0f));
        // 255 * 0.5 = 127.5 → 截断成 127（0x7F），不是四舍五入
        assertEquals(0x7FFFFFFF, WorldProjector.withAlpha(0xFFFFFFFF, 0.5f));
        // 保留 RGB，只改 alpha
        assertEquals(0x3F12AB34, WorldProjector.withAlpha(0xFF12AB34, 0.25f));
    }
}
