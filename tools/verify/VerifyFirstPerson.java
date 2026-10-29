import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.util.Mth;
import org.joml.Vector4f;

import java.lang.reflect.Method;

/**
 * 第一人称坐标系的数值自检（无显卡）。
 *
 * <p>要证明的事：<b>第三人称那套 applyModelSpace + applyAttachment 在第一人称也能直接用</b>。
 * 依据：两处 PoseStack 都只是"部件变换的父空间"（部件自己的 pivot 由 submitModelPart 内部施加），
 * 所以"E 空间 → 父空间"的换算与前面叠了什么相机变换无关。
 *
 * <p>验证方法：取手臂立方体上的若干点，
 *   A = 原版 ModelPart#translateAndRotate（vanilla 真实代码）作用于手臂局部坐标
 *   B = applyModelSpace + applyAttachment 作用于"同一个物理点的 Bedrock E 空间坐标"
 * 两者必须重合（容差 1e-4）。
 */
public final class VerifyFirstPerson {

    private static int failures = 0;
    private static Method applyModelSpace;
    private static Method applyAttachment;

    public static void main(String[] args) throws Exception {
        var mesh = PlayerModel.createMesh(CubeDeformation.NONE, false);
        ModelPart root = mesh.getRoot().bake(64, 64);
        PlayerModel model = new PlayerModel(root, false);

        applyModelSpace = CosmeticRenderDispatcher.class.getDeclaredMethod("applyModelSpace", PoseStack.class);
        applyModelSpace.setAccessible(true);
        applyAttachment = CosmeticRenderDispatcher.class.getDeclaredMethod(
                "applyAttachment", PoseStack.class, PlayerModel.class, String.class, float.class);
        applyAttachment.setAccessible(true);

        // 手臂立方体的 8 个角（局部坐标，单位是模型像素；pivot 由 ModelPart 自己带）
        // 右臂 addBox(-3,-2,-2, 4,12,4) @ offset(-5,2,0)
        float[][] corners = {
                {-3, -2, -2}, {1, -2, -2}, {-3, 10, -2}, {1, 10, -2},
                {-3, -2, 2}, {1, -2, 2}, {-3, 10, 2}, {1, 10, 2},
        };

        // ---- 1) 手臂在 E 空间（Bedrock 作画空间）里到底占哪一块 ----
        eSpaceBox(model.rightArm, corners);
        // 解析期望：x∈[0.25,0.50] y∈[0.751,1.501] z∈[-0.125,0.125]
        checkBox("右臂 E 空间包围盒", model.rightArm, corners,
                0.25F, 0.50F, 0.751F, 1.501F, -0.125F, 0.125F);

        // ---- 2) 第一人称静止姿态（renderHand 里设的 zRot = ±0.1）----
        //    A（原版 ModelPart 变换）必须等于 B（我们的换算）
        checkPose(model, model.rightArm, "right_arm", corners, 0.0F, 0.0F, 0.1F, 1.0F, "右臂 第一人称 zRot=+0.1");
        checkPose(model, model.leftArm, "left_arm", corners, 0.0F, 0.0F, -0.1F, 1.0F, "左臂 第一人称 zRot=-0.1");

        // ---- 3) 挥手臂/吃东西时的姿态也要对（x/y/z 三个轴一起转）----
        checkPose(model, model.rightArm, "right_arm", corners, -0.5F, 0.3F, 0.1F, 1.0F, "右臂 挥动姿态");
        checkPose(model, model.leftArm, "left_arm", corners, 0.4F, -0.6F, -0.1F, 1.0F, "左臂 挥动姿态");
        // 头（第三人称已验证过，这里作为回归）
        float[][] headCorners = {{-4, -8, -4}, {4, -8, -4}, {-4, 0, -4}, {4, 0, 4}};
        checkPose(model, model.head, "head", headCorners, 0.3F, 0.7F, 0.0F, 1.0F, "头部 转头姿态（回归）");

        // ---- 4) 缩放要绕 pivot（第一人称也吃配置 scale）----
        checkScaleAboutPivot(model, model.rightArm, "right_arm", corners);

        // ---- 5) 整条第一人称相机链：手臂与"绑在手臂上的时装"必须重合 ----
        cameraChain(model);

        System.out.println(failures == 0 ? "ALL FIRST-PERSON CHECKS PASSED" : (failures + " CHECK(S) FAILED"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** E 空间 ↔ 模型空间的对应关系：v_E = (-vx, 1.501 - vy, vz)（静止姿态、无旋转）。 */
    private static Vector4f toESpace(ModelPart part, float[] local) {
        PoseStack rest = new PoseStack();
        part.translateAndRotate(rest);              // 此时旋转为 0 → 只有 pivot 位移
        Vector4f v = new Vector4f(local[0] / 16.0F, local[1] / 16.0F, local[2] / 16.0F, 1.0F);
        rest.last().pose().transform(v);
        return new Vector4f(-v.x, 1.501F - v.y, v.z, 1.0F);
    }

    private static void eSpaceBox(ModelPart part, float[][] corners) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float[] c : corners) {
            Vector4f e = toESpace(part, c);
            minX = Math.min(minX, e.x); maxX = Math.max(maxX, e.x);
            minY = Math.min(minY, e.y); maxY = Math.max(maxY, e.y);
            minZ = Math.min(minZ, e.z); maxZ = Math.max(maxZ, e.z);
        }
        System.out.printf("  右臂在 Bedrock E 空间 = x[%.4f, %.4f] y[%.4f, %.4f] z[%.4f, %.4f]%n",
                minX, maxX, minY, maxY, minZ, maxZ);
    }

    private static void checkBox(String label, ModelPart part, float[][] corners,
                                 float x0, float x1, float y0, float y1, float z0, float z1) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (float[] c : corners) {
            Vector4f e = toESpace(part, c);
            minX = Math.min(minX, e.x); maxX = Math.max(maxX, e.x);
            minY = Math.min(minY, e.y); maxY = Math.max(maxY, e.y);
            minZ = Math.min(minZ, e.z); maxZ = Math.max(maxZ, e.z);
        }
        boolean ok = near(minX, x0) && near(maxX, x1) && near(minY, y0)
                && near(maxY, y1) && near(minZ, z0) && near(maxZ, z1);
        report(label, ok, String.format("x[%.4f,%.4f] y[%.4f,%.4f] z[%.4f,%.4f]",
                minX, maxX, minY, maxY, minZ, maxZ));
    }

    /** A（原版 ModelPart 变换）== B（applyModelSpace + applyAttachment） */
    private static void checkPose(PlayerModel model, ModelPart part, String bone, float[][] corners,
                                  float xRot, float yRot, float zRot, float scale, String label) throws Exception {
        part.resetPose();
        part.xRot = xRot;
        part.yRot = yRot;
        part.zRot = zRot;

        PoseStack vanilla = new PoseStack();
        part.translateAndRotate(vanilla);

        PoseStack mine = new PoseStack();
        applyModelSpace.invoke(null, mine);
        applyAttachment.invoke(null, mine, model, bone, scale);

        float maxDelta = 0.0F;
        for (float[] c : corners) {
            Vector4f a = new Vector4f(c[0] / 16.0F, c[1] / 16.0F, c[2] / 16.0F, 1.0F);
            vanilla.last().pose().transform(a);
            // 同一物理点的 E 空间坐标（静止姿态），注意缩放时对应关系不变
            Vector4f e = toESpaceRest(part, c);
            mine.last().pose().transform(e);
            maxDelta = Math.max(maxDelta, Math.abs(a.x - e.x));
            maxDelta = Math.max(maxDelta, Math.abs(a.y - e.y));
            maxDelta = Math.max(maxDelta, Math.abs(a.z - e.z));
        }
        report(label + "（A/B 最大偏差 " + String.format("%.6f", maxDelta) + "）", maxDelta < 1.0e-4F, "");
        part.resetPose();
    }

    /** 静止姿态（旋转清零）下的 E 空间坐标。 */
    private static Vector4f toESpaceRest(ModelPart part, float[] local) {
        float savedX = part.xRot, savedY = part.yRot, savedZ = part.zRot;
        part.xRot = 0;
        part.yRot = 0;
        part.zRot = 0;
        Vector4f e = toESpace(part, local);
        part.xRot = savedX;
        part.yRot = savedY;
        part.zRot = savedZ;
        return e;
    }

    /**
     * 缩放必须绕挂载 pivot（#12）：pivot 点不动，其它点到 pivot 的距离按 scale 等比放大。
     * （不能直接和原版手臂比：原版手臂不带缩放，两者本来就该不同。）
     */
    private static void checkScaleAboutPivot(PlayerModel model, ModelPart part, String bone,
                                             float[][] corners) throws Exception {
        part.resetPose();
        part.zRot = 0.1F;

        // pivot 在 E 空间的坐标（和 applyAttachment 内部一致）
        float px = -part.x / 16.0F;
        float py = 1.501F - part.y / 16.0F;
        float pz = part.z / 16.0F;

        Vector4f pivotBase = transform(bone, model, new Vector4f(px, py, pz, 1.0F), 1.0F);
        Vector4f pivotScaled = transform(bone, model, new Vector4f(px, py, pz, 1.0F), 2.0F);
        float pivotDelta = Math.max(Math.abs(pivotBase.x - pivotScaled.x),
                Math.max(Math.abs(pivotBase.y - pivotScaled.y), Math.abs(pivotBase.z - pivotScaled.z)));
        report("scale=2 时 pivot 不动（偏差 " + String.format("%.6f", pivotDelta) + "）",
                pivotDelta < 1.0e-4F, "");

        float maxRatioError = 0.0F;
        for (float[] c : corners) {
            Vector4f e = toESpaceRest(part, c);
            Vector4f one = transform(bone, model, new Vector4f(e), 1.0F);
            Vector4f two = transform(bone, model, new Vector4f(e), 2.0F);
            // 相对 pivot 的位移应当正好 ×2
            float d1 = distance(one, pivotBase);
            float d2 = distance(two, pivotScaled);
            maxRatioError = Math.max(maxRatioError, Math.abs(d2 - 2.0F * d1));
        }
        report("scale=2 时顶点到 pivot 的距离等比放大（最大误差 "
                + String.format("%.6f", maxRatioError) + "）", maxRatioError < 1.0e-4F, "");
        part.resetPose();
    }

    private static Vector4f transform(String bone, PlayerModel model, Vector4f point, float scale) throws Exception {
        PoseStack ps = new PoseStack();
        applyModelSpace.invoke(null, ps);
        applyAttachment.invoke(null, ps, model, bone, scale);
        return ps.last().pose().transform(point);
    }

    private static float distance(Vector4f a, Vector4f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 复刻 26.3 {@code FirstPersonHandsAndItemsRenderer#renderPlayerArm} 的相机链（javap 逐条核对），
     * 然后分别用"原版手臂"和"绑在手臂上的 Bedrock 时装"算同一个物理点，必须重合。
     */
    private static void cameraChain(PlayerModel model) throws Exception {
        ModelPart part = model.rightArm;
        part.resetPose();
        part.zRot = 0.1F;
        float swing = 0.0F;
        float equip = 0.0F;

        PoseStack vanilla = new PoseStack();
        applyRenderPlayerArm(vanilla, true, equip, swing);
        part.translateAndRotate(vanilla);

        PoseStack mine = new PoseStack();
        applyRenderPlayerArm(mine, true, equip, swing);
        applyModelSpace.invoke(null, mine);
        applyAttachment.invoke(null, mine, model, "right_arm", 1.0F);

        // 手臂立方体中心（局部 (‑1, 4, 0) → 模型像素）
        float[] center = {-1, 4, 0};
        Vector4f a = new Vector4f(center[0] / 16.0F, center[1] / 16.0F, center[2] / 16.0F, 1.0F);
        vanilla.last().pose().transform(a);
        Vector4f e = toESpaceRest(part, center);
        mine.last().pose().transform(e);

        System.out.printf("  第一人称相机空间：原版手臂中心 = (%.4f, %.4f, %.4f)%n", a.x, a.y, a.z);
        System.out.printf("  第一人称相机空间：时装(同一物理点) = (%.4f, %.4f, %.4f)%n", e.x, e.y, e.z);
        float delta = Math.max(Math.abs(a.x - e.x), Math.max(Math.abs(a.y - e.y), Math.abs(a.z - e.z)));
        report("相机链下手臂与时装重合（偏差 " + String.format("%.6f", delta) + "）", delta < 1.0e-4F, "");

        // 合理性：右手应该在相机右下方、并且在前方（MC 相机空间看向 -Z）
        boolean sensible = a.x > 0.05F && a.y < 0.05F && a.z < 0.0F;
        report("右手落在相机右下方且在视野前方", sensible,
                String.format("x=%.3f y=%.3f z=%.3f", a.x, a.y, a.z));

        // 挥动时（swing=0.6）也要重合
        PoseStack v2 = new PoseStack();
        applyRenderPlayerArm(v2, true, 0.2F, 0.6F);
        part.translateAndRotate(v2);
        PoseStack m2 = new PoseStack();
        applyRenderPlayerArm(m2, true, 0.2F, 0.6F);
        applyModelSpace.invoke(null, m2);
        applyAttachment.invoke(null, m2, model, "right_arm", 1.0F);
        Vector4f a2 = new Vector4f(center[0] / 16.0F, center[1] / 16.0F, center[2] / 16.0F, 1.0F);
        v2.last().pose().transform(a2);
        Vector4f e2 = toESpaceRest(part, center);
        m2.last().pose().transform(e2);
        float d2 = Math.max(Math.abs(a2.x - e2.x), Math.max(Math.abs(a2.y - e2.y), Math.abs(a2.z - e2.z)));
        report("挥动+换手（swing=0.6, equip=0.2）时仍重合（偏差 " + String.format("%.6f", d2) + "）",
                d2 < 1.0e-4F, "");
        part.resetPose();
    }

    /** 逐条复刻 renderPlayerArm 的 PoseStack 操作（javap -c 核对）。 */
    private static void applyRenderPlayerArm(PoseStack ps, boolean rightArm, float equipProgress, float swingProgress) {
        float f = rightArm ? 1.0F : -1.0F;
        float f1 = Mth.sqrt(swingProgress);
        float f2 = -0.3F * Mth.sin(f1 * (float) Math.PI);
        float f3 = 0.4F * Mth.sin(f1 * ((float) Math.PI * 2.0F));
        float f4 = -0.4F * Mth.sin(swingProgress * (float) Math.PI);
        ps.translate(f * (f2 + 0.64000005F), f3 + (-0.6F + equipProgress * -0.6F), f4 + -0.71999997F);
        ps.rotateDegrees(Axis.YP, f * 45.0F);
        float f5 = Mth.sin(swingProgress * swingProgress * (float) Math.PI);
        float f6 = Mth.sin(f1 * (float) Math.PI);
        ps.rotateDegrees(Axis.YP, f * f6 * 70.0F);
        ps.rotateDegrees(Axis.ZP, f * f5 * -20.0F);
        ps.translate(f * -1.0F, 3.6F, 3.5F);
        ps.rotateDegrees(Axis.ZP, f * 120.0F);
        ps.rotateDegrees(Axis.XP, 200.0F);
        ps.rotateDegrees(Axis.YP, f * -135.0F);
        ps.translate(f * 5.6F, 0.0F, 0.0F);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0e-3F;
    }

    private static void report(String label, boolean ok, String detail) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + label
                + (detail.isEmpty() ? "" : " -> " + detail));
        if (!ok) {
            failures++;
        }
    }
}
