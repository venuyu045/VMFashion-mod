package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.Deadmau5EarsLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts} 覆盖 Deadmau5 耳朵（用户名 {@code Deadmau5} 的彩蛋层）。
 *
 * <p>它用自己的 {@code HumanoidModel} 画耳朵，不看父模型的 visible，隐藏头部后耳朵会浮着。
 *
 * <p>目标方法 descriptor（javap 确认）：
 * {@code submit(PoseStack, SubmitNodeCollector, int, AvatarRenderState, float, float)V}
 */
@Mixin(Deadmau5EarsLayer.class)
public class Deadmau5EarsLayerMixin {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideEars(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                    AvatarRenderState state, float limbSwing, float limbSwingAmount,
                                    CallbackInfo ci) {
        if (HiddenParts.hidden(CosmeticRenderDispatcher.hiddenParts(state), HiddenParts.HEAD)) {
            ci.cancel();
        }
    }
}
