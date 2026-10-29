package com.venus.vmfashion.loadout;

/**
 * 玩家身上"一件已穿戴的时装"：全局 ID + 这一件在该玩家身上的缩放。
 *
 * <p><b>为什么需要这个类型</b>：旧契约是 {@code slot → cosmeticId}（纯字符串），
 * 后端明明存了每件时装的 {@code scale}（网页衣柜里可以调大小），但数据模型里根本没有这个字段，
 * 于是"后端 scale 参与渲染"这件事<strong>卡在数据层</strong>，不是渲染层没接。
 *
 * <p>设计取舍：
 * <ul>
 *   <li><b>immutable record</b>：解析响应时创建一次、存进缓存，渲染线程只读不 new
 *       （热路径上每帧每件时装都新建对象会持续制造垃圾）；</li>
 *   <li><b>构造时就收敛 scale</b>：无论来自网络还是本地，非法值都进不来，
 *       下游（{@code ScaleMath#combine}）不需要再判一次；</li>
 *   <li>只带"这一件"的信息，不带槽位：槽位是外层 Map 的 key，避免同一份数据存两遍。</li>
 * </ul>
 *
 * @param cosmeticId 时装全局 ID，形如 {@code packId:localId}；空串表示"无效条目"（调用方应跳过）
 * @param scale      后端给的缩放，已在构造时收敛到 {@code [0.01, 100]}，缺省 {@code 1.0}
 */
public record WornCosmetic(String cosmeticId, float scale) {

    public WornCosmetic {
        // 网络数据可能缺字段 / 类型错 → 上层传 null 或脏 float 都不能炸
        if (cosmeticId == null) {
            cosmeticId = "";
        }
        scale = ScaleMath.sanitize(scale);
    }

    /** 只有 ID、没有缩放信息时的简写（本地穿戴、老后端响应）。 */
    public static WornCosmetic of(String cosmeticId) {
        return new WornCosmetic(cosmeticId, ScaleMath.DEFAULT_SCALE);
    }

    /** ID 是否为空（空 ID 的条目应当被跳过）。 */
    public boolean isBlank() {
        return cosmeticId.isBlank();
    }
}
