# Venus Mc Fashion

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62b47a?style=flat-square)
![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.20.1-dbb69c?style=flat-square)
![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square)
![Version](https://img.shields.io/badge/version-0.2.0-blue?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-success?style=flat-square)

Venus Mc Fashion（VMFashion）是一个 Minecraft **Fabric 纯客户端时装模组**：把你用 Blockbench 做的
Bedrock 3D 时装（披风 / 翅膀 / 帽子 / 背饰 / 宠物…）穿到玩家身上，可以和一个网站衣柜做云端同步，
也可以由服务端自动下发时装包 —— **服务器不需要装任何东西**。

```
按 K 打开衣柜 ──► 选中时装 ──► 立刻穿在身上（第三人称/其他玩家都能看到）
                      │
                      └─► 上报到网站衣柜（可选）──► 换台电脑登录也能穿同一套
```

## 功能

| 能力 | 说明 |
|---|---|
| **可视化衣柜** | 默认按 `K` 打开（也可 `/vmfashion open`）：分类 tab、贴图缩略图网格、搜索、试穿/脱下、实时状态 |
| **Bedrock 模型** | `model.geo.json` 由 GeckoLib 直接烘焙，Blockbench 里怎么摆游戏里就怎么显示（脚底原点 / Y 向上 / 1/16 格） |
| **挂载骨骼** | `attachedBone` 支持 `body` / `head` / 左右手臂 / 左右腿，跟着部位一起动 |
| **骨骼动画** | `anim.json`（Bedrock 动画）按关键帧循环播放；每（玩家 × 时装）独立相位，多人同屏互不干扰 |
| **隐藏原版部件** | `hideParts` 不只隐藏玩家模型：盔甲、手持物、披风/鞘翅、头戴、鹦鹉、箭都会一起隐藏，不会穿模 |
| **发光层** | `emissive.png` **只让真正不透明的区域发光**（逐 quad 遮罩），不描边、不受光照影响 |
| **云端衣柜** | 双路认证（正版 / 皮肤站）、进服自动拉取、换装即时上报、周期刷新别人身上的变化 |
| **网站绑定** | `/vmfashion bind` 在聊天栏给出 6 位绑定码，到站点「时装绑定」页输入即可让网页衣柜和游戏内共用一份穿戴 |
| **时装包自动下发** | 别人穿了本地没装的时装时，按 `sha256` 自动下载并安全解压到 `packs/server/`（可关） |
| **重启不丢** | 本地穿戴落盘（`loadout.json`），纯本地模式也不会每次重启就光着 |
| **可选 Mod Menu** | 装了 Mod Menu 会在模组列表里出现「配置」按钮，直接打开衣柜 |
| **中英双语** | 界面与命令输出支持简体中文 / English（跟随游戏语言） |

## 安装（玩家）

1. 装好 **Fabric Loader 0.20.1**（对应 Minecraft **26.3**，需要 **Java 25**）。
2. 从本仓库的 **Releases** 页面下载 `vmfashion-mc26.3-0.2.0.jar`，和下面两个前置一起放进 `.minecraft/mods/`：
   - **Fabric API** `0.165.0+26.3`（必需）
   - **GeckoLib** `5.5.7`（必需，Bedrock 模型渲染引擎）
   - *可选*：**Mod Menu** `21.0.0`（想要「配置」按钮再装）
3. 启动游戏，按 **K** 打开衣柜；或 `/vmfashion list` 看已加载的时装、`/vmfashion equip <id>` 穿上。
4. 想用云端衣柜：在 `config/vmfashion/config.json` 里填 `apiBaseUrl`，再执行 `/vmfashion login`
   （或 `/vmfashion bind` 绑定网站账号）。

> 首次启动会把内置示例时装包释放到 `config/vmfashion/packs/example/`，开箱即可试穿。

## 环境

| 项目 | 版本 |
|------|------|
| Minecraft | 26.3（Mojang 官方名，无 Yarn） |
| Java | 25 |
| Gradle | 9.7.1 |
| Fabric Loom | `net.fabricmc.fabric-loom` 1.18.4 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.165.0+26.3 |
| GeckoLib | 5.5.7（`com.geckolib.*`，不是 4.x 的 `software.bernie.geckolib`） |
| Mod Menu（可选） | 21.0.0 |

## 构建

```bash
./gradlew build          # 需要 JDK 25
```

产物：`build/libs/vmfashion-mc26.3-<版本>.jar`（另有 `-sources.jar`）。

> 离线 / 受限环境：`./gradlew build --offline`；容器内构建用
> `JAVA_HOME=/opt/jdk25/current GRADLE_USER_HOME=/tmp/gradle-home ./gradlew build --offline`。

### 验证

```bash
./gradlew test                 # 单元测试（纯逻辑，不加载 Minecraft 类）
bash tools/verify/run.sh       # 无显卡环境的渲染链路自检（4 步）
bash tools/dev/check-jar.sh    # 构建产物结构检查
```

`tools/verify/` 用**真实生产代码**烘焙示例模型、把顶点写进记录型 `VertexConsumer`，
检查包围盒 / 坐标系换算 / attachedBone 跟随 / 缩放原点 / emissive 遮罩 / 动画骨骼名，
并用 ASM 校验全部 12 个 mixin 的目标方法签名（详见 `tools/verify/README.md`）。

CI（`.github/workflows/build.yml`）在每次 push/PR 上跑：`build`（含测试）→ 渲染自检 →
语言文件一致性 → jar 结构检查；打 tag 时 `release.yml` 自动发布 jar + sha256 + release notes。

## 命令

| 命令 | 说明 |
|------|------|
| `/vmfashion open`（或 `/gui`） | 打开衣柜界面（等同按 `K`） |
| `/vmfashion list` | 列出所有已加载的包和时装 ID |
| `/vmfashion equip <id>` | 穿上某件时装（按分类替换） |
| `/vmfashion unequip <category>` | 脱下某个分类 |
| `/vmfashion status` | 显示穿戴、登录状态、token 过期时间、资产下发与缓存占用 |
| `/vmfashion debug` | 自检：配置、UUID、穿戴、模型烘焙结果（骨骼数/四边形数/贴图 ID） |
| `/vmfashion diag` | 导出诊断包到 `config/vmfashion/diag/`（配置 + 包清单 + 失败原因 + 日志尾巴） |
| `/vmfashion reload` | 重新扫描时装包并清空模型/贴图缓存（`F3+T` 也会触发） |
| `/vmfashion config reload` | 重读 `config.json` 并重建同步层（改 `apiBaseUrl` 不用重启游戏） |
| `/vmfashion cache clear` | 清空服务端下发的时装包缓存 |
| `/vmfashion login` | 手动登录时装后端（换账号 / token 过期时） |
| `/vmfashion sync` | 手动把当前穿戴推送到后端 |
| `/vmfashion bind` | 绑定到网站：聊天栏显示 6 位绑定码，到站点「时装绑定」页输入（10 分钟内有效） |

`<id>` 可以是完整的 `packId:localId`，也可以是唯一的 `localId`。

## 配置

`config/vmfashion/config.json`（缺失字段自动用默认值，旧文件可以直接升级）：

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `enabled` | `true` | 总开关，关掉后不再提交任何时装几何 |
| `renderOtherPlayers` | `true` | 是否渲染其他玩家（本地玩家第三人称始终渲染） |
| `renderDistance` | `64` | 渲染距离（方块），`<=0` 表示不限制 |
| `scale` | `1.0` | 全局模型缩放（与每件时装自己的 `scale` 相乘） |
| `apiBaseUrl` | `""` | 时装后端地址，如 `https://vmtools.example.com/api/fashion`；留空 = 纯本地 |
| `syncEnabled` | `true` | 云端同步开关（需要 `apiBaseUrl` 非空） |
| `authServer` | `""` | 皮肤站 Yggdrasil 地址；留空则自动从 authlib-injector 的 `-javaagent:` 参数探测 |
| `refreshIntervalSeconds` | `45` | 其他玩家穿戴的定时刷新间隔（秒），`0` = 关闭 |
| `downloadServerPacks` | `true` | 是否自动下载服务端下发的时装包 |
| `maxPackCacheMb` | `512` | 服务端时装包缓存上限（MB），超过后按最久未用淘汰 |
| `uiScale` | `1.0` | 衣柜界面缩放（0.5–2.0） |
| `animationDuration` | `200` | 界面打开动画时长（毫秒，0 = 不做动画） |
| `toastEnabled` | `true` | 是否显示 Toast 提示 |

## 云端同步

配置了 `apiBaseUrl` 之后，模组会在**进服时**自动完成认证，然后：

- 先 `GET /loadout/me` 拉取自己在网站衣柜保存的穿戴（**云端为准**，绝不拿本地空状态覆盖）；
- 用 `POST /loadouts/resolve` 批量拉取同服玩家的穿戴（缓存 5 分钟、超过 200 个 UUID 自动分块，
  每 `refreshIntervalSeconds` 秒刷新一次）；
- 按 `UUID → 分类 → 时装 ID` 渲染别人身上的时装；游戏内换装会即时 `PUT` 回去；
- 解析不到的时装 ID 会触发**时装包自动下发**（见下）。

**认证是双路的**（目标服务器同时开正版和皮肤站登录，没有离线玩家）：

| 玩家类型 | 客户端做什么 | 后端做什么 |
|---|---|---|
| 正版 | 向 `sessionserver.mojang.com` join | `hasJoined` 反查并比对 UUID |
| 皮肤站 | 向 `{authServer}/sessionserver/session/minecraft/join` join | 向白名单内的皮肤站 `hasJoined` 反查 |

- 皮肤站地址优先从 JVM 参数 `-javaagent:...authlib-injector.jar=<URL>` 自动读取，读不到才用 `authServer`。
- 后端只信任**白名单内**的皮肤站地址（防 SSRF），上线新皮肤站要先加到后端的 `VMT_FASHION__YGGDRASIL_BASES`。
- token 中途失效（401）会自动重新认证并重放那次请求；运行中切换账号会清掉旧会话。
- 认证失败不影响本地时装：模组会安静地退化成纯本地模式。

**绑定到网站账号**（想在网页衣柜里换装时用）：

1. 游戏内执行 `/vmfashion bind`，聊天栏会显示 6 位绑定码（10 分钟有效，一次性）；
2. 打开站点 `时装绑定` 页（`/fashion/bind`），输入这个码；
3. 绑定成功后，网页衣柜和游戏内 mod 共用同一份穿戴配置（一个网站账号可绑多个 MC 账号）。

接口契约见 [`docs/fashion-api.md`](docs/fashion-api.md)（自建后端时参考）。

### 时装包自动下发

别人穿的时装你本地没装时，模组会：

1. 从 `GET /packs` 拿包清单（`packId` / `version` / `sha256` / `bytes` / 下载地址）；
2. 流式下载 + 边下边算 `sha256`，字节数不符直接丢弃；
3. **安全解压**：拒绝 `..`、绝对路径、符号链接、大小写冲突，并限制条目数 / 单文件大小 /
   总解压大小 / 压缩比（zip bomb）；
4. 校验 `pack.json` 里的 `id` 与请求的 `packId` 一致后**原子移动**到 `packs/server/<packId>/`；
5. 主线程重新加载并刷新贴图缓存 —— 下一帧就能看见对方的时装。

安全边界：
- 你**自己**放进 `packs/<packId>/` 的包永远优先，自动下发不会覆盖它；
- 缓存超过 `maxPackCacheMb` 时按最久未用淘汰；`/vmfashion cache clear` 只清 `packs/server/`；
- 传输安全只有 TLS + `sha256` 完整性校验（**没有**包签名，恶意后端理论上仍可下发不雅内容，
  所以后端需要审核流，见 `docs/fashion-api.md`）。

### 和后端联调前必须先做的事

1. **时装 ID 必须对齐**：后端 `PUT /loadout` 会校验 `cosmeticId` 是否存在于 catalog 且 `enabled=true`。
   模组上报的是全局 ID `<packId>:<localId>`，catalog 的 `cosmeticId` 必须与它**完全一致**
   （`packId` 与 `pack.json` 的 `id` 一致），否则上报会 400、玩家也互相看不到。
2. **皮肤站要进白名单**：后端的 `VMT_FASHION__YGGDRASIL_BASES` 里没有的皮肤站，其玩家会收到 `not_joined`。
3. **nginx 上传上限**：后端上限 64MB，而生产 nginx `client_max_body_size` 只有 50m，大包会先 413。

## 时装包格式

完整教程（含 Blockbench 步骤、字段表、常见错误）见 [`docs/pack-format.md`](docs/pack-format.md)。
最小结构：

```
config/vmfashion/packs/<packId>/
  pack.json                     # 包元数据 + 时装清单
  cosmetics/<cosmeticId>/
    model.geo.json              # Bedrock 几何（format_version 1.12.0）
    texture.png                 # 贴图（建议 64x64）
    emissive.png                # 可选：发光层贴图（只让不透明区域发光）
    anim.json                   # 可选：Bedrock 动画（循环播放）
    meta.json                   # 可选：单件时装元数据（优先级最高）
```

- `cosmetics` 数组省略时自动扫描 `cosmetics/*/`；
- 元数据优先级：目录内容自动推断 → `pack.json` 的 `cosmetics` 数组 → 单件 `meta.json`；
- 分类：`cape` / `wings` / `hat` / `back` / `pet` / `aura` / `other`；
- `attachedBone`：`body`（默认）/ `head` / `left_arm` / `right_arm` / `left_leg` / `right_leg`；
- `hideParts`：`head` / `body` / `left_arm` / `right_arm` / `left_leg` / `right_leg` / `all`
  （对应部位的外层皮肤、盔甲、手持物、披风/鞘翅、头戴、鹦鹉、箭都会一起隐藏）；
- `scale`：单件时装的额外缩放，**绕挂载骨骼的 pivot 缩放**（不是绕脚底）；
- 所有路径相对包根目录，越界（`..`）会被拒绝。

模型坐标系：原点在**玩家脚底**、**Y 轴向上**、单位 1/16 格；`+Z` 是背后、`-Z` 是正面。
渲染时自动换算进原版玩家模型空间，不需要自己写变换。

导入别人做的包：整个包目录丢进 `config/vmfashion/packs/` 即可，然后 `/vmfashion reload`。

## 数据文件

运行期数据保存在 `.minecraft/config/vmfashion/`：

| 文件 / 目录 | 说明 |
|---|---|
| `config.json` | 模组配置 |
| `session.json` | 后端认证 token（含过期时间，过期会自动重新认证） |
| `loadout.json` | 本地穿戴（重启不丢；云端有数据时以云端为准） |
| `ui_state.json` | 界面状态（上次选中的分类） |
| `packs/` | 你自己放的时装包（首次启动会写入 `example/`） |
| `packs/server/` | 服务端自动下发的包（可被 `/vmfashion cache clear` 清理） |
| `diag/` | `/vmfashion diag` 导出的诊断包 |

## 实现要点（给后续维护者）

架构与数据流见 [`docs/architecture.md`](docs/architecture.md)。几个关键决策：

- **资源不走资源包**：时装包在 `config/` 下，26.3 的 Fabric API 只能把 mod 自己的资源目录注册成内置包。
  所以模型直接读磁盘 JSON → `GeckoLibGsonLoader#bakeGeckoLibModelFile` → `BakedGeoModel`；
  贴图 PNG → `NativeImage` → `DynamicTexture` → `TextureManager`。`F3+T` 与 `/vmfashion reload` 都会作废缓存。
- **提交式渲染**：26.3 的 `submitCustomGeometry` 只存一份 `PoseStack.Pose` 快照，真正画的时候才回调，
  所以几何遍历必须写在 lambda 里，顶点自己乘矩阵。
- **UUID 注入**：`AvatarRenderState` 只有会复用的 `int id`，由 mixin 挂 `UUID` 并实现 `FashionRenderState`。
- **hideParts 跨层**：掩码在 `PlayerModel#setupAnim` 的 TAIL 算好写进 `AvatarRenderState`，
  8 个额外 mixin 在各自 layer 的 `submit`/`renderArmorPiece` 处读取并取消。
- **动画状态隔离**：`BakedGeoModel` 是每件时装一份、所有玩家共享的，而 `GeoBone.frameSnapshot`
  是共享骨骼上的可变字段。所以动画帧按（玩家 × 时装）各存一份，渲染时临时挂到共享骨骼上、
  `finally` 里立刻还原 —— 不 clone 骨骼树，也不会让 A 的动画出现在 B 身上。
- **热路径零分配**：`PoseStack` 用 `ThreadLocal` 复用、顶点发射复用 `Vector3f/Vector4f`、
  loadout 快照按 tick 缓存、失败缓存带 30 秒 TTL（修好包后不用重启游戏）。

## 当前限制

- **动画**：支持 Bedrock `anim.json` 的 `loop` / `animation_length` / `bones.{rotation,position,scale}`，
  线性插值；**不支持** molang 表达式、`timeline`、音效关键帧、`animation_controllers`，
  `catmullrom` 按线性处理；一件时装里有多个动画时播第一个。
- **第一人称**：只挂在玩家模型层上，第一人称看不到自己的时装（和披风一致）。
- **后端 scale**：后端存的 `scale` 还没进数据链路（`LoadoutProvider` 只传 `slot → cosmeticId`），
  当前只支持时装包自己声明的 `scale` 与全局 `scale`。
- **光影**：`submitCustomGeometry` 在 Iris 下是否走光影管线未验证（没有测试环境）。
- **半透明排序**：自定义几何的半透明节点按 feature 分组提交、不做距离排序，
  与水/染色玻璃的前后关系由渲染阶段顺序决定。
- **多 `minecraft:geometry`**：一个模型文件里定义多个 geometry 时只渲染第一个。
- **包签名**：自动下发只有 TLS + `sha256` 完整性校验，没有 Ed25519 签名（依赖后端审核流）。
- **内容审核**：举报 / 下架是后端能力，mod 端已支持 `missing=true` 跳过。

## 许可证

MIT，Copyright (c) 2026 Venus。第三方组件许可见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。
