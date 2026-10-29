package com.venus.vmfashion.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 聊天工具类。
 *
 * <p>玩家可见文案一律用 {@link Component#translatable} 传进来（i18n 见路线图 §5.3）；
 * {@link String} 重载只留给"内容本身不是文案"的场景（例如拼接了第三方异常文本），
 * 新代码不要再用它写中文。
 */
public class ChatUtil {

    /** 聊天栏前缀；命令反馈（{@code /vmfashion ...}）与模组主动提示共用同一个前缀。 */
    private static final String PREFIX = "[VMFashion] ";

    /**
     * 发送命令到服务器（不显示在聊天栏）
     */
    public static void sendCommand(String command) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            return;
        }

        // 去掉开头的 /
        if (command.startsWith("/")) {
            command = command.substring(1);
        }

        final String finalCommand = command;
        client.execute(() -> {
            client.getConnection().sendCommand(finalCommand);
        });
    }

    /**
     * 发送聊天消息（显示在聊天栏）
     */
    public static void sendMessage(String message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getConnection() == null) {
            return;
        }

        client.execute(() -> {
            client.getConnection().sendChat(message);
        });
    }

    /**
     * 给一条消息加上 {@code [VMFashion] } 前缀。
     *
     * <p>命令反馈走 {@code source.sendFeedback(...)} 而不是聊天栏，但也必须带同一个前缀，
     * 所以前缀的拼装放在这里统一实现，避免两处各写一遍。
     */
    public static Component prefixed(Component message) {
        Component body = message == null ? Component.empty() : message;
        return Component.literal(PREFIX).append(body);
    }

    /**
     * 显示本地提示消息（仅客户端可见）。
     */
    public static void showLocalMessage(String message) {
        showLocalMessage(Component.literal(message == null ? "" : message));
    }

    /**
     * 显示本地提示消息（可翻译文案，推荐）。
     *
     * @param message 已经构造好的 {@link Component}，例如 {@code Component.translatable("vmfashion.chat.login_failed", detail)}
     */
    public static void showLocalMessage(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        Component line = prefixed(message);
        client.execute(() -> client.player.sendSystemMessage(line));
    }

    /**
     * 显示动作栏消息
     */
    public static void showActionBar(String message) {
        showActionBar(Component.literal(message == null ? "" : message));
    }

    /**
     * 显示动作栏消息（可翻译文案，推荐）。
     */
    public static void showActionBar(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }

        Component line = message == null ? Component.empty() : message;
        client.execute(() -> client.player.sendOverlayMessage(line));
    }
}
