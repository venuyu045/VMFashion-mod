package com.venus.vmfashion.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.venus.vmfashion.VMFashionClient;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 快捷键注册（默认 {@code K} 打开衣柜界面）。
 *
 * <p>由 {@code VMFashionClient} 在初始化时调用 {@link #register()}，
 * 并在每个客户端 tick 调用 {@link #tick()} 消费按键事件。
 *
 * <p>26.3 的 API 与旧版本不同（已用官方 jar 的 {@code javap} 逐个核对）：
 * <ul>
 *   <li>{@code KeyMapping(String, InputConstants.Type, int, KeyMapping.Category)}
 *       —— 旧的 {@code GLFW.GLFW_KEY_K} 常量已换成 {@link InputConstants#KEY_K}；</li>
 *   <li>分类从枚举变成 {@code KeyMapping.Category}（record，按 {@link Identifier} 注册）；</li>
 *   <li>Fabric 侧的注册入口在 0.165 里叫
 *       {@code net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper#registerKeyMapping}
 *       （旧文档里的 {@code KeyBindingHelper} 已改名，模块也改叫 {@code fabric-key-mapping-api-v1}）。
 *       不经过它注册的按键不会出现在「控制 → 按键绑定」里。</li>
 * </ul>
 */
public final class KeyBindings {

    /** 按键分类路径；语言文件里的 key 是 {@code key.category.vmfashion.main}。 */
    private static final String CATEGORY_PATH = "main";
    /** 按键名称（翻译 key），语言文件 {@code key.vmfashion.open_wardrobe}。 */
    private static final String OPEN_KEY_NAME = "key.vmfashion.open_wardrobe";
    /** 默认键：K。 */
    private static final int DEFAULT_KEY = InputConstants.KEY_K;

    private static KeyMapping openWardrobe;
    private static boolean registered;

    private KeyBindings() {
    }

    /** 注册按键（只在客户端初始化时调用一次）。 */
    public static void register() {
        if (registered) {
            // 热重载 / 开发环境重复初始化时不能重复注册：同一个 Category 注册两次会抛异常
            return;
        }
        registered = true;
        try {
            KeyMapping.Category category = resolveCategory();
            openWardrobe = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    OPEN_KEY_NAME, InputConstants.Type.KEYBOARD, DEFAULT_KEY, category));
        } catch (Throwable t) {
            // 注册失败只影响快捷键，绝不能让客户端起不来（命令 / 界面仍然可用）
            VMFashionClient.LOGGER.warn("注册衣柜快捷键失败: {}", t.toString());
        }
    }

    /**
     * 拿到（或创建）本模组的按键分类。
     *
     * <p>{@code KeyMapping.Category.register} 对同一 ID 重复调用会抛
     * {@link IllegalArgumentException}，所以这里降级成直接构造 —— 分类只是排序用的，
     * 构造出来的对象一样能显示在按键列表里。
     */
    private static KeyMapping.Category resolveCategory() {
        Identifier id = Identifier.fromNamespaceAndPath(VMFashionClient.MOD_ID, CATEGORY_PATH);
        try {
            return KeyMapping.Category.register(id);
        } catch (IllegalArgumentException alreadyRegistered) {
            return new KeyMapping.Category(id);
        }
    }

    /** 每客户端 tick 调用；有按键按下就打开界面。 */
    public static void tick() {
        KeyMapping mapping = openWardrobe;
        if (mapping == null) {
            return;
        }
        // 一次性把积压的点击消费掉：按住 K 不应该排队打开 N 次界面
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }
        if (!clicked) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        // 已经有界面开着（聊天 / 其它模组界面）时不抢占焦点
        if (minecraft.gui.screen() != null) {
            return;
        }
        WardrobeScreen.open();
    }

    /** 当前绑定键的可读名称，用于命令提示 / 文档。 */
    public static String keyName() {
        KeyMapping mapping = openWardrobe;
        if (mapping == null) {
            return "K";
        }
        try {
            Component name = mapping.getTranslatedKeyMessage();
            String text = name == null ? "" : name.getString();
            return text.isBlank() ? "K" : text;
        } catch (Throwable t) {
            return "K";
        }
    }
}
