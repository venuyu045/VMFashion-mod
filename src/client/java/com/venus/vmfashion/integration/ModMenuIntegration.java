package com.venus.vmfashion.integration;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.venus.vmfashion.gui.WardrobeScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * Mod Menu 集成：在 Mod Menu 的模组列表里给 VMFashion 一个「配置」按钮，点开直接进衣柜。
 *
 * <p>为什么单独一个类：Mod Menu 是**可选**依赖（{@code compileOnly}），玩家不装也能正常玩。
 * 只有装了 Mod Menu 时，它才会按 {@code fabric.mod.json} 的 {@code modmenu} entrypoint 加载本类；
 * 如果把这个类写进主入口，没装 Mod Menu 的玩家会直接 {@code NoClassDefFoundError}。
 *
 * <p>同理，{@code fabric.mod.json} 里用 {@code suggests} 声明而不是 {@code depends}。
 */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        // Mod Menu 点「配置」时传入当前 Mod 列表界面作为 parent，ESC 后回到那里
        return (ConfigScreenFactory<Screen>) WardrobeScreen::createScreen;
    }
}
