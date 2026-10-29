package com.venus.vmfashion.mixin;

import com.venus.vmfashion.render.FashionRenderState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在提取渲染状态时把实体 UUID 写进 {@link AvatarRenderState}。
 *
 * <p>26.x 的渲染状态里只有 {@code int id}（会复用、跨维度会撞），拿它查穿戴会串号，
 * 所以这里补一个 UUID。注入点用 RETURN，保证原版所有字段都已填好。
 *
 * <p>目标方法签名（javap 确认）：{@code extractRenderState(Avatar, AvatarRenderState, float)V}。
 */
@Mixin(AvatarRenderer.class)
public class AvatarRendererMixin {

    @Inject(
            method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
            at = @At("RETURN")
    )
    private void vmfashion$captureUuid(Avatar entity, AvatarRenderState state, float partialTick, CallbackInfo ci) {
        if (entity != null && state instanceof FashionRenderState fashion) {
            fashion.vmfashion$setUuid(entity.getUUID());
        }
    }
}
