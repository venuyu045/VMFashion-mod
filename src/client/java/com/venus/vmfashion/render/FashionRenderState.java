package com.venus.vmfashion.render;

import java.util.UUID;

/**
 * 26.x 的 {@code AvatarRenderState} 里只有 {@code int id}（实体 id，会复用、跨维度会撞），
 * 没有 UUID，所以由 mixin 额外挂一个 UUID 字段，并通过这个接口暴露给渲染层。
 *
 * <p>由 {@code AvatarRenderStateMixin} 实现，填充点在 {@code AvatarRenderer#extractRenderState} 的 RETURN。
 */
public interface FashionRenderState {

    /** 该渲染状态对应的玩家 UUID；未注入时返回 {@code null}。 */
    UUID vmfashion$getUuid();

    /** 由 {@code AvatarRendererMixin} 在提取渲染状态时写入。 */
    void vmfashion$setUuid(UUID uuid);
}
