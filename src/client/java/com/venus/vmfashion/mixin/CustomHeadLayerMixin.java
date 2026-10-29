package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts} 覆盖头戴物（头盔/南瓜/头颅/玩家头颅）。
 *
 * <p>{@code CustomHeadLayer} 自带一套 {@code SkullModelBase}，位置靠
 * {@code CustomHeadLayer.translateToHead} 直接算，完全不看父模型的 visible →
 * 隐藏头部后头戴物会悬在空气里。
 *
 * <p>目标方法 descriptor（javap 确认，泛型 {@code S} 的擦除边界是 {@code LivingEntityRenderState}）：
 * {@code submit(PoseStack, SubmitNodeCollector, int, LivingEntityRenderState, float, float)V}
 */
@Mixin(CustomHeadLayer.class)
public class CustomHeadLayerMixin {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideHeadWear(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                        LivingEntityRenderState state, float limbSwing, float limbSwingAmount,
                                        CallbackInfo ci) {
        if (HiddenParts.hidden(CosmeticRenderDispatcher.hiddenParts(state), HiddenParts.HEAD)) {
            ci.cancel();
        }
    }
}
