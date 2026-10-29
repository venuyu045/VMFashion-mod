package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ParrotOnShoulderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts} 覆盖肩膀上的鹦鹉。
 *
 * <p>鹦鹉用自己的 {@code ParrotModel}，位置直接算在肩膀上方；隐藏躯干/手臂后鹦鹉会浮着。
 * 归到 {@link HiddenParts#BODY}：鹦鹉是"站在身上"的装饰，躯干没了就没有立足点。
 *
 * <p>目标方法 descriptor（javap 确认）：
 * {@code submit(PoseStack, SubmitNodeCollector, int, AvatarRenderState, float, float)V}
 */
@Mixin(ParrotOnShoulderLayer.class)
public class ParrotOnShoulderLayerMixin {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideParrot(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                      AvatarRenderState state, float limbSwing, float limbSwingAmount,
                                      CallbackInfo ci) {
        if (HiddenParts.hidden(CosmeticRenderDispatcher.hiddenParts(state), HiddenParts.BODY)) {
            ci.cancel();
        }
    }
}
