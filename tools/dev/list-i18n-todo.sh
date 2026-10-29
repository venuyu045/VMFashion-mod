#!/usr/bin/env bash
# 列出"玩家可见但硬编码中文"的位置，给 i18n 迁移当待办清单。
#
# 用法：bash tools/dev/list-i18n-todo.sh [--count]
#
# 只统计玩家能看到的调用（聊天栏 / Toast / 屏幕标题），不含日志和注释。
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$ROOT" || exit 1

PATTERN='showLocalMessage\(|showActionBar|showSuccess\(|showError\(|showInfo\(|showWarning\(|Component\.literal\("'

# shellcheck disable=SC2046
HITS=$(grep -rnE "$PATTERN" src/client/java --include='*.java' \
    | grep -E '"[^"]*[一-龥][^"]*"' || true)

if [ "${1:-}" = "--count" ]; then
    printf '%s\n' "$HITS" | grep -c . || true
    exit 0
fi

if [ -z "$HITS" ]; then
    echo "[i18n] 没有发现硬编码中文的玩家可见文案"
    exit 0
fi

printf '%s\n' "$HITS" | sed 's|^src/client/java/com/venus/vmfashion/||'
echo
printf '共 %s 处待迁移\n' "$(printf '%s\n' "$HITS" | grep -c .)"
