package com.venus.vmfashion.render;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 把 GeckoLib 的 {@link BakedGeoModel} 画进 26.x 的提交式渲染管线。
 *
 * <p>GeckoLib 自己的 {@code BakedGeoModel#render(RenderPassInfo, VertexConsumer, ...)} 需要一整个
 * {@code RenderPassInfo}（内含 GeoRenderer / GeoRenderState / CameraRenderState），
 * 我们只想"借"它的几何和骨骼变换，不想实现一整套 GeoRenderer，所以这里直接复刻它的遍历：
 *
 * <pre>
 * for (GeoBone bone : topLevelBones) {
 *     push(); RenderUtil.prepMatrixForBone(pose, bone);   // 骨骼 pivot + 旋转 + 缩放
 *     bone.render(...);                                   // 立方体
 *     bone.renderChildren(...);                           // 子骨骼
 *     pop();
 * }
 * </pre>
 *
 * <p><b>为什么不用 {@code bone.render()}/{@code quad.render()}：</b>
 * <ul>
 *   <li>{@code GeoQuad#render} 每个 quad 都 {@code new Vector4f()} × 4，
 *       {@code GeoCube#render} 每个 quad 还 {@code new Vector3f()}（法线）——
 *       100 个 quad 的模型 ≈ 500 个对象/帧/实例（路线图 §3.6）。这里全部走复用的 scratch；</li>
 *   <li>emissive 遮罩需要逐 quad 过滤（{@link EmissiveMask}），GeckoLib 的整模型遍历插不进去。</li>
 * </ul>
 * 顶点数学与 GeckoLib <strong>逐字节等价</strong>（已用 javap 核对 {@code GeoQuad#render} /
 * {@code GeoCube#render} / {@code CuboidGeoBone#render} 的指令序列）：
 * 位置 = {@code pose.pose() * (x,y,z,1)}，法线 = {@code pose.normal() * quad.normal} 再
 * {@code RenderUtil.fixInvertedFlatCube} 修正，最后 {@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)}。
 *
 * <p><b>线程约束：</b>只在渲染线程调用（{@link #SCRATCH} 是 ThreadLocal，PoseStack 会被复用）。
 */
public final class GeoModelRenderer {

    /**
     * 复用的 PoseStack 与向量。渲染线程独占：26.x 的自定义几何回调在 flush 时串行执行，
     * 不会嵌套调用 {@link #render}，所以同一个实例可以反复用。
     */
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private GeoModelRenderer() {
    }

    /**
     * 在给定矩阵下渲染整个模型。
     *
     * @param pose   {@code SubmitNodeCollector.CustomGeometryRenderer} 回调里拿到的姿态快照
     * @param light  打包光照
     * @param overlay 打包 overlay（通常是 {@code OverlayTexture.NO_OVERLAY}）
     * @param color  ARGB 颜色
     */
    public static void render(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                              int light, int overlay, int color) {
        draw(model, pose, consumer, light, overlay, color, null);
    }

    /**
     * 只渲染 {@code mask} 命中的四边形（emissive 层）。
     *
     * <p>遮罩为 {@code null} 或空时<strong>什么都不画</strong>——调用方本来就不该提交这一趟，
     * 这里再兜一层，保证不会退化成"整块模型发光"。
     */
    public static void renderEmissive(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                                      int light, int overlay, int color, EmissiveMask mask) {
        if (mask == null || mask.isEmpty()) {
            return;
        }
        draw(model, pose, consumer, light, overlay, color, mask);
    }

    private static void draw(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                             int light, int overlay, int color, EmissiveMask mask) {
        if (model == null) {
            return;
        }
        Scratch scratch = SCRATCH.get();
        PoseStack stack = scratch.stack;
        // 上一次渲染若因为异常中断，栈可能没退干净（lastIndex != 0）——直接换一个新的，
        // 否则后面所有骨骼矩阵都会叠在脏矩阵上
        if (!stack.isEmpty()) {
            stack = new PoseStack();
            scratch.stack = stack;
        }
        stack.last().set(pose);
        for (GeoBone bone : model.topLevelBones()) {
            drawBone(bone, stack, consumer, light, overlay, color, mask, scratch);
        }
    }

    private static void drawBone(GeoBone bone, PoseStack stack, VertexConsumer consumer,
                                 int light, int overlay, int color, EmissiveMask mask, Scratch scratch) {
        BoneSnapshot snapshot = bone.frameSnapshot;
        // 骨骼矩阵无条件生效：GeckoLib 的 isHidden 只隐藏"自己的立方体"，
        // 子骨骼另由 areChildrenHidden 控制（两套语义，见路线图 §3.7）
        stack.pushPose();
        RenderUtil.prepMatrixForBone(stack, bone);
        if ((snapshot == null || !snapshot.isHidden()) && bone instanceof CuboidGeoBone cuboid) {
            drawCubes(cuboid, stack, consumer, light, overlay, color, mask, scratch);
        }
        if (snapshot == null || !snapshot.areChildrenHidden()) {
            for (GeoBone child : bone.children()) {
                drawBone(child, stack, consumer, light, overlay, color, mask, scratch);
            }
        }
        stack.popPose();
    }

    private static void drawCubes(CuboidGeoBone bone, PoseStack stack, VertexConsumer consumer,
                                  int light, int overlay, int color, EmissiveMask mask, Scratch scratch) {
        for (GeoCube cube : bone.cubes) {
            // 复刻 GeoCube#render：绕 pivot 位移 → 旋转 → 移回来，然后逐 quad 出顶点
            stack.pushPose();
            cube.translateToPivotPoint(stack);
            cube.rotate(stack);
            cube.translateAwayFromPivotPoint(stack);
            Matrix4f matrix = stack.last().pose();
            Matrix3f normalMatrix = stack.last().normal();
            GeoQuad[] quads = cube.quads();
            for (GeoQuad quad : quads) {
                if (quad == null) {
                    continue;
                }
                if (mask != null && !mask.isEmissive(quad)) {
                    continue;
                }
                Vector3f normal = scratch.normal.set(quad.normalX(), quad.normalY(), quad.normalZ());
                normalMatrix.transform(normal);
                RenderUtil.fixInvertedFlatCube(cube, normal);
                emitQuad(matrix, normal, consumer, quad, light, overlay, color, scratch);
            }
            stack.popPose();
        }
    }

    /** 复刻 {@code GeoQuad#render}，但不分配（原版每顶点 new 一个 Vector4f）。 */
    private static void emitQuad(Matrix4f matrix, Vector3f normal, VertexConsumer consumer, GeoQuad quad,
                                 int light, int overlay, int color, Scratch scratch) {
        Vector4f vertex = scratch.vertex;
        for (GeoVertex point : quad.vertices()) {
            vertex.set(point.posX(), point.posY(), point.posZ(), 1.0F);
            matrix.transform(vertex);
            consumer.addVertex(vertex.x, vertex.y, vertex.z, color,
                    point.texU(), point.texV(), overlay, light, normal.x, normal.y, normal.z);
        }
    }

    /** 渲染线程独占的复用缓冲。 */
    private static final class Scratch {
        private PoseStack stack = new PoseStack();
        private final Vector3f normal = new Vector3f();
        private final Vector4f vertex = new Vector4f();
    }
}
