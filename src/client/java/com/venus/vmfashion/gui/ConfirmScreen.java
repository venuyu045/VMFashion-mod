package com.venus.vmfashion.gui;

import com.venus.vmfashion.config.ModConfig;
import com.venus.vmfashion.gui.component.ToastWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 通用确认对话框
 */
public class ConfirmScreen extends Screen {

    private static final int PANEL_WIDTH = 220;
    private static final int PANEL_HEIGHT = 100;

    // 配色统一走 ModConfig.UI 的原版色板（与衣柜界面同一套，避免两块界面风格漂移）
    private static final int HEADER_HEIGHT = 26;

    private final Screen parent;
    private final Component title;
    private final Component message;
    private final Component confirmText;
    private final Runnable onConfirm;
    private final Runnable onCancel;

    public ConfirmScreen(Screen parent, Component title, Component message, Component confirmText,
                         Runnable onConfirm, Runnable onCancel) {
        super(title);
        this.parent = parent;
        this.title = title;
        this.message = message;
        this.confirmText = confirmText;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
    }

    /**
     * 创建删除确认对话框（文案走语言文件 {@code vmfashion.gui.*}）。
     */
    public static ConfirmScreen createDeleteConfirm(Screen parent, Component itemName, Runnable onConfirm) {
        return new ConfirmScreen(
                parent,
                Component.translatable("vmfashion.gui.confirm_delete_title"),
                Component.translatable("vmfashion.gui.confirm_delete_message", itemName),
                Component.translatable("vmfashion.gui.delete"),
                onConfirm,
                () -> {}
        );
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelX = centerX - PANEL_WIDTH / 2;
        int panelY = centerY - PANEL_HEIGHT / 2;

        int buttonY = panelY + PANEL_HEIGHT - 30;
        int buttonWidth = 70;

        // 确认按钮
        this.addRenderableWidget(Button.builder(
                confirmText,
                button -> {
                    if (onConfirm != null) onConfirm.run();
                    onClose();
                }
        ).bounds(centerX - buttonWidth - 10, buttonY, buttonWidth, 20).build());

        // 取消按钮
        this.addRenderableWidget(Button.builder(
                Component.translatable("vmfashion.gui.cancel"),
                button -> {
                    if (onCancel != null) onCancel.run();
                    onClose();
                }
        ).bounds(centerX + 10, buttonY, buttonWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        // 半透明遮罩：与衣柜同一档（原版容器界面的遮罩，不遮死，能看见背后的世界）
        context.fill(0, 0, this.width, this.height, ModConfig.UI.OVERLAY_COLOR);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int panelX = centerX - PANEL_WIDTH / 2;
        int panelY = centerY - PANEL_HEIGHT / 2;

        // 原版容器面板（黑外框 + 白高光 + 深灰阴影 + 像素圆角）
        VanillaStyle.panel(context, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, 1.0f);

        // 头部分隔线（原版「1px 深灰 + 1px 白」双线），标题居中
        VanillaStyle.separator(context, panelX + 1, panelX + PANEL_WIDTH - 1,
                panelY + HEADER_HEIGHT, 1.0f);
        drawCenteredText(context, title, centerX,
                panelY + (HEADER_HEIGHT - this.font.lineHeight) / 2, ModConfig.UI.TEXT_COLOR);

        // 消息：危险操作的说明用深红，普通说明用正文色
        drawCenteredText(context, message, centerX, panelY + 45, ModConfig.UI.DANGER_COLOR);

        super.extractRenderState(context, mouseX, mouseY, delta);

        // 全局 Toast 提示（保存/删除/校验失败的反馈）
        ToastWidget.render(context);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return super.mouseClicked(event, doubleClick);
    }


    /**
     * 居中画一行可翻译文案（<b>不带阴影</b>）。
     *
     * <p>不用 {@code context.centeredText}：那个重载固定带阴影，阴影色是"颜色除以 4"，
     * 深灰文字的阴影接近纯黑，压在 {@code #C6C6C6} 浅灰面板上会糊成脏边。
     * 原版在浅色容器底上写字时同样不带阴影。
     */
    private void drawCenteredText(GuiGraphicsExtractor context, Component text, int centerX, int y, int color) {
        String plain = text.getString();
        context.text(this.font, plain, centerX - this.font.width(plain) / 2, y, color, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
