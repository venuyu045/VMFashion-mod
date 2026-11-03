package net.minecraft.network.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * UI 预览器用的 {@code MutableComponent} 精简替身。
 *
 * <p>内容只有两种：字面量（literal）与可翻译键（translatable + 参数）。
 * {@link #getString()} 负责把翻译键解析成当前语言的文案，并按游戏内规则做 {@code %s} 替换。
 */
public final class MutableComponent implements Component {

    private final String literal;
    private final String translationKey;
    private final String fallback;
    private final Object[] args;
    private Style style;
    private final List<Component> siblings;

    private MutableComponent(String literal, String translationKey, String fallback,
                             Object[] args, Style style, List<Component> siblings) {
        this.literal = literal;
        this.translationKey = translationKey;
        this.fallback = fallback;
        this.args = args;
        this.style = style == null ? Style.EMPTY : style;
        this.siblings = siblings == null ? new ArrayList<>() : siblings;
    }

    static MutableComponent literal(String text) {
        return new MutableComponent(text == null ? "" : text, null, null, null, Style.EMPTY, new ArrayList<>());
    }

    static MutableComponent translatable(String key, Object... args) {
        return new MutableComponent(null, key, null, args, Style.EMPTY, new ArrayList<>());
    }

    static MutableComponent translatableWithFallback(String key, String fallback, Object... args) {
        return new MutableComponent(null, key, fallback, args, Style.EMPTY, new ArrayList<>());
    }

    @Override
    public Style getStyle() {
        return style;
    }

    @Override
    public List<Component> getSiblings() {
        return siblings;
    }

    @Override
    public String getString() {
        StringBuilder sb = new StringBuilder();
        sb.append(ownText());
        for (Component sibling : siblings) {
            sb.append(sibling.getString());
        }
        return sb.toString();
    }

    private String ownText() {
        if (literal != null) {
            return literal;
        }
        String pattern = Lang.get(translationKey, fallback);
        if (args == null || args.length == 0) {
            return pattern;
        }
        Object[] resolved = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            // 游戏内 Component 参数会先转成文本再塞进 %s
            resolved[i] = arg instanceof Component component ? component.getString() : arg;
        }
        try {
            return String.format(pattern, resolved);
        } catch (RuntimeException e) {
            // 参数个数与占位符不匹配时不能让界面炸掉，退回未格式化的原文
            return pattern;
        }
    }

    @Override
    public MutableComponent withStyle(Style newStyle) {
        this.style = newStyle == null ? Style.EMPTY : newStyle;
        return this;
    }

    @Override
    public MutableComponent withStyle(UnaryOperator<Style> operator) {
        Style next = operator.apply(this.style);
        this.style = next == null ? Style.EMPTY : next;
        return this;
    }

    @Override
    public MutableComponent copy() {
        return new MutableComponent(literal, translationKey, fallback, args, style, new ArrayList<>(siblings));
    }

    @Override
    public MutableComponent append(String text) {
        siblings.add(MutableComponent.literal(text));
        return this;
    }

    @Override
    public MutableComponent append(Component other) {
        if (other != null) {
            siblings.add(other);
        }
        return this;
    }

    @Override
    public String toString() {
        return "Component[" + getString() + "]";
    }
}
