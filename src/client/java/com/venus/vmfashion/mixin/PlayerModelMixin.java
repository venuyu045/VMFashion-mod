package com.venus.vmfashion.mixin;

import com.venus.vmfashion.render.CosmeticRenderDispatcher;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 处理 {@code hideParts}：穿上某些时装后隐藏原版部件（头/身体/手脚）。
 *
 * <p>注入 {@code PlayerModel#setupAnim} 的 TAIL：原版每帧都会先按渲染状态重设 visible，
 * 我们最后覆盖，所以脱下时装后下一帧就自动恢复，不会"粘住"。
 */
@Mixin(PlayerModel.class)
public class PlayerModelMixin {

    @Inject(
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
            at = @At("TAIL")
    )
    private void vmfashion$applyHideParts(AvatarRenderState state, CallbackInfo ci) {
        CosmeticRenderDispatcher.applyHideParts((PlayerModel) (Object) this, state);
    }
}
