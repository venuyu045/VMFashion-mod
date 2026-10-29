package com.venus.vmfashion.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.loadout.LoadoutService;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Quaternionf;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 时装渲染入口：由 {@code CapeLayerMixin} 在 {@code CapeLayer#submit} 的 HEAD 调用。
 *
 * <p>此时 {@link PoseStack} 已经处在"原版玩家模型空间"（原点是脖子/身体顶部，Y 轴向下，Z 轴向后），
 * 而 GeckoLib 烘焙出来的模型用的是"脚底原点、Y 轴向上"的空间，所以每次渲染都要先做一次
 * {@link #applyModelSpace(PoseStack)} 换算；{@code attachedBone} 则借用 {@link PlayerModel}
 * 对应 {@link ModelPart} 的 pivot / 旋转，跟着头部、手臂一起动。
 */
public final class CosmeticRenderDispatcher {

    private static final int WHITE = 0xFFFFFFFF;

    private CosmeticRenderDispatcher() {
    }

    /**
     * 往渲染管线提交该玩家身上的所有时装。
     *
     * @param playerModel 父模型（{@code CapeLayer#getParentModel()}），用于 attachedBone
     */
    public static void submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                              AvatarRenderState state, PlayerModel playerModel) {
        ModConfig config = config();
        if (config == null || !config.isEnabled()) {
            return;
        }
        // 隐身时不渲染（和披风/盔甲层保持一致）
        if (state.isInvisible) {
            return;
        }

        UUID uuid = uuidOf(state);
        if (uuid == null) {
            return;
        }

        LoadoutService loadouts = LoadoutService.getInstance();
        if (!loadouts.isLocalPlayer(uuid) && !config.isRenderOtherPlayers()) {
            return;
        }

        double distance = config.getRenderDistance();
        if (distance > 0 && state.distanceToCameraSq > distance * distance) {
            return;
        }

        Map<String, String> loadout = loadouts.loadoutFor(uuid);
        if (loadout.isEmpty()) {
            return;
        }

        float globalScale = config.getScale();
        for (String cosmeticId : loadout.values()) {
            CosmeticDefinition definition = loadouts.resolve(cosmeticId);
            if (definition == null) {
                continue;
            }
            CosmeticAssets.Entry entry = CosmeticAssets.get(definition);
            if (entry == null) {
                continue;
            }
            submitOne(poseStack, collector, packedLight, definition, entry, playerModel, globalScale);
        }
    }

    private static void submitOne(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                  CosmeticDefinition definition, CosmeticAssets.Entry entry,
                                  PlayerModel playerModel, float globalScale) {
        float scale = definition.scale() * globalScale;

        poseStack.pushPose();
        try {
            applyModelSpace(poseStack);
            applyAttachment(poseStack, playerModel, definition.attachedBone());
            if (scale != 1.0F) {
                poseStack.scale(scale, scale, scale);
            }

            collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(entry.texture()),
                    (pose, consumer) -> GeoModelRenderer.render(
                            entry.model(), pose, consumer, packedLight, OverlayTexture.NO_OVERLAY, WHITE));

            if (entry.emissive() != null) {
                collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(entry.emissive()),
                        (pose, consumer) -> GeoModelRenderer.render(
                                entry.model(), pose, consumer, packedLight, OverlayTexture.NO_OVERLAY, WHITE));
            }
        } finally {
            poseStack.popPose();
        }
    }

    /**
     * 把"Bedrock / GeckoLib 模型空间"换成"原版模型空间"。
     *
     * <p>两套坐标系的差别：
     * <ul>
     *   <li>GeckoLib 烘焙出来的模型用世界空间语义：原点在<strong>脚底</strong>，<strong>Y 轴向上</strong>
     *       （Blockbench 的 Bedrock 几何就是这么画的，加载器已经帮我们镜像了 X）；</li>
     *   <li>{@code CapeLayer#submit} 里拿到的 PoseStack 是原版玩家模型空间：原点在
     *       <strong>脖子（脚上方 1.501 格）</strong>，<strong>Y 轴向下</strong>。</li>
     * </ul>
     * 所以先上移 1.501 格、再把 X/Y 一起翻转，之后坐标就和 GeckoLib 的模型完全一致了
     * （X 也要翻：GeckoLib 加载时已经把 JSON 的 X 取反过一次，这里必须翻回来，
     * 否则模型会左右镜像——左脚的东西长到右脚）。
     */
    private static void applyModelSpace(PoseStack poseStack) {
        poseStack.translate(0.0F, 1.501F, 0.0F);
        poseStack.scale(-1.0F, -1.0F, 1.0F);
    }

    /**
     * 按 attachedBone 把模型挂到原版模型的对应部位上。
     *
     * <p>pivot 和旋转都从原版 {@link ModelPart} 取，但要换算到上面说的"脚底原点 / Y 向上"坐标系，
     * 这样转头、摆手时时装才会跟着动。
     */
    private static void applyAttachment(PoseStack poseStack, PlayerModel model, String bone) {
        if (model == null || bone == null) {
            return;
        }
        ModelPart part = switch (bone.toLowerCase(java.util.Locale.ROOT)) {
            case "head", "hat", "helmet" -> model.head;
            case "body", "torso", "back", "cape", "wings" -> model.body;
            case "right_arm", "rightarm" -> model.rightArm;
            case "left_arm", "leftarm" -> model.leftArm;
            case "right_leg", "rightleg" -> model.rightLeg;
            case "left_leg", "leftleg" -> model.leftLeg;
            default -> model.body;
        };
        // 换算到"脚底原点 / Y 向上"坐标系：pivot 的 x、y 都取反（x 还要再上移 1.501），
        // 旋转同理——X/Y 轴角度取反，Z 轴不变（这是 F·R·F 共轭，F = scale(-1,-1,1)）
        float px = -part.x / 16.0F;
        float py = 1.501F - part.y / 16.0F;
        float pz = part.z / 16.0F;
        // 绕骨骼 pivot 旋转：先移到 pivot，旋转/缩放，再移回来，
        // 这样模型仍然用"脚底原点"的坐标书写（和 Blockbench 里看到的一致）
        poseStack.translate(px, py, pz);
        if (part.xRot != 0.0F || part.yRot != 0.0F || part.zRot != 0.0F) {
            poseStack.rotate(new Quaternionf().rotationZYX(part.zRot, -part.yRot, -part.xRot));
        }
        if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F) {
            poseStack.scale(part.xScale, part.yScale, part.zScale);
        }
        poseStack.translate(-px, -py, -pz);
    }

    /**
     * 隐藏原版部件（{@code hideParts}）。
     *
     * <p>由 {@code PlayerModelMixin} 在 {@code PlayerModel#setupAnim} 的 TAIL 调用，
     * 因为原版每次 setupAnim 都会重设 visible，所以每帧都会重新覆盖，脱下时装后自动恢复。
     */
    public static void applyHideParts(PlayerModel model, AvatarRenderState state) {
        ModConfig config = config();
        if (config == null || !config.isEnabled()) {
            return;
        }
        UUID uuid = uuidOf(state);
        if (uuid == null) {
            return;
        }
        LoadoutService loadouts = LoadoutService.getInstance();
        // 只在"这件时装真的会被渲染"的前提下隐藏原版部件：
        // 否则关掉渲染他人 / 超出渲染距离时，别人会莫名其妙变成隐形人。
        if (!loadouts.isLocalPlayer(uuid) && !config.isRenderOtherPlayers()) {
            return;
        }
        double distance = config.getRenderDistance();
        if (distance > 0 && state.distanceToCameraSq > distance * distance) {
            return;
        }
        // 只有模型/贴图真的加载成功的时装才参与 hideParts；否则资源损坏时
        // 玩家会变成"隐形人"（连原版皮肤都看不到）。
        Set<String> hidden = new java.util.LinkedHashSet<>();
        for (String cosmeticId : loadouts.loadoutFor(uuid).values()) {
            CosmeticDefinition definition = loadouts.resolve(cosmeticId);
            if (definition != null && CosmeticAssets.get(definition) != null) {
                hidden.addAll(definition.hideParts());
            }
        }
        if (hidden.isEmpty()) {
            return;
        }
        if (hidden.contains("all")) {
            hideAll(model);
            return;
        }
        if (hidden.contains("head")) {
            model.head.visible = false;
            model.hat.visible = false;
        }
        if (hidden.contains("body") || hidden.contains("torso")) {
            model.body.visible = false;
            model.jacket.visible = false;
        }
        if (hidden.contains("right_arm") || hidden.contains("rightarm")) {
            model.rightArm.visible = false;
            model.rightSleeve.visible = false;
        }
        if (hidden.contains("left_arm") || hidden.contains("leftarm")) {
            model.leftArm.visible = false;
            model.leftSleeve.visible = false;
        }
        if (hidden.contains("right_leg") || hidden.contains("rightleg")) {
            model.rightLeg.visible = false;
            model.rightPants.visible = false;
        }
        if (hidden.contains("left_leg") || hidden.contains("leftleg")) {
            model.leftLeg.visible = false;
            model.leftPants.visible = false;
        }
    }

    private static void hideAll(PlayerModel model) {
        model.head.visible = false;
        model.hat.visible = false;
        model.body.visible = false;
        model.jacket.visible = false;
        model.rightArm.visible = false;
        model.leftArm.visible = false;
        model.rightSleeve.visible = false;
        model.leftSleeve.visible = false;
        model.rightLeg.visible = false;
        model.leftLeg.visible = false;
        model.rightPants.visible = false;
        model.leftPants.visible = false;
    }

    private static UUID uuidOf(AvatarRenderState state) {
        return state instanceof FashionRenderState fashion ? fashion.vmfashion$getUuid() : null;
    }

    private static ModConfig config() {
        VMFashionClient client = VMFashionClient.getInstance();
        return client == null ? null : client.getConfig();
    }
}
