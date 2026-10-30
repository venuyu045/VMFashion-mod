package com.venus.vmfashion.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.cosmetic.CosmeticDefinition;
import com.venus.vmfashion.loadout.LoadoutService;
import com.venus.vmfashion.loadout.WornCosmetic;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;

import java.util.Map;
import java.util.UUID;

/**
 * 第一人称：把挂在手臂上的时装画到"相机前的那只手上"。
 *
 * <p><b>为什么需要单独的入口</b>：第三人称的挂点是 {@code CapeLayer#submit}（一个渲染层），
 * 而第一人称走的是
 * {@code FirstPersonHandsAndItemsRenderer#renderPlayerArm} →
 * {@code renderPlayerHand} → {@code AvatarRenderer#renderRightHand/renderLeftHand} →
 * {@code renderHand} → {@code submitModelPart(手臂部件)}，
 * <strong>完全不经过任何 RenderLayer</strong>，所以原来的挂点不会触发。
 * 由 {@code FirstPersonArmMixin} 在 {@code renderPlayerArm} 的 HEAD 调进来。
 *
 * <p><b>为什么坐标系可以复用第三人称的换算</b>（路线图 §3.2 担心"手臂部件空间"要重算）：
 * 核对 26.3 字节码后确认，{@code renderPlayerArm} 拿到的 PoseStack 仍然是
 * <strong>原版模型部件空间</strong>（部件自己的 pivot 是在
 * {@code submitModelPart → Model$Simple#renderToBuffer → ModelPart#render} 里施加的），
 * 和第三人称 {@code CapeLayer#submit} 里的 PoseStack <em>扮演同一个角色</em>——两者都只是
 * "部件变换的父空间"，区别仅在于前面叠的相机变换不同（这部分对我们透明）。
 * 于是 {@code applyModelSpace}（E→模型空间）+ {@code applyAttachment}（绕 pivot）可以直接复用。
 * 这个结论做了无显卡的数值自检（和 {@code tools/verify} 同一套思路的临时程序）：
 * 同一组手臂顶点经"原版 {@code ModelPart#translateAndRotate}"与"我们的换算"必须落到同一点。
 *
 * <p><b>只接受手臂挂件</b>：{@code attachedBone ∈ {right_arm, left_arm}}，且必须和当前这只手匹配。
 * 头/身挂件在第一人称会被相机包住（相机就在头里），画出来只会糊在屏幕上。
 *
 * <p><b>和 hideParts 的竞争</b>：{@code renderHand} 会 {@code resetPose()} 并把
 * {@code visible} 强制设成 true，所以 hideParts 在第三人称写下的 {@code visible=false}
 * 在这里会被原版覆盖，<strong>不能靠 visible 隐藏</strong>。这里的做法是：
 * 由 {@link #submit} 返回"这只手要不要跳过"，mixin 据此 {@code ci.cancel()} 整个
 * {@code renderPlayerArm}——但<strong>先</strong>把时装提交完，所以"机械臂"这类
 * {@code hideParts:["right_arm"]} + {@code attachedBone:"right_arm"} 的时装在第一人称仍然可见。
 *
 * <p><b>原版限制</b>：{@code submitArmWithItem} 只在<strong>空手</strong>时才调
 * {@code renderPlayerArm}（手里有物品时只画物品，不画手臂——javap 核对字节码：唯一的
 * {@code renderPlayerArm} 调用点在 {@code ItemStack.isEmpty()} 分支里）。所以手臂时装在第一人称
 * 也只在空手时可见，这是原版行为，不是本模组的取舍。
 *
 * <p><b>已知残留风险（共享模型实例）</b>：26.x 的 {@code submitModel} 是延迟提交
 * （{@code SubmitNodeCollection#submitModel} 存的是 {@code Pose.copy() + Model + state}），
 * 到 flush 时 {@code ModelFeatureRenderer#prepareModel} 会先 {@code model.setupAnim(state)}
 * 再 {@code renderToBuffer}。第三/第一人称共用同一个 {@code PlayerModel} 实例，而
 * {@code submitModelPart} 包出来的 {@code Model.Simple#setupAnim} 是空实现，所以第一人称手臂
 * 在 flush 时读的是<strong>共享部件最后一次被写入的状态</strong>。正常情况没问题
 * （{@code renderHand} 提交前设了 visible=true），但如果同屏有别的玩家穿着
 * {@code hideParts} 含 right_arm/left_arm 的时装，他的 {@code setupAnim} 会在 flush 时把
 * 共享的 {@code rightArm.visible} 置 false，理论上可能连累本地第一人称手臂。
 * 彻底修需要把 hideParts 从"写 {@code ModelPart.visible}"改成"在 {@code ModelPart#render}
 * 里按渲染状态判定"，属于下一轮的架构改动，本轮先记录。
 */
public final class FirstPersonRenderer {

    /**
     * 第一人称手臂静止姿态的 Z 旋转。
     *
     * <p>{@code AvatarRenderer#renderHand} 内部会把两只手臂设成这个值
     * （javap 核对：{@code leftArm.zRot = -0.1f; rightArm.zRot = 0.1f;}），
     * 目的是让手臂略微内收。我们必须用同一个值算挂载矩阵，否则时装和手臂会差约 5.7°。
     */
    private static final float ARM_Z_ROT = 0.1F;

    private FirstPersonRenderer() {
    }

    /**
     * 提交第一人称手臂上的时装。
     *
     * @return {@code true} 表示要把原版手臂整个跳过（hideParts 命中这只手）
     */
    public static boolean submit(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                 HumanoidArm arm, PlayerRenderState playerState) {
        AvatarRenderState state = playerState == null ? null : playerState.avatarRenderState;
        if (state == null || arm == null) {
            return false;
        }
        // 和第三人称共用同一套判定（配置 / 隐身 / 渲染他人 / 距离），外加"必须是本地玩家"
        if (!CosmeticRenderDispatcher.shouldRenderFirstPerson(state)) {
            return false;
        }
        UUID uuid = state instanceof FashionRenderState fashion ? fashion.vmfashion$getUuid() : null;
        if (uuid == null) {
            return false;
        }
        AvatarRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(state);
        if (renderer == null) {
            return false;
        }
        PlayerModel model = renderer.getModel();
        if (model == null) {
            return false;
        }
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        // 把手臂摆成"第一人称静止姿态"：renderHand 稍后还会做一次同样的操作（幂等），
        // 但 applyAttachment 要读 pivot / 旋转，而 resetPose() 发生在 renderPlayerArm 之后，
        // 所以必须在这里先做（否则会用到第三人称动画留下的旋转）。
        part.resetPose();
        part.zRot = arm == HumanoidArm.RIGHT ? ARM_Z_ROT : -ARM_Z_ROT;

        String bone = arm == HumanoidArm.RIGHT ? "right_arm" : "left_arm";
        Map<String, WornCosmetic> loadout = RenderCache.loadout(uuid);
        if (!loadout.isEmpty()) {
            LoadoutService loadouts = LoadoutService.getInstance();
            float globalScale = CosmeticRenderDispatcher.globalScale();
            for (WornCosmetic worn : loadout.values()) {
                // 防御式：穿戴表来自磁盘 / 后端，理论上不该有 null 值，但一条脏数据
                // 不能把渲染线程搞崩（这里每帧每只手都会跑）
                if (worn == null) {
                    continue;
                }
                CosmeticDefinition definition = loadouts.resolve(worn.cosmeticId());
                if (definition == null || !isArmBone(definition.attachedBone(), bone)) {
                    continue;
                }
                CosmeticAssets.Entry entry = CosmeticAssets.get(definition);
                if (entry == null) {
                    continue;
                }
                CosmeticRenderDispatcher.submitOne(poseStack, collector, packedLight,
                        OverlayTexture.NO_OVERLAY, uuid,
                        definition, entry, worn.scale(), model, globalScale);
            }
        }
        // hideParts：命中这只手就把原版手臂整个跳过（时装已经提交完了）。
        // 判定复用 hiddenMaskFor，所以"配置关掉 / 不在渲染距离 / 资源没加载成功"时
        // 不会出现"手臂没了、时装也没有"的隐形人。
        int mask = CosmeticRenderDispatcher.hiddenMaskFor(state);
        int armPart = arm == HumanoidArm.RIGHT ? HiddenParts.RIGHT_ARM : HiddenParts.LEFT_ARM;
        return HiddenParts.hidden(mask, armPart);
    }

    /** 该挂载骨骼是否就是这只手（大小写不敏感，兼容 {@code rightArm} 写法）。 */
    private static boolean isArmBone(String attachedBone, String expected) {
        if (attachedBone == null) {
            return false;
        }
        if (attachedBone.equalsIgnoreCase(expected)) {
            return true;
        }
        // "rightarm" / "right_arm" 两种写法都认
        return expected.equals("right_arm") && attachedBone.equalsIgnoreCase("rightarm")
                || expected.equals("left_arm") && attachedBone.equalsIgnoreCase("leftarm");
    }
}
