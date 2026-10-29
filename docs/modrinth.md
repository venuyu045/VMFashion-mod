# Modrinth / CurseForge 发布文案

> 这份文件是**发布时复制粘贴用**的文案草稿，不参与构建。改版本号时记得同步。

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

## 版本亮点（v1.0.0 可直接用）

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

- [ ] `bash tools/dev/gradle.sh clean build --offline` 通过（含 231 条单测）
- [ ] `bash tools/verify/run.sh` 7 步全过
- [ ] `bash tools/dev/check-jar.sh` / `check-mod-json.sh` / `check-lang.sh` 全过
- [ ] `bash tools/dev/list-i18n-todo.sh --count` = 0
- [ ] 版本号已 bump（`tools/dev/set-version.sh`），README 徽章、CHANGELOG 段落同步
- [ ] `git tag -a vX.Y.Z` 并 push（CI 会自动出 Release + sha256）
- [ ] Release 正文里的 sha256 与实际产物一致
- [ ] Modrinth 上传 jar + sources jar，填本文案
