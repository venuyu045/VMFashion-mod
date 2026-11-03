# 把这个预览器搬到别的项目

预览器分成两层：**内核**（不认识任何项目类）和**适配层**（唯一知道项目类名的地方）。
换项目只需要写一个新的适配器，内核一行都不用改。

## 目录结构

```
tools/uipreview/
├── stubs/                内核：net.minecraft.* 的替身（按 MC 版本一套）
├── harness/uipreview/    内核：录制器 / Java2D 渲染 / HTTP 服务 / 布局自检
│   └── UiPreviewAdapter.java   ← 唯一的扩展点（接口）
├── fake/                 适配层：项目里"碰了 MC/Fabric 的业务类"的替身
├── app/                  适配层：适配器实现（场景、测试数据）
├── web/index.html        内核：交互页面（标题和场景列表由适配器提供，不用改）
├── assets/mc/            内核：从客户端 jar 拷出的原版 GUI 精灵图
├── run.sh / serve.sh     内核：入口脚本（只需改 ADAPTER_CLASS 一行）
└── gen-mc-font.py        内核：从客户端 jar 重新生成字体数据
```

## 移植五步

### 1. 复制目录

```bash
cp -r <旧项目>/tools/uipreview <新项目>/tools/uipreview
rm -rf <新项目>/tools/uipreview/fake/com/<旧项目包名>
rm -rf <新项目>/tools/uipreview/app/com/<旧项目包名>
```

### 2. 确认 MC / Loom 版本

`stubs/` 是按 MC 大版本写的。**MC 26.x 是提取式渲染**（`extractRenderState(GuiGraphicsExtractor, ...)`），
1.21 及以前是 `render(GuiGraphics, ...)` —— 两套 API 完全不同，`stubs/` 不能混用。

版本一致就直接用；不一致要照新版 API 重写 `stubs/`（约 2000 行，照着 `javap` 抄）。

### 3. 找项目里"碰了 MC/Fabric 的类"，写替身

先跑一次编译，报 `cannot find symbol` 的就是要补的：

```bash
cd <新项目>
CP=$(./gradlew -q -I tools/uipreview/print-classpath.gradle printClientCp --offline \
     | grep '^CLIENT_CP=' | sed 's/^CLIENT_CP=//')
javac -d /tmp/probe -cp "$CP" \
  -sourcepath "tools/uipreview/stubs:tools/uipreview/fake:tools/uipreview/app:src/client/java:tools/uipreview/harness" \
  tools/uipreview/harness/uipreview/UiPreview.java $(find tools/uipreview/app -name '*.java')
```

典型需要替身的类（**只有 4~6 个**，因为大部分业务类本来就不 import MC 类）：

| 类型 | 为什么 | 替身要点 |
|---|---|---|
| 主入口类（`XxxClient`） | implements `ClientModInitializer`，牵进整条 Fabric 启动链 | 只留 `getInstance()` / `LOGGER` / 界面要用的 getter |
| 配置类（`ModConfig`） | 静态字段调 `FabricLoader.getConfigDir()` | **字段默认值必须和真实类逐条对齐**，否则界面初始态不对 |
| 状态持久化（`UIState`） | 同上 | 内存版；**没有记录时 `get()` 必须返回 `null`**（见下面"踩过的坑"） |
| 数据管理器（`XxxManager`） | 要读磁盘 | 内存版 + 一个 `replaceAll(...)` 供注入测试数据 |
| 资源/渲染类（`CosmeticAssets`） | 要烘焙模型、上传 GPU 纹理 | 只返回"一张图"即可 |

**纯数据类不要写替身**（`Waypoint`、`WaypointGroup`、`CosmeticDefinition` 这类）——
直接用生产代码的，这样验证的才是真实行为。

### 4. 写适配器

`app/<你的包>/uipreview/XxxAdapter.java`，实现 `UiPreviewAdapter`：

```java
public final class YourAdapter implements UiPreviewAdapter {
    @Override public String modId()        { return "yourmod"; }         // 找语言文件用
    @Override public Screen createScreen() { return new YourScreen(); }   // 要预览的界面
    @Override public void installFixtures(String scenario, Path workDir) { /* 造测试数据 */ }
    @Override public Map<String, Scenario> scenarios() { /* 场景表 */ }
}
```

一个 mod 有多个界面时，覆写 `createScreen(String scenario)` 按场景返回不同界面：

```java
@Override
public Screen createScreen(String scenario) {
    return switch (scenario) {
        case "settings" -> new SettingsScreen(null);
        default -> new YourScreen();
    };
}
```

### 5. 改脚本里的适配器类名

`run.sh` / `serve.sh` 各一行：

```bash
ADAPTER_CLASS=${UIPREVIEW_ADAPTER:-com.yourmod.uipreview.YourAdapter}
```

也可以不改脚本，用环境变量：`UIPREVIEW_ADAPTER=com.yourmod.uipreview.YourAdapter bash tools/uipreview/run.sh`

## 跑起来

```bash
bash tools/uipreview/run.sh --all --report     # 所有场景出图 + 布局自检
bash tools/uipreview/serve.sh                  # 浏览器里交互
```

前提：项目能 `./gradlew build`（类路径从 `printClientCp` 取）。

## 踩过的坑（都是实际踩到的）

1. **替身的语义必须和真实类一致**，不是"能编过就行"。
   例：`UIState.getWindowState()` 真实实现在"没有记录时返回 `null`"，
   替身写成 `computeIfAbsent`（永远返回对象）→ 界面以为"已有保存位置 (0,0)" →
   所有窗口叠在左上角。**症状是"界面能画出来但布局全错"，最难查**。

2. **场景之间要隔离**。
   同一 JVM 连续跑多个场景时，上一个场景改过的静态状态（记住的分类、窗口位置）
   会串到下一个。适配器的 `installFixtures` 里要主动重置。

3. **鼠标按钮编号是 1-based**（MC 26.3 用 SDL3：左 1 / 中 2 / 右 3），
   不是 GLFW 的 0/2/1。原版 `AbstractWidget#isValidClickButton` 的字节码就是 `button() == 1`。
   网页端已经帮你把浏览器的 0-based 加 1 了。

4. **`outline` 的参数是 `(x, y, width, height)`**，不是 `(x0, y0, x1, y1)` —— 和 `fill` 不一样。

5. **浅色底上的文字不能带阴影**。原版阴影色是"颜色除以 4"，`#404040` 的阴影接近纯黑，
   压在 `#C6C6C6` 上会糊成脏边。用 `context.text(font, text, x, y, color, false)`。

6. **只发 `mouseClicked` 不发 `mouseReleased`**，那些"按下时记拖拽、松开时才判定点击"的界面
   （分组折叠、拖拽排序）在预览里会一直卡在拖拽状态。预览器已经支持 `type=release`。

7. **`§a` 这类颜色码**由渲染器解析（已实现 16 色），但 `§l` 粗体 / `§n` 下划线等格式码只跳过不实现。

## 适用范围

**适合**：纯 2D GUI 为主、绘制 API 面可控的 mod（当前两个项目只用到 `fill` / `text` / `outline` / `blit` / scissor）。

**不适合**：重度自定义渲染（`blitSprite` + 自定义 `RenderPipeline`、shader、`item` / `entity` 渲染）——
`stubs/` 的 API 面会指数级增长，投入产出比会崩。
