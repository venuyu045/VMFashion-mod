package com.venus.vmfashion.ping.core;

import java.util.Locale;

/**
 * 标点类型 —— 轮盘上的五个扇区，也是协议里的 {@code kind} 字段取值。
 *
 * <p>设计约定：
 * <ul>
 *   <li>{@link #id()} 是**协议字段**，一旦发布不能改名（服务端按白名单校验）；</li>
 *   <li>{@link #translationKey()} 是语言文件 key，改文案只改 lang json，不改代码；</li>
 *   <li>{@link #color()} 是 {@code 0xAARRGGBB}，世界渲染与轮盘共用同一份颜色，避免"轮盘上是绿的、
 *       世界里的标记是青的"这种不一致；</li>
 *   <li>枚举顺序 = 轮盘扇区顺序（从正上方开始顺时针）。</li>
 * </ul>
 *
 * <p>合订版适配：包名迁入 {@code com.venus.vmfashion.ping.core}；语言 key 按合订规则
 * {@code vmping.ping.* → vmfashion.ping.kind.*}（与 lang 文件一一对应，改了这里必须同步改 lang）；
 * {@link #id()} 协议值保持不变（后端只认这套 id）。
 */
public enum PingKind {

    /** 注意 / 看这里 —— 默认类型，也是协议缺省值。 */
    DEFAULT("default", "vmfashion.ping.kind.default", 0xFF6FD08C),
    /** 危险 —— 怪物、岩浆、敌对玩家。 */
    DANGER("danger", "vmfashion.ping.kind.danger", 0xFFE05252),
    /** 物品 —— 掉落物、箱子、资源点。 */
    ITEM("item", "vmfashion.ping.kind.item", 0xFFE3C24B),
    /** 集合 / 前进 —— 集合点、路线。 */
    GO("go", "vmfashion.ping.kind.go", 0xFF5AA9E6),
    /** 求助 —— 需要支援。 */
    HELP("help", "vmfashion.ping.kind.help", 0xFFE08A3C);

    /** 协议字段（服务端白名单校验用）。 */
    private final String id;
    /** 语言文件 key。 */
    private final String translationKey;
    /** 标记颜色（0xAARRGGBB）。 */
    private final int color;

    PingKind(String id, String translationKey, int color) {
        this.id = id;
        this.translationKey = translationKey;
        this.color = color;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }

    public int color() {
        return color;
    }

    /** 协议 id → 枚举；未知值返回 {@link #DEFAULT}（服务端/他人客户端版本更新时不能崩）。 */
    public static PingKind fromId(String id) {
        if (id == null || id.isBlank()) {
            return DEFAULT;
        }
        String normalized = id.trim().toLowerCase(Locale.ROOT);
        for (PingKind kind : values()) {
            if (kind.id.equals(normalized)) {
                return kind;
            }
        }
        return DEFAULT;
    }
}
