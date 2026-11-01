# AI 辅助建模工作流

> 目标读者：**让 AI agent（或你自己）从零做出一件能被 VMFashion 加载的时装**。
> 如果你只想手工做包，看 [pack-format.md](pack-format.md) 就够了；这份文档补的是
> 「AI 怎么参与建模、怎么保证它做出来的东西真的能进游戏」。

时装模型是 **Bedrock 几何**（`model.geo.json`，`format_version` `1.12.0`），
在 Blockbench 里用 **Bedrock Entity** 格式做，运行时由 GeckoLib 5 烘焙。
格式细节见 [pack-format.md §3](pack-format.md#3-blockbench-制作流程)，本文不重复。

---

## 0. 一句话总览

```
Blockbench MCP（负责"做"）  →  modeling-gate.sh（负责"判"）  →  预览图（负责"看"）
   .bbmodel → .geo.json           audit_pack.py + render.sh        build/preview/*.png
```

三件事分开，是因为 **GeckoLib 不校验几何**：版本不认识只 `LOGGER.warn`，UV 坏了照样渲染。
所以「MCP 的 `bbmodel_validate` 通过」和「这件时装能进 VMFashion」是两回事 ——
真正的契约只在 `tools/verify/audit_pack.py` 里。

---

## 1. Blockbench MCP（headless）

仓库根目录的 [`.mcp.json`](../.mcp.json) 已经配好：

```json
{
  "mcpServers": {
    "blockbench-headless": {
      "command": "npx",
      "args": ["-y", "github:jasonjgardner/blockbench-mcp-plugin", "--root", "."]
    }
  }
}
```

支持 `.mcp.json` 的客户端（Claude Code / Codex / Cursor / VS Code / DSH…）打开仓库即生效。
手工注册：

```bash
claude mcp add bbmodel -- npx -y github:jasonjgardner/blockbench-mcp-plugin --root .
```

**为什么用 headless 而不是桌面插件**：headless 直接读写 `.bbmodel`，不需要开着 Blockbench，
而且每个 agent 起一个进程就能并行；桌面插件是单线程的，一次只能一个 agent，
还得有人把 Blockbench 打开。

没有 MCP 连接时（子 agent、shell 脚本、CI）可以单发调用：

```bash
bun run headless/call.ts --root . --list
bun run headless/call.ts --root . bbmodel_edit @ops.json
```

### 常用工具

| 工具 | 用途 |
|---|---|
| `bbmodel_create` | `format: "bedrock"` 建 Bedrock 模型 |
| `bbmodel_edit` | 原子批量改骨骼组 / 方块 / 逐面 UV / 贴图 / 动画关键帧 |
| `bbmodel_export_bedrock_geometry` | 编译成 `.geo.json`（和 Blockbench 导出器同一套约定） |
| `bbmodel_export_bedrock_animation` | Bedrock 动画 JSON（`1.8.0`） |
| `bbmodel_validate` | 悬空部件 / 穿模 / 退化块 / 左右不对称 |
| `bbmodel_render` / `bbmodel_contact_sheet` | 出 PNG（需 Node 23.6+ 和 WebGPU GPU） |
| `bbmodel_web_url` | 生成链接直接在 Blockbench 网页版打开看 |

### ⚠️ 两个会让审计直接 ERROR 的坑

`headless/formats/bedrock.ts` 里的版本判定：

```ts
if (faceRotation) return "1.21.0";        // 任何方块用了逐面 UV rotation
if (group.bedrock_binding) return "1.16.0"; // 任何骨骼组带 bedrock_binding
return "1.12.0";                           // ← 默认，我们要的就是这个
```

做时装时**别用逐面 UV rotation、别用 `bedrock_binding`**，否则导出会变成 1.16.0 / 1.21.0，
被 `audit_pack.py` 判为 ERROR（`SUPPORTED_GEO_FORMAT = "1.12.0"`）。

---

## 2. Agent Skills

`.claude/skills/` 里装了三个（Apache-2.0，来自 [blockbench-mcp-project](https://github.com/jasonjgardner/blockbench-mcp-project)）：

| Skill | 什么时候用 |
|---|---|
| `blockbench-use` | 编排器；碰 3D 场景先加载它，它会指到对应领域 skill |
| `blockbench-modeling` | 方块/网格建模、Bedrock 圆柱与管状构造、超大块限制 |
| `blockbench-texturing` | 画贴图、UV 映射、像素画、图层 |

`.claude/` 不进版本库（见 `.gitignore`），换机器重装：

```bash
npx skills add https://github.com/jasonjgardner/blockbench-mcp-project \
  -s blockbench-use -s blockbench-modeling -s blockbench-texturing \
  -a claude-code --copy -y
```

`skills-lock.json` 已入库，也可以用 `npx skills experimental_install` 一键还原。

想加更多（都不强制）：`blockbench-animation`（骨骼动画）、`blockbench-gpt-image-textures`
（AI 出图，见 §4）、`blockbench-vanilla-textures`（拉原版贴图）。

---

## 3. 交付闸门

**建模做完，跑这一个命令，全绿才算完：**

```bash
# 只审计
bash tools/dev/modeling-gate.sh src/main/resources/assets/vmfashion/default_pack

# 审计 + 出预览图
bash tools/dev/modeling-gate.sh <包目录> crown

# 审计 + 包里每件时装都出图 + UV 对位向导
bash tools/dev/modeling-gate.sh <包目录> '*' --uv
```

它做三件事：

1. **`audit_pack.py`（硬闸门）** —— `format_version`、贴图尺寸匹配、UV 越界、
   quad 数上限 20000、`attachedBone` 合法性、目录约定、符号链接……任何 ERROR 直接判失败。
2. **`tools/preview/render.sh`** —— 无头软光栅出 4 个视角 + 4 帧动画 + 缩略图，
   附带对称率 / UV 越界 / 全透明四边形 / 动画顶点位移自检指标。
3. **`tools/dev/uv_layout.py --uv`** —— UV 对位向导（见 §4）。

产物在 `build/preview/<id>/` 和 `build/uv/<id>/`。

> **为什么不直接信 MCP 的 `bbmodel_validate`**：它检查的是 Blockbench 的格式规则
> （悬空、穿模、退化块），不认识 VMFashion 的契约 —— 比如 `attachedBone` 必须是
> 原版骨骼名、`hideParts` 的取值、20000 quad 上限、emissive 语义。两者互补，不是替代。

---

## 4. 贴图：先用代码画

一张时装贴图是 64×64（或 256×256）的**方块展开图集** —— 一个 9×1×9 的方块会散成 6 个
互不相邻的矩形。这个布局用眼睛记不住，让 AI 直接生成更是一定错。

### 4.1 先拿到布局表

```bash
python3 tools/dev/uv_layout.py <时装目录> --out build/uv/crown
```

输出两个文件：

- **`uv_layout.json`** —— 机器可读的矩形表。每个 `(骨骼, 方块, 面)` 一行，
  带 `rect: [x, y, w, h]`。**用代码画贴图时直接读它**，不用再推一遍 UV 数学。
- **`uv_guide.png`** —— 对位向导图。原贴图压暗作底，每张脸盖半透明色块 + 描边，
  图例（颜色 → 骨骼/方块.面 → 矩形）打到 stdout。加 `--no-base` 出纯示意图，
  加 `--scale 10` 放大。

它会顺带检查：UV 越界（ERROR）、矩形部分重叠（WARN，通常是 UV 写错）、
完全共用（INFO，多件相同方块复用同一块贴图，合法）。

### 4.2 再写代码画

`tools/dev/pnglib.py` 是纯标准库的 PNG 读写（没有 Pillow 依赖）：

```python
import sys; sys.path.insert(0, "tools/dev")
from pnglib import read_png, write_png

w, h, rows = read_png("texture.png")     # 每行是 RGBA 的 bytearray
rows[y][x*4+3] = 0                        # 把 (x, y) 改成全透明
write_png("texture.png", rows, w)
```

参考实现：`tools/dev/gen_dragon_wings.py` —— 256×256 贴图 + 发光层 + 几何全部由
Python 算出（纯 `zlib` + `struct` 手写 PNG），**同一条命令重跑结果逐字节一致**，
可以进 CI 做回归。

### 4.3 真需要 AI 出图时

装 `blockbench-gpt-image-textures` skill。它把 `get_cube_uv` 的输出转成像素区域写进
prompt，调 fal.ai 上的 GPT Image，再把结果降采样回图集尺寸（自带
`uv_layout_prompt.mjs` / `resize_to_atlas.py`）。

注意它的局限（skill 自己写明的）：是 **prompt 约束不是遮罩**，会有 1–3 texel 的漂移和
溢出，区域超过 20 个效果明显下降。所以**出图后必须回到 `modeling-gate.sh` 验一遍**。

---

## 5. 端到端示例

```bash
# 1) 建模型（MCP）
bun run headless/call.ts --root . bbmodel_create '{"file":"models/crown.bbmodel","format":"bedrock"}'
bun run headless/call.ts --root . bbmodel_edit @ops.json

# 2) 导出到时装目录
bun run headless/call.ts --root . bbmodel_export_bedrock_geometry \
    '{"file":"models/crown.bbmodel","output":"src/.../cosmetics/crown/model.geo.json"}'

# 3) 看布局，写代码画贴图
python3 tools/dev/uv_layout.py src/.../cosmetics/crown --out build/uv/crown

# 4) 过闸门
bash tools/dev/modeling-gate.sh src/.../packs/mypack crown --uv
#   → ERROR 0 / WARN 0 / PASS，build/preview/crown/ 里有 9 张图
```

第 4 步的预览图是**给 AI 自己看的**：出图 → 读图 → 发现歪了/贴图错位 → 改模型 →
重新过闸门。这是让 agent 真正能自查的关键一环。

---

## 6. 参考

- [Blockbench MCP](https://github.com/jasonjgardner/blockbench-mcp-plugin)（GPL-3.0，**别把代码 vendor 进本仓库**）
- [Blockbench MCP Skills](https://github.com/jasonjgardner/blockbench-mcp-project)（Apache-2.0）
- [Bedrock 几何 1.12.0 规范](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/schemasreference/schemas/minecraftschema_geometry_1.12.0)
- [Bedrock 动画规范](https://bedrock.dev/docs/stable/Animations)（bedrock.dev 的 Models 页已下线，几何看上面 Microsoft Learn）
- 调研记录与实测：`research/ai-modeling-tooling.md`
