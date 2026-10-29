package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts} 覆盖鞘翅 / 翅膀。
 *
 * <p>{@code WingsLayer} 用自己的 {@code ElytraModel}，位置来自
 * {@code HumanoidModel} 的 body 变换而不是父模型的 visible，所以隐藏躯干后翅膀会浮着。
 *
 * <p>目标方法 descriptor（javap 确认，泛型 {@code S} 的擦除边界是 {@code HumanoidRenderState}）：
 * {@code submit(PoseStack, SubmitNodeCollector, int, HumanoidRenderState, float, float)V}
 */
@Mixin(WingsLayer.class)
public class WingsLayerMixin {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideWings(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                     HumanoidRenderState state, float limbSwing, float limbSwingAmount,
                                     CallbackInfo ci) {
        if (HiddenParts.hidden(CosmeticRenderDispatcher.hiddenParts(state), HiddenParts.BODY)) {
            ci.cancel();
        }
    }
}
