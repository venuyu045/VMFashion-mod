package com.venus.vmfashion.render;

import java.util.Set;

/**
 * {@code hideParts} 的位掩码表示。
 *
 * <p>为什么要有位掩码：{@code hideParts} 的解析结果原本只在 {@code PlayerModel#setupAnim}
 * 里直接改 {@code ModelPart.visible}，但 26.x 的玩家渲染器还有 9 个渲染层
 * （盔甲 / 手持 / 披风 / 鞘翅 / 头戴 / 鹦鹉 / 箭 / 旋转攻击 / 蜂刺），它们各用各的模型，
 * <strong>从不看父模型的 {@code visible}</strong>。要让这些层也一起消失，就必须把
 * "这件时装要隐藏哪些部位"从 {@code setupAnim} 传到各层的 {@code submit} 上。
 *
 * <p>传递路径：{@code setupAnim} 的 TAIL 算出掩码 → 写进 {@link FashionRenderState}
 * （挂在 {@code AvatarRenderState} 上的 {@code @Unique} 字段）→ 各层 {@code submit} 的 HEAD 读出来。
 * 用 {@code int} 而不是 {@code Set<String>}：渲染热路径零分配。
 */
public final class HiddenParts {

    /** 什么都不隐藏。 */
    public static final int NONE = 0;
    /** {@code hideParts:["all"]}：整套原版模型 + 所有附属层全部隐藏。 */
    public static final int ALL = 1 << 0;
    public static final int HEAD = 1 << 1;
    public static final int BODY = 1 << 2;
    public static final int RIGHT_ARM = 1 << 3;
    public static final int LEFT_ARM = 1 << 4;
    public static final int RIGHT_LEG = 1 << 5;
    public static final int LEFT_LEG = 1 << 6;

    private HiddenParts() {
    }

    /**
     * 把 {@code pack.json} 里的 {@code hideParts} 字符串集合折成掩码。
     *
     * <p>解析是防御式的：不认识的字符串直接忽略（写错的包不该让玩家变隐形人），
     * 大小写不敏感（Bedrock 生态里 {@code Head} / {@code head} 都有人写）。
     */
    public static int of(Set<String> parts) {
        if (parts == null || parts.isEmpty()) {
            return NONE;
        }
        int mask = NONE;
        for (String part : parts) {
            mask |= ofOne(part);
        }
        return mask;
    }

    /** 单个部件名 → 掩码位；未知名字返回 {@link #NONE}。 */
    public static int ofOne(String part) {
        if (part == null) {
            return NONE;
        }
        if (part.equalsIgnoreCase("all")) {
            return ALL;
        }
        if (part.equalsIgnoreCase("head") || part.equalsIgnoreCase("hat")
                || part.equalsIgnoreCase("helmet")) {
            return HEAD;
        }
        if (part.equalsIgnoreCase("body") || part.equalsIgnoreCase("torso")
                || part.equalsIgnoreCase("chest") || part.equalsIgnoreCase("jacket")) {
            return BODY;
        }
        if (part.equalsIgnoreCase("right_arm") || part.equalsIgnoreCase("rightarm")) {
            return RIGHT_ARM;
        }
        if (part.equalsIgnoreCase("left_arm") || part.equalsIgnoreCase("leftarm")) {
            return LEFT_ARM;
        }
        if (part.equalsIgnoreCase("right_leg") || part.equalsIgnoreCase("rightleg")) {
            return RIGHT_LEG;
        }
        if (part.equalsIgnoreCase("left_leg") || part.equalsIgnoreCase("leftleg")) {
            return LEFT_LEG;
        }
        return NONE;
    }

    /**
     * 某个部位是否被隐藏。
     *
     * <p>{@link #ALL} 隐含所有部位 —— 调用点因此不需要自己判 {@code all}。
     */
    public static boolean hidden(int mask, int part) {
        return (mask & ALL) != 0 || (mask & part) != 0;
    }
}
