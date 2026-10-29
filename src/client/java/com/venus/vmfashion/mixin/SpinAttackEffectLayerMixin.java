package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.SpinAttackEffectLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts:["all"]} 时跳过三叉戟旋转攻击特效。
 *
 * <p>它是一圈绕身体的旋风模型，和玩家模型没有父子关系；整套时装隐藏时留着它很突兀。
 *
 * <p>目标方法 descriptor（javap 确认）：
 * {@code submit(PoseStack, SubmitNodeCollector, int, AvatarRenderState, float, float)V}
 */
@Mixin(SpinAttackEffectLayer.class)
public class SpinAttackEffectLayerMixin {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideSpinAttack(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                          AvatarRenderState state, float limbSwing, float limbSwingAmount,
                                          CallbackInfo ci) {
        if (HiddenParts.hidden(CosmeticRenderDispatcher.hiddenParts(state), HiddenParts.ALL)) {
            ci.cancel();
        }
    }
}
