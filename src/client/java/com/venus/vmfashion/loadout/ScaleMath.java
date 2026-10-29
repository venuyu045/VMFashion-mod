package com.venus.vmfashion.loadout;

/**
 * 缩放语义的唯一实现处：解析端的 clamp 与渲染端的"三层相乘"共用同一套规则。
 *
 * <p>为什么单独成类：这两件事都是纯算术，抽出来就能不加载任何 Minecraft 类直接单测
 * （见 {@code ScaleMathTest}），也避免"解析时 clamp 到 [0.01,100]、渲染时又换一套范围"这种漂移。
 *
 * <p><b>三层缩放</b>（渲染时最终倍率）：
 * <pre>
 * 最终 scale = 时装包自己的 scale（pack.json / meta.json）× 后端 scale（网页衣柜）× 全局 scale（config.json）
 * </pre>
 * 三层缺一不可：包作者决定"这件模型天生多大"、玩家在网页上微调、用户在客户端配置里整体缩放。
 *
 * <p><b>为什么每层都 clamp</b>：三层相乘最坏能到 {@code 100 × 100 × 8 = 80000} 倍，
 * 一个手滑的配置就能把模型放大到看不见或把 GPU 拖死。每层先收敛到
 * {@code [0.01, 100]}（与 {@code PackLoader} 对包内 scale 的处理完全一致），
 * 非有限值 / 非正数一律回退 {@code 1.0}（而不是 0，否则模型直接消失，玩家会以为时装坏了）。
 */
public final class ScaleMath {

    /** 合法缩放下限：再小就只剩一个像素，等价于"看不见"，不如当作 1.0 处理。 */
    public static final float MIN_SCALE = 0.01F;
    /** 合法缩放上限：与 {@code PackLoader} 对包内 scale 的上限一致。 */
    public static final float MAX_SCALE = 100.0F;
    /** 缺省 / 非法时的回退值。 */
    public static final float DEFAULT_SCALE = 1.0F;

    private ScaleMath() {
    }

    /**
     * 把任意输入收敛成合法缩放。
     *
     * <p>规则（与 {@code PackLoader} 对包内 {@code scale} 的处理逐条一致）：
     * <ul>
     *   <li>{@code NaN} / 无穷 / {@code <= 0} → {@code 1.0}（0 会让模型消失，比 1.0 更糟）；</li>
     *   <li>其余 clamp 到 {@code [0.01, 100]}。</li>
     * </ul>
     */
    public static float sanitize(float scale) {
        if (!Float.isFinite(scale) || scale <= 0.0F) {
            return DEFAULT_SCALE;
        }
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    /**
     * 渲染时的最终缩放 = 包 scale × 后端 scale × 全局 scale。
     *
     * <p>三层各自先 {@link #sanitize(float)} 再相乘：这样任何一层传进来脏数据都只影响自己，
     * 不会把另外两层也带崩。乘积本身不再 clamp —— 每层已经限制在 {@code [0.01,100]}，
     * 相乘结果必然有限且为正；只是极端组合（例如 100×100×8）会很大，这是玩家自己选的。
     *
     * @param packScale    时装包声明的缩放（{@code CosmeticDefinition#scale()}）
     * @param backendScale 后端 / 网页衣柜给的缩放（缺省 {@code 1.0}）
     * @param globalScale  客户端配置里的全局缩放（{@code ModConfig#getScale()}）
     */
    public static float combine(float packScale, float backendScale, float globalScale) {
        float result = sanitize(packScale) * sanitize(backendScale) * sanitize(globalScale);
        if (!Float.isFinite(result) || result <= 0.0F) {
            // 理论上到不了这里（每层已 clamp），但防御式兜底比"渲染出一个 NaN 矩阵"好
            return DEFAULT_SCALE;
        }
        return result;
    }
}
