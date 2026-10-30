package com.venus.vmfashion.render.anim;

import java.util.Map;

/**
 * 一件时装里"该播哪个动画"的选择规则（纯逻辑，可单测，不加载任何 Minecraft 类）。
 *
 * <p>背景：{@code anim.json} 的 {@code animations} 下可以有多条动画（待机 / 漂浮 / 挥手…），
 * 旧实现永远取第一条（{@code values().iterator().next()}），作者想换默认动画只能改文件顺序。
 * 现在 {@code pack.json} / {@code meta.json} 可以写 {@code "animationName": "<key>"} 指定。
 *
 * <p>匹配顺序：<b>精确 → 大小写不敏感 → 回退第一条</b>。
 * 指定的名字不存在时返回 {@code null}，由调用方回退第一条并记一次警告
 * （不在这里打日志：这个类要保持可单测、零副作用）。
 */
public final class AnimationSelection {

    private AnimationSelection() {
    }

    /**
     * 按名字挑一条动画。
     *
     * @param animations   动画名 → 动画（保持 {@code anim.json} 声明顺序）
     * @param preferredName 想播的动画名；{@code null}/空白 = 播第一条
     * @return 选中的动画；{@code animations} 为空时返回 {@code null}；
     *         <b>指定了名字但找不到时也返回 {@code null}</b>（调用方据此回退 + 告警）
     */
    public static BedrockAnimation select(Map<String, BedrockAnimation> animations, String preferredName) {
        if (animations == null || animations.isEmpty()) {
            return null;
        }
        if (preferredName == null || preferredName.isBlank()) {
            // 没指定：保持旧行为（第一条，顺序由解析端的 LinkedHashMap 保证）
            return animations.values().iterator().next();
        }
        String name = preferredName.trim();
        BedrockAnimation exact = animations.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, BedrockAnimation> entry : animations.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 指定的动画名是否能在表里找到（大小写不敏感）。
     *
     * <p>给调用方决定"要不要告警"用：{@code select} 返回 null 且表非空 = 名字写错了。
     */
    public static boolean hasName(Map<String, BedrockAnimation> animations, String preferredName) {
        if (animations == null || animations.isEmpty() || preferredName == null || preferredName.isBlank()) {
            return false;
        }
        String name = preferredName.trim();
        if (animations.containsKey(name)) {
            return true;
        }
        for (String key : animations.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
