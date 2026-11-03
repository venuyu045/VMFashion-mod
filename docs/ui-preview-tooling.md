# Minecraft mod 游戏内 UI 预览工具调研（2026-11）

> 背景：VMFashion 的核心界面是 `gui/WardrobeScreen.java`（1600+ 行，继承 MC `Screen`），
> 用 MC 26.3 的提取式渲染 API。开发时想"看界面"只能起客户端，而开发机
> （2 核 / cgroup 1.2GB / 无 X / 无 libGL / 磁盘剩 175MB）根本起不来。
>
> 结论先行：**没有任何现成工具能同时满足「不启动客户端 + 预览任意 mod Screen + 可交互」。**
> 自研 harness 是这台机器上唯一可行的路，最终落地为 [`tools/uipreview/`](../tools/uipreview/README.md)。

## 一、需求拆成三个硬条件

| 条件 | 含义 |
|---|---|
| ① 不启动完整客户端 | 本机无 GL / 无 X / 内存差一个数量级 |
| ② 能预览**任意** Screen | 要能跑 `WardrobeScreen` 的真实绘制代码，不是"设计一个长得像的界面" |
| ③ 可交互 | 点击 / 滚动 / 输入要真的喂进 `Screen` 回调 |

现成工具里，②③ 的交集只有官方 Client GameTest；而它恰好违反 ①。

## 二、逐个核实（带证据）

### 1. Fabric Client GameTest API（官方，唯一同时满足 ②③ 的现成方案）

- 包文档：<https://maven.fabricmc.net/docs/fabric-api-0.165.0+26.3/net/fabricmc/fabric/api/client/gametest/v1/package-summary.html>
  —— 通过 `fabric.mod.json` 的 `fabric-client-gametest` entrypoint 注册；Loom 用
  `fabricApi.configureTests { createSourceSet = true; modId = '...' }`。
- [`ClientGameTestContext`](https://maven.fabricmc.net/docs/fabric-api-0.165.0+26.3/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.html)：
  `takeScreenshot` / `assertScreenshotContains` / `waitForScreen` / `setScreen` / `clickScreenButton`。
- [`TestInput`](https://maven.fabricmc.net/docs/fabric-api-0.165.0+26.3/net/fabricmc/fabric/api/client/gametest/v1/TestInput.html)：
  `pressMouse` / `scroll` / `setCursorPos` / `typeChars` / `pressKey` / `resizeWindow`。
- 官方 testmod 就是"开界面 + 输入 + 截图"的范例：
  <https://raw.githubusercontent.com/FabricMC/fabric/26.3/fabric-client-gametest-api-v1/src/testmodClient/java/net/fabricmc/fabric/test/client/gametest/ClientGameTestTest.java>
- 官方 CI 在 **无 Xvfb** 的 `ubuntu-24.04` + `mesa-vulkan-drivers` 上跑 `runClientGametest`
  并上传 `run/screenshots`：
  <https://raw.githubusercontent.com/FabricMC/fabric/26.3/.github/workflows/build.yml>

**否决原因**：MC 26.3 官方需求是 **Vulkan 1.3 + 2GB VRAM + 8GB 内存**
（<https://minecraft.wiki/w/Java_Edition>）。本机 cgroup 上限 1.2GiB、当前占用 1.14GiB、
无任何 GL/Vulkan 驱动 —— 差一个数量级，不是"慢"而是"不可能"。

→ 这一轨仍然有价值，但只能跑在 GitHub Actions 上（本机 `curl github.com` 返回 000）。

### 2. 游戏内 HUD / GUI 设计器类 mod（全部不满足 ②）

| 名称 | 实际能力 | 为什么不行 |
|---|---|---|
| [hud designer](https://modrinth.com/mod/hud-designer) | 移动/缩放**原版 HUD 元素** + GUI 缩放主题 | 编辑对象是原版 HUD，不加载第三方 Screen；最高 26.2 |
| [Universal Preview](https://www.mcmod.cn/class/30795.html) | 3D 预览物品/方块/实体 + 图文锚点 | 是 3D 模型预览器，不是 GUI |
| [GUI Inspector](https://modrinth.com/mod/gui-inspector) | 运行时检查任意 Screen 的 widget 树 / draw call | 必须先在真机打开界面；不产出离屏图 |
| [DebugBridge](https://modrinth.com/mod/debugbridge) | WebSocket 桥 + Web UI，可 `screenInspect` / `screenshot` | 同上，必须真机；最高 26.2 |
| [ScenePreview](https://modrinth.com/mod/scenepreview) | 页面返回 "Project not found" | **未找到可用证据**（疑已下架） |
| [SnapView](https://modrinth.com/mod/snapview) | 截图画廊 | 与 GUI 预览无关 |
| [Graintfa](https://github.com/RedCrafter07/Graintfa) | 独立桌面 GUI 设计器（Electron） | 不加载 MC / 不加载 mod；仅 Windows；作者声明停更 |

### 3. 虚拟显示器 + 软件渲染（原理成立，本机不可行）

- `mc-runtime-test`（HeadlessMC + Xvfb）支持 26.1–26.3 Fabric：
  <https://github.com/headlesshq/mc-runtime-test>
  —— 并注明 "26.3 无渲染运行还需含 SDL 更新的 HeadlessMC"。
- HeadlessMC 的 `--headless` 是"patch LWJGL 使其不渲染任何东西"：
  <https://headlesshq.github.io/headlessmc/launch/> → 适合逻辑测试，**看不到 UI**。

本机实测反证：

```
ldconfig -p | grep -iE "libGL|libEGL|libOSMesa"   → 只有 libgbm.so.1，其余全无
which Xvfb xvfb-run xdotool ffmpeg glxinfo        → 全部不存在；DISPLAY 为空
apt-get install --dry-run xvfb libgl1-mesa-dri    → E: Unable to locate package xvfb
df -h /                                           → 可用 175 MB
cat /sys/fs/cgroup/memory.max                     → 1258291200 (1.2GiB)
cat /sys/fs/cgroup/memory.current                 → 1223094272 (余量 ≈ 35MiB)
~/.gradle/caches/fabric-loom/                     → 没有 assets 目录，跑真客户端还要下几百 MB 资源
```

即便装上 Xvfb + Mesa，仍差 ~7GB 内存与数百 MB 磁盘。

### 4. 其它思路（均不成立）

- `-Dfabric.debug.*` 系统属性：只有类加载 / mod 发现相关，**没有任何 GUI/渲染预览开关**
  （<https://raw.githubusercontent.com/FabricMC/fabric-loader/master/src/main/java/net/fabricmc/loader/impl/util/SystemProperties.java>）。
- 原版 `/screenshot` 命令：26.3 客户端 jar 里没有该命令类，只有 `net/minecraft/client/Screenshot`（对应 F2）。
- GUI 库（LibGui / owo-lib / KotlinMCUI）：都是"写 UI 的库"，README 里没有预览器。
- ReplayMod：录像回放，不是 GUI 预览（回放内截图仍是 feature request，issue #457）。

### 5. 把 MC Screen 渲染成图片 / 网页的开源项目

| 项目 | 能力 | 差距 |
|---|---|---|
| [mc-gui-lint](https://github.com/yukidoke/mc-gui-lint) | **静态解析** `AbstractContainerScreen` 源码 → Pillow 出 PNG + 布局 lint | 不执行代码、不可交互、只覆盖容器界面 |
| [minecraft-inventory-gui](https://github.com/PrismarineJS/minecraft-inventory-gui) | 浏览器 canvas 渲染原版容器窗口 | 只内置原版窗口，不执行 mod 代码 |
| minosoft | 自研客户端，能截图 | 不加载 Fabric mod |

mc-gui-lint 的思路（离屏出图 + 布局 lint）值得借鉴，本项目 `LayoutCheck` 就参考了它的检查项。

### 6. 中文社区

- mcmod.cn：只有 Universal Preview（3D 预览），**没有**面向 Java 版 mod Screen 的预览工具。
- 网易/基岩生态有成熟的 JSON UI 可视化编辑 + 热重载（[MCDevTool](https://github.com/GitHub-Zero123/MCDevTool)，
  内置 MCP 可截图/点击窗口），但完全不适用于 Java / Fabric。
- MCBBS 已关停。

## 三、最终方案

自研 harness（`tools/uipreview/`），核心洞察是 **MC 26.x 的提取式渲染把"绘制"变成了"命令录制"**：

- `GuiGraphicsExtractor` 在 26.x 里只往 `GuiRenderState` 写命令，不碰 GL
  （`javap -c` 看 `fill(int,int,int,int,int)` 就是转发到另一个 `fill`，纯数据操作）；
- 于是可以把它替换成"记录器"，产出 `DrawCommand` 列表，再用 Java2D 重放成 PNG；
- 生产代码的 `extractRenderState` 一行都不用改。

已验证的 API 面（对 `WardrobeScreen` 实测统计）：
`fill`×12、`text`×8、`textWithWordWrap`×4、`outline`×4、`centeredText`×3、
`blit`×2、`setTooltipForNextFrame`×1、`enableScissor`×1、`disableScissor`×1 —— 共 9 类。

需要 stub 的 MC 类约 20 个（`Screen` / `Minecraft` / `Font` / `GuiGraphicsExtractor` /
`Component` / `Identifier` / `Button` / `EditBox` / 事件类型等），
需要 fake 的项目内类只有 4 个（`CosmeticAssets` / `SyncService` / `AssetSyncService` /
`VMFashionClient`）+ 1 个 `UIState`（它静态初始化要 `FabricLoader`）。

## 四、双轨建议

| | 本地 harness（已落地） | 真机 GameTest（待接入 CI） |
|---|---|---|
| 速度 | 2~3 秒 | 5~10 分钟 |
| 环境 | 任意 JVM | GitHub runner + mesa-vulkan-drivers |
| 保真 | 布局与逻辑真实，字形近似 | 100% 真实 |
| 用途 | 日常迭代、交互行为验证 | 发布前验收、像素回归 |

真机那轨的实施要点（有网环境再做）：
`build.gradle` 加 `fabricApi.configureTests`，写一个 `FabricClientGameTest` 用
`setScreen(() -> new WardrobeScreen(null))` + `getInput()` + `takeScreenshot`，
CI 里 `apt install mesa-vulkan-drivers` + `./gradlew runClientGametest` + 上传 `run/screenshots`。
