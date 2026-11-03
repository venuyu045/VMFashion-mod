package com.venus.vmfashion.gui.component;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * 通用数值滑条（原版 {@link AbstractSliderButton} 的薄封装）。
 *
 * <p>为什么不用原版 {@code OptionInstance} 那套滑条：它要接 {@code Options} /
 * {@code OptionInstance.CaptionBasedToString}，为设置界面里的十来个数值引入整条配置抽象
 * 不值得，而且无法自然表达"0.05 一档的缩放倍率"这种步进。
 *
 * <p>显示格式统一为「标签：数值 + 单位」（{@code vmfashion.gui.settings.slider}），
 * 例如「界面缩放：1.25×」「渲染距离：64 格」「音量：70%」。
 * 数值在 {@link #currentValue()} 里按 {@code step} 量化并四舍五入到 {@code decimals} 位，
 * 拖动时不会出现 0.30000000000000004 这种脏数字。
 */
public final class ValueSlider extends AbstractSliderButton {

    private final String labelKey;
    private final double min;
    private final double max;
    private final double step;
    private final int decimals;
    /** 数值后缀（已经翻译好的组件，例如「×」「%」「 格」）；{@code null} 表示无单位。 */
    private final Component suffix;
    /** 数值变化回调（把值同步回界面字段，resize 重建控件时不丢内容）。 */
    private final DoubleConsumer onChange;

    /**
     * @param labelKey 标签的翻译 key（如 {@code vmfashion.gui.settings.option.ui_scale}）
     * @param min      最小值
     * @param max      最大值（必须 &gt; min）
     * @param initial  初始值（会被夹到 [min, max]）
     * @param step     步进（&lt;= 0 表示不量化）
     * @param decimals 显示小数位（0 表示整数）
     * @param suffix   数值后缀组件，可为 {@code null}
     * @param onChange 数值变化回调，可为 {@code null}
     */
    public ValueSlider(int x, int y, int width, int height, String labelKey,
                       double min, double max, double initial, double step, int decimals,
                       Component suffix, DoubleConsumer onChange) {
        super(x, y, width, height, Component.empty(), toSliderValue(initial, min, max));
        this.labelKey = labelKey;
        this.min = min;
        this.max = max;
        this.step = step;
        this.decimals = Math.max(0, decimals);
        this.suffix = suffix;
        this.onChange = onChange;
        // 父类构造器里会调一次 updateMessage（那时字段还没赋值），这里补一次正确的文案
        updateMessage();
    }

    /** 当前量化后的数值。 */
    public double currentValue() {
        double raw = min + value * (max - min);
        if (step > 0) {
            raw = Math.round(raw / step) * step;
        }
        raw = Math.max(min, Math.min(max, raw));
        if (decimals <= 0) {
            return Math.round(raw);
        }
        double factor = Math.pow(10, decimals);
        return Math.round(raw * factor) / factor;
    }

    @Override
    protected void updateMessage() {
        // 注意：父类构造函数里会调一次 updateMessage，那时子类字段还没赋值
        if (labelKey == null) {
            setMessage(Component.empty());
            return;
        }
        Component valueText = Component.literal(format(currentValue()))
                .append(suffix == null ? Component.empty() : suffix);
        setMessage(Component.translatable("vmfashion.gui.settings.slider",
                Component.translatable(labelKey), valueText));
    }

    @Override
    protected void applyValue() {
        updateMessage();
        if (onChange != null) {
            onChange.accept(currentValue());
        }
    }

    private String format(double value) {
        if (decimals <= 0) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static double toSliderValue(double initial, double min, double max) {
        if (!(max > min) || !Double.isFinite(initial)) {
            return 0.0;
        }
        double clamped = Math.max(min, Math.min(max, initial));
        return (clamped - min) / (max - min);
    }
}
