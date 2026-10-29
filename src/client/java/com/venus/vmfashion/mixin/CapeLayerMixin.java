package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 时装渲染的实际挂载点。
 *
 * <p>选 {@code CapeLayer#submit} 的原因：它是玩家渲染器里固定存在的一层，被调用时
 * {@link PoseStack} 已经处在"玩家模型空间"（setupRotations + scale(-1,-1,1) + translate(0,-1.501,0)
 * 之后），和原版 {@code PlayerModel} 的坐标完全一致，所以不需要自己复刻这套变换。
 *
 * <p>注入点用 HEAD（不是 TAIL）：TAIL 只在方法走到最后才执行，而披风层在没有披风时会提前 return。
 */
@Mixin(CapeLayer.class)
public class CapeLayerMixin {

    @SuppressWarnings("unchecked")
    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD")
    )
    private void vmfashion$submitCosmetics(PoseStack poseStack, SubmitNodeCollector collector, int packedLight,
                                           AvatarRenderState state, float limbSwing, float limbSwingAmount,
                                           CallbackInfo ci) {
        RenderLayer<AvatarRenderState, PlayerModel> self =
                (RenderLayer<AvatarRenderState, PlayerModel>) (Object) this;
        CosmeticRenderDispatcher.submit(poseStack, collector, packedLight, state, self.getParentModel());
    }
}
