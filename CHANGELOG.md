# Changelog

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中

- 动画播放（`anim.json` → 骨骼快照）
- 第一人称时装
- 后端 `scale` 进数据链路
- 全量 i18n（当前仍有硬编码中文）

## [0.2.0] - 2026-10-29

这一版的目标是**「别人装上 mod 就能看到你穿的时装」**，且重启不丢、切服不乱、不用记命令。

### 新增

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

- **JUnit 5 单测**：82 条，覆盖包解析、元数据优先级、zip 安全解压、退避策略、分块、持久化、
  资源 ID、错误体解析（纯逻辑，不加载 Minecraft 类）。
- **无显卡自检扩到 4 步**：渲染链路 / 12 个 mixin 目标 / 缩放原点 / emissive 遮罩。
- **GitHub Actions**：push/PR 跑构建 + 测试 + 自检 + 语言文件一致性 + jar 结构检查；
  打 tag 自动发布 jar + sha256 + release notes。
- 开发脚本：`tools/dev/check.sh`（2 秒编译检查）、`check-jar.sh`、`check-lang.sh`、
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

[Unreleased]: https://github.com/venuyu045/VMFashion-mod/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/venuyu045/VMFashion-mod/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/venuyu045/VMFashion-mod/releases/tag/v0.1.0
