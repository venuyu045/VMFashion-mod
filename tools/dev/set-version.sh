#!/usr/bin/env bash
# 统一改版本号（gradle.properties 里的 mod_version）。
#
# 用法：bash tools/dev/set-version.sh 0.2.0
#
# 说明：fabric.mod.json 用的是 "${version}" 占位符，由 processResources 展开，
# 所以只需要改 gradle.properties 一处；README 徽章与 CHANGELOG 由发布流程手工核对。
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
VERSION=${1:-}

if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$'; then
    echo "用法: $0 <x.y.z>（可带 -pre 后缀）" >&2
    exit 1
fi

sed -i "s/^mod_version=.*/mod_version=$VERSION/" "$ROOT/gradle.properties"
grep '^mod_version=' "$ROOT/gradle.properties"
echo "记得同步：README 版本徽章、CHANGELOG.md 段落标题、Release notes"
