package com.venus.vmfashion.render;

import java.util.UUID;

/**
 * 挂在 26.x 渲染状态上的时装附加数据。
 *
 * <p>26.x 的 {@code AvatarRenderState} 是纯数据快照：只有 {@code int id}（实体 id，会复用、
 * 跨维度会撞），没有 UUID，也没有"这件时装要隐藏哪些原版部位"这种模组私有信息。
 * 渲染层（{@code CapeLayer} / {@code PlayerModel} / 各渲染层）只能拿到状态对象，
 * 所以这些数据必须挂到状态上。
 *
 * <p>由 {@code AvatarRenderStateMixin} 实现：
 * <ul>
 *   <li>UUID 在 {@code AvatarRenderer#extractRenderState} 的 RETURN 填充；</li>
 *   <li>hideParts 掩码在 {@code PlayerModel#setupAnim} 的 TAIL 填充（每帧刷新，
 *       之后各渲染层的 {@code submit} 才读得到）。</li>
 * </ul>
 */
public interface FashionRenderState {

    /** 该渲染状态对应的玩家 UUID；未注入时返回 {@code null}。 */
    UUID vmfashion$getUuid();

    /** 由 {@code AvatarRendererMixin} 在提取渲染状态时写入。 */
    void vmfashion$setUuid(UUID uuid);

    /**
     * 本帧要隐藏的原版部位掩码，见 {@link HiddenParts}。
     *
     * <p>每帧由 {@code PlayerModel#setupAnim} 重算并覆盖，所以脱下时装后下一帧自动恢复，
     * 不会出现"粘住"的隐形人。
     */
    int vmfashion$getHiddenParts();

    /** 由 {@code CosmeticRenderDispatcher#applyHideParts} 每帧写入。 */
    void vmfashion$setHiddenParts(int mask);
}
