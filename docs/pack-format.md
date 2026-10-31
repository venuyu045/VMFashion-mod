# 时装包格式与制作教程

> 目标读者：想给 VMFashion 做时装包的模型作者 / 服主。
> 读完这份文档，你应该能**从零做出一个能被 mod 正确加载并显示在玩家身上的包**。
>
> 本文所有字段名、默认值、目录约定都来自 `src/client/java/com/venus/vmfashion/cosmetic/PackLoader.java`。
> 如果你发现文档和代码不一致，以代码为准，并请提 issue。

- [1. 五分钟做出第一个包](#1-五分钟做出第一个包)
- [2. 目录结构](#2-目录结构)
- [3. Blockbench 制作流程](#3-blockbench-制作流程)
- [4. pack.json 字段表](#4-packjson-字段表)
- [5. 元数据的三级优先级（重点）](#5-元数据的三级优先级重点)
- [6. 分类与挂载骨骼](#6-分类与挂载骨骼)
- [7. 隐藏原版部位（hideParts）](#7-隐藏原版部位hideparts)
- [8. 发光层（emissive）](#8-发光层emissive)
- [9. 动画文件](#9-动画文件)
- [10. 服务端自动下发](#10-服务端自动下发)
- [11. 常见错误对照表](#11-常见错误对照表)
- [12. 交付前自检清单](#12-交付前自检清单)

---

## 1. 五分钟做出第一个包

最小可加载的包只需要 **3 个文件**：`pack.json`、`model.geo.json`、`texture.png`。

```
.minecraft/config/vmfashion/packs/mypack/
├── pack.json
└── cosmetics/
    └── hat01/
        ├── model.geo.json
        └── texture.png
```

`pack.json` 可以只写包信息，一件时装都不声明（会自动扫描 `cosmetics/` 目录）：

```json
{
  "formatVersion": 1,
  "id": "mypack",
  "name": "我的第一个时装包",
  "version": "1.0.0",
  "author": "你的名字",
  "license": "MIT"
}
```

然后进游戏执行 `/vmfashion reload`，再 `/vmfashion list` 应该能看到 `mypack:hat01`，
`/vmfashion equip mypack:hat01` 就能穿上。

> 只写目录、不写 `cosmetics` 数组时，mod 会按文件名猜分类（`hat01` 里有 `hat` → 分类 `hat` → 默认挂到头部）。
> 猜得不准就用 [meta.json](#5-元数据的三级优先级重点) 显式指定。

---

## 2. 目录结构

### 2.1 完整布局

```
<游戏目录>/config/vmfashion/packs/
├── mypack/                        # ← 玩家自己装的包（packId 通常 = 目录名）
│   ├── pack.json                  # 必需：没有它的目录会被静默忽略
│   └── cosmetics/
│       ├── crown/                 # ← 一件时装一个目录，目录名 = localId
│       │   ├── meta.json          # 可选：这件时装的元数据（优先级最高）
│       │   ├── model.geo.json     # 必需：Bedrock 几何
│       │   ├── texture.png        # 必需：主贴图
│       │   ├── emissive.png       # 可选：发光层贴图
│       │   └── anim.json          # 可选：Bedrock 动画（播放，见 §9）
│       └── wings/
│           └── ...
└── server/                        # ← 服务端自动下发专用命名空间，玩家不要手改
    └── <packId>/
        ├── pack.json
        ├── cosmetics/...
        └── .sha256                # 下发时写入的标记（sha256 + LRU 时间戳）
```

### 2.2 文件名约定与自动发现规则

| 文件 | 必需 | 自动发现规则（不写 `pack.json` 显式路径时） |
|---|---|---|
| `pack.json` | ✅ | 缺失 → 整个目录被忽略（当作"放了一半的包"） |
| `model.geo.json` | ✅ | 目录里**第一个** `*.geo.json`（按文件名排序） |
| `texture.png` | ✅ | 优先 `texture.png`；没有则取目录里第一个 `.png`，**排除 `emissive.png`** |
| `emissive.png` | ❌ | 固定文件名 `emissive.png` |
| `anim.json` | ❌ | 目录里第一个动画文件，接受 `anim.json` / `*.anim.json` / `*.animation.json` |
| `meta.json` | ❌ | 固定文件名 `meta.json` |

> 名字大小写不敏感（自动发现时统一转小写比较）。
> 符号链接一律跳过：包目录、`cosmetics/`、资源文件都不接受指向包外的符号链接。

### 2.3 用户包 vs 服务端下发包

| | 用户包 | 服务端下发包 |
|---|---|---|
| 位置 | `packs/<packId>/` | `packs/server/<packId>/` |
| 谁放进去 | 玩家自己（或整合包/服主分发） | mod 自动下载安装 |
| 优先级 | **高**（同 `packId` 时用户包胜出） | 低（被用户包覆盖时只记一条警告） |
| `/vmfashion cache clear` | 不受影响 | 整个目录被删除 |
| LRU 淘汰（`maxPackCacheMb`） | 不受影响 | 按最久未用淘汰 |

> 为什么要分两层：自动下发的是**远程数据**，必须和玩家手工包隔离，
> 否则清缓存 / 版本升级 / LRU 淘汰都可能误删玩家自己的包。

---

## 3. Blockbench 制作流程

### 3.1 新建模型

1. Blockbench → **New Project** → 选 **Bedrock Entity** 格式（不要选 Java Block/Item，也不要选只做贴图的格式）。
2. 建议先摆一个原版玩家模型当比例参照（Blockbench 自带 Player Model 模板；也可以直接打开仓库里的示例 fixture
   `config/vmfashion/packs/example/cosmetics/crown/model.geo.json` 对着改），做完后删掉参考模型。
3. 记住三条坐标系约定（**这是最容易错的地方**）：
   - **原点在玩家脚底**，Y 轴**向上**；
   - 单位是 **1/16 格**：玩家身高 32 单位，脖子 `y=24`，头顶 `y=32`，身体 `y=12..24`；
   - **+Z 是背后**（披风 / 翅膀放在 `z > 0`），**−Z 是正面**。

   一个皇冠应该摆在 `y≈32`、`z≈0` 附近；一个背板应该摆在 `z≈2..6`、`y≈12..24`。

> 渲染时 mod 会自动把"脚底原点 / Y 向上"换算成原版玩家模型空间，
> 所以 **Blockbench 里怎么摆，游戏里就怎么显示**，不需要自己写任何变换。

### 3.2 画贴图

- 格式：**PNG**（带 alpha 通道）。不是 PNG 会加载失败。
- 尺寸：**任意正方形都行，但 `model.geo.json` 里的 `texture_width` / `texture_height` 必须和它一致**。
  简单时装建议 64×64；细节多（翼膜、渐变、发光描边）用 128×128 / 256×256 也完全没问题
  （内置官方包的「深渊龙翼」就是 256×256）。
- 一张贴图可以同时被多件时装复用吗？可以，但每件时装的 `texture` 路径是独立的，没有共享图集。

### 3.3 导出

1. 导出为 **Bedrock Geometry**（`.geo.json`）→ 得到 `xxx.geo.json`。
2. 贴图另存为 `texture.png`。
3. 按 [§2](#2-目录结构) 放进 `cosmetics/<id>/` 目录，文件名改成 `model.geo.json` / `texture.png`。

### 3.4 坐标系速查

| 部位 | 建议位置（单位 = 1/16 格） |
|---|---|
| 头顶（帽子 / 皇冠 / 光环） | `y ≈ 32`，`x/z ≈ 0` |
| 背部（披风 / 翅膀 / 背板） | `y ≈ 12..24`，`z ≈ +2..+6` |
| 肩部 | `y ≈ 24`，`x ≈ ±6` |
| 腰间挂件 | `y ≈ 12`，`z ≈ +2` |
| 脚下宠物 | `y ≈ 0..8`，`z ≈ +4..+8` |

---

## 4. pack.json 字段表

### 4.1 顶层字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `formatVersion` | int | `1` | 格式版本。当前只支持 `1`；写更大值**只警告不拒绝**（可能有字段读不懂） |
| `id` | string | 目录名 | 包 ID。**全局唯一**，会和 `localId` 拼成时装全名 `packId:localId` |
| `name` | string | = `id` | 显示名（GUI 卡片 / 日志） |
| `version` | string | `"1.0.0"` | 包版本，服务端下发时用于判断是否要更新 |
| `author` | string | `"unknown"` | 作者 |
| `license` | string | `"unknown"` | 许可证 |
| `cosmetics` | array | 省略 = 自动发现 | 时装声明数组，见 §4.2。**省略时**自动扫描 `cosmetics/*/` 目录 |

### 4.2 `cosmetics[]` 每一项的字段

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | string | **必需** | 包内唯一 ID（大小写不敏感去重）。缺了这件时装会被跳过并记警告 |
| `name` | string | = `id` | 显示名 |
| `category` | string | 猜（见 §6.1） | 分类，决定 GUI 分组 + 默认挂载骨骼 |
| `attachedBone` | string | = 分类的默认骨骼 | 挂到哪根骨骼上，见 §6.2 |
| `hideParts` | string[] | `[]` | 穿上后隐藏哪些原版部位，见 §7 |
| `scale` | number | `1.0` | 单件缩放（会再乘全局 `scale` 配置）。非法值回退 `1.0` 并警告，最终限制在 `0.01..100` |
| `material` | string | `cutout` | 渲染管线：`cutout`（alpha 二值）/ `translucent`（真混合，做纱裙 / 半透翅膀）。非法值回退 `cutout` 并警告；也接受旧写法 `"translucent": true` |
| `animationName` | string | 第一个动画 | 一件时装有多个动画时选播哪个（`anim.json` 里 `animations` 的 key）；名字对不上会回退第一个并记一次警告 |
| `model` | string | 自动发现 | 模型路径，相对包根目录 |
| `texture` | string | 自动发现 | 主贴图路径，相对包根目录 |
| `emissive` | string | 自动发现 | 发光层路径，相对包根目录 |
| `animation` | string | 自动发现 | 动画文件路径，相对包根目录 |

**路径规则**：

- 一律相对**包根目录**（`pack.json` 所在目录），用 `/` 分隔；
- 不允许绝对路径、不允许 `..` 逃出包目录（会被拒绝并记警告 `模型文件不存在`）；
- 显式写了路径但文件不存在 → 这件时装被跳过。

### 4.3 完整示例（对应 `tools/verify/fixtures/sample_pack/`，旧内置示例包保留作自检用）

```json
{
  "formatVersion": 1,
  "id": "example",
  "name": "VMFashion 示例时装包",
  "version": "1.0.0",
  "author": "VMFashion",
  "license": "MIT",
  "cosmetics": [
    {
      "id": "backboard",
      "name": "示例背板",
      "category": "back",
      "attachedBone": "body",
      "model": "cosmetics/backboard/model.geo.json",
      "texture": "cosmetics/backboard/texture.png",
      "scale": 1.0
    },
    {
      "id": "crown",
      "name": "示例皇冠",
      "category": "hat",
      "attachedBone": "head",
      "model": "cosmetics/crown/model.geo.json",
      "texture": "cosmetics/crown/texture.png",
      "scale": 1.0
    }
  ]
}
```

### 4.4 最小模型文件（`model.geo.json`）

mod 只认 **Bedrock `format_version 1.12.0`** 的几何格式，且必须包含 `minecraft:geometry`：

```json
{
  "format_version": "1.12.0",
  "minecraft:geometry": [
    {
      "description": {
        "identifier": "geometry.my_crown",
        "texture_width": 64,
        "texture_height": 64
      },
      "bones": [
        { "name": "root", "pivot": [0, 24, 0] },
        {
          "name": "crown",
          "parent": "root",
          "pivot": [0, 32, 0],
          "cubes": [
            { "origin": [-4.5, 32, -4.5], "size": [9, 1, 9], "uv": [0, 0] }
          ]
        }
      ]
    }
  ]
}
```

> **限制**：一个文件里只支持**一个** `minecraft:geometry` 定义（取第一个）。
> 需要多个变体就拆成多个包 / 多件时装。
> 骨骼可以随便命名，`attachedBone` 用的是**原版玩家**的骨骼名（见 §6.2），不是这里写的名字。

### 4.5 逐面 UV（`uv` 写对象）与左右镜像

除了上面这种 box UV（`"uv": [u, v]`），也支持 Bedrock 的**逐面 UV**：

```json
{ "origin": [0, 0, 0], "size": [8, 2, 1],
  "uv": {
    "north": { "uv": [0, 0],  "uv_size": [32, 8] },
    "south": { "uv": [0, 0],  "uv_size": [32, 8] },
    "east":  { "uv": [0, 8],  "uv_size": [4, 8]  },
    "west":  { "uv": [0, 8],  "uv_size": [4, 8]  },
    "up":    { "uv": [0, 16], "uv_size": [32, 4] },
    "down":  { "uv": [0, 16], "uv_size": [32, 4] }
  } }
```

逐面 UV 适合「一整块贴图板 + alpha 裁形」的做法（内置官方包的龙翼翼膜就是这么做的：
翼膜外形画在贴图 alpha 里，材质 `cutout` 把透明像素丢掉，外缘就是平滑曲线而不是方块锯齿）。

**方向约定（实测 GeckoLib 5.5.7，逐面 UV）：**

| 面 | `u` 增大的方向 | `v` 增大的方向 |
|---|---|---|
| `north`（-Z） | 模型 **x 减小**方向 | **向下**（y 减小） |
| `south`（+Z） | 模型 **x 增大**方向 | **向下**（y 减小） |
| `east` / `west` | 按 z 方向 | 向下 |
| `up` / `down` | 按 x 方向 | 按 z 方向 |

> 注意 `north` 与 `south` 的 `u` 方向**相反**（同一张贴图贴在正反两面时，从外面看都是正着的）。
> 这条不在 Bedrock 规范里、是实现细节，换渲染器可能变；**能不用就别依赖它**。

**做左右镜像部件时的坑**：如果你用脚本把左半边 cube 镜像成右半边（`origin.x = -(x + w)`、
绕 Y/Z 的旋转取反、`pivot.x` 取反），**同时要把 `north` 与 `south` 的 UV 互换**，
否则同一张贴图在右翼上会左右颠倒（典型症状：翼膜扇形变成"尖端朝外"，两翼一上一下）。

更稳的写法是 Bedrock 原生镜像：给 cube 加 `"mirror": true`（GeckoLib 烘焙时会自己翻 UV），
就不用手工换 UV 了。内置龙翼用的是手工换 UV 的写法（生成脚本
`tools/dev/gen_dragon_wings.py` 里 `mirror_cube()`），因为模型是脚本按角度算出来的。

---

## 5. 元数据的三级优先级（重点）

同一件时装的属性可以从三个地方来，**后者覆盖前者**：

| 优先级 | 来源 | 能提供什么 |
|---|---|---|
| ① 最低 | **目录内容自动推断** | 模型/贴图/发光/动画文件、猜分类、默认骨骼、`scale=1.0`、`hideParts=[]` |
| ② 中 | `pack.json` 的 `cosmetics[]` 里 **按 id 匹配的声明项** | 显式路径 + name / category / attachedBone / hideParts / scale |
| ③ 最高 | 单件目录下的 **`meta.json`** | 与 ② 完全相同的字段（局部覆盖全局） |

### 5.1 为什么要有三级

- 只想快速试一个模型 → 只放目录，什么都不写（①）；
- 一个包有很多件、想统一管理元数据 → 写在 `pack.json`（②）；
- 某一件要单独调参（比如只有它要 `hideParts: ["all"]`）→ 放 `meta.json`（③），
  不用回头改 `pack.json`，也不会影响同一个包里的其它时装。

### 5.2 `meta.json` 完整示例

`cosmetics/crown/meta.json`：

```json
{
  "id": "crown",
  "name": "黄金皇冠",
  "category": "hat",
  "attachedBone": "head",
  "hideParts": ["head"],
  "scale": 1.25,
  "model": "cosmetics/crown/model.geo.json",
  "texture": "cosmetics/crown/texture.png",
  "emissive": "cosmetics/crown/emissive.png",
  "animation": "cosmetics/crown/idle.animation.json"
}
```

字段与 `pack.json` 的 `cosmetics[]` 项**完全一致**，全部可选。

### 5.3 三种写法的效果对照

假设目录是 `cosmetics/crown/{model.geo.json, texture.png, emissive.png, anim.json}`：

| 写法 | 结果 |
|---|---|
| 什么都不写（`pack.json` 无 `cosmetics`，无 `meta.json`） | 猜分类：`crown` 不含 wing/cape/hat/helm/halo/pet → `other`，挂 `body`，`scale=1.0`，不隐藏任何部位。发光层和动画会自动发现 |
| `pack.json` 写 `{"id":"crown","category":"hat","hideParts":["head"],"scale":2.0}` | 分类 `hat`，挂 `head`，隐藏头部，放大 2 倍；模型/贴图/发光/动画仍然自动发现 |
| 再加 `meta.json` 写 `{"scale":3.0}` | 除 `scale=3.0` 外其它字段仍来自 `pack.json` 的声明项（**合并**，不是整体替换） |

> 合并规则：字段级覆盖。`meta.json` 里没写的字段，继续用 `pack.json` 声明项的值；
> `pack.json` 里没写的，继续用自动推断的值。

---

## 6. 分类与挂载骨骼

### 6.1 `category` 取值

| 值 | 含义 | 默认 `attachedBone` |
|---|---|---|
| `cape` | 披风 | `body` |
| `wings` | 翅膀 | `body` |
| `hat` | 帽子 / 头饰 | `head` |
| `back` | 背饰 | `body` |
| `pet` | 宠物 / 跟宠 | `body` |
| `aura` | 光环 / 特效 | `body` |
| `other` | 其他（兜底） | `body` |

写不认识的值 → 回退到 `other`。

**自动猜分类的规则**（只在不写 `category` 时生效，按顺序匹配 `localId`）：

| `localId` 含 | 猜成 |
|---|---|
| `wing` | `wings` |
| `cape` / `cloak` | `cape` |
| `hat` / `helm` / `halo` | `hat` |
| `pet` | `pet` |
| 其它 | `other` |

### 6.2 `attachedBone` 取值

| 值 | 跟随的部位 |
|---|---|
| `body` | 躯干（默认） |
| `head` | 头部（低头 / 转头会跟着动） |
| `left_arm` | 左手 |
| `right_arm` | 右手 |
| `left_leg` | 左腿 |
| `right_leg` | 右腿 |

- 大小写不敏感（内部统一转小写）；
- 写不认识的值 → 保持原样传给渲染层，渲染层找不到对应部位时会退回 `body` 行为；
- **模型本身仍然按"脚底原点"书写**，`attachedBone` 只决定"跟着哪个部位动"，
  不是让你把模型画到骨骼局部坐标里。

### 6.3 缩放的基准点

`scale` 是**绕挂载 pivot 缩放**（例如 `attachedBone: head` 的皇冠绕脖子/头部 pivot 放大），
不是绕脚底。所以放大 2 倍的头饰仍然戴在头上，不会飞出去。

---

## 7. 隐藏原版部位（hideParts）

`hideParts` 是字符串数组，穿上这件时装后把这些原版部位隐藏掉（避免穿模）。

### 7.1 可选值（大小写不敏感）

| 值 | 隐藏内容 |
|---|---|
| `all` | 整套原版玩家模型 + 所有附属层 |
| `head`（别名 `hat` / `helmet`） | 头部 |
| `body`（别名 `torso` / `chest` / `jacket`） | 躯干 |
| `right_arm`（别名 `rightarm`） | 右臂 |
| `left_arm`（别名 `leftarm`） | 左臂 |
| `right_leg`（别名 `rightleg`） | 右腿 |
| `left_leg`（别名 `leftleg`） | 左腿 |

写不认识的值 → **忽略**（不会让你变成隐形人）。

### 7.2 隐藏范围：不止玩家本体

`hideParts` 会同时作用于 **9 个原版渲染层**，不再只有皮肤本体：

| 层 | 受哪个部位控制 |
|---|---|
| 盔甲（`HumanoidArmorLayer`） | 对应部位（头/身/手/腿） |
| 手持物（`PlayerItemInHandLayer`） | 左/右臂 |
| 披风（`CapeLayer`） | `body` |
| 鞘翅 / 翅膀层（`WingsLayer`） | `body` |
| 头戴物（`CustomHeadLayer`） | `head` |
| Deadmau5 耳朵（`Deadmau5EarsLayer`） | `head` |
| 肩头鹦鹉（`ParrotOnShoulderLayer`） | `body` |
| 旋转攻击特效（`SpinAttackEffectLayer`） | 仅 `all` |
| 蜂刺 / 插在身上的箭（`StuckInBodyLayer`） | 仅 `all` |

> 举例：`hideParts: ["head"]` 的帽子会同时隐藏头部皮肤、头盔、头戴物、Deadmau5 耳朵。

### 7.3 典型用法

| 写法 | 适用 |
|---|---|
| `"hideParts": ["head"]` | 帽子 / 头饰 |
| `"hideParts": ["body"]` | 披风 / 翅膀 / 背饰（藏掉躯干避免穿模） |
| `"hideParts": ["head", "body"]` | 整套上身时装 |
| `"hideParts": ["all"]` | 全身替换（玩家会变成"隐形人"，只剩你的模型） |

---

## 8. 发光层（emissive）

- 文件名：`emissive.png`（或 `emissive` 字段显式指定）；
- 语义：**逐面发光** —— 只有 UV 落在 emissive 贴图**不透明像素**上的四边形才会发光；
- 尺寸：必须与 `model.geo.json` 的 `texture_width` / `texture_height` **完全一致**，否则 UV 映射错位；
- 阈值：alpha > 8（1/255 的边缘噪声不会误触发）；
- 整张图全透明 → 等于没有发光层，不会产生额外渲染开销；
- 发光层**不描边**（用的是不写 outline 的渲染管线）。

制作建议：在 Blockbench 里把要发光的区域在 emissive 贴图上画成不透明，其余留全透明；
发光区域在主贴图上正常上色即可。

---

## 9. 动画文件

支持三种文件名（大小写不敏感，目录里取第一个）：

- `anim.json`
- `<任意前缀>.anim.json`
- `<任意前缀>.animation.json`（Blockbench 默认导出名）

```json
{
  "format_version": "1.8.0",
  "animations": {
    "animation.my_crown.float": {
      "loop": true,
      "animation_length": 3.0,
      "bones": {
        "crown": {
          "position": {
            "0.0": [0, 0, 0],
            "1.5": [0, 1.0, 0],
            "3.0": [0, 0, 0]
          },
          "rotation": {
            "0.0": [0, 0, 0],
            "1.5": [0, 4, 0],
            "3.0": [0, 0, 0]
          }
        }
      }
    }
  }
}
```

> 上面是示例 fixture 里皇冠的「上下浮动 + 左右摇摆」动画，可以直接对照。
> 关键帧值必须是**纯数字**（旋转单位是度）—— molang 表达式不支持，见下表。

**支持的能力**：

| 能力 | 说明 |
|---|---|
| `loop` / `animation_length` | 按动画自己的字段循环播放 |
| `bones.<name>.rotation` / `position` / `scale` | 三种通道都支持 |
| 关键帧插值 | **线性插值**（按时间采样） |
| 骨骼名匹配 | 与 `model.geo.json` 里的骨骼 `name` 对应，**大小写不敏感**；对不上的骨骼通道会被忽略（只记警告，不影响模型） |
| 多人同屏 | 每（玩家 × 时装）独立计时与独立骨骼快照，相位互不干扰 |
| 多个动画 | 一个文件里有多个动画时，用 `animationName` 指定播哪个（缺省播第一个，按 `animations` 里的声明顺序） |

**不支持的能力**（写了也不会生效，但不会让包加载失败）：

| 不支持 | 实际行为 |
|---|---|
| molang 表达式（如 `math.sin(query.anim_time * 180)`） | 不是纯数字的关键帧会被跳过 |
| `timeline` / 音效关键帧 / `particle_effects` | 忽略 |
| `animation_controllers` / 状态机 | 忽略（不做控制器求值） |
| `catmullrom` 曲线 | 按线性插值处理 |
| `pre` / `post` 双关键帧写法 | 只取 `post` |

---

## 10. 服务端自动下发

如果你的时装包要**分发给全服玩家**（玩家本地没装也能看到别人穿），需要让后端提供包清单：

### 10.1 后端契约（由服务端实现）

> 完整契约（请求/响应字段、错误码、限流、联调清单）见 [fashion-api.md](fashion-api.md)。下面只列客户端依赖的最小部分。

```
GET /packs                          → { "packs": [ { "id", "version", "sha256", "bytes", "url" } ] }
GET /packs/{id}/{version}/download  → zip 字节流
```

客户端行为：

1. 拉清单（缓存在 `config/vmfashion/cache/pack-catalog.json`）；
2. 看到别人穿了本地没有的时装 → 按 `packId` 入队下载；
3. 流式下载 + **校验 sha256 与字节数**；
4. **安全解压**到 `packs/server/.tmp-*`；
5. 校验 `pack.json` 里的 `id` 与清单一致 → 原子移动到 `packs/server/<packId>/`；
6. 写 `.sha256` 标记 → 回主线程 `reload`（贴图上传需要 GL 上下文）。

后端没有实现 `/packs`（404/501）时，客户端会**自动降级**为"只用本地已装的包"，不影响游戏。

### 10.2 zip 包结构要求

zip 的**根目录**必须直接是包内容（不要多套一层文件夹）：

```
mypack.zip
├── pack.json            # 必需，且 "id" 必须与清单里的 id 完全一致
└── cosmetics/
    └── crown/
        ├── model.geo.json
        └── texture.png
```

### 10.3 安全限制（超了直接拒绝安装）

| 限制 | 值 |
|---|---|
| 条目数 | ≤ 4096 |
| 单个文件解压后 | ≤ 64 MB |
| 总解压大小 | ≤ 512 MB |
| zip 文件本身 | ≤ 128 MB |
| 压缩比 | ≤ 100:1（仅当解压后 > 10 MB 时判定） |
| 条目名长度 | ≤ 255 |
| 单个模型文件（`.geo.json`） | ≤ 4 MB（v1.2.0 起；超限跳过该模型并记日志） |
| 单个模型几何面数 | ≤ 20000 quad（v1.2.0 起；超限同样跳过，不崩客户端） |

**一律拒绝**：`..` 路径逃逸、绝对路径、Windows 盘符（`C:`）、UNC 路径、NTFS 数据流（`a:b`）、
符号链接 / 硬链接条目、大小写冲突（`A.png` 与 `a.png`）、文件与目录同名冲突、
ZIP64、加密 zip、未知压缩算法。

### 10.4 缓存与淘汰

- 缓存上限：`config.json` 的 `maxPackCacheMb`（默认 **512** MB）；
- 超出后按 **LRU**（最久未被穿戴引用）淘汰；
- 用户自己装的同名包**永不覆盖**、**永不被淘汰**；
- 手动清理：`/vmfashion cache clear`（只删 `packs/server/`，用户包不受影响）。

---

## 11. 常见错误对照表

游戏日志位置：`.minecraft/logs/latest.log`。包加载的警告会以 `时装包: ` 为前缀打出来，
也可以 `/vmfashion reload` 后在聊天栏看到 `§e警告：...`。

| 症状 | 日志关键词 | 原因 | 修法 |
|---|---|---|---|
| 包完全没加载 | （无任何日志） | 目录里没有 `pack.json` | 补上 `pack.json` |
| 包被跳过 | `pack.json 解析失败或不是 JSON 对象` | JSON 语法错 / 用了注释 / 尾逗号 | 用 JSON 校验器检查（JSON 不支持注释和尾逗号） |
| 包被跳过 | `不是 JSON 对象` | `pack.json` 顶层是数组或字符串 | 顶层必须是 `{...}` |
| 某件时装没出现 | `模型文件不存在` | `model` 路径写错，或目录里没有 `*.geo.json` | 检查路径相对包根目录；或用目录自动发现 |
| 某件时装没出现 | `贴图文件不存在` | `texture` 路径写错；目录里只有 `emissive.png` | 补 `texture.png`（`emissive.png` 不会被当主贴图） |
| 某件时装没出现 | `有一件时装缺少 id` | `cosmetics[]` 里有项没写 `id` | 补 `id` |
| 某件时装被跳过 | `时装 ID 重复` | 同一个包里两件同名（大小写不敏感） | 改成唯一 id |
| 整个包被跳过 | `重复的 packId=` | 两个目录用了同一个 `id`（含 `packs/server/` 下发的同名包） | 改 `pack.json` 的 `id` 或删掉重复目录；用户包会自动优先 |
| 包被跳过 | `包目录是越界符号链接` | 包目录是指向包外的符号链接 | 用真实目录，不要用符号链接 |
| 模型加载了但不显示 / 变形 | `模型不是有效的 Bedrock 几何（缺少 minecraft:geometry）` | 导出成了 Java 方块模型 / 缺 `minecraft:geometry` 键 | Blockbench 里用 **Bedrock Entity** 格式重新导出 |
| 模型位置全错（在天上/地下/倒着） | — | 坐标系不对：用了 Y 向下或格为单位 | 原点脚底、Y 向上、单位 1/16 格（见 §3.1） |
| 贴图糊了 / 错位 | — | `texture_width/height` 与实际 PNG 尺寸不一致 | 两者保持一致（64×64 起，可到 256×256） |
| 只有 emissive 区域是黑块 | — | emissive.png 与主贴图尺寸不同 | 改成与主贴图同尺寸 |
| 缩放过小/过大 | `scale 非法（...），已回退 1.0` | `scale` 是 0、负数、NaN 或字符串 | 写正数（建议 0.1 ~ 5） |
| 穿上后模型飞走 | — | 误以为 `scale` 绕脚底 | `scale` 绕挂载 pivot；检查 `attachedBone` 是否写对 |
| 穿上后穿模 | — | 没写 `hideParts` | 加 `hideParts`（见 §7） |
| 自己第一人称看不到 | 时装挂在 `head`/`body` 上，或当前手持物品 | 只有 `attachedBone` 是 `right_arm`/`left_arm` 的时装在第一人称可见，且仅空手时（原版行为） | 切第三人称（F5）确认；手臂挂件请空手看 |
| 改了文件没生效 | — | 缓存 | `/vmfashion reload` 或 F3+T 资源重载 |
| 包里的动画没动 | 日志 `动画: 骨骼 ... 不存在` | 动画里的骨骼名与模型骨骼名对不上（大小写不敏感），或该骨骼没有关键帧 | 把动画里的骨骼名改成与模型一致（见 §9） |
| 服务端下发的包没装 | `资产下发：` 状态见 `/vmfashion status` | 后端没实现 `/packs`，或 `downloadServerPacks=false` | 见 §10 / 检查 `config.json` |

---

## 12. 交付前自检清单

- [ ] `pack.json` 是合法 JSON，`id` 全局唯一
- [ ] 每件时装都有 `id` + 能加载的 `model.geo.json` + `texture.png`
- [ ] `model.geo.json` 里有 `minecraft:geometry`，且只有一个 geometry 定义
- [ ] 模型按"脚底原点 / Y 向上 / 1/16 格"摆放
- [ ] `texture_width/height` 与 PNG 尺寸一致
- [ ] `category` / `attachedBone` / `hideParts` 是本文列出的取值
- [ ] `scale` 是正数
- [ ] 在游戏里跑一遍：`/vmfashion reload` → `/vmfashion list` → `/vmfashion equip <id>` → `/vmfashion debug`
- [ ] `logs/latest.log` 里没有 `时装包: ` 开头的警告
- [ ] 要上服务端下发的话：zip 根目录是 `pack.json` + `cosmetics/`，`id` 与清单一致

---

## 相关文档

- 后端契约（`/packs` 清单与下载接口的完整定义）：[fashion-api.md](fashion-api.md)
- 玩家排障（时装不显示 / 同步不上 / 卡顿）：[troubleshooting.md](troubleshooting.md)
- 内部架构与数据流：[architecture.md](architecture.md)
- 想改 mod 代码：[CONTRIBUTING.md](../CONTRIBUTING.md)
