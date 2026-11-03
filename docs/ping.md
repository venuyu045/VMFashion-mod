# 标点功能（VMPing 合订版）

> v2.0 起，独立模组 [VMPing](https://github.com/venuyu045/VMPing-mod) 的标点功能并入 VMFashion，
> 作为时装模组的**附属功能**发布。本文是合订版的完整说明；独立版 VMPing 的仓库保持原样、
> 不再更新（它的协议 v1.1 对合订版仍然适用）。

## 1. 是什么

按住**鼠标中键**弹出轮盘，选一个类型，松开就在你看着的地方打一个标点 —— **同服的其他玩家也能看见**，
而**服务器什么都不用装**。

```
按住中键 ──► 轮盘（注意 / 危险 / 物品 / 集合 / 求助）──► 松开 ──► 标点出现在世界里
                                                        │
                                                        └─► 经 HTTP 中继同步给同服玩家
```

原版服务端没有客户端之间的通信通道，所以「服务器不装 mod 也能互相看见」只有一条路：
**服务器之外的轻量中继**。合订版复用 VMFashion 已经跑通的通信范式：
**MC 账号认证 → Bearer token → HTTP/JSON**，中继挂在 VMTools 网页平台的 `/api/ping` 路由上。

与独立版最大的区别：**认证不再单独做一遍**。标点直接复用时装同步层的
`FashionAuth` 会话（同一个 Minecraft 账号、同一份 24h token、同一个后端地址），
玩家登录一次即可；没登录 / 关掉同步 / 后端不可达时自动降级为**本地标点**（功能照用，只是别人看不见）。

## 2. 功能

| 能力 | 说明 |
|---|---|
| **中键轮盘** | 默认鼠标中键：按住出轮盘、移动选类型、松开标点；Esc 取消 |
| **5 种标点** | 注意（绿）/ 危险（红）/ 物品（黄）/ 集合（蓝）/ 求助（橙） |
| **服务器零安装** | 纯客户端，不发任何自定义载荷给服务器，原版服 / Paper / Velocity 都能用 |
| **多人可见** | 同服玩家（同房间）实时收到彼此的标点，延迟典型 100–250ms |
| **世界标记** | 屏幕空间投影渲染：类型图标 + 距离 + 标点者名字（按住 Tab 强制显示）；远处自动缩小淡出 |
| **屏幕边缘指示** | 标点在屏幕外时，屏幕边缘画方向箭头 + 距离 |
| **20 秒自动消失** | 与服务端 TTL 一致，最后 3 秒淡出，不挡视线 |
| **降级可用** | 中继不可用 / 单机 / 局域网 / 离线账号 → 自动退化为**本地标点** |
| **登录态复用** | 与 VMFashion 共用时装会话，进服免重复认证；正版与皮肤站（authlib-injector）都支持 |
| **可配置** | 14 个配置项，游戏内「时装设置 → 标点设置」可视化修改，`config/vmfashion/config.json` 的 `ping` 对象持久化 |
| **中英双语** | 界面与命令输出跟随游戏语言 |

## 3. 使用

### 3.1 轮盘

- 默认按键：**鼠标中键**（可在「设置 → 控制 → 按键绑定 → Venus Mc Fashion → 标点」改键）。
- 按住打开轮盘，鼠标移到扇区选类型，松开在该方向标点；Esc 取消。
- 轮盘打开期间会抑制原版「选取方块」（中键），关闭后中键恢复原版行为。

### 3.2 命令

所有命令都挂在 `/vmfashion` 下（独立版的 `/vmping` 不再注册，避免两个模组同时装时冲突）：

| 命令 | 说明 |
|---|---|
| `/vmfashion ping status` | 中继状态 / 房间 / 维度 / 标点数 / 后端地址 |
| `/vmfashion ping [类型]` | 不打开轮盘，按准星方向直接标点（`default` / `danger` / `item` / `go` / `help`，缺省 `default`） |
| `/vmfashion ping open` | 打开轮盘界面 |
| `/vmfashion ping settings` | 打开标点设置界面 |
| `/vmfashion ping config reload` | 重读 `config.json` 并重启中继 |
| `/vmfashion ping debug` | 打印诊断信息（房间键、归一化地址、时钟偏移、错误详情） |
| `/vmfashion ping help` | 帮助 |

### 3.3 界面入口

衣柜界面（默认 `K`）右上角「设置」→「标点设置…」。设置界面里的改动点「完成」后
立即热生效（标点中继会重启），不需要重进游戏。

## 4. 配置项

配置文件：`config/vmfashion/config.json` 的 `ping` 对象（与其它时装配置同一个文件）。
所有字段缺失时使用默认值，手改坏了也不会让客户端崩溃（越界值会被收敛）。

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 标点总开关（关闭后不显示轮盘、不发标点） |
| `apiBaseUrl` | `""` | 标点后端地址；**留空 = 跟随时装后端**（见 §5） |
| `showOwnPings` | `true` | 显示自己的标点 |
| `showOthersPings` | `true` | 显示别人的标点 |
| `showDistance` | `true` | 显示距离数字 |
| `showPlayerNames` | `false` | 显示标点者名字（按住 Tab 强制显示） |
| `playSound` | `true` | 提示音 |
| `soundVolume` | `0.7` | 提示音音量（0–1） |
| `maxIconDistance` | `96` | 超过这个距离（格）只画边缘箭头 |
| `pingTtlSeconds` | `20` | 本地显示时长（秒，3–20；不超过服务端 TTL） |
| `pollTimeoutSeconds` | `20` | 长轮询等待时长（秒，5–25） |
| `showEdgeIndicator` | `true` | 屏幕边缘方向箭头 |
| `showStatus` | `true` | HUD 连接状态角标 |
| `degradeToLocal` | `true` | 中继不可用时自动降级为本地标点 |
| `debugLogging` | `false` | 调试日志（排查中继问题时打开） |

> 原独立版 VMPing 的 `authServer` 字段不再需要：认证完全复用时装层，皮肤站地址统一由
> 「时装设置 → 皮肤站地址」/ authlib-injector 探测决定。

## 5. 后端地址与认证

### 5.1 地址推导

标点默认使用**时装后端地址**推导出的 `/api/ping`：

| 时装后端 `apiBaseUrl` | 标点后端 |
|---|---|
| `https://www.venusyu045.cn/api/fashion` | `https://www.venusyu045.cn/api/ping` |
| `https://example.com/api/fashion` | `https://example.com/api/ping` |
| `https://example.com/api`（不以 `/fashion` 结尾） | `https://example.com/api/ping` |
| 留空（纯本地模式） | 无（标点只本地可见） |

需要单独指定时，把「标点后端地址」填上即可（例如自建后端把两组路由挂在不同域名）。

### 5.2 认证

- 复用时装同步层的 `FashionAuth` 会话：`/vmfashion login` 成功、或进服自动认证成功后，
  标点中继直接拿同一个 token 调 `/api/ping`。
- token 的 JWT 受众：合订版用 `aud=fashion`；独立版 VMPing 仍用 `aud=ping`。
  后端 `/api/ping` **两种受众都接受**（详见后端仓库 `docs/ping-api.md`）。
- token 过期 / 被 401 拒绝时，标点中继会让时装层重新认证一次，不需要玩家手动操作。
- 明文 `http`（非本机回环）后端不会发送任何凭据：标点降级为本地。

### 5.3 房间与隐私

- 房间 = 「服务器地址 × 维度」的临时空间：`roomId = hex(sha256(归一化服务器地址))[0:16]`。
- **单机 / 局域网世界不同步**（只本地标点），避免把单人存档暴露给同房间的陌生人。
- 中继是**纯内存**的：不落库、不保留历史，标点 20 秒后自动过期。
- 标点内容只有坐标 / 维度 / 类型 / 玩家 UUID+名字，没有聊天内容。

## 6. 协议摘要（客户端视角）

Base URL：`<标点后端>/api/ping`（见 §5.1），请求/响应均为 UTF-8 JSON。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/rooms/{roomId}/pings` | Bearer | 发送标点，成功 201，返回 `id` / `seq` / `serverTime` / `expiresAt` |
| GET | `/rooms/{roomId}/pings?since=&timeout=&dimension=` | Bearer | 长轮询拉取（最多等 25s），返回存活标点 + 当前 `seq` |

- 房间回收后 `seq` 归零：客户端检测到 `seq < 本地 since` 时重置游标并清空他人标点。
- 长轮询正常超时（200 + 空数组）不算失败，客户端立即发起下一次。
- 限流 / 容量：每玩家 5 条 / 3s；房间满 / 全局长轮询打满返回 429 / 503，客户端退避重试，
  本地标点照常显示。
- 错误体：`{"detail": {"code": "...", "message": "..."}}`；客户端按 `code` 决定重认证 / 退避 / 放弃。

> 完整协议（含独立版 VMPing 的认证端点与 v1.1 修订记录）见 VMPing 仓库的
> [`docs/协议-v1.md`](https://github.com/venuyu045/VMPing-mod/blob/main/docs/协议-v1.md)。
> 合订版不调用 `/auth/challenge`、`/auth/verify`，这两个端点保留给独立版客户端。

## 7. 从独立版 VMPing 迁移

1. 删除 `mods/` 里的 `vmping-*.jar`（**必须删**：同时装会重复注册中键快捷键与 HUD，
   客户端检测到独立版时会在聊天栏提示）。
2. 安装 VMFashion 2.0+ 的 jar（`vmfashion-mc26.3-2.0.0.jar`）。
3. 配置：独立版的 `config/vmping/config.json` 不会自动迁移，请在游戏内
   「时装设置 → 标点设置」里重新设置（默认值已覆盖绝大多数玩家的需求）。
4. 命令：`/vmping ...` → `/vmfashion ping ...`。
5. 后端账号：不需要重新登录 —— 标点复用时装会话；如果之前只登录过 VMPing、没登录过时装，
   进服会自动认证一次。

## 8. 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| 标点只有自己看得见 | 看 `/vmfashion ping status`：未登录时装后端 / 后端不可达 / 单机 / 局域网 → 本地模式 |
| HUD 角标显示「服务不可用」 | 后端地址不是 https、网络不通、或后端 5xx；`/vmfashion ping debug` 看错误码 |
| 按中键没反应 | 键位被其它模组占用（设置 → 控制 → 按键绑定改键）；或标点总开关被关 |
| 中键同时把方块复制到手上 | 说明装的是旧版/独立版 VMPing；删除独立版 jar |
| 别人看不到我的标点，但状态显示在线 | 对方可能关了「显示别人的标点」，或与你不在一台服务器/同一维度 |
| 聊天栏提示「独立 VMPing 模组」 | 删除 `mods/vmping-*.jar`，标点功能已内置 |
