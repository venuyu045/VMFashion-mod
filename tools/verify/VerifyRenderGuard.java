import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.loading.loader.GeckoLibGsonLoader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.venus.vmfashion.render.GeoModelRenderer;
import net.minecraft.resources.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 发布加固：坏模型不能让渲染线程崩。
 *
 * <p>验证 {@link GeoModelRenderer} 的三层兜底：
 *   1) 遍历时抛异常（骨骼 children 为 null）→ 不向外抛，客户端不崩；
 *   2) 同一个坏模型第二次提交直接跳过（拉黑，不刷日志、不重复踩坑）；
 *   3) 正常模型不受影响（顶点数不为 0）。
 */
public final class VerifyRenderGuard {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // ---- 1) 坏模型：children 为 null → 遍历时 NPE ----
        CuboidGeoBone bad = new CuboidGeoBone(null, "bad", null, null, null, 0, 0, 0, 0, 0, 0);
        BakedGeoModel badModel = new BakedGeoModel(new GeoBone[]{bad}, Map.of(), null);

        PoseStack poseStack = new PoseStack();
        RecordingConsumer consumer = new RecordingConsumer();
        boolean threw = false;
        try {
            GeoModelRenderer.render(badModel, poseStack.last(), consumer, 0xF000F0, 0, 0xFFFFFFFF);
        } catch (Throwable t) {
            threw = true;
            System.out.println("  未捕获的异常: " + t);
        }
        report("坏模型渲染不向外抛异常（客户端不崩）", !threw);
        report("坏模型没有产生半个模型的顶点（本次 " + consumer.points + " 个）", consumer.points == 0);

        // ---- 2) 拉黑后第二次提交直接跳过 ----
        RecordingConsumer second = new RecordingConsumer();
        boolean threwAgain = false;
        try {
            GeoModelRenderer.render(badModel, poseStack.last(), second, 0xF000F0, 0, 0xFFFFFFFF);
        } catch (Throwable t) {
            threwAgain = true;
        }
        report("坏模型第二次提交被跳过（不抛、不画）", !threwAgain && second.points == 0);

        // ---- 3) 空立方体的骨骼（cubes = null）安全降级 ----
        CuboidGeoBone emptyBone = new CuboidGeoBone(null, "empty", new GeoBone[0], null, null, 0, 0, 0, 0, 0, 0);
        BakedGeoModel emptyModel = new BakedGeoModel(new GeoBone[]{emptyBone}, Map.of(), null);
        RecordingConsumer emptyConsumer = new RecordingConsumer();
        boolean emptyThrew = false;
        try {
            GeoModelRenderer.render(emptyModel, poseStack.last(), emptyConsumer, 0xF000F0, 0, 0xFFFFFFFF);
        } catch (Throwable t) {
            emptyThrew = true;
        }
        report("cubes=null 的骨骼安全降级（不抛异常）", !emptyThrew);

        // ---- 4) 正常模型不受影响（回归）----
        Path tmp = Files.createTempFile("vmf-guard", ".geo.json");
        Files.writeString(tmp, """
                {
                  "format_version": "1.12.0",
                  "minecraft:geometry": [{
                    "description": {"identifier": "geometry.guard", "texture_width": 64, "texture_height": 64},
                    "bones": [{"name": "b", "pivot": [0, 0, 0],
                      "cubes": [{"origin": [0, 0, 0], "size": [2, 2, 2], "uv": [0, 0]}]}]
                  }]
                }
                """);
        JsonObject json = JsonParser.parseReader(
                Files.newBufferedReader(tmp, StandardCharsets.UTF_8)).getAsJsonObject();
        BakedGeoModel good = new GeckoLibGsonLoader()
                .bakeGeckoLibModelFile(Identifier.fromNamespaceAndPath("vmfashion", "guard/good"), json);
        RecordingConsumer goodConsumer = new RecordingConsumer();
        GeoModelRenderer.render(good, poseStack.last(), goodConsumer, 0xF000F0, 0, 0xFFFFFFFF);
        report("正常模型仍然渲染出 24 个顶点（6 面 × 4）", goodConsumer.points == 24);
        Files.deleteIfExists(tmp);

        System.out.println(failures == 0 ? "ALL RENDER GUARD CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void report(String label, boolean ok) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label);
        if (!ok) {
            failures++;
        }
    }

    private static final class RecordingConsumer implements VertexConsumer {
        int points = 0;

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            points++;
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
