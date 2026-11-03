package net.minecraft.client.gui.components;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * UI 预览器用的 {@code AbstractSliderButton} <b>替身</b>。
 *
 * <p>为什么需要替身：真实的 {@code AbstractSliderButton#extractWidgetRenderState} 会调
 * {@code GuiGraphicsExtractor.blitSprite(...)} 画原版滑条精灵图，而预览器的提取器是
 * 自研的"录制命令"实现（不加载原版 GUI 贴图）。为了让设置界面能在无显卡环境出图，
 * 这里把滑条画成等价语义的纯色块 + 文字：底板、边框、居中数值。
 *
 * <p>与真实类的契约保持一致：{@code protected double value}（0..1）、
 * {@link #updateMessage()} / {@link #applyValue()} 抽象方法、{@code setValue} 会触发
 * {@code applyValue} —— 生产代码 {@code gui/component/ValueSlider} 一行不用改。
 */
public abstract class AbstractSliderButton extends AbstractWidget {

    protected double value;

    public AbstractSliderButton(int x, int y, int width, int height, Component message, double value) {
        super(x, y, width, height, message);
        this.value = Math.max(0.0, Math.min(1.0, value));
    }

    protected abstract void updateMessage();

    protected abstract void applyValue();

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // 预览器不产出无障碍叙述
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        if (clamped != this.value) {
            this.value = clamped;
            applyValue();
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float partialTick) {
        int x = getX();
        int y = getY();
        // 与原版滑条同义的纯色外观：浅灰底 + 深灰边框 + 已选进度条
        context.fill(x, y, x + width, y + height, 0xFF8B8B8B);
        context.outline(x, y, width, height, 0xFF373737);
        int filled = (int) Math.round(width * value);
        if (filled > 2) {
            context.fill(x + 1, y + 1, x + filled, y + height - 1, 0xFFC6C6C6);
        }
        Component message = getMessage();
        String text = message == null ? "" : message.getString();
        Font font = Minecraft.getInstance().font;
        context.text(font, text,
                x + (width - font.width(text)) / 2,
                y + (height - font.lineHeight) / 2,
                0xFF404040, false);
    }
}
