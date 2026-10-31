import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯数据的自检：在没有显卡、没人眼可用的前提下，用可计算的指标代替"看一眼"。
 *
 * <p>覆盖需求里点名的三类问题：
 * <ul>
 *   <li><b>左右不对称</b> —— {@link #symmetryRatio}：每个四边形沿 X 镜像后能否在模型里找到对应块；</li>
 *   <li><b>贴图 UV 错位 / 贴图不对</b> —— {@link #textureReport}：UV 是否越界、
 *       四边形引用的 texel 是否全透明（全透明 = 画出来看不见，通常就是 UV 打错或贴图给错）；</li>
 *   <li><b>动画骨骼对不上 / 没效果</b> —— {@link #maxVertexShift}：相邻动画帧之间顶点最大位移，
 *       0 就说明动画根本没动（骨骼名对不上、通道没解析、快照没生效都会是这个结果）。</li>
 * </ul>
 *
 * <p>这些都是"辅助判断"，不能替代看图：指标全绿也可能构图难看，指标报警也未必是 bug。
 */
final class SelfCheck {

    private SelfCheck() {
    }

    /**
     * X 镜像对称率：四边形沿 X 轴镜像后，能否在原模型里找到同一块（顶点集合一致，容差 0.01 单位）。
     *
     * <p>为什么按"顶点集合"而不是"逐顶点顺序"：镜像会翻转绕序，同一个面在镜像后顶点顺序可能旋转/反转，
     * 只比集合才不会误报。所以对称率 100% 且四边形数为偶数时，基本可以认定左右对称。
     *
     * @return 0..1，找不到镜像对应的四边形比例（1.0 = 完全对称）
     */
    static double symmetryRatio(List<QuadRecorder.Quad> quads) {
        if (quads.isEmpty()) {
            return 1.0;
        }
        Map<String, Integer> index = new HashMap<>(quads.size() * 2);
        for (QuadRecorder.Quad quad : quads) {
            String key = quadKey(quad);
            index.merge(key, 1, Integer::sum);
        }
        int matched = 0;
        for (QuadRecorder.Quad quad : quads) {
            String key = quadKey(quad.mirroredX());
            Integer available = index.get(key);
            if (available != null && available > 0) {
                matched++;
                index.put(key, available - 1);
            }
        }
        return (double) matched / (double) quads.size();
    }

    /** 四边形顶点的排序化字符串（容差 0.01 单位，避免浮点噪声把对称判成不对称）。 */
    private static String quadKey(QuadRecorder.Quad quad) {
        List<String> points = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            points.add(String.format("%.2f,%.2f,%.2f", quad.pos[i][0], quad.pos[i][1], quad.pos[i][2]));
        }
        points.sort(String::compareTo);
        return String.join("|", points);
    }

    /** 贴图 / UV 相关的检查结果。 */
    record TextureReport(boolean uvInRange, double uvMinU, double uvMaxU, double uvMinV, double uvMaxV,
                         int fullyTransparentQuads, int totalQuads, double referencedOpaqueRatio,
                         int degenerateQuads) {
        @Override
        public String toString() {
            return String.format("UV 范围 u[%.3f, %.3f] v[%.3f, %.3f]%s；"
                            + "引用 texel 不透明占比 %.1f%%；完全透明的四边形 %d/%d；退化四边形 %d",
                    uvMinU, uvMaxU, uvMinV, uvMaxV, uvInRange ? "" : "（越界！）",
                    referencedOpaqueRatio * 100.0, fullyTransparentQuads, totalQuads, degenerateQuads);
        }
    }

    /**
     * 贴图自检：UV 越界、四边形是否落在全透明区域、退化（零面积）四边形。
     *
     * <p>"完全透明的四边形"不一定是 bug（模型可能故意留空），但如果比例很高（比如 &gt; 30%），
     * 基本可以确定是 UV 与贴图不匹配。
     */
    static TextureReport textureReport(CosmeticSource source, List<QuadRecorder.Quad> quads) {
        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        int fullyTransparent = 0;
        int degenerate = 0;
        long opaqueTexels = 0;
        long sampledTexels = 0;

        for (QuadRecorder.Quad quad : quads) {
            for (int i = 0; i < 4; i++) {
                minU = Math.min(minU, quad.uv[i][0]);
                maxU = Math.max(maxU, quad.uv[i][0]);
                minV = Math.min(minV, quad.uv[i][1]);
                maxV = Math.max(maxV, quad.uv[i][1]);
            }
            // 退化：四边形面积接近 0（导出的坏 cube）
            if (quadArea(quad) < 1.0e-4) {
                degenerate++;
            }
            // 用 UV 包围盒采样 texel，判断这块面画出来会不会完全看不见
            double qMinU = Double.POSITIVE_INFINITY;
            double qMaxU = Double.NEGATIVE_INFINITY;
            double qMinV = Double.POSITIVE_INFINITY;
            double qMaxV = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < 4; i++) {
                qMinU = Math.min(qMinU, quad.uv[i][0]);
                qMaxU = Math.max(qMaxU, quad.uv[i][0]);
                qMinV = Math.min(qMinV, quad.uv[i][1]);
                qMaxV = Math.max(qMaxV, quad.uv[i][1]);
            }
            int x0 = (int) Math.floor(qMinU * source.textureWidth);
            int x1 = Math.max(x0, (int) Math.ceil(qMaxU * source.textureWidth) - 1);
            int y0 = (int) Math.floor(qMinV * source.textureHeight);
            int y1 = Math.max(y0, (int) Math.ceil(qMaxV * source.textureHeight) - 1);
            boolean anyOpaque = false;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int texel = source.texel(x, y);
                    boolean opaque = ((texel >>> 24) & 0xFF) >= 128;
                    sampledTexels++;
                    if (opaque) {
                        opaqueTexels++;
                        anyOpaque = true;
                    }
                }
            }
            if (!anyOpaque) {
                fullyTransparent++;
            }
        }
        if (quads.isEmpty()) {
            minU = 0.0;
            maxU = 0.0;
            minV = 0.0;
            maxV = 0.0;
        }
        boolean inRange = minU >= -1.0e-4 && maxU <= 1.0 + 1.0e-4 && minV >= -1.0e-4 && maxV <= 1.0 + 1.0e-4;
        double opaqueRatio = sampledTexels == 0 ? 0.0 : (double) opaqueTexels / (double) sampledTexels;
        return new TextureReport(inRange, minU, maxU, minV, maxV,
                fullyTransparent, quads.size(), opaqueRatio, degenerate);
    }

    private static double quadArea(QuadRecorder.Quad quad) {
        // 用两条对角线叉积的一半近似面积（四边形，凹凸都适用）
        double[] d1 = {quad.pos[2][0] - quad.pos[0][0], quad.pos[2][1] - quad.pos[0][1], quad.pos[2][2] - quad.pos[0][2]};
        double[] d2 = {quad.pos[3][0] - quad.pos[1][0], quad.pos[3][1] - quad.pos[1][1], quad.pos[3][2] - quad.pos[1][2]};
        double cx = d1[1] * d2[2] - d1[2] * d2[1];
        double cy = d1[2] * d2[0] - d1[0] * d2[2];
        double cz = d1[0] * d2[1] - d1[1] * d2[0];
        return 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /**
     * 画面左右镜像差异：把图左右翻转后与原图逐像素比较。
     *
     * <p>这是"看起来是否对称"的直接量化 —— 几何对称但贴图/光照不对称时，
     * 顶点级的对称检查（{@link #symmetryRatio}）看不出来，这里能看出来。
     *
     * @return {@code [平均色差(0..255), 差异 &gt; 24 的像素占比(0..1)]}
     */
    static double[] imageMirrorDifference(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        long total = 0;
        long large = 0;
        double sum = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width / 2; x++) {
                int left = image.getRGB(x, y);
                int right = image.getRGB(width - 1 - x, y);
                int dr = Math.abs(((left >> 16) & 0xFF) - ((right >> 16) & 0xFF));
                int dg = Math.abs(((left >> 8) & 0xFF) - ((right >> 8) & 0xFF));
                int db = Math.abs((left & 0xFF) - (right & 0xFF));
                int diff = Math.max(dr, Math.max(dg, db));
                sum += diff;
                total++;
                if (diff > 24) {
                    large++;
                }
            }
        }
        return new double[]{total == 0 ? 0.0 : sum / total, total == 0 ? 0.0 : (double) large / total};
    }

    /**
     * 相邻两帧之间的顶点最大位移（单位：1/16 格）。
     *
     * <p>依赖"同一模型每次渲染的四边形/顶点顺序完全一致"这个前提 —— 对同一个
     * {@link com.geckolib.cache.model.BakedGeoModel} 是成立的（动画只改骨骼矩阵，不改几何结构）。
     *
     * @return 0 表示这两帧完全一样（动画没生效）
     */
    static double maxVertexShift(List<QuadRecorder.Quad> previous, List<QuadRecorder.Quad> current) {
        if (previous == null || current == null || previous.size() != current.size()) {
            return 0.0;
        }
        double max = 0.0;
        for (int i = 0; i < previous.size(); i++) {
            QuadRecorder.Quad a = previous.get(i);
            QuadRecorder.Quad b = current.get(i);
            for (int v = 0; v < 4; v++) {
                double dx = a.pos[v][0] - b.pos[v][0];
                double dy = a.pos[v][1] - b.pos[v][1];
                double dz = a.pos[v][2] - b.pos[v][2];
                max = Math.max(max, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        return max;
    }
}
