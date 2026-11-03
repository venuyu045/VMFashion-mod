package net.minecraft.network.chat;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * UI 预览器用的 {@code net.minecraft.network.chat.Component} 精简替身。
 *
 * <p>游戏里的 Component 是一棵带样式的树；UI 预览只需要两件事：
 * <ol>
 *   <li>{@link #getString()} 能拿到最终要画的文字（含翻译与 {@code %s} 替换）；</li>
 *   <li>{@link #getStyle()} 能拿到颜色 / 粗体等样式。</li>
 * </ol>
 * 因此这里退化成"内容 + 样式 + 兄弟节点"的平坦结构，语义与游戏一致。
 *
 * <p>翻译走 {@link Lang}，由预览器启动时把 {@code assets/vmfashion/lang/*.json} 灌进去，
 * 所以界面上出现的文案与游戏内**逐字相同**（包括中文标点和 emoji）。
 */
public interface Component extends FormattedText {

    Style getStyle();

    List<Component> getSiblings();

    @Override
    String getString();

    MutableComponent withStyle(Style style);

    MutableComponent withStyle(UnaryOperator<Style> operator);

    MutableComponent copy();

    MutableComponent append(String text);

    MutableComponent append(Component other);

    static MutableComponent literal(String text) {
        return MutableComponent.literal(text);
    }

    static MutableComponent translatable(String key) {
        return MutableComponent.translatable(key, new Object[0]);
    }

    static MutableComponent translatable(String key, Object... args) {
        return MutableComponent.translatable(key, args);
    }

    static MutableComponent translatableWithFallback(String key, String fallback) {
        return MutableComponent.translatableWithFallback(key, fallback, new Object[0]);
    }

    static MutableComponent translatableWithFallback(String key, String fallback, Object... args) {
        return MutableComponent.translatableWithFallback(key, fallback, args);
    }

    static MutableComponent empty() {
        return MutableComponent.literal("");
    }

    static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
