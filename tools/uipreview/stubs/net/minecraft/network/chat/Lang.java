package net.minecraft.network.chat;

import java.util.HashMap;
import java.util.Map;

/**
 * UI 预览器的"语言文件"替身。
 *
 * <p>游戏内的 {@code Language} 单例从资源包读 {@code lang/<locale>.json}；预览器没有资源包栈，
 * 由 {@code harness} 在启动时直接把 {@code assets/vmfashion/lang/zh_cn.json}
 * （或 {@code en_us.json}）读成 Map 灌进来。
 *
 * <p>找不到键时的行为与游戏一致：显示原始键名（游戏内会显示 {@code vmfashion.xxx}），
 * 这样"漏翻/键名写错"在预览图上肉眼可见 —— 这正是 UI 预览要抓的问题之一。
 */
public final class Lang {

    private static final Map<String, String> TABLE = new HashMap<>();

    private Lang() {
    }

    public static void install(Map<String, String> table) {
        TABLE.clear();
        if (table != null) {
            TABLE.putAll(table);
        }
    }

    public static String get(String key, String fallback) {
        String value = key == null ? null : TABLE.get(key);
        if (value != null) {
            return value;
        }
        if (fallback != null) {
            return fallback;
        }
        return key == null ? "" : key;
    }

    public static int size() {
        return TABLE.size();
    }
}
