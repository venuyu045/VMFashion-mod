package com.venus.vmfashion.gui;

import com.mojang.blaze3d.platform.InputConstants;
import com.venus.vmfashion.VMFashionClient;
import com.venus.vmfashion.config.PingSettings;
import com.venus.vmfashion.ping.PingService;
import com.venus.vmfashion.ping.gui.PingWheelScreen;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * 快捷键注册。
 *
 * <p>合订版（v2.0）有两组快捷键，都挂在同一个按键分类下：
 * <ul>
 *   <li><b>衣柜</b>：默认 {@code K}，打开时装衣柜界面；</li>
 *   <li><b>标点</b>：默认<b>鼠标中键</b>，按住弹出标点轮盘（原独立模组 VMPing 的行为）。</li>
 * </ul>
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
 *
 * <p><b>中键冲突</b>：原版中键是"选取方块"（{@code keyPickItem}）。轮盘打开期间用
 * {@code ping/mixin/MinecraftPickBlockMixin} 抑制 {@code Minecraft#pickBlockOrEntity}，
 * 避免"标点顺便把方块复制到手上"；其它时候中键仍是原版行为。
 */
public final class KeyBindings {

    /** 按键分类路径；语言文件里的 key 是 {@code key.category.vmfashion.main}。 */
    private static final String CATEGORY_PATH = "main";
    /** 衣柜按键名称（翻译 key），语言文件 {@code key.vmfashion.open_wardrobe}。 */
    private static final String OPEN_KEY_NAME = "key.vmfashion.open_wardrobe";
    /** 标点按键名称（翻译 key），语言文件 {@code key.vmfashion.ping}。 */
    private static final String PING_KEY_NAME = "key.vmfashion.ping";
    /** 衣柜默认键：K。 */
    private static final int DEFAULT_KEY = InputConstants.KEY_K;
    /** 标点默认键：鼠标中键。 */
    private static final int DEFAULT_MOUSE_BUTTON = InputConstants.MOUSE_BUTTON_MIDDLE;

    private static KeyMapping openWardrobe;
    private static KeyMapping pingWheel;
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
        try {
            KeyMapping.Category category = resolveCategory();
            pingWheel = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    PING_KEY_NAME, InputConstants.Type.MOUSE, DEFAULT_MOUSE_BUTTON, category));
        } catch (Throwable t) {
            VMFashionClient.LOGGER.warn("注册标点快捷键失败: {}", t.toString());
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

    /** 每客户端 tick 调用；有按键按下就打开对应界面。 */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        if (consumeAll(openWardrobe)) {
            // 已经有界面开着（聊天 / 其它模组界面）时不抢占焦点
            if (minecraft.gui.screen() == null) {
                WardrobeScreen.open();
            }
            return;
        }
        if (consumeAll(pingWheel) && minecraft.gui.screen() == null && minecraft.player != null) {
            PingSettings settings = PingService.getInstance().settings();
            if (settings.isEnabled()) {
                PingWheelScreen.open();
            }
        }
    }

    /**
     * 一次性把积压的点击消费掉：按住 K / 中键不应该排队打开 N 次界面。
     *
     * @return 这一 tick 是否有按下
     */
    private static boolean consumeAll(KeyMapping mapping) {
        if (mapping == null) {
            return false;
        }
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }
        return clicked;
    }

    /** 衣柜绑定键的可读名称，用于命令提示 / 文档。 */
    public static String keyName() {
        return readableName(openWardrobe, "K");
    }

    /** 标点绑定键的可读名称，用于命令提示 / 文档。 */
    public static String pingKeyName() {
        return readableName(pingWheel, "Mouse Middle");
    }

    /** 标点键（轮盘界面 / 命中判断用；未注册时返回 {@code null}）。 */
    public static KeyMapping pingKey() {
        return pingWheel;
    }

    private static String readableName(KeyMapping mapping, String fallback) {
        if (mapping == null) {
            return fallback;
        }
        try {
            Component name = mapping.getTranslatedKeyMessage();
            String text = name == null ? "" : name.getString();
            return text.isBlank() ? fallback : text;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
