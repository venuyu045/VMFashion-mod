# Changelog

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### 计划中

- 官方签名公钥接入（后端生成密钥对 → 替换内置常量 → 官方后端开 `requireSignature`）
- 内容审核流 / 举报 / 下架（后端）
- 后端多 worker（Redis 或粘性会话）
- 衣柜键盘导航（本轮已给方案，见 `research/audit-v1.2/mod-fixes.md` §3）

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
