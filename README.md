# Venus Mc Fashion

![Minecraft](https://img.shields.io/badge/Minecraft-26.3-62b47a?style=flat-square)
![Fabric Loader](https://img.shields.io/badge/Fabric%20Loader-0.20.1-dbb69c?style=flat-square)
![Java](https://img.shields.io/badge/Java-25-orange?style=flat-square)
![Version](https://img.shields.io/badge/version-0.1.0-blue?style=flat-square)
![License](https://img.shields.io/badge/license-MIT-success?style=flat-square)

Venus Mc Fashion（简称 VMFashion）是一个 Minecraft **Fabric 纯客户端模组**，用于管理时装/外观相关内容。

> 当前状态：**渲染链路已打通**。自定义时装包里的 Bedrock 3D 模型 + 贴图，可以显示在玩家身上
> （第三人称，本地玩家优先；其他玩家走同一套代码，等同步层接入后按 UUID 取数据）。
> 内置一个示例时装包，首次启动会自动释放到 `config/vmfashion/packs/example/`。

## 安装（玩家）

1. 装好 **Fabric Loader 0.20.1**（对应 Minecraft **26.3**）。
2. 从本仓库的 **Releases** 页面下载 `vmfashion-mc26.3-0.1.0.jar`，和下面两个前置一起放进 `.minecraft/mods/`：
   - **Fabric API** `0.165.0+26.3`（必需）
   - **GeckoLib** `5.5.7`（必需，Bedrock 模型渲染引擎）
3. 启动游戏，进服后 `/vmfashion list` 查看已加载的时装，`/vmfashion equip <id>` 穿上；
   首次启动会把示例时装包释放到 `config/vmfashion/packs/example/`。
4. 想用云端衣柜的话，在 `config/vmfashion/config.json` 里填 `apiBaseUrl`，再执行 `/vmfashion login`
   （或 `/vmfashion bind` 绑定网站账号）。

> 想自己造时装包？见 [时装包格式](#时装包格式) 与 [如何制作 / 导入时装包](#如何制作--导入时装包)。
> 从源码构建见 [构建](#构建) 一节。

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

## 构建

```bash
cd VMFashion-mod
./gradlew build            # 需要 JDK 25
```

产物：`build/libs/vmfashion-mc26.3-0.1.0.jar`

> 离线/受限环境可加 `--offline`；容器内构建时用的是
> `JAVA_HOME=/opt/jdk25/current GRADLE_USER_HOME=/tmp/gradle-home ./gradlew build --offline`。

## 项目结构

```
src/
├── client/java/com/venus/vmfashion/     # 业务代码（split source set）
│   ├── VMFashionClient.java             # 模组入口点
│   ├── config/ModConfig.java            # 配置项 + UI 常量（Gson 持久化）
│   ├── cosmetic/                        # 时装包解析（pack.json / 自动发现 / 注册表 / 本地穿戴）
│   ├── command/FashionCommand.java      # /vmfashion 命令
│   ├── loadout/                         # 同步层接口：LoadoutProvider / LocalOnlyProvider / LoadoutService
│   ├── render/                          # 渲染层
│   │   ├── FashionRenderState.java      # 给 AvatarRenderState 挂 UUID 的接口
│   │   ├── CosmeticAssets.java          # 磁盘 .geo.json → BakedGeoModel；PNG → DynamicTexture
│   │   ├── GeoModelRenderer.java        # 骨骼遍历，把 GeckoLib 模型写进 VertexConsumer
│   │   └── CosmeticRenderDispatcher.java# 每帧入口：查穿戴 → 提交几何；hideParts
│   ├── resource/DefaultPackInstaller.java # 首次启动释放内置示例包
│   ├── mixin/                           # 4 个客户端 mixin
│   ├── util/ChatUtil.java, util/FileUtil.java
│   ├── ui/UIState.java
│   └── gui/...
└── main/resources/
    ├── fabric.mod.json
    ├── vmfashion.mixins.json
    ├── assets/vmfashion/lang/{zh_cn,en_us}.json
    └── assets/vmfashion/default_pack/   # 内置示例时装包（首次启动释放）

tools/verify/                            # 无图形环境自检脚本（不参与构建、不进 jar）
```

## 无图形环境下的验证

没有显卡/显示器时，可以用 `tools/verify/run.sh` 验证渲染链路（详见 `tools/verify/README.md`）：

```bash
./gradlew build && bash tools/verify/run.sh
```

它会用**生产代码**烘焙示例模型、把顶点写进记录型 `VertexConsumer`、检查包围盒 / 坐标系换算 /
attachedBone 跟随，并用 ASM 校验 4 个 mixin 的目标方法签名是否存在。


## 时装包格式

时装包放在 `.minecraft/config/vmfashion/packs/<packId>/` 下：

```
config/vmfashion/packs/<packId>/
  pack.json                     # 必需：包元数据 + 时装清单
  cosmetics/<cosmeticId>/
    model.geo.json              # 必需：Bedrock 几何（format_version 1.12.0）
    texture.png                 # 必需：贴图（建议 64x64）
    emissive.png                # 可选：发光层贴图
    anim.json                   # 可选：Bedrock 动画（当前只解析、不播放）
```

### pack.json

```json
{
  "formatVersion": 1,
  "id": "example",
  "name": "示例时装包",
  "version": "1.0.0",
  "author": "you",
  "license": "MIT",
  "cosmetics": [
    {
      "id": "backboard",
      "name": "示例背板",
      "category": "back",
      "attachedBone": "body",
      "model": "cosmetics/backboard/model.geo.json",
      "texture": "cosmetics/backboard/texture.png",
      "emissive": "cosmetics/backboard/emissive.png",
      "hideParts": ["head"],
      "scale": 1.0
    }
  ]
}
```

- `cosmetics` 省略时，会自动扫描 `cosmetics/*/` 目录，按文件名猜分类（`wing`/`cape`/`hat`/`pet`）。
- `category`：`cape` / `wings` / `hat` / `back` / `pet` / `aura` / `other`，决定 UI 分组和默认挂载骨骼。
- `attachedBone`：`body`（默认）、`head`、`left_arm`、`right_arm`、`left_leg`、`right_leg`。
- `hideParts`：穿上后隐藏原版部件，可选 `head` / `body` / `left_arm` / `right_arm` / `left_leg` /
  `right_leg` / `all`（对应部位的外层皮肤也会一起隐藏）。
- `scale`：单件时装的额外缩放，会再乘上配置里的全局 `scale`。
- 路径都是相对包根目录的路径，越界（`..`）会被拒绝。

### 模型坐标系（重要）

模型用 **Bedrock / Blockbench 的常规坐标**：

- 原点在**玩家脚底**，**Y 轴向上**，单位是 1/16 格；
- 玩家身高 32 单位（头顶 `y=32`），脖子在 `y=24`，身体在 `y=12..24`；
- `+Z` 是背后（披风/翅膀放在 `z>0`），`-Z` 是正面。

渲染时 mod 会自动把模型换算进原版玩家模型空间（原点在脖子、Y 轴向下），
所以 **Blockbench 里怎么摆，游戏里就怎么显示**，不需要自己写变换。

`attachedBone` 只是决定"跟着哪个部位动"：骨骼 pivot 取自原版 `PlayerModel` 的对应部位，
模型本身仍然按脚底原点书写（例如帽子就画在 `y=32` 附近，不要画在骨头局部坐标里）。

## 命令

| 命令 | 说明 |
|------|------|
| `/vmfashion reload` | 重新扫描时装包，并清空模型/贴图缓存 |
| `/vmfashion list` | 列出所有已加载的包和时装 ID |
| `/vmfashion status` | 显示当前穿戴 |
| `/vmfashion debug` | 自检：打印配置、本地玩家 UUID、穿戴、模型烘焙结果（骨骼数/四边形数/贴图 ID） |
| `/vmfashion equip <id>` | 穿上某件时装（按分类替换） |
| `/vmfashion unequip <category>` | 脱下某个分类 |
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
| `scale` | `1.0` | 全局模型缩放 |
| `apiBaseUrl` | `""` | 时装后端地址，如 `https://vmtools.example.com/api/fashion`；留空 = 纯本地 |
| `syncEnabled` | `true` | 云端同步开关（需要 `apiBaseUrl` 非空） |
| `authServer` | `""` | 皮肤站 Yggdrasil 地址；留空则自动从 authlib-injector 的 `-javaagent:` 参数探测 |
| `uiScale` / `animationDuration` / `toastEnabled` | — | UI 相关（沿用旧字段） |

## 云端同步

配置了 `apiBaseUrl` 之后，模组会在**进服时**自动完成认证，然后：

- 先 `GET /loadout/me` 拉取自己在网站衣柜保存的穿戴（云端为准，绝不拿本地空状态覆盖）；
- 用 `POST /loadouts/resolve` 批量拉取同服玩家的穿戴（缓存 5 分钟，最多 200 个 UUID 一次）；
- 按 `UUID → 分类 → 时装 ID` 渲染别人身上的时装；游戏内 `/vmfashion equip` 等操作会即时 `PUT` 回去。

**认证是双路的**（目标服务器同时开正版和皮肤站登录，没有离线玩家）：

| 玩家类型 | 客户端做什么 | 后端做什么 |
|---|---|---|
| 正版 | 向 `sessionserver.mojang.com` join | `hasJoined` 反查并比对 UUID |
| 皮肤站 | 向 `{authServer}/sessionserver/session/minecraft/join` join | 向白名单内的皮肤站 `hasJoined` 反查 |

- 皮肤站地址优先从 JVM 参数 `-javaagent:...authlib-injector.jar=<URL>` 自动读取（authlib-injector 的标准启动方式），读不到才用 `authServer` 配置项。
- 后端只信任**白名单内**的皮肤站地址（防 SSRF），所以上线新皮肤站要先加到后端的 `VMT_FASHION__YGGDRASIL_BASES`。
- 认证失败不影响本地时装：模组会安静地退化成纯本地模式。

**绑定到网站账号**（想在网页衣柜里换装时用）：

1. 游戏内执行 `/vmfashion bind`，聊天栏会显示 6 位绑定码（10 分钟有效，一次性）；
2. 打开站点 `时装绑定` 页（`/fashion/bind`），输入这个码；
3. 绑定成功后，网页衣柜和游戏内 mod 共用同一份穿戴配置（一个网站账号可绑多个 MC 账号）。
   没登录时 `/vmfashion bind` 会先自动走一遍认证。

后端接口文档见时装后端仓库的 `docs/fashion-api.md`（自建后端时参考）。

### 和后端联调前必须先做的事

1. **时装 ID 必须对齐**：后端 `PUT /loadout` 会校验 `cosmeticId` 是否存在于 catalog 且 `enabled=true`。
   模组上报的是全局 ID `<packId>:<localId>`（例如 `example:backboard`），catalog 的 `cosmeticId`
   必须与它**完全一致**（`packId` 与 `pack.json` 的 `id` 一致），否则上报会 400、玩家也互相看不到。
   先调管理接口 `POST /api/fashion/admin/catalog` 把目录灌进去。
2. **资产目前不会自动下发**：后端 `resolve` 只回 `assetKey`，模组还不会自动下载时装包。
   也就是说，A 穿的那件时装，B 本地必须也装了同名包才渲染得出来。
   （自动下载是下一步：按 `sha256` 拉 zip 解压到 `packs/server/`。）
3. **皮肤站要进白名单**：后端的 `VMT_FASHION__YGGDRASIL_BASES` 里没有的皮肤站，其玩家会收到 `not_joined`。

## 如何制作 / 导入时装包

1. 用 Blockbench 建一个 **Bedrock Entity** 模型（或 GeckoLib 模型），纹理尺寸建议 64x64。
2. 模型按"脚底原点、Y 向上"摆放（可以直接在 Blockbench 的玩家模板上改）。
3. 导出 `model.geo.json` 和 `texture.png`，按上面的目录结构放进
   `config/vmfashion/packs/<你的包名>/cosmetics/<时装名>/`。
4. 写 `pack.json`（或者干脆省略 `cosmetics` 让它自动扫描）。
5. 进游戏执行 `/vmfashion reload`，然后 `/vmfashion equip <id>`；看不到就 `/vmfashion debug` 看自检输出。

导入别人做的包：整个包目录丢进 `config/vmfashion/packs/` 即可，不需要重启。

## 实现要点（给后续维护者）

### 资源接入 GeckoLib（方案 B：直接读磁盘）

时装包在 `config/` 下，不在资源包里。26.3 的 Fabric API 只能把 **mod 自己的**资源目录注册成内置资源包，
没有"挂载任意目录"的接口，所以没有走资源包方案，而是：

- **模型**：`GeckoLibGsonLoader#bakeGeckoLibModelFile(Identifier, JsonObject)` 是纯 JSON → `BakedGeoModel`，
  不经过 `ResourceManager`，直接把磁盘 JSON 读成 `JsonObject` 就能烘焙（`CosmeticAssets`）。
- **贴图**：PNG → `NativeImage` → `DynamicTexture` → `TextureManager.register`，得到一个普通 `Identifier`，
  再交给 `RenderTypes.entityCutout(...)`。

好处：不污染资源包命名空间、不依赖资源重载时机、`/vmfashion reload` 立即生效。

### UUID 注入

26.x 的 `AvatarRenderState` 只有 `int id`（会复用、跨维度会撞），所以：

- `AvatarRenderStateMixin` 给它加 `@Unique UUID` 并实现 `FashionRenderState`；
- `AvatarRendererMixin` 在 `extractRenderState(Avatar, AvatarRenderState, float)` 的 `RETURN` 填充。

### 26.3 渲染管线关键签名

| 类 | 签名 |
|----|------|
| `net.minecraft.client.renderer.entity.state.AvatarRenderState` | 字段 `id`（int）、`skin`、`showCape`…，**没有 UUID** |
| `net.minecraft.client.renderer.entity.layers.CapeLayer` | `submit(PoseStack, SubmitNodeCollector, int packedLight, AvatarRenderState, float, float)` |
| `net.minecraft.client.renderer.SubmitNodeCollector` | `submitCustomGeometry(PoseStack, RenderType, CustomGeometryRenderer)`，回调 `(PoseStack.Pose, VertexConsumer)` |
| `net.minecraft.client.renderer.rendertype.RenderTypes` | `entityCutout(Identifier)`、`entityTranslucentEmissive(Identifier)`… |
| `net.minecraft.client.model.player.PlayerModel` | `setupAnim(AvatarRenderState)`，字段 `head/hat/body/jacket/leftArm/...` |

渲染是"提交式"的：`submitCustomGeometry` 只记录一份 `PoseStack.Pose` 快照，
真正画的时候才回调，所以顶点必须自己乘上这个矩阵（`GeoModelRenderer` 就是这么做的）。

## 当前限制

- **动画**：`anim.json` 会被解析但不播放（`GeoBone.frameSnapshot` 恒为 `null`），模型只显示静态姿态。
- **同步**：已接入 HTTP 后端（`HttpLoadoutProvider`）；**配置了后端时以远端为准**，
  远端没有记录的玩家就是"没穿时装"。纯本地模式（未配置 `apiBaseUrl`）才会把本地穿戴
  预览到其他玩家身上用于验证渲染链路。
- **资产分发**：目前后端 `resolve` 只回 `assetKey`，模组**还不会自动下载时装包**——
  对方穿的时装必须本地也装了同名包才渲染得出来。自动下载（按 sha256 拉 zip 包解压到 `packs/server/`）是下一步。
- **缩放**：`LoadoutProvider` 只传 `slot → cosmeticId`，后端存的 `scale` 暂时没参与渲染。
- **第一人称**：只挂在玩家模型层上，第一人称看不到自己的时装（和披风一致）。
- **隐藏部位**：只影响原版玩家模型，不影响盔甲层/手持物。
- **发光层**：用 `entityTranslucentEmissive` 简单叠加，没有做真正的全亮着色。
- **GUI**：只有命令，没有可视化的时装选择界面（网站端有衣柜页）。

## 数据文件

运行期数据保存在 `.minecraft/config/vmfashion/`：

- `config.json` — 模组配置
- `session.json` — 后端认证 token（含过期时间，过期会自动重新认证）
- `ui_state.json` — 窗口 UI 状态
- `packs/` — 时装包目录（首次启动会写入 `example/`）

## 许可证

MIT，Copyright (c) 2026 Venus
