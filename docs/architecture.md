# 架构与数据流

> 目标读者：要改这个 mod 的开发者。
> 读完你应该知道"一帧里发生了什么""一个网络响应怎么变成屏幕上的模型"，以及**为什么这么写**。
>
> 代码位置：`src/client/java/com/venus/vmfashion/`。26.3 是 **Mojang 官方名、不重映射**，
> 所有 mixin 的 `name + descriptor` 必须与官方 jar 完全一致（用 `javap` 核对，见
> [CONTRIBUTING.md](../CONTRIBUTING.md#6-263-api-签名怎么查)）。

- [1. 模块图](#1-模块图)
- [2. 启动顺序](#2-启动顺序)
- [3. 渲染链路](#3-渲染链路)
- [4. 同步链路](#4-同步链路)
- [5. 资产下发链路](#5-资产下发链路)
- [6. 线程模型](#6-线程模型)
- [7. 关键设计取舍](#7-关键设计取舍)
- [8. 数据流总图](#8-数据流总图)
- [9. 扩展点](#9-扩展点)

---

## 1. 模块图

```
com.venus.vmfashion
├── VMFashionClient            入口：初始化顺序、退出保存
├── command/
│   ├── FashionCommand         /vmfashion 全部子命令（Brigadier，客户端命令）
│   └── DiagExporter           诊断包导出（config/vmfashion/diag/diag-<时间>.txt）
├── config/
│   ├── ModConfig              config.json（Gson）+ 默认值 + sanitize 收敛
│   └── ConfigReloader         配置热更新：重读文件 → 重建同步层 → 重扫包
├── cosmetic/                  ★ 数据层（纯逻辑，可在单测里跑）
│   ├── PackLoader             扫目录 / 解析 pack.json / meta.json / 自动发现
│   ├── CosmeticPack           一个包
│   ├── CosmeticDefinition     一件时装的静态定义（路径 + 挂载 + 缩放 + hideParts）
│   ├── CosmeticCategory       分类枚举 + 默认骨骼
│   ├── CosmeticRegistry       全局时装表（find 支持全名 / 唯一短名）
│   ├── CosmeticResourceId     ID → 资源路径（可读部分 + CRC32，避免撞车）
│   ├── LocalLoadout           本地玩家的穿戴（分类 → 时装 ID，内存）
│   └── LoadoutStore           loadout.json 原子写 + debounce
├── render/                    ★ 渲染层
│   ├── CosmeticAssets         磁盘 .geo.json → BakedGeoModel；PNG → DynamicTexture
│   ├── CosmeticRenderDispatcher 每帧入口：查穿戴 → 坐标换算 → 提交几何
│   ├── GeoModelRenderer       骨骼遍历 → 顶点写入（复刻 GeckoLib 顶点数学，零分配）
│   ├── EmissiveMask           emissive 贴图 alpha → 逐 quad 遮罩
│   ├── HiddenParts            hideParts → 位掩码
│   ├── RenderCache            每 tick 的穿戴快照 / 隐藏掩码缓存
│   ├── FashionRenderState     挂在 AvatarRenderState 上的 @Unique 字段（UUID + 隐藏掩码）
│   └── anim/                  动画：解析 anim.json → 关键帧采样 → BoneSnapshot（见 §3.6）
│       ├── BedrockAnimations  anim.json → BedrockAnimation（骨骼名小写化、表达式按数值解析）
│       ├── AnimationTrack     一条关键帧轨道（线性插值 + 循环回绕）
│       ├── AnimationFrame     每（玩家 × 时装）一份骨骼快照
│       └── AnimationCache     uuid|cosmeticId → AnimationFrame（30 秒空闲淘汰）
├── mixin/                     13 个客户端 mixin（见 §3.1 / §3.4）
├── loadout/                   同步层与渲染层之间的唯一入口
│   ├── LoadoutProvider        接口：resolve(uuids) → CompletableFuture
│   ├── LocalOnlyProvider      纯本地模式的空实现
│   ├── LoadoutService         本地玩家直读 LocalLoadout；他人走 provider + 代次校验
│   └── RequestBatching        200 个一批分块（纯逻辑，有单测）
├── net/                       网络
│   ├── FashionApi             HTTP 封装（超时 / 重试 / 401 / 错误码）
│   ├── RetryPolicy            指数退避 + 抖动 + Retry-After + 熔断（纯逻辑，有单测）
│   ├── FashionAuth            认证（challenge → join → verify）+ session.json
│   ├── HttpLoadoutProvider    批量解析他人穿戴（5 分钟 TTL + 401 重认证钩子）
│   ├── PackCatalog            GET /packs 清单 + 本地缓存
│   ├── PackDownloader         流式下载 + sha256/字节数校验
│   └── SafeZip                安全解压（防路径逃逸 / 符号链接 / zip bomb）
├── sync/
│   ├── SyncService            认证 / 拉取 / 上报 / 绑定 / 定时刷新 / 错误提示
│   └── AssetSyncService       服务端包下发队列（下载 → 解压 → 校验 → 原子安装 → reload）
├── gui/                       衣柜界面（WardrobeScreen + 组件 + 快捷键 K）
├── ui/UIState                 ui_state.json（窗口位置 / 上次分类）
├── resource/
│   ├── DefaultPackInstaller   首次启动释放内置示例包
│   └── ClientResourceReloader F3+T 资源重载也刷新时装缓存
├── util/                      ChatUtil / FileUtil / HashUtil
└── integration/ModMenuIntegration  Mod Menu 的「配置」按钮（可选依赖）
```

---

## 2. 启动顺序

`VMFashionClient#onInitializeClient`（全部在主线程）：

```
1. ModConfig.load()                       读 config.json（缺失/坏文件 → 默认配置，不崩）
2. DefaultPackInstaller.installIfMissing() 首次启动把 jar 里的示例包释放到 packs/example/
3. PackLoader.reload()                    扫 packs/ + packs/server/，刷新 CosmeticRegistry
4. LoadoutStore.loadInto(LocalLoadout)    读 loadout.json（重启不丢穿戴）
5. CosmeticAssets.invalidate()            清上一轮的模型/贴图缓存（此时还没有内容）
6. SyncService.initialize(config)         纯本地模式 → LocalOnlyProvider；配了后端 → HTTP 全套
7. AssetSyncService.initialize(config)    资产下发服务（未配置/关闭时整体空转）
8. 事件注册：JOIN/DISCONNECT、每 tick、命令、快捷键、F3+T 重载、CLIENT_STOPPING
```

退出时 `saveAll()`：`config.save()` + `LoadoutStore.saveNow()`。

> 顺序很重要：**先加载包，再读穿戴** —— 否则 `LocalLoadout` 里存的 ID 在注册表里还查不到。

### 2.1 数据文件（`config/vmfashion/`）

| 文件 / 目录 | 写入方 | 内容 |
|---|---|---|
| `config.json` | `ModConfig` | 全部配置项（缺失字段用默认值，旧文件可直接升级） |
| `session.json` | `FashionAuth` | 后端认证 token + 过期时间（含过期时间，过期自动重认证） |
| `loadout.json` | `LoadoutStore` | 本地穿戴（分类 → 时装 ID），原子写 + 2 秒 debounce |
| `ui_state.json` | `UIState` | 窗口位置 / 上次选中的分类等 UI 状态 |
| `packs/` | 玩家 / `DefaultPackInstaller` | 用户自己装的时装包（首次启动释放 `example/`） |
| `packs/server/<packId>/` | `AssetSyncService` | 服务端自动下发的包 + `.sha256` 标记（兼 LRU 时间戳） |
| `cache/pack-catalog.json` | `PackCatalog` | `GET /packs` 清单的本地缓存（离线可用） |
| `diag/` | `DiagExporter` | `/vmfashion diag` 导出的诊断文件 `diag-<时间戳>.txt` |

> `packs/server/` 与 `packs/<packId>/` 是**两个命名空间**：清缓存 / LRU 淘汰只动 `server/`，
> 同 `packId` 时用户包优先（见 §5）。

---

## 3. 渲染链路

### 3.1 入口与调用链

时装挂在原版 **`CapeLayer#submit`** 上（披风层，第三人称必然经过，且此时 `PoseStack` 已经在
"原版玩家模型空间"）：

> `CapeLayerMixin` 一个 handler 干两件事：**先提交时装，再决定要不要 `ci.cancel()` 掉原版披风**
> （`hideParts` 含 `body` 时）。不拆成两个 mixin 是因为两个 HEAD 注入的执行顺序取决于 mixin
> 应用顺序，拆开后时装可能跟着被取消。

```
CapeLayerMixin#submit(HEAD, cancellable)
  └─ CosmeticRenderDispatcher.submit(poseStack, collector, light, state, playerModel)
       ├─ shouldRender(state)              # 总开关 / 只渲染他人 / 距离剔除
       ├─ RenderCache.loadout(uuid)        # 每 tick 一份快照（不是每帧）
       ├─ for each cosmeticId:
       │    ├─ LoadoutService.resolve(id)  # ID → CosmeticDefinition（带缓存）
       │    ├─ CosmeticAssets.get(def)     # 懒烘焙模型 + 注册贴图 + 解析动画（首次会卡一帧）
       │    ├─ AnimationCache.frameFor(uuid, def, entry)   # 每（玩家 × 时装）一份动画状态
       │    └─ submitOne(...)
       │         ├─ applyModelSpace()      # 脚底原点/Y向上 → 脖子原点/Y向下
       │         ├─ applyAttachment(..., scale)  # 挂到 attachedBone，绕 pivot 缩放
       │         ├─ collector.submitCustomGeometry(RenderTypes.entityCutout(tex), lambda)
       │         │    └─ [flush 时] GeoModelRenderer.render(..., frame)  # 采样动画 + 骨骼遍历 + 写顶点
       │         └─ 有 emissive 且遮罩非空:
       │              collector.submitCustomGeometry(RenderTypes.eyes(emissive), lambda)
       │                   └─ [flush 时] GeoModelRenderer.renderEmissive(..., mask)
       └─ popPose
```

### 3.2 26.x 的"提交式管线"（最容易踩的坑）

`submitCustomGeometry` **不会立刻画**：它只把 `Pose` 快照和回调存起来，整帧收集完、flush 时才执行。

因此：

- 几何遍历 / 顶点写入**必须写在 lambda 里**；
- 不能在 submit 阶段算完再存结果（那时 `PoseStack` 已经不是回调时那一份了）；
- 回调在渲染线程串行执行、不会嵌套 → 可以安全复用 `ThreadLocal` 的临时对象。

### 3.3 坐标换算

| 空间 | 原点 | Y 轴 | 单位 |
|---|---|---|---|
| Bedrock / GeckoLib 模型 | 玩家**脚底** | 向上 | 1/16 格 |
| 原版玩家模型（`CapeLayer` 时） | 脖子 / 身体顶部 | **向下** | 1 格 |

`applyModelSpace` 做一次 `translate(0, 1.501, 0) + scale(-1, -1, 1)`（绕 Z 的 180° 旋转），
`applyAttachment` 再把模型平移到 `attachedBone` 对应的 `ModelPart` pivot、套用该部位的旋转，
最后平移回脚底原点。

> `scale` 在"移到 pivot"和"移回原点"**之间**应用 —— 这是绕挂载点缩放（头饰放大不会飞出去）。

### 3.4 hideParts 跨层传递

原版玩家渲染器有 10 层，每层用自己的模型，**从不看父模型的 `visible`**。所以：

```
PlayerModelMixin#setupAnim(TAIL)
  └─ CosmeticRenderDispatcher.computeHiddenMask(loadout) → int 位掩码
       └─ FashionRenderState(AvatarRenderState)#setHiddenParts(mask)

9 个 layer mixin 的 submit(HEAD):
  └─ if (HiddenParts.hidden(mask, PART)) return;   // 直接跳过整层
```

- 用 `int` 而不是 `Set<String>`：渲染热路径零分配；
- 掩码挂在 `AvatarRenderState` 的 `@Unique` 字段上（`AvatarRenderStateMixin`）；
- 各层归属见 [pack-format.md §7.2](pack-format.md#72-隐藏范围不止玩家本体)。

### 3.5 emissive 遮罩

- 读 `emissive.png` 的 alpha（CPU，不上 GPU）→ 位图；
- 遍历模型，把每个 quad 的 UV 包围盒投到像素上：**整块透明就丢掉这个 quad**；
- 保留的 quad 整块提交（部分透明的 texel 混合时贡献 0，视觉正确、不做逐 texel 裁剪）；
- 全透明 → `EMPTY`，整趟 emissive pass 都不提交；
- 管线用 `RenderTypes.eyes(...)`：它的 RenderSetup 不调 `useLightmap()`（= 全亮），
  且 outline 属性是 `NONE`（不会给发光玩家描边）。

### 3.6 动画播放（共享骨骼的状态隔离）

`BakedGeoModel` 是**每件时装烘焙一次、所有玩家共享**的，而 `GeoBone.frameSnapshot` 是共享骨骼上的
**可变 public 字段** —— 直接写就会串号（A 的动画帧画到 B 身上，多人同屏必现）。

解法（`render/anim/`）：

```
AnimationCache（uuid|cosmeticId → AnimationFrame，30 秒没渲染就淘汰）
  └─ AnimationFrame（每玩家 × 时装一份）
       ├─ 只为"动画里真有通道"的骨骼建 BoneSnapshot（静态骨骼保持 null，零额外开销）
       ├─ update()：按 System.nanoTime() 采样关键帧 → 写进自己的 BoneSnapshot
       └─ GeoModelRenderer.drawBone：
             previous = bone.frameSnapshot;
             bone.frameSnapshot = 我的快照;   // 临时挂到共享骨骼
             ...画...
             bone.frameSnapshot = previous;   // finally 里还原
```

- 采样**必须在 flush 回调内**做（提交阶段算好的值到 flush 时已经过时）；
- 26.x 的 flush 回调单线程串行 → 临时挂载/还原是安全的；
- 时间基准是创建时的 `nanoTime`，同一件时装在不同玩家身上相位不同（更自然）；
- 一个文件里多个动画时播第一个；骨骼名匹配大小写不敏感。

**支持范围**：`loop` / `animation_length` / `bones.<name>.{rotation,position,scale}`，
关键帧线性插值、按时间循环。
**不支持**：molang 表达式（非纯数字的关键帧被跳过）、`timeline`、音效/粒子关键帧、
`animation_controllers`、`catmullrom`（按线性处理）、`pre`/`post`（只取 `post`）。
解析器是纯逻辑（`BedrockAnimations` 只吃 `JsonObject`），坏数据只跳过、绝不让时装加载失败。

### 3.7 性能设计（热路径）

| 措施 | 位置 | 省掉什么 |
|---|---|---|
| `ThreadLocal<Scratch>` 复用 `PoseStack` / `Vector3f` / `Vector4f` | `GeoModelRenderer` | 每 quad 4~5 个对象 → 0 |
| 每 tick 一份穿戴快照 | `RenderCache` | 每帧 2 次 `Map` 拷贝 → 每 tick 1 次 |
| 隐藏掩码懒计算 | `RenderCache` | 没穿时装时不算 |
| ID → 定义缓存 | `LoadoutService` | 每帧线性搜索注册表 |
| emissive 遮罩缓存（用模型实例做版本号） | `EmissiveMask` | 每帧读盘 / 逐像素 |
| 模型 / 贴图懒烘焙 | `CosmeticAssets` | 启动时烘焙全部（会卡死一帧） |
| 每帧限量烘焙 | `WardrobeScreen` | 打开衣柜时一次烘焙几十件 |

---

## 4. 同步链路

### 4.1 认证（`FashionAuth`）

目标 MC 服务器可能同时支持正版和皮肤站（authlib-injector / Yggdrasil），两条路径协议一致：

```
POST /auth/challenge                     → { serverId, authType }
  ↓ 用当前 MC 会话向「会话服务器」join
    · 正版     → sessionserver.mojang.com
    · 皮肤站   → 皮肤站的 Yggdrasil（从 -javaagent:authlib-injector 参数或 config.authServer 探测）
POST /auth/verify { serverId, ... }      → { token, expiresAt, ... }
  ↓
session.json（含过期时间；过期 / 401 会自动重新认证）
```

### 4.2 他人穿戴（`HttpLoadoutProvider` + `LoadoutService`）

```
渲染线程: LoadoutService.loadoutFor(uuid)
  ├─ 本地玩家 → LocalLoadout.snapshot()（零延迟）
  └─ 其他玩家 → provider 缓存
       ├─ 命中且 < 5 分钟 → 直接用
       └─ 未命中 → 入队（节流 1 秒）→ 后台 resolve
                        └─ RequestBatching 按 200 个 UUID 分块串行
                             └─ POST /loadouts/resolve → { uuid: { slot: cosmeticId } }
                                  ├─ 401 → ReauthHook（SyncService 重新认证一次）→ 重放本批
                                  └─ 结果写回缓存（并发容器）
```

- **代次校验**：换服 / 换 provider / 重载时 `LoadoutService.generation++`，
  在飞响应回来时代次不匹配 → 直接丢弃（否则切服后旧响应会写回新缓存）；
- **`fallbackToLocal`**：只有"根本没配后端地址"的纯本地模式才回退到本地穿戴
  （用于单机预览渲染链路）。配了后端但关掉同步时**绝不回退** ——
  否则自己的时装会渲染到所有玩家身上，带 `hideParts` 的甚至会让别人模型部位消失。

### 4.3 上报与绑定

```
本地换装（命令 / GUI）
  └─ LocalLoadout.wear/unwear → LoadoutStore.requestSave()（2 秒 debounce 落盘）
  └─ SyncService.uploadLocalLoadout() → PUT /loadout（全量覆盖）

/vmfashion bind
  └─ POST /device/start → 显示 6 位用户码 → 轮询 GET /device/status
       直到 approved（网站衣柜绑定成功） / expired
```

### 4.4 定时刷新与换号竞态

- `tick()` 每秒检查一次：到 `refreshIntervalSeconds`（默认 45 秒）就刷新他人穿戴；
- 低频复核当前 Minecraft 账号（UUID 变了 → 旧 token 作废并重新认证），
  避免"A 的穿戴写进 B 的账号"；
- 401 → 重认证 + 重放（带冷却窗口，避免一批分块每块都触发认证）。

---

## 5. 资产下发链路

```
后端: GET /packs  →  { packs: [ {id, version, sha256, bytes, url} ] }
                                    ↓
AssetSyncService（主线程 tick 只做内存判断，拉起 worker）
  ├─ 用户包集合 userPacks（packs/<id>/）：存在则永不下载/覆盖
  ├─ 已装集合 installed（packs/server/<id>/ + .sha256 标记）
  ├─ 入队：LoadoutService 解析不到的 cosmeticId → requestMissing(packId)
  └─ worker（最多 2 个并发）
       ├─ PackDownloader：流式下载 + DigestInputStream 算 sha256 + 校验字节数
       ├─ SafeZip：中央目录预校验 → 流式解压到 packs/server/.tmp-*
       ├─ 校验 pack.json.id == 清单 id
       ├─ ATOMIC_MOVE → packs/server/<id>/ + 写 .sha256 标记（兼 LRU 时间戳）
       └─ 回主线程：PackLoader.reload() + CosmeticAssets.invalidate()（GL 上传）
```

失败处理：单包失败 → 删临时目录 + 冷却 10 分钟（清单里没有的包 60 秒后再看清单）；
后端 404/501 → 永久降级（`catalogUnsupported`），只用本地包。
LRU 淘汰按 `maxPackCacheMb`（默认 512MB），只动 `packs/server/`。

---

## 6. 线程模型

| 线程 | 谁 | 能做什么 | 不能做什么 |
|---|---|---|---|
| **主线程 / 渲染线程**（同一个） | 游戏主循环 | 渲染、GUI、命令、`PackLoader.reload`、`CosmeticAssets.invalidate` | 阻塞网络 / 大文件 IO |
| **HTTP 回调**（CompletableFuture / HttpClient） | `FashionApi`、`FashionAuth`、`HttpLoadoutProvider` | 解析 JSON、写并发容器缓存 | 碰 `Minecraft` / GL / 非并发容器 |
| **资产下发 worker**（`AssetSyncService` 自建，最多 2 个） | 下载 + 解压 + 安装 | 网络、磁盘 | 碰 GL（装完必须回主线程 reload） |
| **落盘守护线程**（`vmfashion-loadout-saver`，懒创建） | `LoadoutStore` | 原子写 `loadout.json` | 碰任何游戏对象 |

规则：

1. **渲染线程只读**：`RenderCache` 是普通 `HashMap`，只有渲染线程访问；
2. 网络回调写缓存用**并发容器**（`ConcurrentHashMap`），由主线程消费；
3. 触碰 GL / `Minecraft` 的操作必须回主线程：`Minecraft.getInstance().execute(...)`；
4. 贴图注册 / 释放（`CosmeticAssets`）**只在主线程**；
5. `LoadoutStore` 的快照在锁外取，避免和 `LocalLoadout` 的监视器形成 ABBA 死锁。

---

## 7. 关键设计取舍

### 7.1 为什么直接读磁盘，不走资源包？

资源包方案要求把时装打进 jar 或挂一个 `resourcepacks/`，玩家改一件时装要重载整个资源栈。
直接读磁盘（`GeckoLibGsonLoader` 烘焙 JSON + `DynamicTexture` 注册 PNG）换来：

- 秒级 reload（`/vmfashion reload` / F3+T）；
- 支持 `packs/server/` 命名空间（远程下发的包和用户包隔离）；
- 不会和别的 mod 的资源包冲突。

代价：贴图生命周期要自己管（`DynamicTexture` 注册/释放），资源 ID 要自己保证唯一
（`CosmeticResourceId` = 可读路径 + CRC32 哈希）。

### 7.2 为什么用 `ThreadLocal` 复用 `PoseStack`？

GeckoLib 自己的 `GeoQuad#render` 每个 quad `new Vector4f()` ×4、`GeoCube#render` 每 quad
还 `new Vector3f()`，100 quad 的模型 ≈ 500 对象/帧/实例。`GeoModelRenderer` 复刻了同样的顶点数学，
但全部走 `ThreadLocal<Scratch>` 里的复用实例 —— 回调串行、不嵌套，所以单实例安全。

### 7.3 为什么 hideParts 用位掩码 + `AvatarRenderState`？

隐藏信息原本只在 `PlayerModel#setupAnim` 里改 `ModelPart.visible`，而 9 个渲染层各用各的模型、
不看父模型。位掩码挂在渲染状态上，各层 `submit` 的 HEAD 读一次就能整层跳过：
`int` 传递零分配，且和 26.x 的"提取状态 → 提交"两阶段模型天然契合。

### 7.4 为什么动画快照要"临时挂载 + 还原"而不是克隆骨骼树？

克隆骨骼树（每玩家一份）会让内存 × 玩家数，且 `BakedGeoModel` 的父子引用要整体重建。
"临时挂到共享骨骼、画完还原"把成本压到每玩家每时装一套 `BoneSnapshot`（只有动画涉及的骨骼），
代价是**必须保证还原**（放在 `finally` 里）—— 漏一处就会把动画泄漏给下一个玩家。

### 7.5 为什么 emissive 不逐 texel 裁剪？

逐 texel 要拆分四边形 + 重建 UV，会破坏法线 / 顶点数一致性，收益远小于风险。
"整 quad 保留 / 丢弃"已经能表达绝大多数发光设计（发光区域通常按面划分）。

### 7.6 为什么资产下发单独一层 `packs/server/`？

远程数据必须和玩家手工包隔离：`/vmfashion cache clear`、LRU 淘汰、版本升级只动 `server/`，
不会误删玩家自己的包；同 `packId` 时用户包永远优先。

### 7.7 为什么防御式解析贯穿所有入口？

时装包来自磁盘、清单来自网络，都不可信。`PackLoader` 单条坏数据只记警告并跳过、
`ModConfig.sanitize` 收敛非法数值、`SafeZip` 把每个 zip 当恶意输入处理 ——
**一个坏包 / 坏响应绝不能让客户端起不来**。

---

## 8. 数据流总图

### 8.1 一帧之内（渲染链路）

```
玩家实体
  └─ AvatarRendererMixin            extractRenderState 末尾写入 UUID
  └─ PlayerModelMixin               setupAnim 末尾算 hideParts 位掩码 → AvatarRenderState
  └─ CapeLayerMixin.submit(HEAD)
       ├─ RenderCache               每 tick 一份穿戴快照
       ├─ LoadoutService.resolve    ID → CosmeticDefinition（带缓存）
       ├─ CosmeticAssets.get        懒烘焙：模型 / 贴图 / 动画（首次会卡一帧）
       ├─ AnimationCache            uuid|cosmeticId → AnimationFrame
       └─ submitCustomGeometry      主层 + 可选 emissive 层（只存 Pose 快照 + 回调）
            └─ flush（本帧末尾，单线程串行）
                 └─ GeoModelRenderer  骨骼遍历 + 动画采样（临时挂快照、finally 还原）
                                      + EmissiveMask 逐 quad 过滤
                      └─ SubmitNodeCollector → 屏幕
  └─ 9 个 layer mixin               submit HEAD 读位掩码 → 整层跳过（盔甲/手持/披风/…）
```

### 8.2 一次同步 / 下发（网络链路）

```
启动    ModConfig.load → PackLoader.reload → CosmeticRegistry → LoadoutStore.loadInto
进服    SyncService.onJoin
          └─ FashionAuth  POST /auth/challenge → 会话服务器 join → POST /auth/verify
               └─ session.json（过期自动重认证）
          └─ HttpLoadoutProvider  POST /loadouts/resolve（200 个 UUID/批，5 分钟 TTL）
               └─ LoadoutService 缓存（代次校验，换服丢弃旧响应）
渲染    LoadoutService.loadoutFor(uuid)
          本地玩家 → LocalLoadout（内存，零延迟）
          其他玩家 → provider 缓存；未命中则入队后台 resolve
换装    命令 / 衣柜 GUI → LocalLoadout → loadout.json（debounce 落盘）
                        → SyncService.uploadLocalLoadout → PUT /loadout
资产    LoadoutService 解析不到某件时装
          └─ AssetSyncService.requestMissing(packId) → 队列（最多 2 并发）
               ├─ GET /packs                      清单（缓存在 cache/pack-catalog.json）
               ├─ GET /packs/{id}/{version}/download
               ├─ PackDownloader                  sha256 + 字节数校验
               ├─ SafeZip                         安全解压到 packs/server/.tmp-*
               └─ 校验 pack.json.id → 原子 move → packs/server/<packId>/
                    └─ 回主线程：PackLoader.reload + CosmeticAssets.invalidate
```

---

## 9. 扩展点

| 想做的事 | 改哪里 | 注意 |
|---|---|---|
| 新增一个原版渲染层的 hideParts 支持 | `mixin/<Layer>Mixin` + `HiddenParts` 归属 | 必须在 `submit` HEAD 读掩码；`tools/verify/run.sh` 会校验 descriptor |
| 新增 `pack.json` 字段 | `PackLoader#buildDefinition` + `CosmeticDefinition` | 字段缺失必须回退默认值（老包兼容） |
| 新增分类 | `CosmeticCategory` 枚举 + `lang/*.json` 的 `vmfashion.gui.tab.*` | 两个语言文件 key 必须一致（`tools/dev/check-lang.sh`） |
| 新增命令 | `FashionCommand#build` + `lang/*.json` | 文案走 `Component.translatable` |
| 新增后端字段 | `FashionApi` + `HttpLoadoutProvider` / `SyncService` | 契约见后端文档；解析必须容错 |
| 新增配置项 | `ModConfig`（字段 + getter/setter + `sanitize`） | 有默认值，旧 `config.json` 可直接升级 |
| 动画控制器 / molang | `render/anim/BedrockAnimations`（当前只按数值解析关键帧） | 支持表达式求值需要引入 molang 求值器；状态隔离已经做好（见 §3.6） |
