import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 纯 CPU 的软光栅化器：透视相机 + z-buffer + 贴图最近邻采样 + alpha cutout + 逐面方向光。
 *
 * <p>为什么自己写：这台机器没有显卡、没有 Minecraft 客户端，GeckoLib 的顶点只能写进一个
 * {@link com.mojang.blaze3d.vertex.VertexConsumer}。想"看到"模型，就得自己在 CPU 上把三角形画出来。
 * 依赖只有 JDK（{@link BufferedImage} + {@link javax.imageio.ImageIO}），不碰 LWJGL / OpenGL。
 *
 * <p>坐标：E 空间（脚底原点、Y 向上、+Z 背后、单位 1/16 格），相机把世界坐标直接投影到屏幕。
 *
 * <p>渲染顺序（从远到近，和真实绘制一致）：
 * <ol>
 *   <li>{@link #fillBackground} —— 竖直渐变 + 中心光斑 + 四周压暗；</li>
 *   <li>{@link #drawGroundShadow} —— 地面椭圆阴影（逐顶点 alpha 的三角形扇，落在 y≈0 平面）；</li>
 *   <li>{@link #drawQuads} —— 模型本体（不透明，写深度，alpha cutout）；</li>
 *   <li>{@link #drawBox} —— 原版玩家比例参考剪影（半透明灰，深度测试让它被模型正确遮挡）。</li>
 * </ol>
 */
final class SoftRasterizer {

    /** 近裁剪面（相机空间深度，正数）。 */
    private static final double NEAR = 0.02;
    /** alpha cutout 阈值：alpha &lt; 0.5 直接丢弃（与需求一致，比原版 cutout 的 0.1 更干净）。 */
    private static final double ALPHA_CUTOUT = 0.5;
    /**
     * 深度偏置（相对值）。模型里两块立方体共面时（导出的背板就有一处），
     * 两个面的深度只差浮点噪声，逐像素比较会闪烁出雪花 —— 加一点偏置让"先画的赢"，
     * 结果稳定且肉眼无差别（0.0016 单位的偏移远小于任何真实遮挡差异）。
     */
    private static final double DEPTH_BIAS = 1.0e-4;
    /** 光线：环境光 + 主光 + 补光 + 边缘光，最后 clamp 到 1。 */
    private static final double AMBIENT = 0.40;
    private static final double KEY_LIGHT = 0.42;
    private static final double FILL_LIGHT = 0.20;
    private static final double RIM_LIGHT = 0.10;

    /** 一次绘制用的材质。 */
    private record Material(boolean textured, boolean lit, boolean blend, boolean depthWrite) {
        /** 模型本体：贴图 + 光照 + 不透明 + 写深度。 */
        static final Material MODEL = new Material(true, true, false, true);
        /** 参考剪影：纯色 + 光照 + 半透明混合 + 写深度（避免同一剪影的多个面重复叠加变深）。 */
        static final Material SILHOUETTE = new Material(false, true, true, true);
        /** 地面阴影：纯色 + 不光照 + 半透明混合 + 写深度。 */
        static final Material SHADOW = new Material(false, false, true, true);
    }

    private final int width;
    private final int height;
    /** 0xAARRGGBB（非预乘）。 */
    private final int[] pixels;
    /** 相机空间深度（正数，越小越近）。 */
    private final float[] depth;
    /** 模型覆盖掩码（自检用：模型在画面上占了多少像素）。 */
    private final boolean[] covered;

    private final double[] eye = new double[3];
    private final double[] forward = new double[3];
    private final double[] right = new double[3];
    private final double[] up = new double[3];
    private final double tanV;
    private final double tanH;

    private final int[] texels;
    private final int texW;
    private final int texH;

    /** 主光 / 补光方向（世界空间，随相机走：光永远来自"相机左上方"，产品图打光）。 */
    private final double[] lightKey = new double[3];
    private final double[] lightFill = new double[3];

    private boolean trackCoverage;
    private int coverageCount;

    private SoftRasterizer(int width, int height, double fovDegrees, int[] texels, int texW, int texH) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        this.depth = new float[width * height];
        this.covered = new boolean[width * height];
        this.texels = texels;
        this.texW = texW;
        this.texH = texH;

        double aspect = (double) width / (double) height;
        this.tanV = Math.tan(Math.toRadians(fovDegrees) * 0.5);
        this.tanH = tanV * aspect;
    }

    /** 默认竖直视场角。32° 左右接近"产品图"的观感，太广会有明显透视畸变。 */
    static final double DEFAULT_FOV_Y_DEGREES = 32.0;

    /**
     * 按"必须入镜的点集"自动取景创建光栅化器。
     *
     * <p>为什么用点集而不是包围盒 8 个角：包围盒的角往往不在任何几何上（比如翅膀的包围盒
     * 又宽又深，最靠近相机的那个"角"是空的），按角取景会白让出十几 % 的画面。
     * 直接拿真实顶点（模型顶点 + 玩家剪影的 8 个角）解距离，构图才够满。
     *
     * @param width      渲染宽度（含超采样）
     * @param height     渲染高度（含超采样）
     * @param center     构图中心（相机看向这里）
     * @param fitPoints  必须完整入镜的点（世界坐标 xyz）
     * @param dirToEye   从中心指向相机的单位方向（相机在哪一侧）
     * @param margin     取景余量（1.2 ≈ 四周各留 8%）
     * @param fovDegrees 竖直视场角（度）
     */
    static SoftRasterizer create(int width, int height, double[] center, List<double[]> fitPoints,
                                 double[] dirToEye, double margin, double fovDegrees,
                                 int[] texels, int texW, int texH) {
        SoftRasterizer raster = new SoftRasterizer(width, height, fovDegrees, texels, texW, texH);

        double[] dir = normalize(new double[]{dirToEye[0], dirToEye[1], dirToEye[2]});
        double[] forward = normalize(new double[]{-dir[0], -dir[1], -dir[2]});
        // 俯视图的"上"不能再用 +Y（与视线平行），改用 -Z：画面正上方是模型正前方
        double[] worldUp = Math.abs(dir[1]) > 0.95 ? new double[]{0.0, 0.0, -1.0} : new double[]{0.0, 1.0, 0.0};
        double[] right = normalize(cross(forward, worldUp));
        double[] up = cross(right, forward);

        System.arraycopy(forward, 0, raster.forward, 0, 3);
        System.arraycopy(right, 0, raster.right, 0, 3);
        System.arraycopy(up, 0, raster.up, 0, 3);

        double centerX = center[0];
        double centerY = center[1];
        double centerZ = center[2];

        // 解最小距离：让每个点都落进视锥（|camX| <= (d+camF)*tanH、|camY| <= (d+camF)*tanV）
        double distance = 1.0;
        for (double[] point : fitPoints) {
            double rx = point[0] - centerX;
            double ry = point[1] - centerY;
            double rz = point[2] - centerZ;
            double camX = rx * right[0] + ry * right[1] + rz * right[2];
            double camY = rx * up[0] + ry * up[1] + rz * up[2];
            double camF = rx * forward[0] + ry * forward[1] + rz * forward[2];
            distance = Math.max(distance, Math.abs(camX) / raster.tanH - camF);
            distance = Math.max(distance, Math.abs(camY) / raster.tanV - camF);
        }
        distance = Math.max(distance * margin, 1.0);

        raster.eye[0] = centerX + dir[0] * distance;
        raster.eye[1] = centerY + dir[1] * distance;
        raster.eye[2] = centerZ + dir[2] * distance;

        // 打光：主光来自相机左上方、补光来自右前方（都跟着相机，换视角不会突然变暗）
        double[] key = new double[3];
        double[] fill = new double[3];
        // 左右基本对称（X 分量很小）：预览的主要用途之一是判断"左右对称"，光偏得太厉害会让
        // 对称的模型看起来一边亮一边暗；同时保留一点偏移，纯正面光会让侧面失去形体
        for (int i = 0; i < 3; i++) {
            key[i] = -0.14 * right[i] + 0.66 * up[i] - 1.00 * forward[i];
            fill[i] = 0.46 * right[i] + 0.12 * up[i] - 0.60 * forward[i];
        }
        System.arraycopy(normalize(key), 0, raster.lightKey, 0, 3);
        System.arraycopy(normalize(fill), 0, raster.lightFill, 0, 3);

        Arrays.fill(raster.depth, Float.POSITIVE_INFINITY);
        return raster;
    }

    // ==================== 背景 / 阴影 / 剪影 ====================

    /**
     * 背景：竖直渐变 + 中心径向光斑 + 四周压暗（影棚背景板的感觉）。
     *
     * @param topArgb    顶部颜色（0xAARRGGBB）
     * @param bottomArgb 底部颜色
     */
    void fillBackground(int topArgb, int bottomArgb) {
        Arrays.fill(depth, Float.POSITIVE_INFINITY);
        Arrays.fill(covered, false);
        coverageCount = 0;
        double centerX = 0.5;
        double centerY = 0.40;
        for (int y = 0; y < height; y++) {
            double t = height <= 1 ? 0.0 : (double) y / (height - 1);
            int base = lerpColor(topArgb, bottomArgb, t);
            for (int x = 0; x < width; x++) {
                double dx = (x + 0.5) / width - centerX;
                double dy = ((y + 0.5) / height - centerY) * 1.15;
                double radius = Math.sqrt(dx * dx + dy * dy);
                double glow = Math.exp(-radius * radius * 5.5) * 0.17;
                double vignette = -Math.min(0.09, radius * radius * 0.055);
                pixels[y * width + x] = shiftColor(base, glow + vignette);
            }
        }
    }

    /**
     * 地面椭圆阴影：以 {@code (cx, cz)} 为中心、{@code (rx, rz)} 为半径的圆盘，
     * 中心 alpha 最大、边缘 0，落在 y 略高于 0 的平面上（免得和 y=0 的东西打架）。
     *
     * <p>用三角形扇 + 逐顶点 alpha 插值来做"软边"，比逐像素算距离便宜，效果也够。
     */
    void drawGroundShadow(double cx, double cz, double rx, double rz, int rgb, int centerAlpha) {
        int segments = 72;
        double y = 0.02;
        CamVert center = camVert(cx, y, cz, 0.0, 0.0, (centerAlpha << 24) | (rgb & 0xFFFFFF));
        CamVert[] rim = new CamVert[segments + 1];
        for (int i = 0; i <= segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            double px = cx + Math.cos(angle) * rx;
            double pz = cz + Math.sin(angle) * rz;
            rim[i] = camVert(px, y, pz, 0.0, 0.0, rgb & 0xFFFFFF);
        }
        for (int i = 0; i < segments; i++) {
            drawCameraTriangle(center, rim[i], rim[i + 1], Material.SHADOW, 1.0);
        }
    }

    /**
     * 画一个轴对齐长方体（参考剪影用）：6 个面、背面剔除、半透明混合。
     *
     * @param argb 含 alpha 的颜色
     */
    void drawBox(double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
        // +X
        drawFace(new double[][]{{x1, y0, z0}, {x1, y0, z1}, {x1, y1, z1}, {x1, y1, z0}}, 1, 0, 0, argb);
        // -X
        drawFace(new double[][]{{x0, y0, z1}, {x0, y0, z0}, {x0, y1, z0}, {x0, y1, z1}}, -1, 0, 0, argb);
        // +Y
        drawFace(new double[][]{{x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}}, 0, 1, 0, argb);
        // -Y
        drawFace(new double[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y0, z0}, {x0, y0, z0}}, 0, -1, 0, argb);
        // +Z
        drawFace(new double[][]{{x0, y0, z1}, {x0, y1, z1}, {x1, y1, z1}, {x1, y0, z1}}, 0, 0, 1, argb);
        // -Z
        drawFace(new double[][]{{x1, y0, z0}, {x1, y1, z0}, {x0, y1, z0}, {x0, y0, z0}}, 0, 0, -1, argb);
    }

    private void drawFace(double[][] corners, double nx, double ny, double nz, int argb) {
        // 背面剔除：法线背离相机就不画（剪影是闭合长方体，不需要双面）
        double cx = (corners[0][0] + corners[1][0] + corners[2][0] + corners[3][0]) * 0.25;
        double cy = (corners[0][1] + corners[1][1] + corners[2][1] + corners[3][1]) * 0.25;
        double cz = (corners[0][2] + corners[1][2] + corners[2][2] + corners[3][2]) * 0.25;
        double vx = eye[0] - cx;
        double vy = eye[1] - cy;
        double vz = eye[2] - cz;
        if (nx * vx + ny * vy + nz * vz <= 0.0) {
            return;
        }
        double shade = shadeForNormal(new double[]{nx, ny, nz});
        CamVert[] verts = new CamVert[4];
        for (int i = 0; i < 4; i++) {
            verts[i] = camVert(corners[i][0], corners[i][1], corners[i][2], 0.0, 0.0, argb);
        }
        List<CamVert> clipped = clipNear(verts);
        if (clipped.size() < 3) {
            return;
        }
        ScreenVert[] screen = project(clipped);
        for (int i = 1; i + 1 < screen.length; i++) {
            drawTriangle(screen[0], screen[i], screen[i + 1], Material.SILHOUETTE, shade);
        }
    }

    // ==================== 模型 ====================

    /** 画模型本体（贴图 + cutout + 光照），同时统计画面覆盖率。 */
    void drawModel(List<QuadRecorder.Quad> quads) {
        trackCoverage = true;
        try {
            for (QuadRecorder.Quad quad : quads) {
                drawQuad(quad, Material.MODEL, shadeForQuad(quad));
            }
        } finally {
            trackCoverage = false;
        }
    }

    private void drawQuad(QuadRecorder.Quad quad, Material material, double shade) {
        CamVert[] verts = new CamVert[4];
        for (int i = 0; i < 4; i++) {
            verts[i] = camVert(quad.pos[i][0], quad.pos[i][1], quad.pos[i][2],
                    quad.uv[i][0], quad.uv[i][1], quad.argb[i]);
        }
        List<CamVert> clipped = clipNear(verts);
        if (clipped.size() < 3) {
            return;
        }
        ScreenVert[] screen = project(clipped);
        for (int i = 1; i + 1 < screen.length; i++) {
            drawTriangle(screen[0], screen[i], screen[i + 1], material, shade);
        }
    }

    // ==================== 光栅化核心 ====================

    /** 相机空间顶点（{@code z} 为正 = 在相机前方）。 */
    private record CamVert(double x, double y, double z, double u, double v, int argb) {
    }

    /** 屏幕空间顶点。 */
    private record ScreenVert(double x, double y, double z, double u, double v, int argb) {
    }

    private CamVert camVert(double wx, double wy, double wz, double u, double v, int argb) {
        double vx = wx - eye[0];
        double vy = wy - eye[1];
        double vz = wz - eye[2];
        double cx = vx * right[0] + vy * right[1] + vz * right[2];
        double cy = vx * up[0] + vy * up[1] + vz * up[2];
        double cz = vx * forward[0] + vy * forward[1] + vz * forward[2];
        return new CamVert(cx, cy, cz, u, v, argb);
    }

    /** 近裁剪面裁剪（Sutherland–Hodgman）：只保留 {@code z >= NEAR} 的部分。 */
    private static List<CamVert> clipNear(CamVert[] poly) {
        List<CamVert> out = new ArrayList<>(6);
        int count = poly.length;
        for (int i = 0; i < count; i++) {
            CamVert current = poly[i];
            CamVert next = poly[(i + 1) % count];
            boolean currentInside = current.z >= NEAR;
            boolean nextInside = next.z >= NEAR;
            if (currentInside) {
                out.add(current);
            }
            if (currentInside != nextInside) {
                double t = (NEAR - current.z) / (next.z - current.z);
                out.add(new CamVert(
                        current.x + (next.x - current.x) * t,
                        current.y + (next.y - current.y) * t,
                        NEAR,
                        current.u + (next.u - current.u) * t,
                        current.v + (next.v - current.v) * t,
                        current.argb));
            }
        }
        return out;
    }

    private ScreenVert[] project(List<CamVert> verts) {
        ScreenVert[] out = new ScreenVert[verts.size()];
        for (int i = 0; i < out.length; i++) {
            CamVert vert = verts.get(i);
            double sx = (vert.x / (vert.z * tanH) * 0.5 + 0.5) * width;
            double sy = (0.5 - vert.y / (vert.z * tanV) * 0.5) * height;
            out[i] = new ScreenVert(sx, sy, vert.z, vert.u, vert.v, vert.argb);
        }
        return out;
    }

    /** 相机空间的三角形 → 近裁剪 → 投影 → 光栅化（阴影这种直接给相机空间顶点的场合用）。 */
    private void drawCameraTriangle(CamVert a, CamVert b, CamVert c, Material material, double shade) {
        List<CamVert> clipped = clipNear(new CamVert[]{a, b, c});
        if (clipped.size() < 3) {
            return;
        }
        ScreenVert[] screen = project(clipped);
        for (int i = 1; i + 1 < screen.length; i++) {
            drawTriangle(screen[0], screen[i], screen[i + 1], material, shade);
        }
    }

    /**
     * 画一个三角形：屏幕空间重心坐标 + 透视校正插值（1/z 线性插值）+ z-buffer。
     *
     * @param shade 逐面明暗系数（模型/剪影是平坦着色，一个四边形一个值）
     */
    private void drawTriangle(ScreenVert a, ScreenVert b, ScreenVert c, Material material, double shade) {
        double area = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
        if (Math.abs(area) < 1.0e-12) {
            return;
        }
        double invArea = 1.0 / area;

        int minX = (int) Math.floor(Math.min(a.x, Math.min(b.x, c.x)));
        int maxX = (int) Math.ceil(Math.max(a.x, Math.max(b.x, c.x)));
        int minY = (int) Math.floor(Math.min(a.y, Math.min(b.y, c.y)));
        int maxY = (int) Math.ceil(Math.max(a.y, Math.max(b.y, c.y)));
        minX = Math.max(minX, 0);
        maxX = Math.min(maxX, width - 1);
        minY = Math.max(minY, 0);
        maxY = Math.min(maxY, height - 1);
        if (minX > maxX || minY > maxY) {
            return;
        }

        double invZa = 1.0 / a.z;
        double invZb = 1.0 / b.z;
        double invZc = 1.0 / c.z;

        double aA = (a.argb >>> 24) & 0xFF;
        double aR = (a.argb >> 16) & 0xFF;
        double aG = (a.argb >> 8) & 0xFF;
        double aB = a.argb & 0xFF;
        double bA = (b.argb >>> 24) & 0xFF;
        double bR = (b.argb >> 16) & 0xFF;
        double bG = (b.argb >> 8) & 0xFF;
        double bB = b.argb & 0xFF;
        double cA = (c.argb >>> 24) & 0xFF;
        double cR = (c.argb >> 16) & 0xFF;
        double cG = (c.argb >> 8) & 0xFF;
        double cB = c.argb & 0xFF;

        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double wa = ((b.x - px) * (c.y - py) - (b.y - py) * (c.x - px)) * invArea;
                if (wa < -1.0e-9) {
                    continue;
                }
                double wb = ((c.x - px) * (a.y - py) - (c.y - py) * (a.x - px)) * invArea;
                if (wb < -1.0e-9) {
                    continue;
                }
                double wc = 1.0 - wa - wb;
                if (wc < -1.0e-9) {
                    continue;
                }
                double invZ = wa * invZa + wb * invZb + wc * invZc;
                if (!(invZ > 0.0)) {
                    continue;
                }
                double z = 1.0 / invZ;
                int index = y * width + x;
                if (z >= depth[index] - DEPTH_BIAS * Math.max(1.0, z)) {
                    continue;
                }

                double alpha;
                double red;
                double green;
                double blue;
                if (material.textured()) {
                    double u = (wa * a.u * invZa + wb * b.u * invZb + wc * c.u * invZc) * z;
                    double v = (wa * a.v * invZa + wb * b.v * invZb + wc * c.v * invZc) * z;
                    int texel = texelAt((int) Math.floor(u * texW), (int) Math.floor(v * texH));
                    if (((texel >>> 24) & 0xFF) / 255.0 < ALPHA_CUTOUT) {
                        continue; // cutout：透明像素直接丢弃（翅膀的镂空、羽毛边缘都靠这个）
                    }
                    double tintR = (wa * aR * invZa + wb * bR * invZb + wc * cR * invZc) * z / 255.0;
                    double tintG = (wa * aG * invZa + wb * bG * invZb + wc * cG * invZc) * z / 255.0;
                    double tintB = (wa * aB * invZa + wb * bB * invZb + wc * cB * invZc) * z / 255.0;
                    red = ((texel >> 16) & 0xFF) * tintR * shade;
                    green = ((texel >> 8) & 0xFF) * tintG * shade;
                    blue = (texel & 0xFF) * tintB * shade;
                    alpha = 1.0;
                } else {
                    alpha = (wa * aA * invZa + wb * bA * invZb + wc * cA * invZc) * z / 255.0;
                    red = (wa * aR * invZa + wb * bR * invZb + wc * cR * invZc) * z * shade;
                    green = (wa * aG * invZa + wb * bG * invZb + wc * cG * invZc) * z * shade;
                    blue = (wa * aB * invZa + wb * bB * invZb + wc * cB * invZc) * z * shade;
                }
                if (alpha <= 0.0) {
                    continue;
                }
                if (trackCoverage && !covered[index]) {
                    covered[index] = true;
                    coverageCount++;
                }
                if (material.blend()) {
                    int dst = pixels[index];
                    double dr = (dst >> 16) & 0xFF;
                    double dg = (dst >> 8) & 0xFF;
                    double db = dst & 0xFF;
                    red = red * alpha + dr * (1.0 - alpha);
                    green = green * alpha + dg * (1.0 - alpha);
                    blue = blue * alpha + db * (1.0 - alpha);
                }
                pixels[index] = 0xFF000000
                        | (clamp255(red) << 16)
                        | (clamp255(green) << 8)
                        | clamp255(blue);
                if (material.depthWrite()) {
                    depth[index] = (float) z;
                }
            }
        }
    }

    // ==================== 光照 ====================

    /** 四边形明暗：用面法线做 N·L；法线背对相机时翻过来（单层面片要双面可见）。 */
    private double shadeForQuad(QuadRecorder.Quad quad) {
        double[] normal = quad.averageNormal();
        return shadeForNormal(normal);
    }

    private double shadeForNormal(double[] normal) {
        double nx = normal[0];
        double ny = normal[1];
        double nz = normal[2];
        // forward 指向场景内部；法线朝相机 ⇒ dot(n, forward) < 0。背对就翻转，双面都能看到明暗
        if (nx * forward[0] + ny * forward[1] + nz * forward[2] > 0.0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }
        double key = Math.max(0.0, nx * lightKey[0] + ny * lightKey[1] + nz * lightKey[2]);
        double fill = Math.max(0.0, nx * lightFill[0] + ny * lightFill[1] + nz * lightFill[2]);
        // 边缘光：正对相机时最弱，接近侧向时最强，给翅膀/薄片一点轮廓
        double facing = Math.abs(nx * forward[0] + ny * forward[1] + nz * forward[2]);
        double rim = Math.pow(1.0 - facing, 3.0) * RIM_LIGHT;
        double shade = AMBIENT + KEY_LIGHT * key + FILL_LIGHT * fill + rim;
        return Math.min(1.0, shade);
    }

    // ==================== 输出 ====================

    /** 模型在画面上覆盖的像素比例（0..1），用于自检"是不是什么都没画出来"。 */
    double coverageRatio() {
        return (double) coverageCount / (double) (width * height);
    }

    /**
     * 降采样成图片（超采样抗锯齿）。
     *
     * @param supersample 超采样倍数（1 = 不降采样）
     */
    BufferedImage toImage(int supersample) {
        int ss = Math.max(1, supersample);
        int outW = width / ss;
        int outH = height / ss;
        BufferedImage image = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
        if (ss == 1) {
            image.setRGB(0, 0, outW, outH, pixels, 0, width);
            return image;
        }
        int[] out = new int[outW * outH];
        double samples = ss * ss;
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                double red = 0.0;
                double green = 0.0;
                double blue = 0.0;
                double alpha = 0.0;
                for (int sy = 0; sy < ss; sy++) {
                    int row = (y * ss + sy) * width + x * ss;
                    for (int sx = 0; sx < ss; sx++) {
                        int argb = pixels[row + sx];
                        double a = ((argb >>> 24) & 0xFF) / 255.0;
                        red += ((argb >> 16) & 0xFF) * a;
                        green += ((argb >> 8) & 0xFF) * a;
                        blue += (argb & 0xFF) * a;
                        alpha += a;
                    }
                }
                int outA = clamp255(alpha / samples * 255.0);
                if (alpha <= 0.0) {
                    out[y * outW + x] = 0;
                } else {
                    out[y * outW + x] = (outA << 24)
                            | (clamp255(red / alpha) << 16)
                            | (clamp255(green / alpha) << 8)
                            | clamp255(blue / alpha);
                }
            }
        }
        image.setRGB(0, 0, outW, outH, out, 0, outW);
        return image;
    }

    // ==================== 小工具 ====================

    private int texelAt(int x, int y) {
        int tx = x < 0 ? 0 : Math.min(x, texW - 1);
        int ty = y < 0 ? 0 : Math.min(y, texH - 1);
        return texels[ty * texW + tx];
    }

    private static int clamp255(double value) {
        int rounded = (int) Math.round(value);
        return rounded < 0 ? 0 : Math.min(rounded, 255);
    }

    private static int lerpColor(int from, int to, double t) {
        int a = (int) Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (clamp255(a) << 24) | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
    }

    /** 整体加亮 / 压暗（delta 是 0..1 的比例，正数变亮）。 */
    private static int shiftColor(int argb, double delta) {
        double amount = delta * 255.0;
        int r = clamp255(((argb >> 16) & 0xFF) + amount);
        int g = clamp255(((argb >> 8) & 0xFF) + amount);
        int b = clamp255((argb & 0xFF) + amount);
        return (clamp255((argb >>> 24) & 0xFF) << 24) | (r << 16) | (g << 8) | b;
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalize(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (!(length > 1.0e-9)) {
            return new double[]{0.0, 0.0, 1.0};
        }
        return new double[]{v[0] / length, v[1] / length, v[2] / length};
    }
}
