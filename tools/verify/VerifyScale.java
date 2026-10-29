import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.player.PlayerModel;
import org.joml.Vector4f;

import java.lang.reflect.Method;

/**
 * 验证 #12 缩放原点修复：scale 必须绕 attachedBone 的 pivot，而不是绕脚底。
 *
 * 用法：applyAttachment 现在有 (PoseStack, PlayerModel, String) 和
 * (PoseStack, PlayerModel, String, float) 两个重载；3 参数版必须和旧行为完全一致
 * （tools/verify/Verify.java 用反射按 3 参数签名调它）。
 */
public final class VerifyScale {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        ModelPart root = mesh.getRoot().bake(64, 64);
        PlayerModel model = new PlayerModel(root, false);

        Method attach = CosmeticRenderDispatcher.class.getDeclaredMethod(
                "applyAttachment", PoseStack.class, PlayerModel.class, String.class, float.class);
        attach.setAccessible(true);
        Method attachLegacy = CosmeticRenderDispatcher.class.getDeclaredMethod(
                "applyAttachment", PoseStack.class, PlayerModel.class, String.class);
        attachLegacy.setAccessible(true);

        // head 的 pivot 在脖子：E 空间 y = 1.501（part.y = 0）
        float pivotY = 1.501F;
        // 皇冠顶在 E 空间 y = 2.125（头顶），绕 pivot 放大 2 倍
        float probeY = 2.125F;
        float expectedNew = pivotY + 2.0F * (probeY - pivotY);   // 2.749
        float expectedOld = 2.0F * probeY;                      // 4.25（绕脚底）

        PoseStack scaled = new PoseStack();
        attach.invoke(null, scaled, model, "head", 2.0F);
        Vector4f probe = new Vector4f(0.0F, probeY, 0.0F, 1.0F);
        scaled.last().pose().transform(probe);
        System.out.printf("  新行为：head scale=2 时 (0, %.3f, 0) -> (%.4f, %.4f, %.4f)%n",
                probeY, probe.x, probe.y, probe.z);
        check("绕 pivot 缩放（期望 y = " + expectedNew + "）", near(probe.y, expectedNew), true);
        check("不再是绕脚底（y != " + expectedOld + "）", Math.abs(probe.y - expectedOld) > 1.0e-3, true);
        check("缩放不改变 X/Z（pivot 在轴上）", near(probe.x, 0.0F) && near(probe.z, 0.0F), true);

        // scale = 1 必须和旧代码（3 参数重载）逐位一致
        PoseStack one = new PoseStack();
        attach.invoke(null, one, model, "head", 1.0F);
        PoseStack legacy = new PoseStack();
        attachLegacy.invoke(null, legacy, model, "head");
        check("scale=1 与旧 3 参数重载矩阵一致", one.last().pose().equals(legacy.last().pose(), 0.0F), true);

        // 低头 30 度 + 放大 2 倍：缩放绕 pivot，所以"离头"的距离同比放大
        model.head.xRot = (float) Math.toRadians(30.0);
        PoseStack tilted = new PoseStack();
        attach.invoke(null, tilted, model, "head", 2.0F);
        Vector4f p1 = new Vector4f(0.0F, probeY, 0.0F, 1.0F);
        tilted.last().pose().transform(p1);
        // 先把 pivot 从 (0,1.501,0) 移到原点，放大 2 倍后 + 回 pivot；低头 30° 时
        // 顶点相对 pivot 的向量是 (0, 0.624, 0) 旋转后 = (0, 0.5404, -0.312)
        double dy = 2.0 * 0.624 * Math.cos(Math.toRadians(30.0));
        double dz = -2.0 * 0.624 * Math.sin(Math.toRadians(30.0));
        System.out.printf("  低头30度+放大2倍：-> (%.4f, %.4f, %.4f)，期望 (0, %.4f, %.4f)%n",
                p1.x, p1.y, p1.z, pivotY + dy, dz);
        check("低头 + 缩放仍绕 pivot", near(p1.y, (float) (pivotY + dy)) && near(p1.z, (float) dz), true);

        // body 骨骼：pivot 也在脖子，scale=2 时同一套逻辑
        model.head.xRot = 0.0F;
        PoseStack body = new PoseStack();
        attach.invoke(null, body, model, "body", 2.0F);
        Vector4f p2 = new Vector4f(0.0F, probeY, 0.0F, 1.0F);
        body.last().pose().transform(p2);
        check("body 也绕脖子 pivot 缩放", near(p2.y, expectedNew), true);

        System.out.println(failures == 0 ? "ALL SCALE CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0e-3F;
    }

    private static void check(String label, boolean ok, boolean ignored) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label);
        if (!ok) {
            failures++;
        }
    }
}
