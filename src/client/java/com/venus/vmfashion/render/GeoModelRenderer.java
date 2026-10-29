package com.venus.vmfashion.render;

import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.animation.state.BoneSnapshot;
import com.geckolib.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

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
 *     bone.render(null, pose, vc, ...);                   // 立方体
 *     bone.renderChildren(null, pose, vc, ...);           // 子骨骼
 *     pop();
 * }
 * </pre>
 *
 * <p>{@code CuboidGeoBone#render} 内部完全不解引用 RenderPassInfo（已用 javap 确认），
 * 传 {@code null} 是安全的；{@code RenderUtil#prepMatrixForBone} 本身也允许 null。
 */
public final class GeoModelRenderer {

    private GeoModelRenderer() {
    }

    /**
     * 在给定矩阵下渲染整个模型。
     *
     * @param pose   {@code SubmitNodeCollector.CustomGeometryRenderer} 回调里拿到的姿态
     * @param light  打包光照
     * @param overlay 打包 overlay（通常是 {@code OverlayTexture.NO_OVERLAY}）
     * @param color  ARGB 颜色
     */
    public static void render(BakedGeoModel model, PoseStack.Pose pose, VertexConsumer consumer,
                              int light, int overlay, int color) {
        if (model == null) {
            return;
        }
        // 提交式渲染里回调拿到的是一份"快照"，顶点必须自己乘上这个矩阵
        PoseStack stack = new PoseStack();
        stack.last().set(pose);
        for (GeoBone bone : model.topLevelBones()) {
            renderBone(bone, stack, consumer, light, overlay, color);
        }
    }

    private static void renderBone(GeoBone bone, PoseStack stack, VertexConsumer consumer,
                                   int light, int overlay, int color) {
        BoneSnapshot snapshot = bone.frameSnapshot;
        if (snapshot != null && snapshot.isHidden()) {
            return;
        }
        stack.pushPose();
        RenderUtil.prepMatrixForBone(stack, bone);
        bone.render(null, stack, consumer, light, overlay, color);
        bone.renderChildren(null, stack, consumer, light, overlay, color);
        stack.popPose();
    }
}
