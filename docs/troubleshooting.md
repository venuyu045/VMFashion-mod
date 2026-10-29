# 排障指南（玩家向）

> 目标读者：装了 mod、但"时装不显示 / 同步不上 / 卡"的玩家。
> 每条都给出**先看什么 → 再看什么 → 常见原因**，不需要读代码。
>
> 做包的问题（模型位置不对、贴图错位、字段写错）看 [pack-format.md](pack-format.md)；
> 想改代码看 [CONTRIBUTING.md](../CONTRIBUTING.md)。

- [0. 先做这三件事](#0-先做这三件事)
- [1. 时装完全不显示](#1-时装完全不显示)
- [2. 别人看不到我穿的时装](#2-别人看不到我穿的时装)
- [3. 快捷键 / 衣柜界面](#3-快捷键--衣柜界面)
- [4. 包加载失败](#4-包加载失败)
- [5. 云端同步不生效](#5-云端同步不生效)
- [6. 服务端自动下发的包没出现](#6-服务端自动下发的包没出现)
- [7. 中文乱码](#7-中文乱码)
- [8. 游戏崩溃](#8-游戏崩溃)
- [9. 卡顿 / 掉帧](#9-卡顿--掉帧)
- [10. 向开发者反馈时带什么](#10-向开发者反馈时带什么)
- [附录 A：配置文件字段一览](#附录-a配置文件字段一览)

---

## 0. 先做这三件事

```
/vmfashion status      # 一眼看全局：同步 / token / 资产下发 / 本地包 / 当前穿戴
/vmfashion debug       # 渲染链路自检：每件时装的分类、骨骼、四边形数、贴图 ID
/vmfashion diag        # 导出诊断文件，聊天栏会给出完整路径
```

`diag` 生成的文件在：

```
<游戏目录>/config/vmfashion/diag/diag-<时间戳>.txt
```

里面包含：模组版本 / 系统 / Java、全部配置、包清单、时装清单、当前穿戴、同步状态、
资产下发状态、以及**最近 150 行日志**。

日志本体在 `<游戏目录>/logs/latest.log`，搜 `vmfashion` 或 `时装包:`。

---

## 1. 时装完全不显示

按顺序排查：

### 1.1 先确认"包加载到了"

```
/vmfashion list
```

- 输出 `没有加载到任何时装包…` → 跳到 [§4](#4-包加载失败)；
- 有包但列表里没有你要的那件 → 那件被跳过了，日志里有 `时装包: ` 开头的警告（见 [§4](#4-包加载失败)）。

### 1.2 再确认"穿上了"

```
/vmfashion equip <id>        # id 可以是全名 packId:localId，也可以是唯一的短名
/vmfashion unequip <分类>    # 脱下某个分类（例如 unequip hat）
/vmfashion status            # 底部会列出当前穿戴
```

`找不到时装：xxx` → 先 `/vmfashion list` 看准确 ID。

### 1.3 再确认"渲染链路通了"

```
/vmfashion debug
```

输出里每件时装一行：

- `找不到定义` → ID 对不上（包被换了 / 拼错）；
- `资源加载失败（看日志）` → 模型烘焙或贴图注册失败，日志里有 `资源加载失败`；
- `分类=… 骨骼=… 根骨骼=N 四边形=M 贴图=vmfashion:…` → 说明链路是通的，
  那么问题在**位置 / 尺寸 / 遮挡**，不在加载。

### 1.4 常见原因

| 现象 | 原因 | 修法 |
|---|---|---|
| 第一人称看不到 | ① 时装挂在 `head`/`body` 上（第一人称只渲染 `right_arm`/`left_arm` 挂件）；② 手里拿着物品（原版空手才画手臂） | 空手 + 换一件 `attachedBone` 为手臂的时装验证 |
| 别人身上有，自己身上没有 | 纯本地模式（没配 `apiBaseUrl`）时"别人"渲染的是**你自己的**穿戴，容易看反 | `/vmfashion debug` 看自己的 UUID 那条 |
| 周围有，远处没有 | 渲染距离默认 **64 格** | `config.json` 里调大 `renderDistance`（`<= 0` 表示不限制） |
| 全部时装都不见了 | 配置里 `enabled=false` | 改成 `true`，`/vmfashion config reload` |
| 隐身药水/旁观时没有时装 | 隐身状态不渲染（和原版披风/盔甲层一致） | 正常现象 |
| 其他玩家身上都没有 | `renderOtherPlayers=false` | 改成 `true`，`/vmfashion config reload` |
| 只有这件不显示，其它正常 | 模型烘焙失败 / 坐标在视野外 | 看 `logs/latest.log`；`/vmfashion debug` 的四边形数是否为 0 |
| 改了模型文件没生效 | 缓存 | `/vmfashion reload`（或 F3+T 资源重载） |
| 动画不动 | 见 [§1.5 动画不动](#15-动画不动) | 骨骼名对不上 / 该构建不含播放 |

### 1.5 动画不动

`anim.json` 的解析失败**不会**影响模型显示（只记一条警告，模型按静态姿态渲染），所以先按下面顺序确认：

| 检查 | 怎么查 |
|---|---|
| 动画文件被识别了吗 | 文件名必须是 `anim.json` / `*.anim.json` / `*.animation.json`，且放在 `cosmetics/<id>/` 下（或用 `animation` 字段显式指定） |
| 骨骼名对得上吗 | 动画里的骨骼名必须与 `model.geo.json` 里的骨骼 `name` **一致**（大小写不敏感）；对不上的骨骼通道会被**静默忽略** |
| 有多个动画？ | 一个文件里放多个动画时**只播第一个**（按 JSON 里的声明顺序） |
| 播完就停？ | `"loop": false` 的动画播完会停在最后一帧，这是正常行为 |
| 以上都对但还是不动 | 多半用到了**不支持的动画特性**（molang 表达式 / `timeline` / `animation_controllers` / `catmullrom`），见 [pack-format.md §9](pack-format.md#9-动画文件) 里的「不支持」表；换成纯数字关键帧即可 |

> 想确认解析结果：`/vmfashion reload` 后用 `logs/latest.log` 搜 `动画`；
> 或直接用示例包 `config/vmfashion/packs/example/cosmetics/crown/anim.json` 对照格式。

---

## 2. 别人看不到我穿的时装

这取决于**服务器/后端**有没有在跑，以及别人本地有没有那个包。

| 情况 | 结果 |
|---|---|
| 你和别人都**没配后端**（纯本地模式） | 每个人只看到**自己**的穿戴（"别人身上的时装"其实是自己的，用于预览） |
| 配了后端，后端有你的穿戴记录，别人也配了同一后端 | ✅ 别人能看到（走 `resolve` 拿 `slot → cosmeticId`） |
| 后端有记录，但**别人本地没有那个包** | 需要**服务端下发包**（`downloadServerPacks=true` + 后端实现 `/packs`），否则显示不出来 |
| 后端记录里时装被禁用 / 你超出槽位上限 | 聊天栏会提示具体错误码对应的原因（见 §5.5） |

自查：

```
/vmfashion status      # 同步状态 + token 是否有效
/vmfashion sync        # 手动把当前穿戴推到后端
```

---

## 3. 快捷键 / 衣柜界面

- 默认快捷键 **K**（可在 `选项 → 控制 → 按键绑定 → Venus Mc Fashion` 分类里改，条目叫「打开衣柜」）；
- 也可以 `/vmfashion open` 或 `/vmfashion gui` 打开；
- 装了 **Mod Menu** 的话，模组列表里的「配置」按钮也指向衣柜。

### 3.1 K 没反应

| 原因 | 排查 |
|---|---|
| 按键被别的 mod 抢了 | 控制 → 按键绑定 → Venus Mc Fashion，看 K 有没有冲突（红色提示） |
| 当前已经开着界面（聊天框 / 物品栏 / 别的 mod 界面） | 关掉界面再按（mod 不会抢占焦点） |
| 快捷键注册失败 | 日志里搜 `注册衣柜快捷键失败`；命令 `/vmfashion open` 仍然可用 |
| 输入法/键盘布局问题 | 换一个键绑定试试 |

### 3.2 界面很卡

- 首次打开会**逐件烘焙模型 + 上传贴图**，每帧有数量上限，慢慢补齐；
- 时装很多时可以把 `uiScale` 调小（0.5~2.0）；
- `animationDuration` 设成 `0` 可以关掉打开动画。

---

## 4. 包加载失败

所有包加载问题都会在**日志**里留一行以 `时装包: ` 开头的警告，
`/vmfashion reload` 时也会直接显示在聊天栏（`§e警告：…`）。

| 日志关键词 | 原因 | 修法 |
|---|---|---|
| `pack.json 解析失败或不是 JSON 对象` | JSON 语法错（注释 / 尾逗号 / 全角引号） | 用 JSON 校验器检查 |
| `模型文件不存在` | `model` 路径错，或目录里没有 `*.geo.json` | 检查路径（相对包根目录） |
| `贴图文件不存在` | `texture` 路径错；目录里只有 `emissive.png` | 补 `texture.png` |
| `模型不是有效的 Bedrock 几何` | 导出成了 Java 方块模型 / 缺 `minecraft:geometry` | Blockbench 里用 **Bedrock Entity** 重新导出 |
| `时装 ID 重复` | 同一个包里两件同名（大小写不敏感） | 改成唯一 id |
| `重复的 packId=` | 两个目录用了同一个 `id`（含 `packs/server/` 下发的同名包） | 改 `id`；用户包会自动优先，删掉多余的即可 |
| `有一件时装缺少 id` | `cosmetics[]` 里某项没写 `id` | 补 `id` |
| `scale 非法（…），已回退 1.0` | `scale` 是 0 / 负数 / NaN / 字符串 | 写正数 |
| `包目录是越界符号链接` | 包目录是指向包外的符号链接 | 用真实目录 |
| `既没有 cosmetics 列表，也没有 cosmetics/ 目录` | 空包 | 补 `cosmetics/<id>/` 或 `cosmetics` 数组 |
| `没有解析出任何时装` | 所有条目都被跳过了 | 看上面其它警告定位 |

> 详细的字段规则和目录约定见 [pack-format.md](pack-format.md)。

---

## 5. 云端同步不生效

### 5.1 先看是不是"纯本地模式"

```
/vmfashion status
```

- `同步：纯本地模式（未配置后端）` → `config.json` 里 `apiBaseUrl` 是空的；
- `同步：已配置后端 <url>，尚未登录` → 地址有了，但还没认证成功（执行 `/vmfashion login`）；
- `同步：已登录 <名字>（authType）→ <url>` → 这一行正常，问题不在认证；
- 填法（注意是**到 `/api/fashion` 这一级**，末尾斜杠会被自动去掉）：

```json
{
  "apiBaseUrl": "https://你的后端地址/api/fashion",
  "syncEnabled": true
}
```

改完**不用重启**：`/vmfashion config reload`。

### 5.2 再确认登录

```
/vmfashion login      # 用当前 Minecraft 会话向后端认证
/vmfashion status     # 看 Token 行，三种形态：
                      #   Token：剩余 N 分钟（authType）   ← 正常
                      #   Token：已过期，执行 /vmfashion login 重新认证
                      #   Token：尚未登录时装后端
```

- token 过期会**自动重新认证**（进服、401、定时复核都会触发），不用手动反复 login；
- 换账号后旧 token 会自动作废（运行中也会复核 UUID）。

### 5.3 想用网站衣柜

```
/vmfashion bind       # 聊天栏显示 6 位用户码
```

然后到网站「时装绑定」页输入这个码，等 mod 轮询到 `approved` 即可。

### 5.4 别人的时装看不到

- 后端 `resolve` 只回 `slot → cosmeticId`，**资产要靠包下发**（见 [§6](#6-服务端自动下发的包没出现)）；
- 刷新有间隔：`refreshIntervalSeconds`（默认 **45 秒**），改小可以让"别人换装"更快体现；
- 换服后旧响应会被丢弃（代次校验），不会串服。

### 5.5 聊天栏里的错误码

后端返回的错误码会被翻译成玩家能看懂的提示（同一条提示会去重，不会刷屏）：

| 聊天栏提示 | 后端错误码 | 怎么办 |
|---|---|---|
| 时装不存在或已下架：`X` | `unknown_cosmetic` | 后端 catalog 里没有这个 ID，检查拼写或让服主上架 |
| 后端不认识这件时装：`X` | `invalid_cosmetic` | 同上（ID 格式不对） |
| 这件时装已被管理员禁用：`X` | `cosmetic_disabled` | 换一件 |
| 穿戴槽位超过后端上限，请先卸下几件再试 | `too_many_items` | 卸掉几件再试 |
| 这个账号还没绑定网站，执行 `/vmfashion bind` 完成绑定 | `not_joined` | 先绑定；皮肤站玩家还要确认后端白名单 / `authServer` |
| 尚未登录时装后端，执行 `/vmfashion login` | `no_token`（401） | 已自动重认证一次仍失败 → 手动 `/vmfashion login` |
| 当前启动器没有提供有效的登录令牌（离线登录无法同步时装） | `no_token`（无会话） | 用正版 / 皮肤站账号启动，离线登录无法同步 |
| 操作太频繁，请稍后再试 | `rate_limited` / 429 | 等一会儿（后端限流） |
| 时装后端连续失败，已暂停请求，稍后会自动重试 | `circuit_open` | 熔断保护，等自动恢复；持续出现说明后端挂了 |
| 连不上时装后端：`X` | `network_error` | 检查网络 / `apiBaseUrl` 是否可达 |
| 没有配置时装后端地址（…`apiBaseUrl`） | `no_base_url` | 填 `config.json` 的 `apiBaseUrl` |
| 时装后端地址非法：`X` | `bad_base_url` | 必须是 `http(s)://` 开头的合法地址 |
| 没有权限执行这个操作：`X` | `forbidden` / 403 | 检查账号权限 |
| 会话服务器拒绝了 join（HTTP `X`）…请重新登录游戏 | `join_failed` | 重新登录游戏；皮肤站玩家检查皮肤站是否正常 |

> 皮肤站地址探测顺序：`-javaagent:…authlib-injector.jar=<URL>` 启动参数 → `config.json` 的 `authServer`。
> 正版玩家两者都留空即可。

---

## 6. 服务端自动下发的包没出现

```
/vmfashion status
```

看 `资产下发：` 那一行：

| 输出 | 含义 | 怎么办 |
|---|---|---|
| `已在配置中关闭（downloadServerPacks=false）` | 配置关了 | 改成 `true` + `/vmfashion config reload` |
| `未配置后端地址` | 纯本地模式 | 填 `apiBaseUrl` |
| `后端未提供 /packs，已禁用` | 后端没实现包清单接口 | 让服主升级后端；本地已装的包仍可用 |
| `N 个包 / X MB · 队列 M · 清单 K` | 正常工作 | 队列 M 长时间不降 → 看日志里的下载失败原因 |
| `未启用` | 服务未初始化 | 重启游戏 |

其它手段：

- 缓存占满（`maxPackCacheMb`，默认 512MB）会按 LRU 淘汰，不用手动清；
- 想强制重下：`/vmfashion cache clear`（**只删 `packs/server/`，不会动你自己装的包**）；
- 清单缓存文件：`config/vmfashion/cache/pack-catalog.json`，删掉后下次会重新拉；
- 下载失败的包会冷却 **10 分钟**才重试（避免对坏包疯狂重试），着急就 `cache clear`。

---

## 7. 中文乱码

| 现象 | 原因 | 修法 |
|---|---|---|
| 游戏内聊天栏中文正常，日志文件里乱码 | 日志文件的编码 / 查看器编码不是 UTF-8 | 用 UTF-8 打开 `latest.log`（VS Code / Notepad++ 里选 UTF-8） |
| Windows 控制台里乱码 | 控制台代码页是 GBK | 不影响游戏内显示，可 `chcp 65001` |
| 自己写的 `pack.json` / `meta.json` 中文变问号 | 文件存成了 GBK | 另存为 **UTF-8（无 BOM）** |
| 界面里显示 `vmfashion.gui.xxx` 原始 key | 语言文件缺 key | 属 bug，请带上 `diag` 反馈 |

> 界面 / 命令输出已支持**中英双语**（跟随游戏语言）；`/vmfashion reload` 打出的
> **包加载警告是开发者向诊断信息，仍然只有中文**，这是有意的，不影响使用。

---

## 8. 游戏崩溃

1. 先找崩溃报告：`<游戏目录>/crash-reports/crash-<时间>.txt`；
2. 看 `Description:` 和 `Caused by:` 第一段；
3. 常见关键词：

| 关键词 | 含义 | 怎么办 |
|---|---|---|
| `Mixin apply failed` / `vmfashion.mixins.json` | mixin 目标方法对不上（**版本不匹配**） | 确认是 MC **26.3**；升级/降级 mod 到对应版本 |
| `NoClassDefFoundError: com/geckolib/...` | 缺 GeckoLib | 装 **GeckoLib 5.5.7+**（不是 4.x） |
| `NoClassDefFoundError: net/fabricmc/...` | Fabric API 版本太低 | 升级 Fabric API 到 **0.165.0+26.3** |
| `UnsupportedClassVersionError` | Java 版本太低 | 用 **Java 25** 启动 |
| `NoClassDefFoundError: com/terraformersmc/...` | 单独装了 Mod Menu 集成但缺 Mod Menu | 这个类只会在装了 Mod Menu 时加载；如出现说明环境异常，先卸 Mod Menu |

4. 如果崩溃发生在**打开衣柜 / 装备某件时装**时，把该包从 `packs/` 移走，看是否恢复 ——
   能定位到具体包的话，附上 `diag` + 那个包一起反馈。

---

## 9. 卡顿 / 掉帧

| 现象 | 原因 | 缓解 |
|---|---|---|
| 第一次见到某件时装卡一下 | 首次渲染才烘焙模型 + 上传贴图（懒加载） | 正常；之后走缓存 |
| 人多的地方掉帧 | 每个玩家每件时装 1~2 次几何提交（不合批） | 调小 `renderDistance`，或 `renderOtherPlayers=false` |
| 打开衣柜卡 | 逐件烘焙（每帧有上限，会慢慢补齐） | 减少时装数量 / 调小 `uiScale` / `animationDuration=0` |
| 内存涨 | 服务端包缓存 + 每件一张 `DynamicTexture` | 调小 `maxPackCacheMb`；`/vmfashion cache clear` |
| 一进服就卡几秒 | 认证 + 批量 resolve（后台线程，但主线程会处理结果） | 正常 |

> 排查性能问题前先确认不是光影/其它 mod：把光影关掉、只留 Fabric API + GeckoLib + VMFashion 复现一次。

---

## 10. 向开发者反馈时带什么

最少这些：

1. `/vmfashion diag` 生成的 **`diag-<时间戳>.txt`**（含配置、包清单、同步状态、日志尾部）；
2. `/vmfashion debug` 的聊天栏截图（或复制文字）；
3. **完整** `logs/latest.log`（问题发生那一次）；
4. 如果是崩溃：`crash-reports/crash-<时间>.txt`；
5. 环境：MC 版本、Fabric Loader / Fabric API / GeckoLib / Mod Menu 版本、Java 版本；
6. 复现步骤（"进服 → 穿 X → 切第三人称 → 不显示"）。

> `diag` 文件**不包含** token 明文（只写是否有效 / 剩余时间），但包含 UUID 和本地路径，
> 公开贴出前可以自行删掉不想要的行。

---

## 附录 A：配置文件字段一览

位置：`config/vmfashion/config.json`。**改完执行 `/vmfashion config reload` 即可生效，不用重启游戏。**
写错的值会被自动收敛到安全范围（不会崩），缺失字段用默认值。

| 字段 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关。关掉后不再提交任何时装几何（包仍会加载） |
| `renderOtherPlayers` | `true` | 是否渲染其他玩家身上的时装 |
| `renderDistance` | `64.0` | 渲染距离（格）。`<= 0` 表示不限制，上限 512 |
| `scale` | `1.0` | 全局缩放，会乘到每件时装自己的 `scale` 上（0.05 ~ 8.0） |
| `apiBaseUrl` | `""` | 时装后端地址（留空 = 纯本地模式），例如 `https://example.com/api/fashion` |
| `syncEnabled` | `true` | 云端同步开关（需要 `apiBaseUrl` 且认证成功） |
| `authServer` | `""` | 皮肤站 Yggdrasil 地址；留空则从 `-javaagent:authlib-injector` 参数自动探测 |
| `refreshIntervalSeconds` | `45` | 其他玩家穿戴的定时刷新间隔（秒），`0` 关闭（0 ~ 3600） |
| `downloadServerPacks` | `true` | 是否自动下载服务端下发的时装包 |
| `maxPackCacheMb` | `512` | 服务端包缓存上限（MB），超出按 LRU 淘汰（0 ~ 8192） |
| `uiScale` | `1.0` | 衣柜界面缩放（0.5 ~ 2.0） |
| `animationDuration` | `200` | 界面打开动画时长（毫秒，0 ~ 1000；`0` = 不要动画） |
| `toastEnabled` | `true` | 是否显示 Toast 提示 |

> 配置项含义的详细解释见 [pack-format.md](pack-format.md)（包相关）与
> [architecture.md](architecture.md)（同步 / 资产下发相关）。
