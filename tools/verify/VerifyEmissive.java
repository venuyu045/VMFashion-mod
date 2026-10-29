import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.loading.loader.GeckoLibGsonLoader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.render.EmissiveMask;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 无显卡环境下验证 emissive 遮罩的<strong>数学</strong>（不碰 NativeImage / LWJGL 原生库）：
 *   1. GeckoLib 烘焙出来的 UV 到底是归一化的还是 texel 单位；
 *   2. 只有"emissive 贴图上确实不透明"的四边形才进遮罩。
 *
 * 模型两个立方体：cube A 用贴图左上角（uv 0..8 / 0..6），cube B 用 (32,32) 起 8x8。
 * emissive 只在左上 8x8 不透明 → 期望 cube A 的 6 个 quad 命中、cube B 的 6 个不命中。
 */
public final class VerifyEmissive {

    private static int failures = 0;
    private static Method fromAlpha;

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("vmf-emissive");
        Path modelFile = dir.resolve("model.geo.json");
        Files.writeString(modelFile, MODEL, StandardCharsets.UTF_8);
        BakedGeoModel model = bake(modelFile, "emissive-check/model");

        check("四边形总数", countQuads(model.topLevelBones()), 12);
        dumpUv(model);
        check("UV 是归一化的（最大值 <= 1.0）", maxUv(model) <= 1.0001F, true);

        fromAlpha = Class.forName("com.venus.vmfashion.render.EmissiveMask")
                .getDeclaredMethod("fromAlpha", BakedGeoModel.class, boolean[].class, int.class, int.class);
        fromAlpha.setAccessible(true);

        // ---- 只有左上 8x8 不透明 → cube A 的 6 个面发光 ----
        EmissiveMask mask = maskOf(model, opaqueBitmap(dir, 64, 0, 0, 8, 8));
        check("遮罩非空", mask != null && !mask.isEmpty(), true);
        check("发光四边形数", mask == null ? -1 : mask.size(), 6);
        check("cube A（贴图左上）命中面数", hits(mask, model, 0), 6);
        check("cube B（贴图右下）命中面数", hits(mask, model, 1), 0);

        // ---- 只有右下 8x8 不透明 → 反过来 ----
        EmissiveMask maskB = maskOf(model, opaqueBitmap(dir, 64, 32, 32, 8, 8));
        check("反向：cube A 命中面数", hits(maskB, model, 0), 0);
        check("反向：cube B 命中面数", hits(maskB, model, 1), 6);

        // ---- 全透明 → EMPTY（调用方整趟跳过 emissive pass）----
        EmissiveMask maskEmpty = maskOf(model, opaqueBitmap(dir, 64, 0, 0, 0, 0));
        check("全透明 emissive → 空遮罩", maskEmpty != null && maskEmpty.isEmpty(), true);

        // ---- 全不透明 → 12 个面全发光（退化成旧行为，不能漏）----
        EmissiveMask maskAll = maskOf(model, opaqueBitmap(dir, 64, 0, 0, 64, 64));
        check("全不透明 emissive → 12 个面", maskAll == null ? -1 : maskAll.size(), 12);

        System.out.println(failures == 0 ? "ALL EMISSIVE CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static EmissiveMask maskOf(BakedGeoModel model, boolean[] opaque) throws Exception {
        return (EmissiveMask) fromAlpha.invoke(null, model, opaque, 64, 64);
    }

    /** 用 ImageIO 读 PNG（不依赖 LWJGL），转成 EmissiveMask 要的 alpha 位图。 */
    private static boolean[] opaqueBitmap(Path dir, int size, int x, int y, int w, int h) throws Exception {
        Path file = dir.resolve("emissive-" + x + "-" + y + "-" + w + "-" + h + ".png");
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                boolean inside = px >= x && px < x + w && py >= y && py < y + h;
                image.setRGB(px, py, inside ? 0xFFFFFFFF : 0x00000000);
            }
        }
        ImageIO.write(image, "png", file.toFile());

        BufferedImage read = ImageIO.read(file.toFile());
        boolean[] opaque = new boolean[size * size];
        for (int py = 0; py < size; py++) {
            for (int px = 0; px < size; px++) {
                opaque[py * size + px] = ((read.getRGB(px, py) >>> 24) & 0xFF) > 8;
            }
        }
        return opaque;
    }

    private static int hits(EmissiveMask mask, BakedGeoModel model, int cubeIndex) {
        if (mask == null) {
            return -1;
        }
        CuboidGeoBone bone = (CuboidGeoBone) model.topLevelBones()[0];
        int count = 0;
        for (GeoQuad quad : bone.cubes[cubeIndex].quads()) {
            if (mask.isEmissive(quad)) {
                count++;
            }
        }
        return count;
    }

    private static void dumpUv(BakedGeoModel model) {
        CuboidGeoBone bone = (CuboidGeoBone) model.topLevelBones()[0];
        for (int c = 0; c < bone.cubes.length; c++) {
            GeoQuad quad = bone.cubes[c].quads()[0];
            float minU = Float.MAX_VALUE, maxU = -Float.MAX_VALUE;
            float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            for (GeoVertex v : quad.vertices()) {
                minU = Math.min(minU, v.texU());
                maxU = Math.max(maxU, v.texU());
                minV = Math.min(minV, v.texV());
                maxV = Math.max(maxV, v.texV());
            }
            System.out.printf("  cube[%d] quad0 UV = u[%.4f, %.4f] v[%.4f, %.4f]%n", c, minU, maxU, minV, maxV);
        }
    }

    private static float maxUv(BakedGeoModel model) {
        float max = 0.0F;
        for (GeoBone bone : model.topLevelBones()) {
            if (bone instanceof CuboidGeoBone cuboid) {
                for (var cube : cuboid.cubes) {
                    for (GeoQuad quad : cube.quads()) {
                        for (GeoVertex v : quad.vertices()) {
                            max = Math.max(max, Math.max(v.texU(), v.texV()));
                        }
                    }
                }
            }
        }
        return max;
    }

    private static BakedGeoModel bake(Path file, String name) throws Exception {
        JsonObject json = JsonParser.parseReader(
                Files.newBufferedReader(file, StandardCharsets.UTF_8)).getAsJsonObject();
        return new GeckoLibGsonLoader()
                .bakeGeckoLibModelFile(Identifier.fromNamespaceAndPath("vmfashion", name), json);
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
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label + " = " + actual
                + (ok ? "" : " (期望 " + expected + ")"));
        if (!ok) {
            failures++;
        }
    }

    private static final String MODEL = """
            {
              "format_version": "1.12.0",
              "minecraft:geometry": [{
                "description": {"identifier": "geometry.emissive_check", "texture_width": 64, "texture_height": 64},
                "bones": [{"name": "b", "pivot": [0, 0, 0], "cubes": [
                  {"origin": [0, 0, 0], "size": [2, 2, 2], "uv": [0, 0]},
                  {"origin": [4, 0, 0], "size": [2, 2, 2], "uv": [32, 32]}
                ]}]
              }]
            }
            """;
}
