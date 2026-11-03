package com.venus.vmfashion.mixin.ping;

import com.venus.vmfashion.ping.gui.PingWheelScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 抑制"选取方块" —— 标点轮盘打开时，鼠标中键不应该顺便触发原版的 {@code pickBlock}。
 *
 * <p>为什么需要这个 mixin：原版中键默认绑定 {@code keyPickItem}（选取方块）。
 * 我们把中键也绑成了标点键，一次按键会**同时**被两个 KeyMapping 消费：
 * 原版在 {@code Minecraft#handleKeybinds} 里先处理 {@code keyPickItem}，
 * 于是"标点"会顺带把准星指着的方块复制到手上，甚至触发创造模式物品栏。
 *
 * <p>为什么不直接换个默认键：用户明确要求中键标点；而换键是玩家自由，
 * 我们只在**轮盘打开的那一帧**抑制选取方块，其它时候中键仍然是原版行为
 * （玩家不按标点键时，中键选取方块完全不受影响）。
 *
 * <p>注入点 {@code Minecraft#pickBlockOrEntity}：26.3 里 {@code handleKeybinds()} 调用的
 * 就是它（{@code javap -p} 核对过方法名）。
 *
 * <p>合订版适配：包名改为 {@code com.venus.vmfashion.mixin.ping}（mixin json 登记为
 * {@code ping.MinecraftPickBlockMixin}），轮盘界面换成 {@link PingWheelScreen}；注入逻辑不变。
 */
@Mixin(Minecraft.class)
public class MinecraftPickBlockMixin {

    @Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
    private void vmfashion$skipPickBlockWhilePinging(CallbackInfo ci) {
        if (PingWheelScreen.isOpen()) {
            ci.cancel();
        }
    }
}
