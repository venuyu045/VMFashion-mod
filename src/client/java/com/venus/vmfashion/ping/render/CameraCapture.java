package com.venus.vmfashion.ping.render;

import org.joml.Matrix4f;

/**
 * 每帧一份的"相机快照"：视图矩阵 + 投影矩阵 + 相机世界坐标 + 视口尺寸。
 *
 * <p>为什么需要它：26.3 的 HUD 绘制（{@code GuiGraphicsExtractor}）拿到的只有 2D 上下文，
 * 拿不到世界渲染用的矩阵。矩阵只在 {@code GameRenderer#extract} 里短暂存在，
 * 所以由 {@code com.venus.vmfashion.mixin.ping.GameRendererMixin} 在那个时刻抓一份快照放这里，
 * HUD 渲染时再取出来投影（上游 Ping Wheel 26.3 分支就是这套做法）。
 *
 * <p>为什么不直接在 HUD 里读 {@code Minecraft#gameRenderer}：26.3 里
 * {@code GameRenderState} 是 {@code private final} 字段，没有公开 getter。
 *
 * <p><b>线程模型</b>：写（{@link #capture}）和读（{@link #current}）都发生在渲染线程。
 * 为了即使将来有人在别的线程读也不会读到"一半新一半旧"的矩阵，
 * 这里用两块快照<b>交替发布</b>（ping-pong）：先填另一块，填完再换引用。
 * 整个过程<b>零分配</b>（矩阵数组是复用的），不会给每帧的 GC 添负担。
 *
 * <p>合订版适配：只改包名（{@code com.venus.vmfashion.ping.render}）与 mixin 位置说明；
 * 快照结构与发布机制不变。
 */
public final class CameraCapture {

    /** 两块交替发布的快照。 */
    private static final Snapshot[] SLOTS = {new Snapshot(), new Snapshot()};
    /** 当前对外可见的那一块。 */
    private static volatile Snapshot published = SLOTS[0];
    /** 快照代数：每帧 +1，渲染侧可用来判断"这一帧的矩阵是不是新的"。 */
    private static volatile long generation;

    private CameraCapture() {
    }

    /** 一份相机快照（可变对象，由 {@link CameraCapture} 独占写入）。 */
    public static final class Snapshot {
        /** 视图矩阵（列主序，16 个 float）。 */
        public final float[] modelView = new float[16];
        /** 投影矩阵（列主序，16 个 float，已含 bob/反胃修正）。 */
        public final float[] projection = new float[16];
        /** 相机世界坐标。 */
        public double cameraX;
        public double cameraY;
        public double cameraZ;
        /** 视口尺寸（GUI 逻辑像素，窗口 resize 后下一帧自动更新）。 */
        public int viewportWidth;
        public int viewportHeight;
        /** 是否有效（没抓到矩阵、或不在世界里时为 false）。 */
        public boolean valid;
        /** 快照代数。 */
        public long generation;

        private void fill(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                          double camX, double camY, double camZ,
                          int width, int height, long gen) {
            modelViewMatrix.get(modelView);
            projectionMatrix.get(projection);
            cameraX = camX;
            cameraY = camY;
            cameraZ = camZ;
            viewportWidth = Math.max(1, width);
            viewportHeight = Math.max(1, height);
            generation = gen;
            valid = true;
        }

        private void invalidate() {
            valid = false;
            viewportWidth = 0;
            viewportHeight = 0;
        }
    }

    /**
     * 抓一帧快照（在 {@code GameRenderer#extract} 里调用，每帧一次）。
     *
     * @param modelViewMatrix  视图矩阵（{@code CameraRenderState#viewRotationMatrix}）
     * @param projectionMatrix 投影矩阵（已叠加 bob/反胃修正）
     * @param camX             相机世界 x
     * @param camY             相机世界 y
     * @param camZ             相机世界 z
     * @param viewportWidth    GUI 逻辑宽
     * @param viewportHeight   GUI 逻辑高
     */
    public static void capture(Matrix4f modelViewMatrix, Matrix4f projectionMatrix,
                               double camX, double camY, double camZ,
                               int viewportWidth, int viewportHeight) {
        if (modelViewMatrix == null || projectionMatrix == null) {
            return;
        }
        long gen = generation + 1;
        // 写"另一块"，写完再发布，读者永远看到完整的 16 个 float
        Snapshot target = published == SLOTS[0] ? SLOTS[1] : SLOTS[0];
        target.fill(modelViewMatrix, projectionMatrix, camX, camY, camZ,
                viewportWidth, viewportHeight, gen);
        published = target;
        generation = gen;
    }

    /** 当前快照；从未抓过时返回一个 {@code valid=false} 的对象（不返回 null，调用方少判一次）。 */
    public static Snapshot current() {
        return published;
    }

    /** 当前快照代数（诊断用）。 */
    public static long generation() {
        return generation;
    }

    /** 退出世界 / 断开连接时清空，避免用上一局的矩阵画幽灵标记。 */
    public static void invalidate() {
        Snapshot target = published == SLOTS[0] ? SLOTS[1] : SLOTS[0];
        target.invalidate();
        published = target;
        generation++;
    }
}
