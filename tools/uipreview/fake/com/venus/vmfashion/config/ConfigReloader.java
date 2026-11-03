package com.venus.vmfashion.config;

/**
 * UI 预览器专用的 {@code ConfigReloader} <b>替身</b>。
 *
 * <p>真实实现会重建同步层 / 资产层 / 标点中继并重扫时装包，整条链路会牵进 Fabric API、
 * 网络与 GL 相关类，预览器既不需要也不该执行它。设置界面只在「完成」时调用
 * {@link #reload()}，预览场景不会点那个按钮；这个替身保证类能编译、万一被调用也只是空操作。
 */
public final class ConfigReloader {

    private ConfigReloader() {
    }

    /** 空操作：预览器不应用配置。 */
    public static int reload() {
        return 0;
    }
}
