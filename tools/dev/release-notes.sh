#!/usr/bin/env bash
# 从 CHANGELOG.md 抽取某个版本的发布说明，写到 build/release-notes.md。
#
# 用法：bash tools/dev/release-notes.sh <version>   # 例如 0.2.0 或 v0.2.0
#
# 约定：CHANGELOG.md 用 Keep a Changelog 格式，版本标题形如 "## [0.2.0] - 2026-10-29"。
# 抽不到就退回一个最小说明（并给出提示），绝不因为文档格式问题让发布失败。
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
VERSION=${1:-}
OUT="$ROOT/build/release-notes.md"

if [ -z "$VERSION" ]; then
    echo "用法: $0 <version>" >&2
    exit 1
fi
# 容忍 v 前缀
NORMALIZED=${VERSION#v}

mkdir -p "$ROOT/build"

CHANGELOG="$ROOT/CHANGELOG.md"
if [ -f "$CHANGELOG" ]; then
    awk -v ver="$NORMALIZED" '
        BEGIN { capture = 0 }
        /^## \[/ {
            if (capture) { exit }
            if (index($0, "[" ver "]") > 0) { capture = 1 }
            next
        }
        capture { print }
    ' "$CHANGELOG" > "$OUT"
fi

if [ ! -s "$OUT" ]; then
    echo "警告: CHANGELOG.md 里没有 $NORMALIZED 的段落，使用兜底说明" >&2
    {
        echo "## VMFashion ${NORMALIZED}"
        echo
        echo "见仓库 README 与提交记录。"
    } > "$OUT"
fi

echo "release notes -> $OUT ($(wc -l < "$OUT") 行)"
