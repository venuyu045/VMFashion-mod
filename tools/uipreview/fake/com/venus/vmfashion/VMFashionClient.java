package com.venus.vmfashion;

import com.venus.vmfashion.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * UI 预览器专用的 {@code VMFashionClient} <b>替身</b>（编译时替换真实类）。
 *
 * <p><b>为什么必须替掉：</b>真实类 implements {@code ClientModInitializer}，
 * 初始化链会牵进 Fabric API、命令注册、按键绑定、资源重载监听……整条客户端启动流程。
 * UI 预览只需要它的两样东西：{@link #LOGGER}（异常兜底日志）和
 * {@link #getConfig()}（界面读 UI 缩放 / 动画时长 / 配色开关）。
 *
 * <p>配置默认返回 {@code null} —— 界面里所有 {@code configOrNull()} 的分支都会走
 * "没有配置"的兜底路径（{@code uiScale = 1.0}、{@code animationDuration = 0} 即不做淡入）。
 * 这正好是预览想要的：<b>拿到一帧完整的、不透明的界面</b>，而不是动画中间态。
 */
public class VMFashionClient {

    /** 与真实类同名的日志器（预览器跑在 JDK 里，走 slf4j 的 NOP 实现即可）。 */
    public static final Logger LOGGER = LoggerFactory.getLogger("vmfashion-uipreview");

    private static final VMFashionClient INSTANCE = new VMFashionClient();

    private VMFashionClient() {
    }

    public static VMFashionClient getInstance() {
        return INSTANCE;
    }

    /** 预览器不加载配置：返回 {@code null} 让界面走兜底分支。 */
    public ModConfig getConfig() {
        return null;
    }
}
