package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.StuckInBodyLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts:["all"]} 时跳过插在身上的箭 / 蜂刺。
 *
 * <p>{@code ArrowLayer} 和 {@code BeeStingerLayer} 都继承 {@link StuckInBodyLayer} 的
 * {@code submit}，且各自用自己的模型，所以挂在这个基类上一次覆盖两层。
 *
 * <p>目标方法 descriptor（javap 确认）：{@code submit(PoseStack, SubmitNodeCollector, int, AvatarRenderState, float, float)V}。
 * 该类是 {@code RenderLayer<AvatarRenderState, M>}，所以状态类型是 {@code AvatarRenderState}
 * （同一个类里还有一个 {@code EntityRenderState} 的桥接方法，不能命中那个）。
 */
@Mixin(StuckInBodyLayer.class)
public class StuckInBodyLayerMixin {

    @Inject(
            method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideStuckItems(PoseStack poseStack, SubmitNodeCollector collector, int light,
                                          AvatarRenderState state, float limbSwing, float limbSwingAmount,
                                          CallbackInfo ci) {
        // 只有整套隐藏（all）才需要处理：箭扎在身体各处，局部隐藏时无法判断该不该留
        if (HiddenParts.hidden(CosmeticRenderDispatcher.hiddenParts(state), HiddenParts.ALL)) {
            ci.cancel();
        }
    }
}
