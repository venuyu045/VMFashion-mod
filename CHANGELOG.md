# Changelog

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中

- 官方签名公钥接入（后端生成密钥对 → 替换内置常量 → 官方后端开 `requireSignature`）
- 内容审核流 / 举报 / 下架（后端）
- 后端多 worker（Redis 或粘性会话）
- 衣柜键盘导航（本轮已给方案，见 `research/audit-v1.2/mod-fixes.md` §3）

## [2.0.0] - 2026-11-03

「合订版」：把独立标点模组 **VMPing** 完整并入时装模组 —— 标点复用时装账号体系与后端，
配置入口进游戏内「时装设置 → 标点设置」，玩家只需要登录一次、配一次后端。
配套后端（VMTools-Web）的 `/api/ping` 已支持时装 token 并已上线；独立版 VMPing 仓库保持原样。

### 新增（标点：VMPing 合订版）

- **独立标点模组 [VMPing](https://github.com/venuyu045/VMPing-mod) 的功能完整并入**，作为时装模组的附属功能发布（`com.venus.vmfashion.ping` 子系统）。独立版 VMPing 仓库不再更新，协议 v1.1 对合订版仍然适用。
- **标点轮盘**：默认按住**鼠标中键**弹出轮盘（注意 / 危险 / 物品 / 集合 / 求助 5 种），松开在准星方向打标点，Esc 取消；按住标点键期间用 mixin 抑制原版「选取方块」。
- **多人可见**：标点经 `/api/ping` 中继长轮询同步给同服玩家（服务器零安装），世界内渲染类型图标 + 距离 + 标点者名字（按住 Tab 强制显示名字），屏幕外画边缘方向箭头，20 秒 TTL 最后 3 秒淡出。
- **降级可用**：未登录时装后端 / 后端不可达 / 单机 / 局域网 → 自动退化为本地标点（功能照用，只是别人看不见），HUD 状态角标说明原因，不弹窗不刷屏。
- **认证复用时装账号体系**：标点中继不再自己跑 challenge/join/verify，通过新增的 `PingAuthBridge` 复用同步层的 `FashionAuth` 会话（同一个 MC 账号、同一份 24h token）；token 过期 / 401 时自动重新认证。配套后端 `/api/ping` 已改为同时接受 `aud=fashion` 与 `aud=ping`（旧独立版 VMPing 客户端不受影响），见 VMTools-Web `docs/ping-api.md`。
- **后端地址默认跟随时装后端**：`/api/fashion` → `/api/ping` 自动推导，玩家只需要配一次后端；也可以在标点设置里单独覆盖。
- **15 个标点配置项**：`config.json` 新增 `ping` 对象（开关 / 显示项 / 音量 / 最远距离 / TTL / 长轮询 / 边缘箭头 / 状态角标 / 降级 / 调试日志 / 后端覆盖地址），全部可在游戏内修改并热生效。完整表格见新增的 [`docs/ping.md`](docs/ping.md)。
- **命令**：`/vmfashion ping`、`/vmfashion ping [类型]`、`/vmfashion ping open|settings|status|debug|help`、`/vmfashion ping config reload`（不再注册顶层 `/vmping`，避免与仍装独立版的玩家冲突）。
- **键位**：新增「标点」键位（默认鼠标中键），与衣柜 `K` 同分类，可在「设置 → 控制 → 按键绑定」改键。
- **独立版检测**：同时装了独立 VMPing 时，聊天栏提示移除（会重复注册中键与 HUD）。
- **测试**：移植 8 组 / 75 个纯逻辑用例（协议编解码、房间键、投影、轮盘几何、边缘箭头、存储）；新增 `PingSettingsTest` 覆盖默认值 / 越界收敛 / 后端地址推导。全仓单测 438 个全绿。

### 新增（设置界面）

- **游戏内时装设置界面 `gui/SettingsScreen`**：14 个时装配置项（UI 缩放 / 动画 / Toast / 总开关 / 渲染他人 / 渲染距离 / 模型缩放 / 同步开关 / 衣柜过滤 / 刷新间隔 / 后端地址 / 皮肤站 / 下载服务器包 / 缓存上限）可视化修改 —— 此前只能手改 `config.json`。完成 = 写回配置 + 存盘 + `ConfigReloader.reload()` 热生效；取消丢弃；恢复默认。
- **标点设置界面 `gui/PingSettingsScreen`**：标点全部 15 个配置项 + 「实际使用的后端地址」显示（跟随时装后端推导的结果），入口在时装设置里。
- **衣柜 header 新增「设置」按钮**（「关闭」左侧），打开时装设置。
- 两套界面沿用 `VanillaStyle` 原版容器色板（与衣柜一致：浅灰面板、深灰文字、凸起/凹陷，不用紫色描边），自适应窗口大小与 GUI 缩放。

### 变更（界面）

- **游戏内衣柜改成 Minecraft 原版容器界面风格**（物品栏 / 箱子那种），不再是自绘的深紫配色。
  配色不是"看着像"，而是从客户端 jar 的
  `assets/minecraft/textures/gui/container/inventory.png` 里**逐像素量出来**的：
  面板底 `#C6C6C6`、槽位内底 `#8B8B8B`、槽位边框 `#373737`、面板阴影 `#555555`。
  - **面板**：1px 纯黑外框 + 左/上 2px 纯白高光 + 右/下 2px 深灰阴影 + 四角各留 1px
    （原版那种"像素圆角"）。标题改为居中 —— 原版容器标题都是居中的。
  - **分类 tab**：改用原版的「凸起 / 凹陷」语言。选中的 tab 是"按下去"的凹陷，
    未选中是凸起，悬停整体提亮一档。**不再用紫色描边** —— 原版 GUI 从不用颜色表示选中，只靠明暗。
  - **时装卡片**：做成放大的原版物品格（深灰边框 + 浅灰内底 + 右/底内侧 1px 白）。
    格子间距从 6 收到 3，相邻格只隔 1px 面板色，看起来是一片格子矩阵而不是一堆独立卡片。
    空槽位不再填色（原版空物品格就是纯槽位底，再叠一层灰反而糊成一块）。
  - **预览栏**：做成原版"凹陷面板"；未选中时画一个空物品格 + 提示，而不是一片空白。
  - **滚动条**：网格内容超出时出现原版滚动条（黑色轨道 + `#C0C0C0` 滑块 + 1px 描边），
    画在网格与预览栏之间的 padding 里，不占卡片空间。配色取自原版
    `widget/scroller.png` / `scroller_background.png`（那两张本来就是纯色块，用 `fill` 画等价）。
  - **文字**：浅色底上一律**不带阴影**。原版阴影色是"颜色除以 4"，`#404040` 的阴影接近纯黑，
    压在 `#C6C6C6` 上会糊成一团脏边 —— 这是改造过程中最影响观感的一处。
    顺带把 `centeredText` / `textWithWordWrap` 换成自实现的 `drawCentered` / `drawWrapped`，
    因为原版这两个重载固定带阴影、没有 `shadow = false` 的版本。
  - **按钮 / 输入框**：本来就是原版精灵图（`widget/button.png`、`widget/text_field.png` 九宫格），保持不变。
  - **Toast**：从"每个状态一种彩色底"改成原版 tooltip 语言（深紫黑底 `#100010` + 亮色文字 +
    状态色边框），压在浅灰面板上不再像贴纸。
  - **确认框**（`ConfirmScreen`）同步改造，配色和画法与衣柜统一。
- **新增 `gui/VanillaStyle`**：把原版绘制原语（面板 / 物品格 / 凸起凹陷 / 双线分隔）抽成一处，
  衣柜与确认框共用。各写一份必然会漂移 —— 改了一处忘了另一处，界面就会"一半像原版、一半不像"。
- **`ModConfig.UI` 重新定义为原版色板**，并删掉 4 个改造后已无引用的常量
  （`BG_COLOR` / `HEADER_COLOR` / `BORDER_COLOR` / `INSET_COLOR`）——
  项目路线图把"死字段"列为要清理的问题，不留着当摆设。
- **副产物**：上面这些改动全部在 `tools/uipreview/` 里逐帧验证过（8 个场景 + 布局自检 +
  浏览器交互），预览器本身没改一行就能用来做这次 UI 重构。

### 新增（开发工具）

- **`tools/uipreview/` 改造成可跨项目复用**。原来它把 `WardrobeScreen`、`Fixtures`、
  场景表全硬编码在内核里，换一个 mod 就得改内核代码。现在分成两层：
  - **内核**（`stubs/` + `harness/` + `web/` + `assets/`）：不认识任何项目类，
    只通过 `UiPreviewAdapter` 接口问"预览哪个界面、造什么数据、有哪些场景"；
  - **适配层**（`app/` + `fake/`）：唯一知道项目类名的地方。
  移植五步见 `tools/uipreview/PORTING.md`（已在 VMTools 项目上实测跑通）。
  顺带补上的通用能力：
  - `--all` 改为遍历适配器声明的场景（脚本不再硬编码场景名）；
  - 支持**一个 mod 多个界面**（`createScreen(scenario)`，`--scenario=settings` 能预览设置界面）；
  - 交互事件补 `release`（很多界面把"没拖动就当点击"放在 `mouseReleased` 里）；
  - 渲染器支持 `§0`–`§f` 原版颜色码（原来会原样画出 `§a开启`）；
  - 新增 `FabricLoader` 替身（把配置目录指到可写临时目录），
    让那些直接用 `FabricLoader.getInstance().getConfigDir()` 的类能跑通；
  - `/info` 返回 `title` / `scenarios` / `state`，网页的场景下拉框改为动态填充。

- **`tools/uipreview/`：无头 UI 预览器 —— 不用显卡、不用启动游戏就能看界面，而且能在浏览器里真的点。**
  背景：开发机无 X / 无 libGL / cgroup 1.2GB，起不了客户端；查遍现有工具（详见
  `docs/ui-preview-tooling.md`）发现**没有任何现成方案**能同时满足「不启动客户端 + 预览任意
  Screen + 可交互」—— 官方的 Fabric Client GameTest 要真客户端（差 7GB 内存），
  hud designer / Universal Preview / GUI Inspector 之类只管 HUD、3D 模型或必须真机。
  实现方式：MC 26.x 是提取式渲染，`GuiGraphicsExtractor` 只往渲染状态写命令、不碰 GL，
  于是把它换成录制器（`stubs/net/minecraft/client/gui/GuiGraphicsExtractor.java`），
  产出 `DrawCommand` 列表后用 Java2D 重放成 PNG。**`WardrobeScreen` 一行未改**，
  布局与逻辑全部由生产代码计算，所以预览图上的坐标就是游戏里会出现的坐标。

  - `bash tools/uipreview/run.sh --all --report`：8 个场景各出一张 PNG + 布局自检
    （越界 / 零尺寸 / 文字出屏 / 空帧）。
  - `bash tools/uipreview/serve.sh`：起交互服务，浏览器里点击 / 滚轮 / 打字全部喂进真实的
    `Screen#mouseClicked` / `mouseScrolled` / `charTyped`，改完代码刷新页面即可看新布局；
    `GET /info` 返回界面算出来的真实布局坐标（面板 / 网格 / 每个 tab / 每个按钮），供脚本定位元素。
  - 字宽逐字符取自游戏内 `ascii.png`（`gen-mc-font.py` 生成 `McAsciiFont`），
    中文按 unifont 的 9px 步进；按钮 / 输入框用客户端 jar 里原样的九宫格精灵图。
  - 过程中核对出三处「猜错就会让预览骗人」的语义：`outline` 参数是 `(x,y,w,h)` 而不是
    `(x0,y0,x1,y1)`；鼠标左键编号是 SDL3 的 **1** 而不是 GLFW 的 0；`text` 的颜色会被
    `Component` 自带样式覆盖。详见 `tools/uipreview/README.md`「踩过的坑」。

### 修复（发布前独立审查）

发布前做了一轮独立静态审查（逐条用 26.3 客户端 jar 字节码核对），发现并修掉：

- **中键抑制"选取方块"的 mixin 原本是死代码**：26.3 的 `Minecraft.tick()` 只在没有界面时
  才调 `handleKeybinds()`，而轮盘是在 tick 末尾（Fabric `END_CLIENT_TICK`）才打开的 ——
  "轮盘已打开"的守卫永远不成立，每次标点都会顺带触发原版 pickBlock（创造模式会覆盖手上物品）。
  改为「轮盘打开 **或标点键正被按住**」才取消，现在按下中键的那一 tick 起就抑制。
- **401 不再无脑清共享时装会话**：加了 60 秒冷却 + 认证成功不再清零失败计数，避免自建旧后端
  持续 401 时每秒一轮 challenge/join/verify 的认证风暴（也避免连带删掉时装同步的 token）。
- **房间 seq 回退时真的重置长轮询游标**：之前只清他人标点、游标不动，房间被回收后会永久收不到新标点。
- **429 限流也会退避重试**：之前一次限流就永久降级到本地，直到退服或手动 reload。
- **`start()` 重置"长轮询在飞"标志**：JOIN 事件与 tick 兜底竞争时不再出现"状态显示在线、但永远收不到标点"。
- **`/vmfashion ping open` 修复**：命令在聊天界面里同步执行，旧的 `screen() != null` 守卫让它变成空操作；
  `ping settings` 也不再把聊天框当 parent（关闭后不会弹回聊天框）。
- 轮盘 Esc 判断改用 `KeyEvent#isEscape()`（26.3 是 SDL 键码，硬编码 256 无效）；
  `PingService.tick()` 加异常保护（tick 异常不再可能崩客户端）；长轮询加 200ms 地板防请求风暴；
  HUD 状态角标现在显示具体原因（单机 / 未登录 / 明文后端 / 限流…），不再只有一句"服务不可用"；
  标点设置界面的"实际后端地址"改为 init 时计算，不再每帧 new 对象。
- 文档同步：配置项数量 14→15、抑制选取方块的准确描述、`/vmfashion ping open` 可用性、状态角标说明。
- 新增 `PingStore#resetCursor` 单测。

### 工程 / 发布

- 版本号升至 **2.0.0**（新增标点功能，旧 `config.json` 向后兼容：没有 `ping` 对象时使用默认值）。
- `fabric.mod.json` 描述补充标点功能；README 更新功能 / 安装 / 命令 / 配置 / 限制章节；新增 `docs/ping.md`；`docs/architecture.md` 补标点链路。
- UI 预览器新增 `settings` / `ping_settings` 两个场景（支持一个 mod 多界面），`--all` 共 10 个场景出图 + 布局自检；新增 `AbstractSliderButton` / `ConfigReloader` / `ChatFormatting` 等预览替身。
- 渲染自检的 mixin 校验从 13 个增加到 15 个（新增 2 个标点 mixin）。

## [1.3.1] - 2026-11-02

修复版。1.3.0 把游戏内衣柜改成「网站收藏」之后，第 6 轮独立审查（mod 两路 + 网站前后端三路，
共 **61 条发现：严重 0 / 中等 22 / 轻微 39**）里 mod 侧的问题全部修掉；同时修掉配套网站后端
在本轮暴露的 5 处问题（见「配套网站」）。**升级即生效，无需改配置**。

### 修复

- **已下架时装（`missing`）现在真的"显示但不可穿"**：1.3.0 完全没解析服务端这个字段，
  已下架的时装在衣柜里长得跟正常一样、点上去必然静默失败。现在：`WardrobeCollection` 解析并落盘
  `missing`；衣柜卡片置灰 + 红色「已下架」角标（盖掉「未下载/下载中」）+ 悬停说明；
  点击只选中不下载不穿戴；详情页按钮文案变「已下架，不可穿戴」；保存穿戴时自动丢弃这些槽位
  并提示（否则每次保存都会被后端 skip 一串）；状态行与 `/vmfashion wardrobe` 显示「已下架 N」。
- **换账号 / 换后端时衣柜串号（隐私）**：`wardrobe.json` 现在记录归属（`owner = mcUuid|apiBaseUrl`），
  启动 / 进服 / 拉到新收藏三处校验；换号或换后端立即清内存并删缓存，断网时也不会把 A 的收藏
  显示给 B。旧缓存没有 owner 字段时只补记、不清理。
- **离线清单从未生效**：1.3.0 每次启动都会删掉 `pack-catalog.json`，于是文档承诺的
  「断网时按上次清单渲染」从来没发生过。改为首次初始化不再删（`lastBaseUrl` 用 `null` 哨兵区分
  「没初始化」和「后端地址被清空」）。
- **`/vmfashion wardrobe refresh` 四条失败路径静默无反馈**：现在 `refreshWardrobe` 返回结果枚举
  （已发出 / 已有请求在飞 / 同步未启用 / 未登录 / 后端不支持该端点 / 退避中），命令按真实原因提示；
  成功也会回报件数。**零新增 lang key**。
- **收藏镜像没有输入上限**：异常/恶意后端返回超大数组时会把内存与界面打爆。现在
  `parse` + `normalize` 双闸（最多 4096 条、单字段 200 字符，超限丢弃并记一次日志）。
- **服务端省略 `linked` 时被误判成「未绑定」**：现在「有条目但没 `linked` 字段」按已绑定处理
  （显式 `false` 仍然尊重），避免网站明明加了收藏、游戏里却完全没生效。
- **短 ID 收藏在包下载完成后「已穿」标记消失**：收藏里存的是 `angel`（无包名前缀），下载完
  时装包后注册表里的 ID 变成 `lw_angel:angel`，旧代码只做全等比较 → 判定「没穿」、预览被清空。
  新增纯逻辑 `gui/CosmeticIdMatch`（全名相等，或「至少一方没有包名前缀时 localId 相等」；
  两边都有前缀且不同 → 不是同一件），`isWorn` / `ensureSelectedValid` 改用它。
- **大 GUI 缩放 / 窄窗口下三栏重叠且会误点**：面板被夹到 `width-8` 后网格与搜索框画到预览栏上，
  但命中判定仍覆盖被遮挡区域（点预览区会命中卡片）。布局改为按「实际面板宽反推缩放」
  （`min(uiScale, 宽/320, 高/174)`），命中区域与绘制区域一致；面板下限不再越过屏幕。
- **点未下载的时装却只弹「正在下载…」然后没动静**：纯本地模式 / 已有同名包 / 验签被拒 / 退避窗口里
  下发请求会静默返回，玩家在等一个永远不来的包。现在按「是否真的进了下发队列」分流提示。
- **空态文案优先级**：搜索无结果时错显示「衣柜是空的，去网站加收藏」，改为搜索优先。
- **其他（轻微）**：卡片间隙命中 off-by-one；底部状态行每帧 2 次 `describeStatus` + 6 次字符串分配
  （加 250ms 缓存）；Toast 跨线程投递的 `ConcurrentModificationException`（改 COW）+ 换行结果缓存 +
  窄屏文本夹取；`registerTexture` 失败时 `NativeImage` 泄漏；`invalidate()` 只清真正释放过的贴图；
  动画负缓存加时间戳可整条淘汰；`ui_state.json` 改原子写（写一半崩溃不再丢分类记忆）；
  `countQuads` / `indexBones` 的 null 防护；`/vmfashion wardrobe` 未加载时误报「未绑定」；
  `LocalLoadout#wear` 入参加固（长度 / 控制字符）；`/vmfashion diag` 补「网站衣柜收藏」段
  （linked / 件数 / 已下架数 / 上次刷新 / 归属身份 / 在飞 / 退避 / 端点不支持）。
- **emissive 遮罩没有像素上限**：4096² 贴图 = 1600 万次像素读 + 16MB 布尔数组，在渲染线程同步跑。
  加 2048² 闸（超限 = 该贴图不发光 + 每件只告警一次）。

### 修复（配套网站，同版本）

网站后端（`vmtools-next`）本轮同步修了 5 处，**不升级 mod 也用得到**：

- `/assets/{key}` 与 `/packs/{id}/{v}/download` 恢复 `Depends(get_db)` 依赖注入（事故修复保留：
  查完立刻 `close()`，文件流期间不占连接池）—— 之前绕过 DI 导致测试注入的临时库失效；
- 管理端上传（资产 / 时装包）的落盘与 zip 校验移出事件循环（单 worker 下上传不再卡住全站推送）；
- `POST /wardrobe` 落库统一成**规范全局 ID**（`packId:localId`）：历史「简单 ID」行在再次加入时
  就地升级，幂等判定同时认上报值 / 规范值 / 目录原值 —— 修掉「mod 里永远显示未下载、点了不动」；
- `/packs` 清单快照只在 SQL 里取 `.zip` 行（不再把全部资产行物化）；
- 修掉 12 条与实现漂移的测试（`download_pack` / `get_asset` 签名、router 前缀断言）。

### 变更

- 语言文件 163 → **169 key**（中英成对，占位符逐条比对 0 处不一致）。
- `/vmfashion wardrobe` 的输出多一段「已下架 N」（仅在确有已下架条目时出现，不打扰正常玩家）。
- 衣柜底部状态行第三行在收藏视图下追加「· 已下架 N」。

### 兼容

- `config/vmfashion/wardrobe.json` 旧格式（无 `owner` / 无 `missing`）可直接读：缺字段按默认值处理，
  首次成功拉取后自动补写新字段。
- 自建后端没实现 `GET /wardrobe/me` 时行为不变（记一次日志 → 本会话不再重试 → 衣柜退回显示本地全部）。
- 已知限制：窗口逻辑分辨率小于 230×190 时三栏布局仍会重叠（点击不会误触，只是视觉拥挤），
  需要重构布局才能彻底解决，留待后续版本。
- 已知限制：衣柜键盘导航（方向键 / 焦点框）仍未实现（v1.2 起就有的增强项，需要真机验证按键行为）。

## [1.3.0] - 2026-11-01

衣柜和网站「我的衣柜」打通：**游戏内衣柜只显示你在网站时装商店里「加入衣柜」的时装**，
不再把"本地装了哪些包"全列出来。

### 新增

- **游戏内衣柜 = 网站衣柜收藏**：进服认证成功后拉 `GET /api/fashion/wardrobe/me`，
  衣柜界面只渲染这份收藏（新类 `cosmetic/WardrobeCollection`，内存镜像 + 磁盘缓存）。
  收藏里**本地还没装包**的时装也会列出来（名称 / 分类 / 所属包来自服务端），
  卡片显示「未下载」/「下载中…」角标，点穿上会自动触发时装包下发，装好后卡片自动变成正常卡片。
- **收藏缓存落盘**：`config/vmfashion/wardrobe.json`（原子写 + fsync）。断网 / 后端不可用时
  按上次的收藏过滤，而不是突然退回"全部时装"。
- **`/vmfashion wardrobe`**：查看绑定状态、收藏件数、上次刷新时间、当前是否按收藏过滤；
  **`/vmfashion wardrobe refresh`** 立即重拉（成功 / 失败都会在聊天栏回一句）。
- **底部状态行第三行**：常驻说明当前列表的数据来源（收藏 N 件 / 未绑定 / 未加载 / 配置关闭），
  玩家发现衣柜里东西变少时一眼能看出原因，而不是以为模组坏了。
- **配置项 `wardrobeFilterEnabled`**（默认 `true`）：关掉就退回旧行为（显示本地全部时装）。

### 变更

- 未绑定网站账号 / 后端没实现该端点 / 收藏还没加载时，**自动退回显示本地全部**并在状态行
  说明原因 —— 不会出现"打开衣柜一片空白"。
- 收藏同步策略：进服一次、此后每 5 分钟最多一次、绑定成功后一次、打开衣柜时按 60 秒节流刷新。
  401 走一次重新认证后重试（不会循环）；404/405/501 记一次日志后本会话不再重试；
  其它失败指数退避（30s 起，封顶 5 分钟），**绝不清空**已有收藏。
- 衣柜界面数据源变化（收藏刷新 / 时装包下载完成）后自动重建列表，不用重开界面。

### 兼容

- 自建后端没实现 `GET /wardrobe/me` 时自动降级为旧行为（显示本地全部），不报错、不刷屏。
- 网站侧接口契约见 `docs/fashion-api.md` §5.5。

## [1.2.1] - 2026-10-31

修复版：衣柜分类栏 / 卡片左键点不动、默认连不上官方后端，并清掉内置示例包。

### 修复

- **衣柜分类栏和时装卡片左键点击无效**：26.3 的鼠标事件由 SDL3 上报，按钮编号从 1 开始
  （左 1 / 中 2 / 右 3），而 `WardrobeScreen` 仍按 GLFW 的 0 判定左键，导致自定义绘制的
  分类 tab 与卡片把所有左键点击都吞掉（原版 Button 不受影响，因为它们按原版
  `AbstractWidget#isValidClickButton` 判定）。现在按 1 判定，左侧分类栏、中间卡片都能正常点选。
- **默认连不上官方后端**：`apiBaseUrl` 的默认值从「留空」改成官方实例
  `https://www.venusyu045.cn/api/fashion`，装好 mod 就能用云端衣柜；老配置里的空地址会在
  启动时一次性迁移（写入 `configVersion=2`，之后玩家主动清空就保持纯本地，不会被反复填回）。
- **删除内置示例包**：jar 里不再打包 `default_pack`（示例背板 / 示例皇冠），启动时会自动
  删除旧版本释放到 `config/vmfashion/packs/example/` 的那份（只删 `id=example` 且
  `author=VMFashion` 的清单，玩家自己的包不碰）。

### 变更

- 无头自检改用 `tools/verify/fixtures/sample_pack/`：原示例包保留为测试 fixture，
  不再随 jar 分发、也不再释放到玩家目录。

### 新增

- **官方时装包 `official`（深渊龙翼）**：内置时装包 `assets/vmfashion/official_pack/`
  （packId=`official`），首件时装 `official:dragon_wings`（分类 `wings`，挂 `body`）。
  做法是「整块 alpha 裁形的翼膜贴图板 + 独立翼骨 + emissive 前缘发光棱 + `flap` / `idle`
  两段 Bedrock 动画」；翼膜外形（扇形、指间扇贝弧、拖尾）直接画在 256×256 贴图的 alpha
  通道里，靠 `cutout` 裁出平滑轮廓，而不是用一堆小方块拼扇骨。
- **内置包改为按版本更新**：`DefaultPackInstaller` 原来「本地有 `pack.json` 就跳过」，
  而内置包落在**用户包目录**（`AssetSyncService` 对用户包永不下载/永不更新）—— 官方包以后
  在网站上升级，玩家更新 jar 也拿不到新内容（审计 v1.3 R1）。现在改为「jar 里的 `version`
  严格更新才覆盖」，本地清单读不出来（被手改坏）一律不动；新增单测覆盖版本比较与覆盖判定。
- **无头效果图渲染器 `tools/preview/`**：无显卡环境下用**生产代码**烘焙 + 渲染时装模型
  （GeckoLib 烘焙 → `GeoModelRenderer` 出顶点 → 纯 CPU 软光栅出 PNG），支持多视角、
  动画帧、商店缩略图，并输出包围盒 / 对称性 / 贴图自检报告。
- **时装包发布脚本 `tools/publish/publish_pack.py`**：把包目录打成可复现的 zip（固定时间戳）、
  走站点管理端 API 上传包 + 缩略图 + 目录条目，并回读校验 sha256 / 字节数。
- **模型生成脚本 `tools/dev/gen_dragon_wings.py`**：龙翼几何 / 贴图 / 发光层 / 动画全部由脚本
  参数化生成（左右镜像、角度符号换算都在脚本里），改一个参数就能重出整套资源。

## [1.2.0] - 2026-10-31

「看不见的失败」与边界条件版本。本轮做的是**第二轮独立审计**（`research/audit-v1.2/mod.md`，
0 严重 / 8 中等 / 14 轻微）里 9 个中等项的修复；没有新功能，目标是「出问题时玩家能看见、
并发/畸形输入下不静默坏掉」。**升级后如果 `apiBaseUrl` 是明文 http，同步会明确报错**（见下）。

### 修复

- **同步失败不再静默（N01）**：后端 5xx 或返回未识别的错误码时，客户端原来把提示映射成 `null`
  → 玩家看到「已穿上」但云端没保存，下次进服被覆盖。现在统一兜底成
  「时装后端暂时不可用（HTTP xxx），本次改动可能没有保存，请稍后重试」，沿用既有 15 秒去重，
  不会刷屏。
- **LRU 淘汰与安装竞态（N04）**：淘汰线程可能在「目录已就位、`installed` 还没登记」的窗口里
  删掉刚装好的包，导致该时装本会话永久不显示。现在淘汰前校验「记录未被替换 + 不在 inFlight」，
  并用 `remove(key, value)` 两参删除。
- **`/vmfashion cache clear` 不再卡主线程（N02）**：磁盘递归删除挪到已有 IO 线程池，
  完成后回一条「清理 N 个目录 / 释放 X MB」。
- **缺失 UUID 负缓存（N03）**：后端在 `resolve` 响应里省略某 UUID 时，客户端原来每秒重查一次；
  现在 45 秒负缓存，长期后台流量归零。
- **明文后端不再发送凭据（N05，行为变更）**：`apiBaseUrl` / 清单里的下载地址必须是 `https`
  （`http://localhost`、`127.0.0.1`、`[::1]` 回环除外）。非 https 时**不发送 `Authorization`**，
  同步层报 `insecure_backend` 并只 warn 一次。与 `authServer` 既有的 https 强制口径一致。
  局域网 http 后端需要改 https 或走本机回环。
- **远程模型复杂度上限（N06）**：单个 `.geo.json` ≤ 4 MB、单模型 ≤ 20000 quad，
  超限跳过该模型并记日志（走既有坏模型兜底路径，不崩客户端），防止一个 64 MB 的 JSON 卡死渲染线程。
- **衣柜逐件状态反馈（N08）**：下载中显示 `…`、加载失败显示 `!` 并可点击重试（30 秒后自动重试一次），
  底部显示「失败 N」，穿上失败改成错误 toast —— 不再出现「界面说已穿上、身上却没有」。
- **`pack.json` 声明 id 校验（N13）**：声明的 localId 含 `/`、`\`、`..` 时拒绝加载，
  堵住 `resolveSafe` 被绕过后读包目录外文件。
- **验签状态可见（N18）**：`/vmfashion status` 与 `/vmfashion diag` 现在显示签名策略
  （严格/宽松）、受信密钥数、最近一次结论与拒绝计数、以及「内置公钥是否仍是占位」，
  不再需要读源码判断。不输出公钥明文。

### 文档

- `docs/fashion-api.md`：补「下载地址与 `apiBaseUrl` 必须是 https（回环除外）」「明文后端不带凭据」，
  故障表新增对应行；
- `docs/pack-format.md`：安全限制表补 4 MB 模型文件 / 20000 quad 两条；
- `docs/troubleshooting.md`：`apiBaseUrl` 条目补 https 要求。

### 工程

- 单测 **255 → 265**（新增 10 条：下载地址白名单、负缓存 TTL、模型大小上限、淘汰竞态 3 条等）；
- `check-lang` / `check-jar` / `check-mod-json` / `list-i18n-todo` 全过；`tools/verify/run.sh` 7/7。

## [1.1.0] - 2026-10-30

「表现力 + 数据链路」版本。核心是修掉一条**名义上存在、实际永远不生效**的链路
（服务端包新版本永远拉不下来），并补三个内容制作侧的直接痛点。

### 新增

- **服务端时装包自动更新（P0）**：以前 `installed` 里一旦有记录就永久短路，后端发了新版本，
  老玩家会静默停在旧包，只能 `/vmfashion cache clear`。现在：
  - 清单刷新后 diff `version` / `sha256`，有过期包立刻排队更新；
  - 安装改成「旧目录整体改名让位 → 装新版本 → 成功后删旧」，任一步失败都把旧目录移回去；
  - 缓存体积按差值更新（不再把旧包体积重复计入，避免误触发 LRU 淘汰）；
  - 判定规则抽成纯函数 `PackUpdatePolicy`（sha 优先、版本回退、信息不足保守不动），有 9 条单测。
- **半透明材质**：`pack.json` / `meta.json` 支持 `material: "translucent"`（兼容旧写法
  `"translucent": true`），走 `entityTranslucent` 管线，可以做纱裙 / 半透翅膀 / 玻璃挂件。
  默认仍是 `cutout`；非法值回退 `cutout` 并告警。
- **多动画按名选择**：一件时装里有多个动画时，用 `animationName` 指定播哪个
  （精确匹配 → 大小写不敏感 → 回退第一个并只告警一次）。以前只能播第一个。
- **受伤红闪**：时装的主层与 emissive 层都改用 `OverlayTexture.pack(NO_WHITE_U, hasRedOverlay)`，
  挨打时和原版皮肤一起闪红（以前写死 `NO_OVERLAY`，看起来像"时装没挂住"）。

### 修复

- **文档与实现不一致**：`docs/fashion-api.md` 说客户端按 `(packId, sha256)` 判重、`packVersion`
  用于增量更新 —— 实际旧实现只看"本地有没有"，本轮实现后才真正成立；文档同步改写。
- **5 处过时数字**：`docs/architecture.md` 的 mixin 数（12 → 13）、`CONTRIBUTING.md` 与
  `tools/verify/README.md` 的自检步数（5/4 → 7）、`docs/modrinth.md` 的单测数（231 → 255）。

### 工程化

- 单测 **235 → 255**：`PackUpdatePolicyTest`（9 条）、`AnimationSelectionTest`（6 条）、
  `PackLoaderTest` 补材质 / 动画名解析（5 条）。
- `PackLoader` 的材质解析对非法值只告警不丢件；`animationName` 空白等价于旧行为。

### 已知限制

- 半透明自定义几何**不按距离排序**（提交阶段决定前后关系），同屏多件半透明可能穿帮；
- 包更新仍是"整包重下"，没有做增量 / 断点续传（`docs/fashion-api.md` 的 `packVersion` 只用于判重）；
- 其余限制同 1.0.1（molang / 动画控制器、第一人称手持、hideParts 共享模型、光影兼容性、多 geometry）。

## [1.0.1] - 2026-10-29

第 4 轮审查（mod 客户端 + 配套网站的前端/后端一起审）后的修复版。功能与 1.0.0 一致，
修的是**长时间在线 / 反复重载 / 多人同屏**下才会暴露的问题，以及和后端实际实现对齐的兼容性。

### 修复

- **配置热重载泄漏 HttpClient**：`/vmfashion config reload` 是官方支持的改后端地址方式，但每次重载
  都会 `new` 两套 `HttpClient`（API 层 + 认证层）且从不关闭 → 每次重载泄漏一套选择器线程 + 线程池
  （JDK 21+ 回收时还会打 "HttpClient was not closed"）。现在 `SyncService.initialize` 覆盖前统一释放。
- **在线超过 token 有效期后，换装上报与云端拉取永久静默失效**：`Session.expired()` 为真时旧实现
  直接 `return`，玩家换装永远不上报、网站衣柜的改动也永远拉不下来，且**零提示**（表现为"换装不生效"）。
  现在会补一次认证，认证成功后用**当前快照**补报一次；带 60 秒冷却，避免后端返回极短 `expiresIn` 时
  形成认证风暴。
- **`not_joined` 提示把玩家引向错误操作**：后端这个错误码的真实含义是「会话服务器没有确认该玩家已加入」
  （登录令牌过期 / join 未完成），旧文案却写"还没绑定网站，执行 /vmfashion bind"。
- **5 类错误码此前完全静默**：`challenge_expired` / `uuid_mismatch` / `upstream_unavailable` /
  `server_misconfigured` 没有映射 → 认证失败时玩家看不到任何提示；`invalid_slot` / `duplicate_slot` /
  `too_many_uuids` / `invalid_scale` 等参数级错误码同理。现在都有对应提示。
- **渲染热路径每帧 `new String[]`**：多人同屏时每秒上千次分配，与"渲染热路径零分配"的承诺不符。
  改为 `ThreadLocal` 复用缓冲（用完清引用，不会长期持有字符串）。
- **`GET /packs` 兼容后端列表惯例**：后端若返回 `{"items":[...]}`（`/catalog`、`/identities/me`
  等都是这个形状）旧实现会解析出 0 个包，只留一行"清单已更新：0 个包"。现在 `packs` 优先、
  缺失时回退 `items`。
- **`publishedAt` 兼容 ISO-8601**：站点文档的全局约定是 ISO-8601 字符串，而契约要求毫秒 number；
  旧实现只认 number，后端照文档实现时时间戳会被静默读成 0。现在两种都认（认不出回退 0，不丢条目）。

### 文档

- `docs/fashion-api.md`：§1 补充客户端对 `{"items":[...]}` 与 ISO-8601 `publishedAt` 的兼容；
  §6.6 **纠正"官方后端建议在 v1.0 就打开严格模式"的错误建议** —— 后端没真的签名时开严格模式会
  拒绝全部服务端包，正确顺序是"先签名验证通过 → 再开严格模式"。
- `README.md`：修正过时描述（渲染自检 4 步 → 7 步、mixin 12 → 13 个），删掉"后端 scale 还没进数据链路"
  这条已过时的限制。

### 工程化

- JUnit 5 单测 **231 → 235**：`PackCatalogTest` 新增 4 条契约形状回归（`items` 回退、`packs` 优先、
  毫秒/ISO/数字字符串时间戳解析）。
- 语言文件 121 → 125 keys（新增 4 条错误提示），`check-lang.sh` 通过。
- 无显卡自检 7/7 通过（含 13 个 mixin 的目标方法签名校验）。

### 已知限制

- **本地包里的时装同步到云端**：内置示例包（`example:crown` 等）不在站点目录里。配套后端（vmtools-next
  本轮已改）现在会**跳过**这些条目并在响应的 `warnings` 里列出，其余槽位照常保存；旧后端仍是
  "任一条不存在就整条 404"，那种情况下上报会失败。想完全消除：把这些 ID 登记进站点 catalog，
  或上报前先卸下本地包时装。
- 其余限制同 1.0.0（光影兼容性、多人同屏真机帧率、动画子集等）。

## [1.0.0] - 2026-10-29

首个**可公开发布**版本（Modrinth 上架、陌生人敢装）。相对 0.2.0，补齐了 v1.0 清单上最后
四块：后端 scale 链路、包签名验签、发布加固、诊断可观测性；并修复了一个能让玩家掉线的严重缺陷。

### 新增

- **后端 `scale` 进数据链路**：provider 契约改为 `Map<UUID, Map<String, WornCosmetic>>`，
  最终缩放 = 包 scale × 网页衣柜 scale × 全局 scale（规则集中在 `ScaleMath`，仍绕挂载 pivot）。
  上报刻意不带 scale，避免游戏内换装把网页衣柜里的设置重置成 1.0。
- **时装包 Ed25519 签名与客户端验签**：清单增加 `signature` / `signingKeyId`，客户端用 JDK 自带
  Ed25519 在**解压前**验签；`config/vmfashion/trusted_keys.json` 支持多把公钥与 `requireSignature` 严格模式；
  `bash tools/dev/gen-signing-key.sh` 一键生成密钥对（纯 JDK）。
  默认**宽松模式**：未签名包放行、签名来自未知密钥也放行（只提示一次），只有"签名结构非法 /
  已知密钥但验不过"才拒绝 —— 这样后端开始签名不会打挂已有客户端。

- **诊断包补齐**：`/vmfashion diag` 现在每件时装带 `anim=` / `emissive=` 状态，
  并新增「渲染缓存 / mixin」段（已烘焙资源、动画实例数、`AvatarRenderStateMixin` 是否生效）；
  `/vmfashion status` 增加一行渲染缓存。玩家遇到"时装不显示"能自助定位。

### 修复

- **自动下发可能整体卡死（严重）**：下载没有读超时、安装用无超时 `join()`，后端返回 200 后卡住
  不发 body 会永久占住下载线程池 → 清单刷新/淘汰/后续安装全部停摆。现在有读超时 + 有超时等待 +
  看门狗强拆（close 卡住的流）+ 离服/清缓存立即中止。
- **离服后时装"消失 10 分钟"**：取消下载被当成失败写回了 10 分钟冷却。现在取消不计失败。
- **正在穿的时装被 LRU 误淘汰导致闪现**：渲染命中不刷新"最近使用"。现在命中会推进 LRU。
- **marker 写失败导致包在盘上却不显示**：现在写失败会回滚，下次重试重新安装。
- **严格验签配置写错会静默放行伪造签名（fail-open）**：现在配置错误一律 fail-closed（全拒）。
- **配置了真实公钥时未知密钥仍被宽松放行**：现在含真实公钥时未知密钥一律拒绝。
- **坏动画包每帧重建动画状态**：现在负缓存，只尝试一次。
- **坏模型不再崩客户端**：渲染回调加顶层 `try/catch(Throwable)` + 按模型实例拉黑（reload 自愈），
  骨骼 `cubes/quads/vertices` 为 null 时安全降级。26.x 的 flush 在主循环里，以前一个坏包能让玩家掉线。
- 动画缓存每帧拼 String key → 两层 Map 零分配；`AnimationFrame.update()` 同一帧只求值一次
  （主层 + emissive 层共用一组采样值）。
- 关键帧采样线性扫描 → 二分查找（上万关键帧时快 48 倍），并给单轨道关键帧数量加上限（防恶意 anim.json）。
- 烘焙失败的重试间隔 30 秒 → 5 分钟（重试在渲染线程做 IO + 烘焙，太频繁会周期性卡帧）。

### 工程化

- **JUnit 5 单测 231 条**（较 0.2.0 新增 67 条）：新增覆盖三层缩放、Ed25519 验签与宽松/严格策略、
  动画同帧采样节流（含整数溢出回归）、诊断探针。
- **无显卡自检 7 步**：在前 6 步基础上增加「坏模型兜底」（坏模型渲染不抛异常、第二次提交被跳过、
  正常模型不受影响）。
- 新增开发脚本：`check-mod-json.sh`（元数据字段 + entrypoint 类真的在 jar 里）、
  `gen-signing-key.sh` / `GenSigningKey.java`（纯 JDK 生成 Ed25519 密钥对）。
- CI 增加「元数据校验」步骤；发布文案与检查清单见 `docs/modrinth.md`。

### 已知限制 / 待外部环境验证

- **官方签名公钥**：内置公钥目前是占位值，真正的信任锚需后端用 `tools/dev/gen-signing-key.sh`
  生成密钥对后替换常量（在此之前带签名的包按宽松模式放行）。
- **光影（Iris）兼容性**：无 GL 环境，未验证。
- **其他时装 mod 的注入顺序**：13 个 mixin 都是 `@Inject`（无 `@Shadow`），但未在装了同类 mod 的环境实测。
- **多人同屏性能**：代码层面已做到热路径零分配，真实帧率需真机验证。
- **动画子集**：不支持 molang / timeline / animation_controllers，catmullrom 按线性处理。
- **第一人称**：手臂挂件仅空手时可见（原版行为）。

## [0.2.0] - 2026-10-29

这一版的目标是**「别人装上 mod 就能看到你穿的时装」**，且重启不丢、切服不乱、不用记命令。

### 新增

- **第一人称时装**：挂在 `right_arm` / `left_arm` 上的时装在第一人称空手时可见，
  跟随挥手 / 拉弓 / 装备切换动作；动画、emissive、scale、hideParts 全部自动跟随。
- **骨骼动画播放**：`anim.json`（Bedrock 动画）按关键帧循环播放，
  每（玩家 × 时装）独立相位 —— 多人同屏各自动画互不干扰。
  支持 `loop` / `animation_length` / `bones.{rotation,position,scale}` 线性插值；
  不支持 molang / timeline / animation_controllers。
- **中英双语**：界面与命令输出全部走翻译 key（120 个），跟随游戏语言；
  只剩包加载警告等开发者诊断信息是中文。
- **可视化衣柜界面**（默认按 `K` 打开，或 `/vmfashion open`）：
  分类 tab、贴图缩略图网格、搜索、右侧预览、穿上/脱下、同步与资产状态。
  缩略图懒加载（只烘焙画到屏幕上的卡片，每帧最多 2 件）。
- **时装包自动下发**：别人穿的时装你本地没装时，按 `sha256` 自动下载、
  安全解压（拒绝 `..`/绝对路径/符号链接/zip bomb）、原子安装到 `packs/server/`，
  装完主线程重载即可见。用户自己放的包永远优先；缓存按 LRU 淘汰，`/vmfashion cache clear` 只清服务端包。
- **本地穿戴持久化**：`config/vmfashion/loadout.json` 原子写 + 2 秒 debounce，
  纯本地模式重启不再丢穿戴。
- **配置热更新**：`/vmfashion config reload` 重读配置并重建同步层，改 `apiBaseUrl` 不用重启游戏。
- **诊断导出**：`/vmfashion diag` 生成配置 + 包清单 + 失败原因 + 日志尾巴的诊断包。
- **Mod Menu 集成**（可选依赖）：模组列表里出现「配置」按钮，直接打开衣柜。
- 新配置项：`refreshIntervalSeconds`、`downloadServerPacks`、`maxPackCacheMb`。
- `F3+T` 资源重载也会刷新时装缓存（以前只有 `/vmfashion reload` 生效）。
- 模组图标、`contact`/`sources`/`issues` 元数据、英文描述。

### 修复

- **缩放绕脚底**（真 bug）：`scale` 现在绕 `attachedBone` 的 pivot 缩放，
  皇冠在 `head` 上放大 2 倍会停在头顶（2.749）而不是飞到 4.25。
- **`anim.json` 自动发现永远失效**：旧代码 `endsWith(".anim.json")` 对裸文件名恒为 false，
  现在接受 `anim.json` / `*.anim.json` / `*.animation.json`。
- **自动发现的时装拿不到 hideParts / scale / attachedBone / category**：新增 `meta.json`，
  并把元数据优先级统一成「目录推断 → pack.json 声明 → meta.json」。
- **贴图 ID 撞车**：资源 `Identifier` 追加 `CRC32` 后缀，`A:B` 与 `A_B` 不再落到同一张贴图，
  也不再复用别人的贴图。
- **换服竞态**：在飞的 `resolve` 响应带代次校验，切服后旧响应直接丢弃，不会把上个服的时装写进新服。
- **401 不重认证**：token 中途过期时自动重新认证并重放那次请求（以前别人时装永久不显示）。
- **超过 200 个 UUID 被静默丢弃**：改为分块串行提交再合并。
- **`syncEnabled=false` 时自己的时装渲染到所有玩家身上**：只有 `apiBaseUrl` 为空（纯本地）才允许回退。
- **运行中切换账号会把 A 的穿戴写进 B**：进服与每 30 秒复核 MC 账号，不一致就清会话重认证。
- **失败缓存永久拉黑**：烘焙失败 30 秒后允许重试（补好包不用重启游戏）。
- 自动发现主贴图时会把 `emissive.png` 当主贴图。

### 改进

- **hideParts 扩展到 8 类渲染层**：盔甲（按装备槽）、手持物（按左右手）、披风、鞘翅、
  头戴、Deadmau5 耳、鹦鹉、箭/蜂刺、旋转攻击 —— 整套时装不再和这些原版元素穿模。
- **发光层只让 emissive 区域发光**：按 `emissive.png` 的 alpha 逐 quad 遮罩，
  整块透明的四边形直接丢弃；渲染管线换成不描边的全亮管线。
- **渲染热路径零分配**：`PoseStack` 用 `ThreadLocal` 复用；顶点发射从每 quad 5 个对象降到 0；
  loadout 快照从每帧 2 次降到每 tick 1 次；去掉每帧 `toLowerCase` / `new Quaternionf`。
- **弱网健壮性**：指数退避 + 抖动 + 尊重 `Retry-After` + 熔断；只重试幂等请求。
- **后端错误码可读化**：`unknown_cosmetic` / `cosmetic_disabled` / `too_many_items` / `not_joined`
  等映射成聊天栏提示（15 秒去重，网络抖动不打扰）。
- 周期刷新别人身上的穿戴（默认 45 秒）。

### 工程化

- **JUnit 5 单测 164 条**：覆盖包解析、元数据优先级、zip 安全解压（32 个攻击样本）、退避策略、
  分块、持久化、资源 ID、错误体解析、动画解析与采样（纯逻辑，不加载 Minecraft 类）。
- **无显卡自检 6 步**：渲染链路 / 13 个 mixin 目标 / 缩放原点 / emissive 遮罩 / 动画骨骼名 / 第一人称坐标系。
- **GitHub Actions**：push/PR 跑构建 + 测试 + 自检 + 语言文件一致性 + jar 结构检查；
  打 tag 自动发布 jar + sha256 + release notes。
- 开发脚本：`tools/dev/check.sh`（2 秒编译检查）、`check-jar.sh`、`check-mod-json.sh`、`check-lang.sh`、
  `list-i18n-todo.sh`、`set-version.sh`、`make-icon.py`。
- 文档：`docs/pack-format.md`、`docs/architecture.md`、`docs/troubleshooting.md`、
  `docs/fashion-api.md`、`CONTRIBUTING.md`、`THIRD_PARTY_NOTICES.md`。

## [0.1.0] - 2026-10-28

首个公开版本，目标是打通渲染链路。

### 新增

- Bedrock 几何 + 贴图渲染（GeckoLib 烘焙 + 26.3 提交式管线），第三人称可见。
- 坐标系换算（脚底原点 / Y 向上 → 原版玩家模型空间）、`attachedBone` 挂载、
  `hideParts` 隐藏原版玩家部件。
- 云端同步：双路认证（正版 / 皮肤站）、进服拉取穿戴、换装上报、网站绑定（设备码）。
- 命令：`/vmfashion reload|list|status|debug|equip|unequip|login|sync|bind`。
- 内置示例时装包（`backboard` / `crown`），首次启动自动释放。
- `tools/verify/` 无图形环境自检（几何 / 变换 / mixin 签名）。

[Unreleased]: https://github.com/venuyu045/VMFashion-mod/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/venuyu045/VMFashion-mod/compare/v0.2.0...v1.0.0
[0.2.0]: https://github.com/venuyu045/VMFashion-mod/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/venuyu045/VMFashion-mod/releases/tag/v0.1.0
