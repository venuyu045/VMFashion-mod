import com.mojang.blaze3d.vertex.VertexConsumer;

import java.util.ArrayList;
import java.util.List;

/**
 * 记录型 {@link VertexConsumer}：把 {@code GeoModelRenderer} 吐出来的顶点原样存下来。
 *
 * <p>为什么需要它：{@code GeoModelRenderer} 是生产渲染路径，它只负责"算矩阵 + 出顶点"，
 * 顶点最终去哪由 {@link VertexConsumer} 决定 —— 游戏里是 26.x 的提交式管线，这里换成一个
 * 把 {@code x,y,z,u,v,nx,ny,nz,argb} 全记下来的实现，就能在无显卡环境拿到完整几何。
 *
 * <p>四边形约定：GeckoLib 的 {@code GeoQuad} 固定 4 个顶点（顺序与 {@code GeoQuad#vertices()}
 * 一致，未做任何重排），所以每收满 4 个顶点就是一个四边形。两条写入路径都支持：
 * <ul>
 *   <li>{@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)} —— 生产渲染器实际走的那条
 *       （{@code VertexConsumer} 里的 default 方法，这里直接覆盖，一次拿全所有属性）；</li>
 *   <li>{@code addVertex(x,y,z)} + {@code setUv/setNormal/setColor} 的分步写法 —— 兜底，
 *       万一上游换回逐属性调用也能正确记录。</li>
 * </ul>
 *
 * <p>线程：单线程使用（预览工具是单线程的），不加锁。
 */
final class QuadRecorder implements VertexConsumer {

    /** 一个四边形：4 个顶点的位置 / UV / 法线 / 颜色，顺序与 GeckoLib 的 {@code GeoQuad} 一致。 */
    static final class Quad {
        final float[][] pos = new float[4][3];
        final float[][] uv = new float[4][2];
        final float[][] normal = new float[4][3];
        final int[] argb = new int[4];

        /** 四边形中心（用于背面法线翻转、排序等）。 */
        double centerX() {
            return (pos[0][0] + pos[1][0] + pos[2][0] + pos[3][0]) * 0.25;
        }

        double centerY() {
            return (pos[0][1] + pos[1][1] + pos[2][1] + pos[3][1]) * 0.25;
        }

        double centerZ() {
            return (pos[0][2] + pos[1][2] + pos[2][2] + pos[3][2]) * 0.25;
        }

        /** 平均法线（GeckoLib 每个 quad 只有一个法线，4 个顶点相同，这里取平均以防万一）。 */
        double[] averageNormal() {
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            for (int i = 0; i < 4; i++) {
                x += normal[i][0];
                y += normal[i][1];
                z += normal[i][2];
            }
            double len = Math.sqrt(x * x + y * y + z * z);
            if (len < 1.0e-9) {
                return new double[]{0.0, 0.0, 0.0};
            }
            return new double[]{x / len, y / len, z / len};
        }

        /** 复制并做 X 镜像（x → -x，法线同样翻转），用于左右对称自检。 */
        Quad mirroredX() {
            Quad copy = new Quad();
            for (int i = 0; i < 4; i++) {
                copy.pos[i][0] = -pos[i][0];
                copy.pos[i][1] = pos[i][1];
                copy.pos[i][2] = pos[i][2];
                copy.uv[i][0] = uv[i][0];
                copy.uv[i][1] = uv[i][1];
                copy.normal[i][0] = -normal[i][0];
                copy.normal[i][1] = normal[i][1];
                copy.normal[i][2] = normal[i][2];
                copy.argb[i] = argb[i];
            }
            return copy;
        }

        @Override
        public String toString() {
            return String.format("Quad[c=(%.2f, %.2f, %.2f) n=(%.2f, %.2f, %.2f)]",
                    centerX(), centerY(), centerZ(),
                    normal[0][0], normal[0][1], normal[0][2]);
        }
    }

    /** 已完成的四边形（保持上游输出顺序）。 */
    final List<Quad> quads = new ArrayList<>();

    /** 当前正在填的四边形。 */
    private Quad pending;
    /** 当前四边形已收到的顶点数（0..4）。 */
    private int count;
    /** 分步写入时"最后一个顶点"的下标（{@code setUv}/{@code setNormal} 作用在它上面）。 */
    private int current = -1;

    /** 换一个新四边形；旧的若刚好收满 4 个顶点就先收尾。 */
    private Quad slot() {
        if (pending != null && count == 4) {
            flushPending();
        }
        if (pending == null) {
            pending = new Quad();
            count = 0;
        }
        return pending;
    }

    private void flushPending() {
        if (pending == null) {
            return;
        }
        // 顶点不足 4 个的残片（三角形/线段）丢弃：预览只画四边形，残缺块画出来是错的
        if (count == 4) {
            quads.add(pending);
        }
        pending = null;
        count = 0;
        current = -1;
    }

    /** 渲染结束后必须调用：把最后一个四边形收进列表。 */
    void finish() {
        flushPending();
    }

    /** 清空并复用（多帧渲染时每帧重新记录）。 */
    void reset() {
        quads.clear();
        pending = null;
        count = 0;
        current = -1;
    }

    // ==================== VertexConsumer ====================

    /**
     * 生产渲染器走的就是这条（11 参数）路径：一次拿到位置 + 颜色 + UV + 法线。
     *
     * <p>覆盖 default 实现而不是靠 {@code setUv/setNormal} 逐个回调，好处是属性原子写入，
     * 不会因为上游改了调用顺序而错位。
     */
    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        Quad quad = slot();
        int index = count;
        quad.pos[index][0] = x;
        quad.pos[index][1] = y;
        quad.pos[index][2] = z;
        quad.uv[index][0] = u;
        quad.uv[index][1] = v;
        quad.argb[index] = color;
        quad.normal[index][0] = nx;
        quad.normal[index][1] = ny;
        quad.normal[index][2] = nz;
        count = index + 1;
        current = index;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        Quad quad = slot();
        int index = count;
        quad.pos[index][0] = x;
        quad.pos[index][1] = y;
        quad.pos[index][2] = z;
        // 缺省：白色 + UV 0（后续 setUv/setNormal 会覆盖）
        quad.argb[index] = 0xFFFFFFFF;
        count = index + 1;
        current = index;
        return this;
    }

    @Override
    public VertexConsumer setColor(int r, int g, int b, int a) {
        if (current >= 0) {
            pending.argb[current] = (clampByte(a) << 24) | (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
        }
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        if (current >= 0) {
            pending.argb[current] = color;
        }
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        if (current >= 0) {
            pending.uv[current][0] = u;
            pending.uv[current][1] = v;
        }
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer setUv3(float u, float v) {
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        if (current >= 0) {
            pending.normal[current][0] = x;
            pending.normal[current][1] = y;
            pending.normal[current][2] = z;
        }
        return this;
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        return this;
    }

    private static int clampByte(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
