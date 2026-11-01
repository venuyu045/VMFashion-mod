#!/usr/bin/env bash
# 时装交付闸门：建模完之后跑这一个脚本，全绿才算做完。
#
# 为什么需要它
# ------------
# AI（或人）用 Blockbench MCP 生成 `.geo.json` 之后，"看起来对"和"能进游戏"是两回事：
# GeckoLib **不校验**几何（版本不认识只打 warn，UV 坏了照样渲染），所以 MCP 的
# `bbmodel_validate` 通过 ≠ 这件时装能进 VMFashion。真正的契约在
# `tools/verify/audit_pack.py` 里：format_version 必须是 1.12.0、贴图尺寸要和
# texture_width/height 一致、UV 不能越界、quad 数不能超 20000、attachedBone 得是
# 原版骨骼名……这些 MCP 一概不认。
#
# 所以这个脚本的定位是：**MCP 负责"做"，audit_pack.py 负责"判"，它负责串起来并卡住。**
#
# 用法
# ----
#   bash tools/dev/modeling-gate.sh <包目录>                    # 只审计
#   bash tools/dev/modeling-gate.sh <包目录> <cosmeticId>       # 审计 + 出预览图
#   bash tools/dev/modeling-gate.sh <包目录> '*'                # 审计 + 给每件时装都出预览图
#   bash tools/dev/modeling-gate.sh <包目录> <id> --uv          # 额外产出 UV 对位向导图
#
# 产物：
#   build/preview/<cosmeticId>/   视角图 + 缩略图 + anim-*.png
#   build/uv/<cosmeticId>/        uv_layout.json + uv_guide.png（加 --uv 时）
#
# 退出码：0 = 全绿；1 = 审计或渲染失败；2 = 用法 / 环境错误。
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT" || exit 2

UV=0
ARGS=()
for a in "$@"; do
    case "$a" in
        --uv) UV=1 ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) ARGS+=("$a") ;;
    esac
done

if [ ${#ARGS[@]} -lt 1 ]; then
    echo "用法：bash tools/dev/modeling-gate.sh <包目录> [cosmeticId|'*'] [--uv]" >&2
    exit 2
fi

PACK=${ARGS[0]}
COSMETIC=${ARGS[1]:-}

if [ ! -d "$PACK" ]; then
    echo "错误：包目录不存在：$PACK" >&2
    exit 2
fi

FAIL=0

# ── 1. 契约审计（硬闸门）────────────────────────────────────────────────
echo "═══ [1/2] 契约审计 ═══"
if ! python3 tools/verify/audit_pack.py "$PACK"; then
    echo
    echo "✗ 审计未通过 —— 先修 ERROR 再谈渲染。" >&2
    FAIL=1
fi

# ── 2. 无头预览（有 cosmeticId 才跑）────────────────────────────────────
if [ -n "$COSMETIC" ]; then
    echo
    echo "═══ [2/2] 无头预览 ═══"

    # '*' = 把包里每件时装都渲染一遍
    if [ "$COSMETIC" = "*" ]; then
        mapfile -t IDS < <(
            python3 - "$PACK" <<'PY'
import json, sys
from pathlib import Path
pack = Path(sys.argv[1])
manifest = pack / "pack.json"
ids = []
if manifest.is_file():
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        ids = [c["id"] for c in data.get("cosmetics", []) if isinstance(c, dict) and "id" in c]
    except (json.JSONDecodeError, OSError):
        ids = []
if not ids:  # pack.json 没声明就扫目录
    cosdir = pack / "cosmetics"
    if cosdir.is_dir():
        ids = sorted(p.name for p in cosdir.iterdir() if p.is_dir())
print("\n".join(ids))
PY
        )
        if [ ${#IDS[@]} -eq 0 ]; then
            echo "WARN  包里没找到任何时装" >&2
        fi
    else
        IDS=("$COSMETIC")
    fi

    for id in "${IDS[@]}"; do
        echo "--- $id ---"
        if ! bash tools/preview/render.sh "$PACK" "$id" "build/preview/$id"; then
            echo "✗ 渲染失败：$id" >&2
            FAIL=1
        fi
    done
else
    echo
    echo "（没给 cosmeticId，跳过预览。想看图：bash tools/dev/modeling-gate.sh $PACK <id>）"
fi

# ── 3. UV 对位向导（可选）──────────────────────────────────────────────
if [ "$UV" -eq 1 ]; then
    echo
    echo "═══ [附加] UV 对位向导 ═══"
    for dir in "$PACK"/cosmetics/*/; do
        [ -f "$dir/model.geo.json" ] || continue
        name=$(basename "$dir")
        echo "--- $name ---"
        python3 tools/dev/uv_layout.py "$dir" --out "build/uv/$name" | tail -6
    done
fi

echo
if [ "$FAIL" -eq 0 ]; then
    echo "════════ 全绿：这件时装可以交付 ════════"
else
    echo "════════ 有失败项，见上方 ✗ ════════" >&2
fi
exit "$FAIL"
