# Changelog

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中

- 官方签名公钥接入（后端生成密钥对 → 替换内置常量 → 官方后端开 `requireSignature`）
- 内容审核流 / 举报 / 下架（后端）
- 后端多 worker（Redis 或粘性会话）

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

- **坏模型不再崩客户端**：渲染回调加顶层 `try/catch(Throwable)` + 按模型实例拉黑（reload 自愈），
  骨骼 `cubes/quads/vertices` 为 null 时安全降级。26.x 的 flush 在主循环里，以前一个坏包能让玩家掉线。
- 动画缓存每帧拼 String key → 两层 Map 零分配；`AnimationFrame.update()` 同一帧只求值一次
  （主层 + emissive 层共用一组采样值）。
- 关键帧采样线性扫描 → 二分查找（上万关键帧时快 48 倍），并给单轨道关键帧数量加上限（防恶意 anim.json）。
- 烘焙失败的重试间隔 30 秒 → 5 分钟（重试在渲染线程做 IO + 烘焙，太频繁会周期性卡帧）。

### 工程化

- **JUnit 5 单测 221 条**（较 0.2.0 新增 57 条）：新增覆盖三层缩放、Ed25519 验签与宽松/严格策略、
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
