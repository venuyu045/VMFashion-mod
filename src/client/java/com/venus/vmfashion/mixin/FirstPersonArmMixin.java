package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.FirstPersonRenderer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 第一人称手臂上的时装。
 *
 * <p>挂点选 {@code renderPlayerArm} 而不是 {@code renderRightHand/renderLeftHand}：
 * <ul>
 *   <li>它有 {@link PlayerRenderState}，能拿到 {@code avatarRenderState}（UUID / hideParts 掩码都在上面），
 *       而 {@code renderHand} 只拿到一个 {@code ModelPart}，拿不到 UUID；</li>
 *   <li>它的 HEAD 处 PoseStack 正好是"部件变换的父空间"（部件 pivot 由
 *       {@code submitModelPart} 内部施加），和第三人称 {@code CapeLayer#submit} 的角色一致，
 *       所以坐标系换算可以直接复用；</li>
 *   <li>整个方法就是"摆好手臂 → 画手臂"，在这里 {@code cancel()} 可以干净地跳过原版手臂
 *       （hideParts 命中时），而 {@code renderHand} 里 {@code visible} 被强制设成 true，
 *       没法用 visible 隐藏。</li>
 * </ul>
 *
 * <p>目标方法 descriptor（javap 确认，private）：
 * {@code renderPlayerArm(PoseStack, SubmitNodeCollector, int, float, float, HumanoidArm, PlayerRenderState)V}
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public class FirstPersonArmMixin {

    @Inject(
            method = "renderPlayerArm(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;IFFLnet/minecraft/world/entity/HumanoidArm;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$submitFirstPersonCosmetics(PoseStack poseStack, SubmitNodeCollector collector,
                                                      int packedLight, float equipProgress, float swingProgress,
                                                      HumanoidArm arm, PlayerRenderState playerState,
                                                      CallbackInfo ci) {
        if (FirstPersonRenderer.submit(poseStack, collector, packedLight, arm, playerState)) {
            // hideParts 命中这只手：时装已经提交完了，这里把原版手臂整个跳过
            ci.cancel();
        }
    }
}
