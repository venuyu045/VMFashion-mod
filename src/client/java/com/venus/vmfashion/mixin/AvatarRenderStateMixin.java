package com.venus.vmfashion.mixin;

import com.venus.vmfashion.render.FashionRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/**
 * 给 {@link AvatarRenderState} 加 UUID 和 hideParts 掩码两个字段。
 *
 * <p>26.x 的渲染状态是"数据快照"，没有 UUID，只有会复用的实体 id；
 * 也没有"要隐藏哪些原版部位"的位置。渲染层（CapeLayer / PlayerModel / 各渲染层）
 * 只能拿到状态对象，所以必须把这两项挂到状态上。
 *
 * <p>{@code @Unique} 保证字段名不会和原版 / 其它模组的字段撞车。
 */
@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements FashionRenderState {

    @Unique
    private UUID vmfashion$uuid;

    /** 本帧 hideParts 掩码；0 表示不隐藏任何部位。 */
    @Unique
    private int vmfashion$hiddenParts;

    @Override
    public UUID vmfashion$getUuid() {
        return this.vmfashion$uuid;
    }

    @Override
    public void vmfashion$setUuid(UUID uuid) {
        this.vmfashion$uuid = uuid;
    }

    @Override
    public int vmfashion$getHiddenParts() {
        return this.vmfashion$hiddenParts;
    }

    @Override
    public void vmfashion$setHiddenParts(int mask) {
        this.vmfashion$hiddenParts = mask;
    }
}
