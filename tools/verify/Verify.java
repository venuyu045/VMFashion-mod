import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.loading.loader.GeckoLibGsonLoader;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.venus.vmfashion.render.GeoModelRenderer;
import net.minecraft.resources.Identifier;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 无图形环境下的渲染链路验证：
 *   1. 用 GeckoLib 的 GeckoLibGsonLoader 把示例 .geo.json 烘焙成 BakedGeoModel（验证资源接入方案）；
 *   2. 用生产代码 GeoModelRenderer 遍历骨骼 + 立方体，把顶点写进一个记录型 VertexConsumer；
 *   3. 检查顶点包围盒是否符合"脚底原点 / Y 向上"的 Bedrock 坐标约定；
 *   4. 再叠加 CosmeticRenderDispatcher 用的 E→S 变换，检查换算后确实落在原版玩家模型空间。
 */
public final class Verify {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);

        // ---- 背板：贴在背后 z=+2..3，高度 6..34（脚底为 0，头顶 32）----
        BakedGeoModel backboard = bake(root.resolve("cosmetics/backboard/model.geo.json"), "backboard");
        check("backboard 根骨骼数", backboard.topLevelBones().length, 1);
        Box box = renderAndMeasure(backboard, false);
        System.out.println("  backboard bbox(E) = " + box);
        check("backboard 在背后 z>0", box.minZ > 0.05 && box.maxZ < 0.30, true);
        check("backboard 高度覆盖腰到头顶", box.minY > 0.30 && box.minY < 0.45, true);
        check("backboard 顶部在头顶附近", box.maxY > 1.9 && box.maxY < 2.2, true);
        check("backboard 左右对称", Math.abs(box.minX + box.maxX) < 1.0e-4, true);

        Box boxS = renderAndMeasure(backboard, true);
        System.out.println("  backboard bbox(S, 叠加 E→S 变换) = " + boxS);
        check("变换后落在原版模型空间（Y 向下，仍在脚底以上）",
                boxS.minY < -0.5 && boxS.maxY > 1.0 && boxS.maxY < 1.501, true);

        // ---- 皇冠：头顶 y=32..36 ----
        BakedGeoModel crown = bake(root.resolve("cosmetics/crown/model.geo.json"), "crown");
        Box crownBox = renderAndMeasure(crown, false);
        System.out.println("  crown bbox(E) = " + crownBox);
        check("皇冠坐在头顶", crownBox.minY > 1.9 && crownBox.maxY < 2.4, true);
        check("皇冠宽度合理", crownBox.maxX - crownBox.minX > 0.5 && crownBox.maxX - crownBox.minX < 0.7, true);

        mirrorCheck(root);
        attachmentChecks();

        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** 验证 X 轴镜像：JSON 里的 +X 必须落在玩家左手边（原版模型空间的 +X）。 */
    private static void mirrorCheck(Path root) throws Exception {
        Path tmp = Files.createTempFile("vmfashion-mirror", ".geo.json");
        Files.writeString(tmp, """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {"identifier": "geometry.test_mirror", "texture_width": 64, "texture_height": 64},
                    "bones": [{"name": "b", "pivot": [0, 0, 0],
                      "cubes": [{"origin": [0, 0, 0], "size": [2, 2, 2], "uv": [0, 0]}]}]
                  }]
                }
                """);
        BakedGeoModel model = bake(tmp, "mirror");
        Box e = renderAndMeasure(model, false);
        Box s = renderAndMeasure(model, true);
        System.out.println("  mirror bbox(E, GeckoLib 烘焙后) = " + e);
        System.out.println("  mirror bbox(S, 换算后) = " + s);
        check("GeckoLib 烘焙时把 JSON 的 X 取反", e.maxX <= 1.0e-4, true);
        check("换算后回到玩家左手边（+X）", s.minX >= -1.0e-4, true);
        Files.deleteIfExists(tmp);
    }

    /** 验证 attachedBone：皇冠必须跟着头的俯仰/转头一起动。 */
    private static void attachmentChecks() throws Exception {
        var mesh = net.minecraft.client.model.player.PlayerModel.createMesh(
                net.minecraft.client.model.geom.builders.CubeDeformation.NONE, false);
        net.minecraft.client.model.geom.ModelPart root = mesh.getRoot().bake(64, 64);
        var playerModel = new net.minecraft.client.model.player.PlayerModel(root, false);

        var method = Class.forName("com.venus.vmfashion.render.CosmeticRenderDispatcher")
                .getDeclaredMethod("applyAttachment", PoseStack.class, playerModel.getClass(), String.class);
        method.setAccessible(true);

        // 不转头：pivot 在脖子，先移到 pivot 再移回来，净效果应为恒等
        PoseStack pose = new PoseStack();
        method.invoke(null, pose, playerModel, "head");
        org.joml.Vector4f probe = new org.joml.Vector4f(0.0F, 2.125F, 0.0F, 1.0F);
        pose.last().pose().transform(probe);
        System.out.println("  head 不转头时皇冠顶点 = " + fmt(probe));
        check("未转头时皇冠仍在头顶", Math.abs(probe.y - 2.125F) < 1.0e-4 && Math.abs(probe.z) < 1.0e-4, true);

        // 低头 30 度：皇冠应当向前（-Z）倾
        playerModel.head.xRot = (float) Math.toRadians(30.0);
        PoseStack pose2 = new PoseStack();
        method.invoke(null, pose2, playerModel, "head");
        org.joml.Vector4f probe2 = new org.joml.Vector4f(0.0F, 2.125F, 0.0F, 1.0F);
        pose2.last().pose().transform(probe2);
        System.out.println("  head 低头30度后皇冠顶点 = " + fmt(probe2));
        check("低头时皇冠向前倾", probe2.z < -0.25F && probe2.z > -0.40F, true);
        check("低头时皇冠高度下降", probe2.y < 2.10F && probe2.y > 1.95F, true);

        // 转头 90 度：探针 (0.5, 2.125, 0) 在 E 空间应绕脖子转到 (0, 2.125, -0.5)
        playerModel.head.xRot = 0.0F;
        playerModel.head.yRot = (float) Math.toRadians(90.0);
        PoseStack pose4 = new PoseStack();
        method.invoke(null, pose4, playerModel, "head");
        // S 空间的 (0.5, -0.624, 0)（玩家左手边）换算到 E 空间是 (-0.5, 2.125, 0)
        org.joml.Vector4f probe4 = new org.joml.Vector4f(-0.5F, 2.125F, 0.0F, 1.0F);
        pose4.last().pose().transform(probe4);
        System.out.println("  head 转头90度后探针 = " + fmt(probe4));
        check("转头时皇冠跟着转", Math.abs(probe4.x) < 1.0e-4 && Math.abs(probe4.z + 0.5F) < 1.0e-4, true);
        playerModel.head.yRot = 0.0F;

        // body 骨骼 pivot 也在脖子，不旋转时应为恒等
        PoseStack pose3 = new PoseStack();
        method.invoke(null, pose3, playerModel, "body");
        org.joml.Vector4f probe3 = new org.joml.Vector4f(0.0F, 1.0F, 0.15F, 1.0F);
        pose3.last().pose().transform(probe3);
        check("body 骨骼不动时保持原位", Math.abs(probe3.y - 1.0F) < 1.0e-4 && Math.abs(probe3.z - 0.15F) < 1.0e-4, true);
    }

    private static String fmt(org.joml.Vector4f v) {
        return String.format("(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
    }

    private static BakedGeoModel bake(Path file, String name) throws Exception {
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            json = element.getAsJsonObject();
        }
        BakedGeoModel model = new GeckoLibGsonLoader()
                .bakeGeckoLibModelFile(Identifier.fromNamespaceAndPath("vmfashion", "verify/" + name), json);
        System.out.println(name + ": 根骨骼=" + model.topLevelBones().length
                + " 四边形=" + countQuads(model.topLevelBones())
                + " 贴图尺寸=" + model.properties().textureWidth() + "x" + model.properties().textureHeight());
        return model;
    }

    private static Box renderAndMeasure(BakedGeoModel model, boolean applyModelSpace) {
        PoseStack poseStack = new PoseStack();
        if (applyModelSpace) {
            // CosmeticRenderDispatcher#applyModelSpace 的等价变换
            poseStack.translate(0.0F, 1.501F, 0.0F);
            poseStack.scale(-1.0F, -1.0F, 1.0F);
        }
        RecordingConsumer consumer = new RecordingConsumer();
        GeoModelRenderer.render(model, poseStack.last(), consumer, 0xF000F0, 0, 0xFFFFFFFF);
        return Box.of(consumer.points);
    }

    private static int countQuads(GeoBone[] bones) {
        int total = 0;
        for (GeoBone bone : bones) {
            if (bone instanceof CuboidGeoBone cuboid) {
                for (var cube : cuboid.cubes) {
                    total += cube.quads().length;
                }
            }
            total += countQuads(bone.children());
        }
        return total;
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label + " = " + actual + (ok ? "" : " (期望 " + expected + ")"));
        if (!ok) {
            failures++;
        }
    }

    private static final class Box {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        static Box of(List<float[]> points) {
            Box box = new Box();
            for (float[] p : points) {
                box.minX = Math.min(box.minX, p[0]);
                box.minY = Math.min(box.minY, p[1]);
                box.minZ = Math.min(box.minZ, p[2]);
                box.maxX = Math.max(box.maxX, p[0]);
                box.maxY = Math.max(box.maxY, p[1]);
                box.maxZ = Math.max(box.maxZ, p[2]);
            }
            return box;
        }

        @Override
        public String toString() {
            return String.format("x[%.4f, %.4f] y[%.4f, %.4f] z[%.4f, %.4f]",
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
    }

    /** 只记录位置的最小 VertexConsumer。 */
    private static final class RecordingConsumer implements VertexConsumer {
        final List<float[]> points = new ArrayList<>();

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            points.add(new float[]{x, y, z});
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
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
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
