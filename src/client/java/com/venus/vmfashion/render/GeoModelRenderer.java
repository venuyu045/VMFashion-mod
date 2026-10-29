package com.venus.vmfashion.render;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.animation.state.BoneSnapshot;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.render.anim.AnimationFrame;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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

    /**
     * 渲染时抛过异常的模型（按实例身份）。
     *
     * <p>用 {@link LinkedHashSet} 而不是 {@code IdentityHashMap} 的 set：需要"插入顺序"来淘汰最早的条目。
     * {@link BakedGeoModel} 没有重写 {@code equals}/{@code hashCode}，所以这里就是身份语义。
     * 渲染线程独占，不需要并发容器。
     */
    private static final Set<BakedGeoModel> BROKEN = new LinkedHashSet<>();
    /** 拉黑表上限：坏模型再多也不该让集合无限长、或永久持有旧模型。 */
    private static final int MAX_BROKEN = 256;
    /** 最多打多少条"模型坏了"的日志，避免一堆坏模型把日志刷爆（之后只打一条汇总）。 */
    private static final int MAX_BROKEN_LOGS = 16;
    private static int brokenLogs;
    private static boolean brokenOverflowLogged;

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
        draw(model, pose, consumer, light, overlay, color, null, null);
    }

    /**
     * 带骨骼动画的渲染。
     *
     * <p>{@code frame} 为 {@code null} 时等价于静态渲染。动画求值在**回调内**进行
     * （{@link AnimationFrame#update()}），因为 26.x 是延迟提交，submit 阶段算出来的值到 flush 时已经过时。
     */
    public static void render(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                              int light, int overlay, int color, AnimationFrame frame) {
        draw(model, pose, consumer, light, overlay, color, null, frame);
    }

    /**
     * 只渲染 {@code mask} 命中的四边形（emissive 层）。
     *
     * <p>遮罩为 {@code null} 或空时<strong>什么都不画</strong>——调用方本来就不该提交这一趟，
     * 这里再兜一层，保证不会退化成"整块模型发光"。
     */
    public static void renderEmissive(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                                      int light, int overlay, int color, EmissiveMask mask) {
        renderEmissive(model, pose, consumer, light, overlay, color, mask, null);
    }

    /** 带骨骼动画的 emissive 渲染。 */
    public static void renderEmissive(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                                      int light, int overlay, int color, EmissiveMask mask,
                                      AnimationFrame frame) {
        if (mask == null || mask.isEmpty()) {
            return;
        }
        draw(model, pose, consumer, light, overlay, color, mask, frame);
    }

    private static void draw(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                             int light, int overlay, int color, EmissiveMask mask, AnimationFrame frame) {
        if (model == null) {
            return;
        }
        // 渲染回调里抛异常 = 客户端崩溃（26.x 的 flush 在主循环里，没有谁替我们兜）。
        // 一个坏模型（缺骨骼 / 空立方体 / NaN 顶点 / 层级过深爆栈）只应该"这一件不画"，
        // 不该让玩家掉线 —— 陌生人装模组时最怕的就是"某个人的时装让我崩游戏"。
        if (BROKEN.contains(model)) {
            return;
        }
        try {
            drawModel(model, pose, consumer, light, overlay, color, mask, frame);
        } catch (Throwable t) {
            markBroken(model, t);
        }
    }

    /**
     * 把渲染失败的模型拉黑（按实例身份，渲染线程独占）。
     *
     * <p>用身份而不是 ID：{@code /vmfashion reload} 会重新烘焙出新的
     * {@link BakedGeoModel} 实例，新实例不在集合里 → 自动重试一次，玩家改好包就能自愈，
     * 不需要额外的清理钩子。
     *
     * <p>表满时<strong>淘汰最早的一条</strong>（而不是整表清空）：整表清空会让所有坏模型立刻
     * 重试一遍，一帧里连崩 N 次；淘汰最早的只影响一件，且内存仍然有界。
     */
    private static void markBroken(BakedGeoModel model, Throwable error) {
        if (BROKEN.size() >= MAX_BROKEN) {
            Iterator<BakedGeoModel> oldest = BROKEN.iterator();
            if (oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }
        BROKEN.add(model);
        if (brokenLogs < MAX_BROKEN_LOGS) {
            brokenLogs++;
            // 带 identityHashCode：日志里能和 CosmeticAssets 的"烘焙失败"对上号，
            // 玩家报"某件时装不显示"时至少有线索可查
            VMFashionClient.LOGGER.warn("时装模型渲染失败，已跳过该模型（第 {} 个，identity={}，reload 后会重试）: {}",
                    brokenLogs, Integer.toHexString(System.identityHashCode(model)), error.toString());
            if (brokenLogs == MAX_BROKEN_LOGS && !brokenOverflowLogged) {
                brokenOverflowLogged = true;
                VMFashionClient.LOGGER.warn("时装模型渲染失败日志已达上限 {} 条，后续只跳过不再记录；"
                        + "执行 /vmfashion reload 可重试全部模型", MAX_BROKEN_LOGS);
            }
        }
    }

    private static void drawModel(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                                  int light, int overlay, int color, EmissiveMask mask, AnimationFrame frame) {
        // 动画在这里求值（而不是 submit 阶段）：时间最接近真实渲染，也避免"提交时算好、flush 时已过时"
        Map<GeoBone, BoneSnapshot> animated = null;
        if (frame != null) {
            frame.update();
            animated = frame.snapshots();
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
            drawBone(bone, stack, consumer, light, overlay, color, mask, animated, scratch);
        }
    }

    private static void drawBone(GeoBone bone, PoseStack stack, VertexConsumer consumer,
                                 int light, int overlay, int color, EmissiveMask mask,
                                 Map<GeoBone, BoneSnapshot> animated, Scratch scratch) {
        // 动画快照是"临时挂到共享骨骼上"的：画完必须还原，否则会泄漏到下一个玩家身上
        BoneSnapshot previous = bone.frameSnapshot;
        if (animated != null) {
            bone.frameSnapshot = animated.get(bone);
        }
        BoneSnapshot snapshot = bone.frameSnapshot;
        // 骨骼矩阵无条件生效：GeckoLib 的 isHidden 只隐藏"自己的立方体"，
        // 子骨骼另由 areChildrenHidden 控制（两套语义，见路线图 §3.7）
        stack.pushPose();
        try {
            RenderUtil.prepMatrixForBone(stack, bone);
            if ((snapshot == null || !snapshot.isHidden()) && bone instanceof CuboidGeoBone cuboid) {
                drawCubes(cuboid, stack, consumer, light, overlay, color, mask, scratch);
            }
            if (snapshot == null || !snapshot.areChildrenHidden()) {
                for (GeoBone child : bone.children()) {
                    drawBone(child, stack, consumer, light, overlay, color, mask, animated, scratch);
                }
            }
        } finally {
            stack.popPose();
            if (animated != null) {
                bone.frameSnapshot = previous;
            }
        }
    }

    private static void drawCubes(CuboidGeoBone bone, PoseStack stack, VertexConsumer consumer,
                                  int light, int overlay, int color, EmissiveMask mask, Scratch scratch) {
        GeoCube[] cubes = bone.cubes;
        if (cubes == null) {
            // 防御式：空骨骼（只用来挂子骨骼）在个别导出器里 cubes 可能是 null，
            // 让这一个骨骼不画即可，不该连累整个模型
            return;
        }
        for (GeoCube cube : cubes) {
            if (cube == null) {
                continue;
            }
            // 复刻 GeoCube#render：绕 pivot 位移 → 旋转 → 移回来，然后逐 quad 出顶点
            stack.pushPose();
            cube.translateToPivotPoint(stack);
            cube.rotate(stack);
            cube.translateAwayFromPivotPoint(stack);
            Matrix4f matrix = stack.last().pose();
            Matrix3f normalMatrix = stack.last().normal();
            GeoQuad[] quads = cube.quads();
            if (quads != null) {
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
            }
            stack.popPose();
        }
    }

    /** 复刻 {@code GeoQuad#render}，但不分配（原版每顶点 new 一个 Vector4f）。 */
    private static void emitQuad(Matrix4f matrix, Vector3f normal, VertexConsumer consumer, GeoQuad quad,
                                 int light, int overlay, int color, Scratch scratch) {
        GeoVertex[] points = quad.vertices();
        if (points == null) {
            return;
        }
        Vector4f vertex = scratch.vertex;
        for (GeoVertex point : points) {
            if (point == null) {
                continue;
            }
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
