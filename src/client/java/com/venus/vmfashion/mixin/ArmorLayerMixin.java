package com.venus.vmfashion.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import com.venus.vmfashion.render.HiddenParts;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code hideParts} 覆盖盔甲层。
 *
 * <p>{@code HumanoidArmorLayer} 用的是 {@code ArmorModelSet} 里自己的模型，
 * <strong>从不看父模型 {@code ModelPart.visible}</strong>，所以整套时装（{@code hideParts:["all"]}）
 * 会让盔甲浮在空中。这里按装备槽在 {@code renderArmorPiece} 的 HEAD 跳过。
 *
 * <p>目标方法 descriptor（javap 确认）：
 * {@code renderArmorPiece(PoseStack, SubmitNodeCollector, ItemStack, EquipmentSlot, int, HumanoidRenderState)V}
 * —— 泛型 {@code S} 的擦除边界是 {@code HumanoidRenderState}。
 */
@Mixin(HumanoidArmorLayer.class)
public class ArmorLayerMixin {

    @Inject(
            method = "renderArmorPiece(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;ILnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void vmfashion$hideArmor(PoseStack poseStack, SubmitNodeCollector collector, ItemStack stack,
                                     EquipmentSlot slot, int light, HumanoidRenderState state, CallbackInfo ci) {
        int mask = CosmeticRenderDispatcher.hiddenParts(state);
        if (mask == HiddenParts.NONE) {
            return;
        }
        boolean hide = switch (slot) {
            case HEAD -> HiddenParts.hidden(mask, HiddenParts.HEAD);
            case CHEST, BODY -> HiddenParts.hidden(mask, HiddenParts.BODY);
            // 护腿和靴子是"一整块网格盖住双腿"：任意一条腿被隐藏就整块藏掉，
            // 否则会剩下半截浮空的裤腿（比整块藏掉更难看）
            case LEGS, FEET -> HiddenParts.hidden(mask, HiddenParts.RIGHT_LEG)
                    || HiddenParts.hidden(mask, HiddenParts.LEFT_LEG);
            default -> false;
        };
        if (hide) {
            ci.cancel();
        }
    }
}
