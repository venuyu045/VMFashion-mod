package com.venus.vmfashion.ping.render;

/**
 * 世界坐标 → 屏幕坐标的<b>纯逻辑</b>投影器（不依赖任何 Minecraft 类，可直接单测）。
 *
 * <p>数学来源：上游 Ping Wheel 的 {@code MathUtils#worldToScreen}（26.3 分支），我们照抄并
 * 补齐了三个它没做的事：
 * <ol>
 *   <li><b>背向判定</b>：裁剪空间的 {@code w} 就是"离相机多远"，{@code w <= 0} 表示在相机背后
 *       （透视投影会把背后的点镜像到屏幕上，不判就会在身后出现幽灵标记）；</li>
 *   <li><b>视口裁剪</b>：屏幕外 / 贴边 / 在安全区内，交给 {@link Projected#inViewport}；</li>
 *   <li><b>距离缩放</b>：{@link #scaleForDistance}，远处的标点画小一点、淡一点。</li>
 * </ol>
 *
 * <p>为什么用 {@code float[16]} 而不是 JOML 的 {@code Matrix4f}：矩阵每帧从渲染线程的
 * {@code CameraRenderState} 拷过来，用裸数组可以让这个类**完全没有外部依赖**，
 * 单元测试不需要启动 Minecraft，也不需要 JOML 在测试类路径上。
 *
 * <p>线程模型：只在渲染线程使用（{@link PingRenderer} 持有一个实例），
 * 内部复用一个 {@link Projected} 结果对象，<b>每帧零分配</b>。
 * 注意：{@link #project} 返回的是同一个对象，连续调用会覆盖上一次的结果。
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.render}）；矩阵来源改成
 * {@code com.venus.vmfashion.mixin.ping.GameRendererMixin}，算法与单测覆盖的行为保持不变。
 */
public final class WorldProjector {

    /** 矩阵元素个数（4×4，列主序，与 JOML {@code Matrix4f#get(float[])} 一致）。 */
    public static final int MATRIX_SIZE = 16;

    /** 屏幕坐标的"无穷远"钳制值，防止 depth 接近 0 时算出 ±Infinity 把界面画崩。 */
    private static final float MAX_SCREEN_COORD = 1.0e6f;

    /** 视图矩阵（只有旋转，平移由相机坐标承担）。 */
    private final float[] modelView = new float[MATRIX_SIZE];
    /** 投影矩阵（含 bob/反胃修正，见 {@code GameRendererMixin}）。 */
    private final float[] projection = new float[MATRIX_SIZE];
    /** 相机世界坐标。 */
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    /** 视口尺寸（GUI 逻辑像素）。 */
    private int viewportWidth;
    private int viewportHeight;
    /** 矩阵是否已经填过（没填过时 {@link #project} 返回 {@code projected=false}）。 */
    private boolean ready;

    /** 复用的投影结果（避免每帧给每个标点 new 一个对象）。 */
    private final Projected result = new Projected();

    /** 一次投影的结果。字段是 public 的，渲染侧直接读，不再 getter 套一层。 */
    public static final class Projected {
        /** 屏幕 x（逻辑像素，原点左上）。 */
        public float x;
        /** 屏幕 y（逻辑像素，原点左上，y 向下）。 */
        public float y;
        /** 裁剪空间 w：&gt;0 在相机前方，&lt;=0 在相机背后。 */
        public float depth;
        /** 到相机的世界距离（格）。 */
        public double distance;
        /** 是否在相机背后（此时 {@link #x}/{@link #y} 是镜像位置，只能用来算方向）。 */
        public boolean behindCamera;
        /** 是否得到了有效投影（矩阵没准备好 / 退化情况为 false）。 */
        public boolean projected;

        /** 屏幕点是否落在视口内（{@code margin} 是四边内缩的像素）。 */
        public boolean inViewport(int width, int height, float margin) {
            return projected && !behindCamera
                    && x >= margin && x <= width - margin
                    && y >= margin && y <= height - margin;
        }

        /** 重置（复用时先清空）。 */
        void reset() {
            x = 0f;
            y = 0f;
            depth = 0f;
            distance = 0d;
            behindCamera = true;
            projected = false;
        }
    }

    /**
     * 每帧更新一次相机快照。
     *
     * @param modelView      视图矩阵（16 个 float，列主序）
     * @param projection     投影矩阵（16 个 float，列主序）
     * @param camX           相机世界 x
     * @param camY           相机世界 y
     * @param camZ           相机世界 z
     * @param viewportWidth  视口宽（GUI 逻辑像素）
     * @param viewportHeight 视口高（GUI 逻辑像素）
     */
    public void setView(float[] modelView, float[] projection,
                        double camX, double camY, double camZ,
                        int viewportWidth, int viewportHeight) {
        if (modelView == null || projection == null
                || modelView.length < MATRIX_SIZE || projection.length < MATRIX_SIZE) {
            ready = false;
            return;
        }
        System.arraycopy(modelView, 0, this.modelView, 0, MATRIX_SIZE);
        System.arraycopy(projection, 0, this.projection, 0, MATRIX_SIZE);
        this.cameraX = camX;
        this.cameraY = camY;
        this.cameraZ = camZ;
        this.viewportWidth = Math.max(1, viewportWidth);
        this.viewportHeight = Math.max(1, viewportHeight);
        this.ready = true;
    }

    /** 矩阵是否已就绪。 */
    public boolean ready() {
        return ready;
    }

    /** 视口宽（逻辑像素）。 */
    public int viewportWidth() {
        return viewportWidth;
    }

    /** 视口高（逻辑像素）。 */
    public int viewportHeight() {
        return viewportHeight;
    }

    /**
     * 把一个世界坐标投影到屏幕。
     *
     * <p>等价于 {@code P * MV * (world - camera)}，再除以 {@code w} 做透视除法，
     * 最后把 NDC（-1..1，y 向上）映射成屏幕坐标（0..w，y 向下）。
     *
     * @return 复用的结果对象（下一次调用会被覆盖）
     */
    public Projected project(double worldX, double worldY, double worldZ) {
        Projected out = result;
        out.reset();
        if (!ready) {
            return out;
        }

        double dx = worldX - cameraX;
        double dy = worldY - cameraY;
        double dz = worldZ - cameraZ;
        out.distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // ① 视图矩阵（列主序：m[0..3] 是第一列）
        float mx = (float) (modelView[0] * dx + modelView[4] * dy + modelView[8] * dz + modelView[12]);
        float my = (float) (modelView[1] * dx + modelView[5] * dy + modelView[9] * dz + modelView[13]);
        float mz = (float) (modelView[2] * dx + modelView[6] * dy + modelView[10] * dz + modelView[14]);
        float mw = (float) (modelView[3] * dx + modelView[7] * dy + modelView[11] * dz + modelView[15]);

        // ② 投影矩阵
        float px = projection[0] * mx + projection[4] * my + projection[8] * mz + projection[12] * mw;
        float py = projection[1] * mx + projection[5] * my + projection[9] * mz + projection[13] * mw;
        float pw = projection[3] * mx + projection[7] * my + projection[11] * mz + projection[15] * mw;

        out.depth = pw;
        out.behindCamera = pw <= 0f;
        out.projected = true;

        if (pw == 0f) {
            // 退化：正好在相机平面上，没有可用的屏幕位置
            out.x = viewportWidth * 0.5f;
            out.y = viewportHeight * 0.5f;
            out.projected = false;
            return out;
        }

        // ③ 透视除法 + NDC → 屏幕（y 轴翻转）
        float ndcX = px / pw;
        float ndcY = py / pw;
        out.x = clamp(viewportWidth * (0.5f + ndcX * 0.5f));
        out.y = clamp(viewportHeight * (0.5f - ndcY * 0.5f));
        return out;
    }

    /**
     * 距离 → 缩放系数（照抄上游 {@code PingView#calculateScale}）。
     *
     * <p>公式 {@code max(1, 2 / distance^0.3) * 0.5 * sizePercent/100}：
     * 指数 0.3 让"远处缩小"非常缓慢（1 格→1.0，10 格→0.5，100 格→0.5，1000 格→0.5），
     * 只有贴脸时才会放大，避免远处标记糊成一片。
     *
     * @param distance     与相机的距离（格）
     * @param sizePercent  用户配置的标记大小百分比（100 = 默认）
     * @return 缩放系数，正常范围 {@code [0.5, 1.0]}（sizePercent=100 时）
     */
    public static float scaleForDistance(double distance, float sizePercent) {
        double safeDistance = Math.max(1.0d, distance);
        double base = 2.0d / Math.pow(safeDistance, 0.3d);
        float scale = (float) Math.max(1.0d, base) * 0.5f * (sizePercent / 100f);
        return Math.max(0.1f, scale);
    }

    /**
     * 距离 → 透明度系数：越远越淡（但不低于 45%，否则玩家会以为标记丢了）。
     *
     * @param distance    与相机的距离（格）
     * @param maxDistance 图标最远绘制距离（格）；{@code <= 0} 表示不做距离淡化
     */
    public static float distanceAlpha(double distance, double maxDistance) {
        if (maxDistance <= 0d) {
            return 1f;
        }
        float t = (float) Math.min(1.0d, Math.max(0.0d, distance / maxDistance));
        return 1f - 0.55f * t;
    }

    /**
     * 给 ARGB 颜色乘一个透明度系数。
     *
     * <p>单独抽出来是因为渲染层到处要用（淡出、自己的标点更淡、边缘箭头），
     * 而且它是纯函数，单测能直接覆盖。
     */
    public static int withAlpha(int argb, float factor) {
        int alpha = (int) (((argb >>> 24) & 0xFF) * clamp01(factor));
        return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0x00FFFFFF);
    }

    /** 把 0..1 之外的值夹回区间（NaN 当 0 处理）。 */
    public static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    private static float clamp(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(-MAX_SCREEN_COORD, Math.min(MAX_SCREEN_COORD, value));
    }
}
