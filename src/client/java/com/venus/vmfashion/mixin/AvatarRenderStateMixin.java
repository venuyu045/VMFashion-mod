package com.venus.vmfashion.mixin;

import com.venus.vmfashion.render.FashionRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.UUID;

/**
 * 给 {@link AvatarRenderState} 加一个 UUID 字段。
 *
 * <p>26.x 的渲染状态是"数据快照"，没有 UUID，只有会复用的实体 id。
 * 渲染层（CapeLayer / PlayerModel）只能拿到状态对象，所以必须把 UUID 挂到状态上。
 */
@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements FashionRenderState {

    @Unique
    private UUID vmfashion$uuid;

    @Override
    public UUID vmfashion$getUuid() {
        return this.vmfashion$uuid;
    }

    @Override
    public void vmfashion$setUuid(UUID uuid) {
        this.vmfashion$uuid = uuid;
    }
}
