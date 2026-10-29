# 时装包下发 API 契约（后端实现指南）

> 目标读者：**VMTools 后端 / 站点同学**。
> 本文定义 VMFashion 客户端「时装包自动下发」需要的全部 HTTP 契约，照此实现即可让
> **「A 穿的时装，B 没装也能看见」** 生效。
>
> - 客户端实现：`src/client/java/com/venus/vmfashion/net/PackCatalog.java`、
>   `PackDownloader.java`、`SafeZip.java`、`sync/AssetSyncService.java`
> - 时装包本身的结构（`pack.json`、`cosmetics/` 目录）：见 [pack-format.md](pack-format.md) §10
> - 如果你发现文档和代码不一致，**以代码为准**，并请提 issue。

---

## 0. 一句话现状

现在的 `resolve` / `loadout/me` 是**按单件资源**设计的（`assetKey` 是单个文件），
而一件时装的运行时单位是**目录**（`model.geo.json` + `texture.png` + 可选 `emissive.png` / `anim.json`）。
所以缺的不是「一个下载接口」，而是一层**包级（pack）契约**：清单 → 整包 zip → 客户端安全解压安装。

| 端点 | 状态 | 作用 |
|---|---|---|
| `GET /packs` | **需新增** | 包清单（id / version / sha256 / bytes / url） |
| `GET /packs/{id}/{version}/download` | **需新增** | 整包 zip 字节流 |
| `POST /loadouts/resolve` | 需**补字段** | 每个 item 补 `packId` / `packVersion` |
| `GET /loadout/me` | 需**补字段** | 同上 |

客户端在拿不到这些端点时会**自动降级**（见 §9），不会崩、不会刷屏。

---

## 1. `GET /packs` —— 包清单

### 请求

```http
GET /api/fashion/packs HTTP/1.1
Accept: application/json
User-Agent: VMFashion
Authorization: Bearer <token>        # 可选：清单一般公开，带了也接受
```

客户端行为（务必按此假设设计）：

- **不跟随 302/301**（`HttpClient.Redirect.NEVER`，防止 `Authorization` 被带到别的 host）→ 本端点必须直接返回 `200`；
- 超时：连接 10s、请求 15s；
- 清单本地缓存 **10 分钟**（磁盘 `config/vmfashion/cache/pack-catalog.json`），进服会强制刷新一次；
- 失败后至少间隔 **30s** 才会重试，不会形成风暴。

### 响应 `200`

```json
{
  "packs": [
    {
      "id": "wings",
      "version": "1.2.0",
      "sha256": "9f2c1a4b7d8e0f3a5b6c7d8e9f0a1b2c3d4e5f60718293a4b5c6d7e8f9012345",
      "bytes": 481203,
      "url": "/packs/wings/1.2.0/download",
      "publishedAt": 1735603200000
    }
  ]
}
```

也接受**直接返回数组**（`[{...}, {...}]`），客户端两种形状都认。

### 字段表

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `id` | string | ✅ | packId。**同时是客户端目录名**，只允许 `[A-Za-z0-9._-]{1,64}`，且不能是 `.` / `..`；不合法条目会被**静默丢弃** |
| `version` | string | ✅ | 包版本，≤ 64 字符。客户端按 `(packId, sha256)` 判重，版本变了但 sha256 相同不会重复下载 |
| `sha256` | string | ✅ | 整包 zip 的 sha256，**64 位 hex（大小写均可，建议小写）**。没有可信哈希的条目会被丢弃（客户端拒绝下载无法校验的包） |
| `bytes` | number | ✅ | zip 的**精确字节数**，必须 > 0。客户端边下边校验，多了立刻中断 |
| `url` | string | ⬜ | 下载地址。可绝对（`https://cdn...`）也可相对（`/packs/{id}/{version}/download`）；**留空时客户端按相对地址拼** |
| `publishedAt` | number | ⬜ | 发布时间（毫秒时间戳），仅用于展示/排查 |

### 响应头建议

```http
Cache-Control: public, max-age=300
ETag: "catalog-v42"
```

客户端有 10 分钟内存/磁盘 TTL，服务端给 5 分钟 `max-age` 比较合适；支持 `ETag` + `304` 可以省流量
（客户端目前每次都是完整 GET，`304` 会当作空清单处理，**所以请直接返回 200 全量**，除非你愿意客户端下次迭代加 `If-None-Match`）。

### 错误

| 情况 | 建议返回 | 客户端行为 |
|---|---|---|
| 端点未实现 | `404` / `501` / `405` | 记**一次** info 日志，**本次会话内关闭自动下发**（不重试） |
| 服务端 5xx | `500`/`503` | 30s 后重试，只写 debug 日志 |
| 未登录 | `200`（清单公开） | 若必须鉴权，`401` 会走同步层的重新认证流程 |

---

## 2. `GET /packs/{id}/{version}/download` —— 整包 zip

### 请求

```http
GET /api/fashion/packs/wings/1.2.0/download HTTP/1.1
Accept: application/zip, application/octet-stream
User-Agent: VMFashion
Authorization: Bearer <token>        # 仅当 URL 与 apiBaseUrl 同源时才会带
```

### 硬性要求（客户端会按这些假设校验）

1. **必须 `200` 直出字节流，不能 302 到 CDN** —— 客户端不跟随重定向，会直接判定失败。
   要放 CDN，请在清单里给 `url` 填 CDN 的**绝对地址**（此时客户端不会带 token，CDN 需支持匿名下载）。
2. **`Content-Length` 必须准确**，且等于清单里的 `bytes`；客户端会校验实际字节数。
3. **`Content-Type`**：`application/zip`（或 `application/octet-stream`）。
4. **不支持 Range / 断点续传**（客户端每次整包下载）。
5. **`ETag` / `Last-Modified` 可选**，客户端不发送条件请求。

### zip 结构要求

zip **根目录**直接是包内容，不要多套一层文件夹：

```
wings.zip
├── pack.json            # 必需，且 "id" 必须与清单里的 id 完全一致（客户端会校验，不一致直接丢弃）
└── cosmetics/
    └── crown/
        ├── model.geo.json
        └── texture.png
```

> `pack.json` 的字段见 [pack-format.md](pack-format.md) §4。`pack.json.id` 与清单 `id` 的大小写
> 不敏感比较，但建议**完全一致**，避免玩家端目录名与 registry id 混淆。

### 大小与结构限制（客户端硬限制，超了直接拒绝安装）

| 限制 | 值 | 客户端拒绝原因 |
|---|---|---|
| 条目数 | ≤ 4096 | `TOO_MANY_ENTRIES` |
| 单个文件解压后 | ≤ 64 MB | `ENTRY_TOO_LARGE` |
| 总解压大小 | ≤ 512 MB | `TOTAL_TOO_LARGE` |
| zip 本身 | ≤ 128 MB | `TOTAL_TOO_LARGE` |
| 压缩比 | ≤ 100:1（仅当解压后 > 10 MB） | `RATIO_TOO_HIGH` |
| 条目名长度 | ≤ 255 | `BAD_ENTRY_NAME` |

**一律拒绝**（每条都有客户端单测）：`..` 路径逃逸、绝对路径、Windows 盘符（`C:`）、UNC、
NTFS 数据流（`a:b`）、Windows 保留设备名（`CON`/`NUL`/`COM1`…）、符号链接 / 硬链接 / Windows reparse point、
仅大小写不同的重复条目（`A.png` 与 `a.png`）、文件与目录同名冲突（`a` 与 `a/b`）、
ZIP64、加密 zip、未知压缩算法（只允许 stored / deflate）。

> 服务端在**上传时**就应该用同样的规则先校验一遍：坏包在服务端被挡住，玩家端只会看到"没有这个包"，
> 而不是安装失败。

---

## 3. `resolve` / `loadout/me` 补字段

`POST /loadouts/resolve` 与 `GET /loadout/me` 的每个 item 需要补两个字段，客户端据此知道
「这件时装属于哪个包、什么版本」，从而判断要不要下载：

```json
{
  "loadouts": {
    "069a79f4-44e9-4726-a5be-fca90e38aaf5": {
      "items": [
        {
          "slot": "cape",
          "cosmeticId": "wings:angel_white",
          "packId": "wings",
          "packVersion": "1.2.0"
        }
      ]
    }
  },
  "missing": []
}
```

- **向后兼容**：老客户端只读 `slot` / `cosmeticId`，多出的字段会被忽略；
- 客户端也支持「只有 `cosmeticId`」的退化路径：`cosmeticId` 前缀（冒号前）就是 packId，
  所以 **`cosmeticId` 必须保持 `<packId>:<localId>` 格式**；
- `packVersion` 目前只用于诊断与未来的增量更新，客户端判重以 `(packId, sha256)` 为准。

---

## 4. 错误响应格式

沿用现有统一格式，两种形状客户端都认：

```json
{ "detail": { "code": "pack_not_found", "message": "包不存在或已下架" } }
```

```json
{ "code": "pack_not_found", "message": "包不存在或已下架" }
```

| HTTP | `code` | 含义 | 客户端行为 |
|---|---|---|---|
| 400 | `bad_request` / `invalid_request` | 参数非法 | 不重试，聊天栏提示 |
| 401 | `no_token` / `unauthorized` | 未登录 / token 失效 | 触发一次重新认证，然后重放（仅 resolve 路径） |
| 403 | `forbidden` | 无权限 | 不重试，聊天栏提示 |
| 404 | `pack_not_found` / `unknown_cosmetic` | 包/时装不存在 | 不重试，10 分钟内不再请求该包 |
| 404 / 405 / 501 | （任意） | **端点未实现** | 关闭本次会话的自动下发（只记一次日志） |
| 429 | `rate_limited` / `too_many_requests` | 限流 | 按 `Retry-After` 等待后重试 |
| 503 | `service_unavailable` | 后端维护 | 指数退避重试（最多 3 次尝试） |

**`Retry-After`**（429 / 503）支持两种格式，客户端都会解析：

```http
Retry-After: 5                       # 秒
Retry-After: Wed, 21 Oct 2015 07:28:00 GMT   # HTTP-date
```

> 客户端上限 60s：给更大的值也只会等 60s。等待期间不会重试其它请求（熔断）。

---

## 5. 限流、缓存与并发建议

| 项 | 客户端实际行为 | 服务端建议 |
|---|---|---|
| 清单拉取 | 10 分钟 TTL + 进服强制刷新一次 | 5 分钟 `max-age`，按 IP 限流 |
| 包下载并发 | 同时最多 **2** 个，其余排队 | 每 IP 10 req/min 足够 |
| 失败重试 | 单包失败冷却 **10 分钟**；网络错误 30s 后重试清单 | 返回 `Retry-After` 更友好 |
| 熔断 | 连续 5 次"后端不可用"类失败 → 30s 内直接失败 | 维护时统一返回 503 + `Retry-After` |
| 缓存 | 下过一次就留在 `packs/server/`，离线可用 | 不必考虑客户端离线场景 |

---

## 6. 安全建议（强烈建议在 v1.0 前完成）

`sha256` 只能防**传输损坏**，防不了**恶意源**：任何有上传权限的账号都能把任意 zip 推给所有玩家。

1. **包签名（推荐）**：`pack.json` 增加 `"signature"` 字段，用 Ed25519 对
   `sha256(pack.json 去掉 signature 字段后的规范化 JSON)` 签名，mod 内置公钥验签。
   客户端目前**未实现验签**（见路线图 §6.1），但契约里先留出字段，避免以后改格式：
   ```json
   { "formatVersion": 1, "id": "wings", "version": "1.2.0", "signature": "ed25519:<base64>" }
   ```
2. **上传时静态校验**：zip 结构、`pack.json` 必填字段、模型 JSON 大小上限、拒绝可执行文件
   （`.exe` / `.jar` / `.sh` / `.dll`）与隐藏文件。
3. **审核流**：新包 `pending` → 管理员审核 → `published`；未发布的包不出现在 `/packs`。
4. **下架**：把包从 `/packs` 移除即可；客户端下个 TTL 周期不再下载，但**已下载的包不会被远程删除**
   （安全考虑：远程删除能力等于远程删文件）。如需强制清理，走 `/vmfashion cache clear`。
5. **内容安全**：贴图是玩家可见内容，需要和皮肤站同一套审核标准。

---

## 7. 鉴权

- `/packs` 建议**公开**（匿名可读），这样未登录玩家也能看到别人穿的时装；
- `/packs/{id}/{version}/download` 建议**公开**，否则 CDN 无法直出；
- 若必须鉴权：客户端只在 **URL 与 `apiBaseUrl` 同源**时带 `Authorization: Bearer <token>`，
  绝对地址指向第三方时**绝不带 token**（防泄漏）；
- token 由现有 `/auth/*` 流程签发，有效期 24h，过期后同步层会自动重新认证。

---

## 8. 联调自测清单

```bash
BASE=https://vmtools.example.com/api/fashion

# 1) 清单：必须 200 + JSON，且 packs[].sha256 是 64 位 hex
curl -s "$BASE/packs" | jq '.packs[0]'

# 2) 下载：必须 200 直出（加 -I 看不到 302），Content-Length == 清单 bytes
curl -sI "$BASE/packs/wings/1.2.0/download" | grep -iE 'HTTP/|content-length|content-type|location'

# 3) 哈希与字节数必须和清单一致
curl -s "$BASE/packs/wings/1.2.0/download" -o /tmp/wings.zip
sha256sum /tmp/wings.zip
stat -c %s /tmp/wings.zip

# 4) zip 根目录直接是包内容，pack.json.id == 清单 id
unzip -l /tmp/wings.zip | head
unzip -p /tmp/wings.zip pack.json | jq -r .id

# 5) resolve 里每个 item 都带 packId / packVersion
curl -s -X POST "$BASE/loadouts/resolve" \
  -H 'Authorization: Bearer <token>' -H 'Content-Type: application/json' \
  -d '{"mcUuids":["069a79f4-44e9-4726-a5be-fca90e38aaf5"]}' | jq '.loadouts'

# 6) 未实现时的降级：临时把端点指到 404，客户端日志应只出现一次
#    "后端未实现 GET /packs（HTTP 404），本次会话内关闭时装包自动下发"
```

客户端日志关键字（排查用）：

```
时装包自动下发已启用：<baseUrl>（已缓存 N 个包）
时装包清单已更新：N 个包
已下发时装包 <packId> v<version>（x.x MB，N 个文件）
时装包重载完成：N 个包 / M 件时装
下发时装包 <packId> 失败（10 分钟后再试）: <原因>
后端未实现 GET /packs（HTTP 404），本次会话内关闭时装包自动下发；本地已装的包照常可用
```

---

## 9. 后端未实现时的降级行为矩阵

| 场景 | 客户端表现 |
|---|---|
| `/packs` 返回 404/501/405 | 只记一次 info 日志，本次会话关闭自动下发；本地包、渲染、衣柜、同步全部正常 |
| `/packs` 网络错误 | 每 30s 最多重试一次，debug 日志；玩家无感 |
| 单个包下载失败 | 该包冷却 10 分钟，其它包不受影响；玩家看到"别人身上没显示这件时装" |
| sha256 / 字节数不符 | 删除残包，不安装（不会出现"半坏"的包） |
| zip 触发安全规则 | 拒绝安装并清理临时目录；日志里有明确原因码 |
| `resolve` 缺 `packId` | 退化为按 `cosmeticId` 前缀推导 packId，仍可工作 |
| `downloadServerPacks=false`（配置） | 整个服务空转：零请求、零磁盘写入 |

---

## 10. 版本与兼容

- 清单接口**不加版本号**：新增字段向后兼容，删除/改语义需要新端点（如 `/v2/packs`）；
- 客户端 `packId` 正则与长度限制**不会放宽**（它直接进文件系统路径）；
- 单个 zip 上限 128 MB 是客户端硬限制，未来提高需要发新版客户端；
- 客户端缓存目录：`config/vmfashion/packs/server/<packId>/`，其中 `.sha256` 是客户端写的标记文件
  （含 `packId` / `version` / `sha256` / `bytes` / `installedAt` / `lastUsedAt`），服务端无需关心。

---

## 11. 相关文档

- [pack-format.md](pack-format.md) —— 时装包制作与格式（模型作者视角）
- [../README.md](../README.md) —— 玩家安装与使用
- [../开发路线图.md](../开发路线图.md) §2.1 / §6.1 —— 自动下发与内容安全的整体规划
