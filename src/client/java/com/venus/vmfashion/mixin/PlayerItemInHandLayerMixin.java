package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts} 覆盖手持物。
 *
 * <p>玩家手臂被隐藏后，手里的剑/盾/火把仍然会画在原位（{@code PlayerItemInHandLayer} 用
 * 自己的 {@code ArmedModel} 定位），于是看起来像"空气举着剑"。这里在
 * {@code submitArmWithItem} 的 HEAD 按<strong>具体哪只手</strong>跳过。
 *
 * <p>目标方法 descriptor（javap 确认，注意有两份同名重载，必须带完整 descriptor 才能命中
 * 参数是 {@code AvatarRenderState} 的那个实现，另一份是 {@code ArmedEntityRenderState} 桥接）：
 * {@code submitArmWithItem(AvatarRenderState, ItemStackRenderState, ItemStack, HumanoidArm, PoseStack, SubmitNodeCollector, int)V}
 */
@Mixin(PlayerItemInHandLayer.class)
public class PlayerItemInHandLayerMixin {

    @Inject(
            method = "submitArmWithItem(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideHeldItem(AvatarRenderState state, ItemStackRenderState itemState, ItemStack stack,
                                        HumanoidArm arm, PoseStack poseStack, SubmitNodeCollector collector,
                                        int light, CallbackInfo ci) {
        int mask = CosmeticRenderDispatcher.hiddenParts(state);
        if (mask == HiddenParts.NONE) {
            return;
        }
        int part = arm == HumanoidArm.RIGHT ? HiddenParts.RIGHT_ARM : HiddenParts.LEFT_ARM;
        if (HiddenParts.hidden(mask, part)) {
            ci.cancel();
        }
    }
}
