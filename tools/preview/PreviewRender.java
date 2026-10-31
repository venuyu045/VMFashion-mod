import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.GeoModelRenderer;
import com.venus.vmfashion.render.anim.AnimationTrack;
import com.venus.vmfashion.render.anim.BedrockAnimation;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 无头时装预览渲染器：把一件 Bedrock 时装（model.geo.json + texture.png + anim.json）
 * 渲染成 PNG 效果图，全程不碰显卡、不碰 Minecraft 客户端。
 *
 * <p>链路（每一环都是生产代码，预览与游戏内看到的东西同源）：
 * <pre>
 * .geo.json ──GeckoLibGsonLoader#bakeGeckoLibModelFile──▶ BakedGeoModel
 * BakedGeoModel ──GeoModelRenderer#render──▶ VertexConsumer（{@link QuadRecorder} 记下全部顶点）
 * 顶点 + texture.png ──{@link SoftRasterizer}──▶ BufferedImage ──ImageIO──▶ PNG
 * </pre>
 *
 * <p>坐标系是 E 空间：脚底原点、Y 向上、+Z 背后、单位 1/16 格（原版玩家身高 32 单位）。
 * 画面里那套灰色半透明方块就是原版玩家比例的参考剪影，用来判断"这件时装到底多大、戴在哪"。
 *
 * <p>用法：
 * <pre>
 * bash tools/preview/render.sh &lt;包目录&gt; &lt;cosmeticId&gt; &lt;输出目录&gt;
 * # 等价于：
 * java -cp ... PreviewRender &lt;包目录&gt; &lt;cosmeticId&gt; &lt;输出目录&gt; [--ss=2] [--size=1280x960]
 * </pre>
 */
public final class PreviewRender {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 960;
    private static final int DEFAULT_THUMB = 512;
    private static final int DEFAULT_SUPERSAMPLE = 2;
    /** 视角图与缩略图都画 4 帧动画（t = 0、1/4、1/2、3/4 周期）。 */
    private static final int ANIMATION_FRAMES = 4;
    /** 取景时额外采样的动画时刻数（保证动画摆到最开时也不会出画）。 */
    private static final int FRAMING_SAMPLES = 8;

    private static final int BACKGROUND_TOP = 0xFF262E3B;
    private static final int BACKGROUND_BOTTOM = 0xFF0C1016;
    private static final int THUMB_BACKGROUND_TOP = 0xFF242C39;
    private static final int THUMB_BACKGROUND_BOTTOM = 0xFF090D13;
    private static final int SILHOUETTE_RGB = 0xC9CED8;
    /** 参考剪影 alpha（0–255）：半透明到能看见后面的模型，又能看清人形。 */
    private static final int SILHOUETTE_ALPHA = 54;

    /** 原版玩家：身高 32 单位，宽 8（含手臂 16），厚 4（头 8）。 */
    private static final double PLAYER_HEIGHT = 32.0;
    /**
     * 比例参照用的玩家剪影包围盒（E 空间、1/16 格单位）：
     * 脚底 y=0、头顶 y=32（2 格）、左右 ±8（±0.5 格，含手臂）、前后 ±4。
     */
    private static final Bounds PLAYER_BOX = Bounds.ofBox(-8.0, 0.0, -4.0, 8.0, PLAYER_HEIGHT, 4.0);
    /**
     * 小于这个尺寸的时装（皇冠 / 耳饰 / 小挂件）不把整个人框进来，而是把玩家剪影裁到模型附近。
     *
     * <p>阈值取玩家身高的 0.45（≈14 单位）：翅膀 / 披风 / 背板这类"贴着人"的大件仍然整人入画，
     * 小配件则只留"头顶 / 肩膀"这一圈做比例参照 —— 否则皇冠在画面里只有 1% 大小，什么也看不清。
     */
    private static final double CONTEXT_CLIP_EXTENT = PLAYER_HEIGHT * 0.45;

    /** 视角预设：{@code dirToEye} 是"从取景盒中心指向相机"的方向。 */
    private record ViewPreset(String name, String label, double[] dirToEye) {
    }

    private static final List<ViewPreset> VIEWS = List.of(
            // 主视角：正后方。+Z 是背后，所以相机在 +Z 侧、略微抬高
            new ViewPreset("back", "正后方", new double[]{0.0, 0.21, 0.98}),
            // 前侧 3/4：前方是 -Z，再偏到 -X 一侧
            new ViewPreset("front34", "前侧 3/4", new double[]{-0.62, 0.26, -0.74}),
            // 侧面：+X（E 空间里是玩家右侧）
            new ViewPreset("side", "侧面", new double[]{0.99, 0.15, 0.0}),
            // 俯视：正上方，略微从后方看，避免完全垂直导致朝向读不出来
            new ViewPreset("top", "俯视", new double[]{0.0, 0.97, 0.26}));

    private static final ViewPreset THUMB_VIEW = new ViewPreset("thumb", "缩略图",
            new double[]{0.30, 0.24, 0.92});

    private PreviewRender() {
    }

    public static void main(String[] args) throws Exception {
        // 无图形环境：显式 headless，避免 AWT 去连 X server（连不上会抛 HeadlessException）
        System.setProperty("java.awt.headless", "true");

        if (args.length < 3) {
            System.err.println("用法: bash tools/preview/render.sh <包目录> <cosmeticId> <输出目录>");
            System.err.println("      可选: --ss=2 --size=1280x960 --thumb=512 --fov=32 --no-label --no-silhouette");
            System.exit(2);
        }

        Options options = Options.parse(args);
        Path packDir = Path.of(args[0]);
        String cosmeticId = args[1];
        Path outDir = Path.of(args[2]);
        Files.createDirectories(outDir);

        List<String> report = new ArrayList<>();
        try {
            render(packDir, cosmeticId, outDir, options, report);
        } finally {
            // 报告无论成功失败都落盘：出问题时这份文本比一张黑图有用得多
            Files.write(outDir.resolve("report.txt"), report);
            for (String line : report) {
                System.out.println(line);
            }
        }
    }

    private static void render(Path packDir, String cosmeticId, Path outDir, Options options,
                               List<String> report) throws Exception {
        CosmeticSource source = CosmeticSource.load(packDir, cosmeticId);
        BakedGeoModel model = source.model;

        report.add("=== 时装预览 ===");
        report.add("包目录   : " + source.packDir);
        report.add("时装     : " + source.cosmeticId
                + (source.displayName.equals(source.cosmeticId) ? "" : "（" + source.displayName + "）"));
        if (!source.category.isBlank() || !source.attachedBone.isBlank()) {
            report.add("分类/挂点: " + (source.category.isBlank() ? "-" : source.category)
                    + " / " + (source.attachedBone.isBlank() ? "-" : source.attachedBone));
        }
        report.add("模型     : " + relativize(source.packDir, source.modelFile));
        report.add("贴图     : " + relativize(source.packDir, source.textureFile)
                + "（" + source.textureWidth + "×" + source.textureHeight + "）");
        report.add("动画     : " + (source.animationFile == null
                ? "无" : relativize(source.packDir, source.animationFile)));
        report.add("文件指纹 : 模型 " + fileFingerprint(source.modelFile)
                + "，贴图 " + fileFingerprint(source.textureFile));
        if (source.emissiveFile != null) {
            report.add("发光层   : " + relativize(source.packDir, source.emissiveFile)
                    + "（预览是纯 CPU 光栅化，不渲染 emissive 发光，游戏内该层会自发光）");
        }
        report.add(String.format(Locale.ROOT, "烘焙结果 : 根骨骼 %d，四边形 %d，贴图声明 %d×%d",
                model.topLevelBones().length, countQuads(model),
                model.properties().textureWidth(), model.properties().textureHeight()));
        if (Math.abs(source.declaredScale - 1.0F) > 1.0e-4F) {
            // 生产端会绕 attachedBone 的 pivot 缩放；预览按 1:1 原始尺寸渲染，这里只提示
            report.add(String.format(Locale.ROOT,
                    "注意     : pack.json 声明 scale=%.3f，游戏内会绕挂点缩放；预览按原始尺寸渲染", source.declaredScale));
        }
        for (String warning : source.warnings) {
            report.add("警告     : " + warning);
        }

        // ---- 1. 静态几何 ----
        List<QuadRecorder.Quad> staticQuads = capture(model);
        if (staticQuads.isEmpty()) {
            throw new IllegalStateException("GeoModelRenderer 没有输出任何四边形：模型可能烘焙失败或被判定为坏模型");
        }
        Bounds modelBox = Bounds.of(staticQuads);
        Bounds animBox = new Bounds();
        Bounds playerBox = PLAYER_BOX;

        // ---- 2. 动画准备（先测一遍动画全程的包围盒，取景才不会把动作切掉） ----
        AnimationPose pose = source.animation == null ? null : AnimationPose.create(model, source.animation);
        /** 取景要覆盖动画全过程，所以 8 个采样时刻的顶点都留着（顺便给"点集取景"用）。 */
        List<List<QuadRecorder.Quad>> framingSamples = new ArrayList<>();
        List<List<QuadRecorder.Quad>> animationQuads = new ArrayList<>();
        List<Double> animationTimes = new ArrayList<>();
        double animationLength = source.animation == null ? 0.0 : source.animation.length();

        if (pose != null) {
            report.add("动画选择 : " + source.animation.name()
                    + String.format(Locale.ROOT, "（时长 %.2fs，%s）",
                    animationLength, source.animation.loop() ? "循环" : "单次"));
            report.add("动画骨骼 : " + String.join(", ", pose.boneNames()));
            for (int i = 0; i < FRAMING_SAMPLES; i++) {
                double time = animationLength * i / FRAMING_SAMPLES;
                pose.apply(time);
                List<QuadRecorder.Quad> quads = capture(model);
                animBox.union(Bounds.of(quads));
                framingSamples.add(quads);
                if (i < ANIMATION_FRAMES) {
                    animationQuads.add(quads);
                    animationTimes.add(time);
                }
            }
            pose.clear();
            // 帧间最大顶点位移：0 = 动画没生效（骨骼名对不上 / 通道没解析 / 快照没挂上）
            double maxShift = 0.0;
            for (int i = 0; i < animationQuads.size(); i++) {
                List<QuadRecorder.Quad> previous = animationQuads.get(i);
                List<QuadRecorder.Quad> next = animationQuads.get((i + 1) % animationQuads.size());
                maxShift = Math.max(maxShift, SelfCheck.maxVertexShift(previous, next));
            }
            report.add(String.format(Locale.ROOT,
                    "动画幅度 : 相邻帧顶点最大位移 %.2f 单位%s", maxShift,
                    maxShift < 0.05 ? "（几乎不动！动画可能没生效）" : ""));
        } else if (source.animationFile != null) {
            report.add("动画     : 文件存在但没有可用动画，动画帧按静态姿态输出");
        }

        // ---- 3. 自检 ----
        report.add("包围盒(E): " + modelBox);
        if (!animBox.isEmpty()) {
            report.add("动画包围盒: " + animBox);
        }
        double symmetry = SelfCheck.symmetryRatio(staticQuads);
        report.add(String.format(Locale.ROOT, "左右对称 : %.1f%% 的四边形有 X 镜像对应%s",
                symmetry * 100.0, symmetry > 0.999 ? "（完全对称）" : "（可能有非对称部件，或模型本身左右不一致）"));
        report.add("贴图自检 : " + SelfCheck.textureReport(source, staticQuads));

        // ---- 4. 取景 ----
        Bounds contentBox = Bounds.of(staticQuads);
        contentBox.union(animBox);
        Bounds frameBox = chooseFrameBox(contentBox);
        report.add("取景盒(E): " + frameBox);
        boolean groundVisible = frameBox.minY <= 0.75;

        // 必须完整入镜的点：静态顶点 + 动画各帧顶点 + 玩家剪影的 8 个角
        List<double[]> fitPoints = new ArrayList<>();
        addQuadPoints(fitPoints, staticQuads);
        for (List<QuadRecorder.Quad> sample : framingSamples) {
            addQuadPoints(fitPoints, sample);
        }
        // 剪影的角也要入镜，但只算"取景参照"那一块（小配件时玩家剪影被裁到模型附近，
        // 否则皇冠会因为要装下玩家的脚而缩成一个小点）
        addBoxCorners(fitPoints, contextBox(contentBox));
        double[] frameCenter = {frameBox.centerX(), frameBox.centerY(), frameBox.centerZ()};

        // ---- 5. 出图 ----
        report.add("");
        report.add("=== 输出 ===");
        List<ViewPreset> views = options.views();
        double backCoverage = 1.0;
        for (ViewPreset view : views) {
            SoftRasterizer raster = newRaster(options.width(), options.height(), options,
                    frameCenter, fitPoints, view.dirToEye, VIEW_MARGIN, source);
            raster.fillBackground(BACKGROUND_TOP, BACKGROUND_BOTTOM);
            drawGroundShadowIfNeeded(raster, groundVisible, contentBox);
            raster.drawModel(staticQuads);
            if (options.silhouette) {
                drawPlayerSilhouette(raster);
            }
            double coverage = raster.coverageRatio();
            if ("back".equals(view.name())) {
                backCoverage = coverage;
            }
            BufferedImage image = raster.toImage(options.supersample);
            // 先量"左右镜像差异"再画标注（标注在左下角，会污染镜像比较）
            double[] mirror = SelfCheck.imageMirrorDifference(image);
            if (options.labels) {
                drawCaption(image, source.cosmeticId + " · " + view.name() + "（" + view.label() + "）",
                        "E 空间 · 单位 1/16 格 · 灰色剪影 = 原版玩家（身高 32 单位）");
            }
            Path file = outDir.resolve(view.name() + ".png");
            ImageIO.write(image, "png", file.toFile());
            report.add(String.format(Locale.ROOT, "%-16s %4d×%-4d 模型占画面 %.1f%%  左右镜像差异 平均 %.1f/255、>24 的像素 %.2f%%",
                    relativize(outDir, file), image.getWidth(), image.getHeight(), coverage * 100.0,
                    mirror[0], mirror[1] * 100.0));
        }

        // 动画帧：与主视角同一台相机，只改时间，方便逐帧对比
        for (int i = 0; i < ANIMATION_FRAMES; i++) {
            SoftRasterizer raster = newRaster(options.width(), options.height(), options,
                    frameCenter, fitPoints, VIEWS.get(0).dirToEye(), VIEW_MARGIN, source);
            raster.fillBackground(BACKGROUND_TOP, BACKGROUND_BOTTOM);
            drawGroundShadowIfNeeded(raster, groundVisible, contentBox);
            List<QuadRecorder.Quad> quads;
            double time;
            if (pose != null && i < animationQuads.size()) {
                quads = animationQuads.get(i);
                time = animationTimes.get(i);
            } else {
                quads = staticQuads;
                time = 0.0;
            }
            raster.drawModel(quads);
            if (options.silhouette) {
                drawPlayerSilhouette(raster);
            }
            BufferedImage image = raster.toImage(options.supersample);
            if (options.labels) {
                drawCaption(image, source.cosmeticId + " · anim-" + i,
                        pose == null
                                ? "无动画（静态姿态）"
                                : String.format(Locale.ROOT, "t = %.2fs / %.2fs · back 视角",
                                time, animationLength));
            }
            Path file = outDir.resolve("anim-" + i + ".png");
            ImageIO.write(image, "png", file.toFile());
            report.add(String.format(Locale.ROOT, "%-16s %4d×%-4d t = %.2fs",
                    relativize(outDir, file), image.getWidth(), image.getHeight(), time));
        }

        // 缩略图：1:1、"人 + 时装"完整入画、模型水平居中、背景深色渐变
        Bounds thumbFrame = thumbnailFrame(contentBox);
        double[] thumbCenter = {thumbFrame.centerX(), thumbFrame.centerY(), thumbFrame.centerZ()};
        SoftRasterizer thumb = newRaster(options.thumbWidth(), options.thumbWidth(), options,
                thumbCenter, fitPoints, THUMB_VIEW.dirToEye, THUMB_MARGIN, source);
        thumb.fillBackground(THUMB_BACKGROUND_TOP, THUMB_BACKGROUND_BOTTOM);
        thumb.drawModel(staticQuads);
        if (options.silhouette) {
            drawPlayerSilhouette(thumb);
        }
        double thumbCoverage = thumb.coverageRatio();
        BufferedImage thumbImage = thumb.toImage(options.supersample);
        Path thumbFile = outDir.resolve("thumb.png");
        ImageIO.write(thumbImage, "png", thumbFile.toFile());
        report.add(String.format(Locale.ROOT, "%-16s %4d×%-4d 模型占画面 %.1f%%",
                relativize(outDir, thumbFile), thumbImage.getWidth(), thumbImage.getHeight(),
                thumbCoverage * 100.0));

        report.add("");
        if (backCoverage < 0.005) {
            report.add("结论     : 视角图里模型占比不足 0.5%，可能是取景失败或模型太小，请检查上面的包围盒");
        } else {
            report.add("结论     : 渲染完成（" + (views.size() + ANIMATION_FRAMES + 1) + " 张 PNG）");
        }
    }

    // ==================== 渲染辅助 ====================

    /**
     * GeckoLib 烘焙时把 Bedrock 的 1/16 格换成了"格"（1 格 = 16 单位，背板 16 单位宽 → 1.0 格）。
     * 预览统一按 Bedrock 的 1/16 格单位出图（玩家身高 32 单位），所以抓完顶点乘回 16。
     */
    private static final double BLOCKS_TO_UNITS = 16.0;

    /** 用生产渲染器把模型的全部顶点抓下来（E 空间、单位 1/16 格）。 */
    private static List<QuadRecorder.Quad> capture(BakedGeoModel model) {
        QuadRecorder recorder = new QuadRecorder();
        // light 给满亮（0xF000F0），overlay 用 0（无受击闪红），color 白（不做 tint）
        GeoModelRenderer.render(model, new PoseStack().last(), recorder, 0xF000F0, 0, 0xFFFFFFFF);
        recorder.finish();
        for (QuadRecorder.Quad quad : recorder.quads) {
            for (int i = 0; i < 4; i++) {
                quad.pos[i][0] = (float) (quad.pos[i][0] * BLOCKS_TO_UNITS);
                quad.pos[i][1] = (float) (quad.pos[i][1] * BLOCKS_TO_UNITS);
                quad.pos[i][2] = (float) (quad.pos[i][2] * BLOCKS_TO_UNITS);
            }
        }
        return new ArrayList<>(recorder.quads);
    }

    private static int countQuads(BakedGeoModel model) {
        int total = 0;
        for (GeoBone bone : model.topLevelBones()) {
            total += countQuads(bone);
        }
        return total;
    }

    private static int countQuads(GeoBone bone) {
        int total = 0;
        if (bone instanceof com.geckolib.cache.model.cuboid.CuboidGeoBone cuboid && cuboid.cubes != null) {
            for (var cube : cuboid.cubes) {
                if (cube != null && cube.quads() != null) {
                    total += cube.quads().length;
                }
            }
        }
        for (GeoBone child : bone.children()) {
            total += countQuads(child);
        }
        return total;
    }

    /** 建一台相机（内部按超采样倍数放大分辨率，最后再盒式降采样）。 */
    private static SoftRasterizer newRaster(int width, int height, Options options, double[] center,
                                            List<double[]> fitPoints, double[] dirToEye, double margin,
                                            CosmeticSource source) {
        int ss = options.supersample();
        return SoftRasterizer.create(width * ss, height * ss, center, fitPoints, dirToEye, margin,
                options.fovDegrees(), source.texturePixels, source.textureWidth, source.textureHeight);
    }

    /** 视角图留边：1.20 ≈ 四周各留 8%（构图偏满但不顶边）。 */
    private static final double VIEW_MARGIN = 1.20;
    /** 缩略图留边稍紧一点（1:1 画幅本来就容易显空）。 */
    private static final double THUMB_MARGIN = 1.06;

    /** 把一组四边形的 4×n 个顶点加进取景点集。 */
    private static void addQuadPoints(List<double[]> points, List<QuadRecorder.Quad> quads) {
        for (QuadRecorder.Quad quad : quads) {
            for (int i = 0; i < 4; i++) {
                points.add(new double[]{quad.pos[i][0], quad.pos[i][1], quad.pos[i][2]});
            }
        }
    }

    /** 把一个包围盒的 8 个角加进取景点集（参考剪影是方块，角就是真实几何）。 */
    private static void addBoxCorners(List<double[]> points, Bounds box) {
        for (int i = 0; i < 8; i++) {
            points.add(new double[]{
                    (i & 1) == 0 ? box.minX : box.maxX,
                    (i & 2) == 0 ? box.minY : box.maxY,
                    (i & 4) == 0 ? box.minZ : box.maxZ});
        }
    }

    /**
     * 取景盒 = 模型包围盒 ∪ 原版玩家剪影，四周留 10% 边距。
     *
     * <p>这样"翅膀 + 人"一定完整入画，比例参照才有意义；只有极小配件
     * （最大边 &lt; 玩家身高 0.45 的小配件，比如皇冠）才把玩家剪影裁到模型附近，
     * 否则整张图里模型只有几个像素。
     */
    private static Bounds chooseFrameBox(Bounds modelBox) {
        Bounds frame = new Bounds();
        frame.union(modelBox);
        frame.union(contextBox(modelBox));
        return frame;
    }

    /**
     * 取景时"比例参照"要覆盖的玩家范围：通常就是整个玩家；极小配件则裁到模型附近。
     */
    private static Bounds contextBox(Bounds modelBox) {
        double extent = modelBox.maxSize();
        if (extent >= CONTEXT_CLIP_EXTENT) {
            return PLAYER_BOX;
        }
        double radius = Math.max(extent * 1.6, 8.0);
        double cx = modelBox.centerX();
        double cy = modelBox.centerY();
        double cz = modelBox.centerZ();
        Bounds near = Bounds.ofBox(cx - radius, cy - radius, cz - radius,
                cx + radius, cy + radius, cz + radius);
        return intersect(PLAYER_BOX, near);
    }

    private static Bounds intersect(Bounds a, Bounds b) {
        Bounds out = new Bounds();
        out.minX = Math.max(a.minX, b.minX);
        out.minY = Math.max(a.minY, b.minY);
        out.minZ = Math.max(a.minZ, b.minZ);
        out.maxX = Math.min(a.maxX, b.maxX);
        out.maxY = Math.min(a.maxY, b.maxY);
        out.maxZ = Math.min(a.maxZ, b.maxZ);
        return out.isEmpty() ? a : out;
    }

    /**
     * 缩略图取景：1:1 正方形，内容同样是"模型 ∪ 玩家剪影"，模型水平居中。
     *
     * <p>竖直方向按并集居中（保证人和模型都完整），水平方向按模型居中（翅膀左右对称时就是正中间）。
     */
    private static Bounds thumbnailFrame(Bounds modelBox) {
        Bounds content = new Bounds();
        content.union(modelBox);
        content.union(contextBox(modelBox));
        // 构图中心：水平取模型中心（翅膀对称时就是正中间），竖直取"人 + 模型"并集中心
        return Bounds.ofBox(modelBox.centerX(), content.centerY(), content.minZ,
                modelBox.centerX(), content.centerY(), content.maxZ);
    }

    /** 地面阴影：只有当取景盒包含 y≈0 时才画（否则阴影在画外，白费功夫）。 */
    private static void drawGroundShadowIfNeeded(SoftRasterizer raster, boolean groundVisible,
                                                 Bounds contentBox) {
        if (!groundVisible) {
            return;
        }
        Bounds footprint = new Bounds();
        footprint.union(contentBox);
        footprint.union(PLAYER_BOX);
        double cx = footprint.centerX();
        double cz = footprint.centerZ();
        double rx = Math.max(4.0, footprint.sizeX() * 0.5 * 1.02);
        double rz = Math.max(4.0, footprint.sizeZ() * 0.5 * 1.35);
        raster.drawGroundShadow(cx, cz, rx, rz, 0x000000, 96);
    }

    /**
     * 原版玩家比例参考剪影（灰色半透明方块）。
     *
     * <p>尺寸严格按原版玩家模型：头 8×8×8（y=24..32）、身体 8×12×4（y=12..24）、
     * 手臂 4×12×4（中心 x=±6）、腿 4×12×4。渲染顺序在模型之后 + 深度测试，
     * 所以模型在前的部分会正确遮住剪影，剪影在前的部分会给模型蒙一层灰（就是想要的"半透明人形"效果）。
     */
    private static void drawPlayerSilhouette(SoftRasterizer raster) {
        int argb = (SILHOUETTE_ALPHA << 24) | SILHOUETTE_RGB;
        raster.drawBox(-4.0, 24.0, -4.0, 4.0, 32.0, 4.0, argb);    // 头
        raster.drawBox(-4.0, 12.0, -2.0, 4.0, 24.0, 2.0, argb);    // 身体
        raster.drawBox(-8.0, 12.0, -2.0, -4.0, 24.0, 2.0, argb);   // 右臂
        raster.drawBox(4.0, 12.0, -2.0, 8.0, 24.0, 2.0, argb);     // 左臂
        raster.drawBox(-4.0, 0.0, -2.0, 0.0, 12.0, 2.0, argb);     // 右腿
        raster.drawBox(0.0, 0.0, -2.0, 4.0, 12.0, 2.0, argb);      // 左腿
    }

    // ==================== 文字标注 ====================

    /** 选一个能显示中文的字体（容器里一般有 Noto Sans CJK；都没有就退回逻辑字体）。 */
    private static String pickFontFamily() {
        try {
            List<String> available = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames());
            for (String candidate : List.of("Noto Sans CJK SC", "Noto Sans CJK JP",
                    "Source Han Sans SC", "WenQuanYi Zen Hei", "DejaVu Sans")) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // headless 环境拿不到字体列表就用逻辑字体
        }
        return Font.SANS_SERIF;
    }

    private static String fontFamily;

    /** 左下角画两行标注（视角名 + 坐标系/比例说明），带半透明底板。 */
    private static void drawCaption(BufferedImage image, String title, String subtitle) {
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = image.getWidth() / 1280.0;
            if (fontFamily == null) {
                fontFamily = pickFontFamily();
            }
            Font titleFont = new Font(fontFamily, Font.BOLD, Math.max(12, (int) Math.round(24 * scale)));
            Font subtitleFont = new Font(fontFamily, Font.PLAIN, Math.max(10, (int) Math.round(17 * scale)));
            FontMetrics titleMetrics = graphics.getFontMetrics(titleFont);
            FontMetrics subtitleMetrics = graphics.getFontMetrics(subtitleFont);

            int padding = Math.max(6, (int) Math.round(14 * scale));
            int margin = Math.max(8, (int) Math.round(26 * scale));
            int lineGap = Math.max(2, (int) Math.round(4 * scale));
            int boxWidth = Math.max(titleMetrics.stringWidth(title), subtitleMetrics.stringWidth(subtitle))
                    + padding * 2;
            int boxHeight = titleMetrics.getHeight() + subtitleMetrics.getHeight() + lineGap + padding * 2;
            int x = margin;
            int y = image.getHeight() - margin - boxHeight;

            graphics.setColor(new Color(8, 11, 16, 165));
            graphics.fillRoundRect(x, y, boxWidth, boxHeight, Math.max(6, (int) Math.round(12 * scale)),
                    Math.max(6, (int) Math.round(12 * scale)));
            graphics.setColor(new Color(214, 222, 235, 60));
            graphics.setStroke(new BasicStroke(Math.max(1.0F, (float) scale)));
            graphics.drawRoundRect(x, y, boxWidth, boxHeight, Math.max(6, (int) Math.round(12 * scale)),
                    Math.max(6, (int) Math.round(12 * scale)));

            graphics.setFont(titleFont);
            graphics.setColor(new Color(238, 243, 250));
            graphics.drawString(title, x + padding, y + padding + titleMetrics.getAscent());
            graphics.setFont(subtitleFont);
            graphics.setColor(new Color(154, 168, 188));
            graphics.drawString(subtitle, x + padding,
                    y + padding + titleMetrics.getHeight() + lineGap + subtitleMetrics.getAscent());
        } finally {
            graphics.dispose();
        }
    }

    // ==================== 动画姿态 ====================

    /**
     * 动画姿态：把某条 {@link BedrockAnimation} 在时刻 {@code t} 的采样值写进
     * {@link BoneSnapshot}，再挂到共享骨骼的 {@code frameSnapshot} 上。
     *
     * <p>为什么要走 {@code frameSnapshot}：{@code GeoModelRenderer} 的
     * {@code render(model, pose, consumer, light, overlay, color)} 不带 {@code AnimationFrame} 参数时，
     * {@code RenderUtil.prepMatrixForBone} 直接读 {@code bone.frameSnapshot} —— 这正是生产端
     * 挂/还原快照的同一条路径，所以预览里看到的形变和游戏里完全一致。
     *
     * <p>单位（与 {@code AnimationFrame#update} 一致，不要自己换算）：
     * rotation 是<b>弧度</b>（解析时已从度转好）、position 是 Bedrock 的 1/16 格
     * （{@code BoneSnapshot#translate} 内部自己除以 16）、scale 是倍率。
     */
    private static final class AnimationPose {

        private final BedrockAnimation animation;
        /**
         * 骨骼 → 快照 / 通道。必须用 {@link IdentityHashMap}：
         * {@code GeoBone#hashCode} 会把子骨骼（以及父骨骼）递归算进去，拿它当 HashMap 的 key
         * 会直接 StackOverflowError（生产端 {@code AnimationFrame} 也是用 IdentityHashMap）。
         */
        private final Map<GeoBone, BoneSnapshot> snapshots = new IdentityHashMap<>();
        private final Map<GeoBone, BedrockAnimation.BoneTracks> tracks = new IdentityHashMap<>();
        /** 保持动画里的声明顺序，报告和"画完还原"都按这个顺序走。 */
        private final List<GeoBone> bones = new ArrayList<>();
        private final float[] scratch = new float[3];

        private AnimationPose(BedrockAnimation animation) {
            this.animation = animation;
        }

        /** 按骨骼名（小写）把动画通道对到真实骨骼上；对不上的骨骼直接忽略。 */
        static AnimationPose create(BakedGeoModel model, BedrockAnimation animation) {
            AnimationPose pose = new AnimationPose(animation);
            Map<String, GeoBone> bonesByName = new LinkedHashMap<>();
            for (GeoBone bone : model.topLevelBones()) {
                indexBones(bone, bonesByName);
            }
            for (Map.Entry<String, BedrockAnimation.BoneTracks> entry : animation.bones().entrySet()) {
                GeoBone bone = bonesByName.get(entry.getKey().toLowerCase(Locale.ROOT));
                if (bone == null) {
                    continue;
                }
                pose.tracks.put(bone, entry.getValue());
                pose.snapshots.put(bone, BoneSnapshot.create(bone));
                pose.bones.add(bone);
            }
            return pose;
        }

        private static void indexBones(GeoBone bone, Map<String, GeoBone> out) {
            if (bone == null) {
                return;
            }
            if (bone.name() != null) {
                out.putIfAbsent(bone.name().toLowerCase(Locale.ROOT), bone);
            }
            for (GeoBone child : bone.children()) {
                indexBones(child, out);
            }
        }

        /** 动画里真正对得上模型的骨骼名（自检报告用）。 */
        List<String> boneNames() {
            List<String> names = new ArrayList<>();
            for (GeoBone bone : bones) {
                names.add(bone.name());
            }
            return names;
        }

        /** 采样时刻 {@code time} 并挂上快照（渲染前调用）。 */
        void apply(double time) {
            double t = AnimationTrack.wrap(time, animation.length(), animation.loop());
            for (GeoBone bone : bones) {
                BoneSnapshot snapshot = snapshots.get(bone);
                BedrockAnimation.BoneTracks boneTracks = tracks.get(bone);
                if (snapshot == null || boneTracks == null) {
                    continue;
                }
                if (boneTracks.rotation() != null) {
                    boneTracks.rotation().sample(t, scratch);
                    snapshot.setRotation(scratch[0], scratch[1], scratch[2]);
                } else {
                    snapshot.setRotation(0.0F, 0.0F, 0.0F);
                }
                if (boneTracks.position() != null) {
                    boneTracks.position().sample(t, scratch);
                    snapshot.setTranslation(scratch[0], scratch[1], scratch[2]);
                } else {
                    snapshot.setTranslation(0.0F, 0.0F, 0.0F);
                }
                if (boneTracks.scale() != null) {
                    boneTracks.scale().sample(t, scratch);
                    snapshot.setScale(scratch[0], scratch[1], scratch[2]);
                } else {
                    snapshot.setScale(1.0F, 1.0F, 1.0F);
                }
                bone.frameSnapshot = snapshot;
            }
        }

        /** 还原共享骨骼（渲染后必须调用，否则动画会污染后面所有渲染）。 */
        void clear() {
            for (GeoBone bone : bones) {
                bone.frameSnapshot = null;
            }
        }
    }

    // ==================== 参数 ====================

    /** 命令行参数（都可选）。 */
    private record Options(int width, int height, int thumbWidth, int supersample, boolean labels,
                           boolean silhouette, double fovDegrees, List<ViewPreset> views) {

        static Options defaults() {
            return new Options(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_THUMB, DEFAULT_SUPERSAMPLE,
                    true, true, SoftRasterizer.DEFAULT_FOV_Y_DEGREES, VIEWS);
        }

        static Options parse(String[] args) {
            Options options = defaults();
            for (int i = 3; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--no-label")) {
                    options = options.withLabels(false);
                } else if (arg.equals("--no-silhouette")) {
                    options = options.withSilhouette(false);
                } else if (arg.startsWith("--ss=")) {
                    options = options.withSupersample(Math.max(1, Math.min(4, Integer.parseInt(arg.substring(5)))));
                } else if (arg.startsWith("--size=")) {
                    String[] parts = arg.substring(7).toLowerCase(Locale.ROOT).split("x");
                    if (parts.length == 2) {
                        options = options.withSize(Math.max(64, Integer.parseInt(parts[0])),
                                Math.max(64, Integer.parseInt(parts[1])));
                    }
                } else if (arg.startsWith("--thumb=")) {
                    options = options.withThumb(Math.max(64, Integer.parseInt(arg.substring(8))));
                } else if (arg.startsWith("--fov=")) {
                    options = options.withFov(Double.parseDouble(arg.substring(6)));
                } else if (arg.startsWith("--views=")) {
                    options = options.withViews(parseViews(arg.substring(8)));
                } else {
                    System.err.println("忽略未知参数: " + arg);
                }
            }
            return options;
        }

        /** {@code --views=back,front34,side}：按名字挑视角，未知名字直接忽略。 */
        private static List<ViewPreset> parseViews(String raw) {
            List<ViewPreset> chosen = new ArrayList<>();
            for (String name : raw.toLowerCase(Locale.ROOT).split(",")) {
                String trimmed = name.trim();
                for (ViewPreset preset : VIEWS) {
                    if (preset.name().equals(trimmed) && !chosen.contains(preset)) {
                        chosen.add(preset);
                    }
                }
            }
            if (chosen.isEmpty()) {
                System.err.println("--views 里没有认识的视角，退回默认四个: " + raw);
                return VIEWS;
            }
            return chosen;
        }

        Options withLabels(boolean value) {
            return new Options(width, height, thumbWidth, supersample, value, silhouette, fovDegrees, views);
        }

        Options withSilhouette(boolean value) {
            return new Options(width, height, thumbWidth, supersample, labels, value, fovDegrees, views);
        }

        Options withSupersample(int value) {
            return new Options(width, height, thumbWidth, value, labels, silhouette, fovDegrees, views);
        }

        Options withSize(int width, int height) {
            return new Options(width, height, thumbWidth, supersample, labels, silhouette, fovDegrees, views);
        }

        Options withThumb(int value) {
            return new Options(width, height, value, supersample, labels, silhouette, fovDegrees, views);
        }

        Options withFov(double value) {
            return new Options(width, height, thumbWidth, supersample, labels, silhouette, value, views);
        }

        Options withViews(List<ViewPreset> value) {
            return new Options(width, height, thumbWidth, supersample, labels, silhouette, fovDegrees, value);
        }
    }

    /** 文件指纹（大小 + 修改时间）：模型可能正在被别的同学改，报告里留个凭据。 */
    private static String fileFingerprint(Path file) {
        try {
            return Files.size(file) + "B@" + java.time.Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis())
                    .atZone(java.time.ZoneOffset.UTC).toLocalTime().toString().substring(0, 8);
        } catch (IOException e) {
            return "?";
        }
    }

    private static String relativize(Path base, Path file) {
        try {
            return base.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString();
        } catch (RuntimeException e) {
            return file.toString();
        }
    }
}
