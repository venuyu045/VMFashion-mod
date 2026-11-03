package com.venus.vmfashion.cosmetic;

import java.util.List;

/**
 * 往 {@link CosmeticRegistry} 里灌测试数据。
 *
 * <p><b>为什么放在这个包：</b>{@code CosmeticRegistry#replaceAll} 是<b>包私有</b>的
 * （只有 {@code PackLoader} 能调，防止外部随意改写全局表）。预览器要造测试数据又不该
 * 改动生产代码的可见性，所以把注入器放在同一个包里 —— 这是 Java 允许的最窄提权方式，
 * 比用反射改 private 字段稳得多（重构时编译期就会报错）。
 */
public final class RegistryInjector {

    private RegistryInjector() {
    }

    /** 整体替换已加载的时装包列表。 */
    public static void inject(List<CosmeticPack> packs) {
        CosmeticRegistry.getInstance().replaceAll(packs);
    }

    /** 清空（模拟"一件时装都没装"的空衣柜）。 */
    public static void clear() {
        CosmeticRegistry.getInstance().replaceAll(List.of());
    }
}
