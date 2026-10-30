# Modrinth / CurseForge 发布文案（备用，暂不上架）

> 这份文件是**发布时复制粘贴用**的文案草稿，不参与构建。改版本号时记得同步。
>
> **当前分发策略（2026-10-30 决定）**：只通过本仓库的 **GitHub Releases** 分发 jar + sources jar，
> **不发布到 Modrinth / CurseForge** —— 目前面向朋友间自用与朋友服务器推广。本文案留作将来要上架时备用。

## 基本信息

| 字段 | 内容 |
|---|---|
| 名称 | Venus Mc Fashion |
| 摘要（≤256 字符） | Wear your own Blockbench outfits in Minecraft — capes, wings, hats, pets — as a pure client-side Fabric mod. Optional web wardrobe sync and server-side pack delivery. |
| 分类 | `decoration`、`cosmetic`、`client-side` |
| 加载器 | Fabric |
| 游戏版本 | 26.3 |
| 环境 | Client only |
| 许可证 | MIT |
| 源码 | https://github.com/venuyu045/VMFashion-mod |
| Issue | https://github.com/venuyu045/VMFashion-mod/issues |

## 中文简介

Venus Mc Fashion（VMFashion）是一个 Minecraft **Fabric 纯客户端时装模组**：把你用 Blockbench 做的
Bedrock 3D 时装（披风 / 翅膀 / 帽子 / 背饰 / 宠物…）穿到玩家身上。**服务器不需要装任何东西**。

- 按 **K** 打开衣柜，点一下就能换装（也有 `/vmfashion` 命令）
- 别人穿的时装你没装？模组会**自动下载**服务端下发的时装包（校验 sha256 + 安全解压）
- 支持 **Bedrock 骨骼动画**（`anim.json`），每件时装独立相位
- `hideParts` 会让盔甲、手持物、披风/鞘翅、头戴、鹦鹉一起隐藏，不会穿模
- 发光层只让 `emissive.png` 里不透明的区域发光
- 可选云端衣柜：网页上换装，进游戏自动同步；`/vmfashion bind` 一键绑定
- 中英双语；重启不丢穿戴；第一人称也能看到手臂挂件

## English summary

Venus Mc Fashion (VMFashion) is a **pure client-side** Fabric mod for Minecraft that puts your own
Blockbench / Bedrock 3D outfits — capes, wings, hats, back items, pets — on your character.
**The server needs nothing installed.**

- Press **K** to open the wardrobe (or use `/vmfashion` commands)
- **Automatic pack delivery**: if another player wears a cosmetic you don't have, the mod downloads the
  pack from the server (sha256 verified, hardened zip extraction)
- **Bedrock skeletal animation** (`anim.json`), per-player phase so a crowd doesn't move in lockstep
- `hideParts` also hides armor, held items, cape/elytra, headwear and parrots — no clipping
- Emissive layer only lights up the areas that are actually opaque in `emissive.png`
- Optional **web wardrobe sync**: change outfits on the site, it follows you in game (`/vmfashion bind`)
- English + Simplified Chinese; outfits persist across restarts; arm cosmetics visible in first person

## 安装

需要 **Minecraft 26.3** + **Fabric Loader 0.20.1** + **Java 25**。

| 必需 | 版本 |
|---|---|
| Fabric API | `0.165.0+26.3` |
| GeckoLib | `5.5.7` |
| Mod Menu（可选） | `21.0.0` |

## 版本亮点（v1.1.0 可直接用）

```
## v1.1.0 — 表现力 + 数据链路

- 服务端时装包**自动更新**：后端发新版本后客户端自动重下并原子替换旧目录，
  不再需要手动 /vmfashion cache clear；替换失败会回滚旧版本
- **半透明材质**：pack.json 写 material:"translucent" 做纱裙 / 半透翅膀 / 玻璃挂件
- **多动画按名选择**：一件时装多个动画时用 animationName 指定播哪个
- **受伤红闪**：挨打时时装和皮肤一起闪红（以前只有皮肤闪）
- 单测 235 → 255

已知限制：半透明不按距离排序（同屏多件可能穿帮）；molang / 动画控制器仍不支持。
```

## 版本亮点（v1.0.1 可直接用）

```
## v1.0.1 — 稳定性修复（功能同 1.0.0）

修的是长时间在线 / 反复重载 / 多人同屏才暴露的问题，功能与 1.0.0 一致：
- 配置热重载不再泄漏 HttpClient（反复 /vmfashion config reload 不再堆线程）
- 在线超过 token 有效期后，换装上报与云端拉取不再静默失效（自动补认证，带冷却）
- 5 类后端错误码此前完全静默（认证失败看不到提示），现在都有明确提示
- not_joined 提示文案纠正（原来会把玩家引向"去绑定网站"的错误操作）
- 渲染热路径每帧 new String[] 改为复用缓冲（多人同屏少上千次分配/秒）
- 兼容后端两种列表形状（{"packs":[…]} / {"items":[…]})与 ISO-8601 publishedAt

已知限制：本地包里的时装无法同步到云端（后端目录里没有该 ID）；光影(Iris)兼容性、
多人同屏帧率仍需真机验证。
```

## 版本亮点（v1.0.0）

```
## v1.0.0 — 首个可公开发布版本

相对 0.2.0，补齐了 v1.0 清单上的最后四块：
- 后端 scale 进数据链路（网页衣柜里调的时装大小现在会生效）
- 时装包 Ed25519 签名与客户端验签（可开严格模式）
- 发布加固：坏模型不再崩客户端、动画缓存零分配、关键帧采样快 48 倍
- 诊断包补齐：/vmfashion diag 显示动画 / 发光层 / mixin 是否生效

已知限制：官方签名公钥待后端生成替换；光影(Iris)兼容性、多人同屏帧率需真机验证。
```

## 版本亮点模板（打 tag 时按 CHANGELOG 填）

```
## vX.Y.Z

### 新增
- ...

### 修复
- ...

### 已知限制
- 动画只支持 Bedrock anim.json 子集（无 molang / timeline / animation_controllers）
- 第一人称手臂挂件仅在空手时可见（原版行为）
- 光影（Iris）兼容性未验证
```

## 检查清单（发布前）

- [ ] `bash tools/dev/gradle.sh clean build --offline` 通过（含 255 条单测）
- [ ] `bash tools/verify/run.sh` 7 步全过
- [ ] `bash tools/dev/check-jar.sh` / `check-mod-json.sh` / `check-lang.sh` 全过
- [ ] `bash tools/dev/list-i18n-todo.sh --count` = 0
- [ ] 版本号已 bump（`tools/dev/set-version.sh`），README 徽章、CHANGELOG 段落同步
- [ ] `git tag -a vX.Y.Z` 并 push（CI 会自动出 Release + sha256）
- [ ] Release 正文里的 sha256 与实际产物一致
- [ ] Modrinth 上传 jar + sources jar，填本文案
