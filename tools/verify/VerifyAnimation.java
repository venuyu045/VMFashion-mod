import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.loading.loader.GeckoLibGsonLoader;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.venus.vmfashion.render.anim.AnimationTrack;
import com.venus.vmfashion.render.anim.BedrockAnimation;
import com.venus.vmfashion.render.anim.BedrockAnimations;
import net.minecraft.resources.Identifier;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 无显卡环境下验证动画链路：{@code anim.json} 能被解析，且里面的骨骼名在烘焙后的模型里**真的存在**。
 *
 * <p>为什么需要这一步：动画解析和模型烘焙是两条独立的链路，骨骼名写错（例如模型里叫
 * {@code cape}、动画里写 {@code Cape}）在 JUnit 里测不出来 —— 那边没有真实模型。
 * 这里用示例包做端到端检查，同时验证采样数值（t=0.6 时背板应该转到 -3.5°）。
 *
 * <p>用法：{@code java VerifyAnimation <default_pack 目录>}
 */
public final class VerifyAnimation {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path packDir = Path.of(args.length > 0 ? args[0]
                : "src/main/resources/assets/vmfashion/default_pack");

        check("背板动画", packDir.resolve("cosmetics/backboard/anim.json"),
                packDir.resolve("cosmetics/backboard/model.geo.json"));
        check("皇冠动画", packDir.resolve("cosmetics/crown/anim.json"),
                packDir.resolve("cosmetics/crown/model.geo.json"));

        // 采样数值：背板 t=0.6 时 rotation.x = -3.5°（-0.0611 rad）
        Path animFile = packDir.resolve("cosmetics/backboard/anim.json");
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(readJson(animFile), System.out::println);
        BedrockAnimation sway = animations.get("animation.vmfashion.backboard.sway");
        check("动画名解析", sway != null, true);
        if (sway != null) {
            AnimationTrack rotation = sway.bones().get("board").rotation();
            check("关键帧数量", rotation.size(), 5);
            check("循环播放", sway.loop(), true);
            float[] out = new float[3];
            rotation.sample(0.6, out);
            check("t=0.6 时绕 X 转 -3.5°",
                    near(out[0], (float) Math.toRadians(-3.5)), true);
            rotation.sample(1.2, out);
            check("t=1.2 时回到 0°", near(out[0], 0.0F), true);
            // 循环：t = 2.4 + 0.6 = 3.0 回绕后应该等价于 t = 0.6
            float[] wrapped = new float[3];
            rotation.sample(AnimationTrack.wrap(3.0, sway.length(), true), wrapped);
            check("循环回绕后与 t=0.6 一致",
                    near(wrapped[0], (float) Math.toRadians(-3.5)), true);
        }

        System.out.println(failures == 0 ? "ALL ANIMATION CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static void check(String label, Path animFile, Path modelFile) throws Exception {
        if (!Files.isRegularFile(animFile)) {
            check(label + "：动画文件存在", false, true);
            return;
        }
        Map<String, BedrockAnimation> animations = BedrockAnimations.parse(readJson(animFile), System.out::println);
        check(label + "：解析到动画", !animations.isEmpty(), true);

        BakedGeoModel model = bake(modelFile);
        Set<String> bones = new HashSet<>();
        for (GeoBone bone : model.topLevelBones()) {
            collectBones(bone, bones);
        }
        for (BedrockAnimation animation : animations.values()) {
            for (String boneName : animation.bones().keySet()) {
                boolean exists = bones.contains(boneName.toLowerCase(Locale.ROOT));
                check(label + "：动画骨骼 '" + boneName + "' 在模型里存在", exists, true);
            }
            check(label + "：时长 > 0", animation.length() > 0.0, true);
        }
    }

    private static void collectBones(GeoBone bone, Set<String> out) {
        if (bone == null) {
            return;
        }
        if (bone.name() != null) {
            out.add(bone.name().toLowerCase(Locale.ROOT));
        }
        for (GeoBone child : bone.children()) {
            collectBones(child, out);
        }
    }

    private static BakedGeoModel bake(Path modelFile) throws Exception {
        try (Reader reader = Files.newBufferedReader(modelFile, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            return new GeckoLibGsonLoader().bakeGeckoLibModelFile(
                    Identifier.fromNamespaceAndPath("vmfashion_verify", "anim_check"), json);
        }
    }

    private static JsonObject readJson(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0e-4F;
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = expected.equals(actual);
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label + " = " + actual
                + (ok ? "" : " (期望 " + expected + ")"));
        if (!ok) {
            failures++;
        }
    }

    private static void check(String label, boolean ok, boolean ignored) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label);
        if (!ok) {
            failures++;
        }
    }
}
