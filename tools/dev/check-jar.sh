#!/usr/bin/env bash
# 校验构建产物 jar 的结构（发布前必过）。
#
# 用法：bash tools/dev/check-jar.sh [jar 路径]
# 默认取 build/libs/ 下最新的主 jar（排除 -sources）。
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

if [ $# -ge 1 ]; then
    JAR=$1
else
    JAR=$(ls -t "$ROOT"/build/libs/*.jar 2>/dev/null | grep -v -- '-sources' | head -1)
fi

if [ -z "${JAR:-}" ] || [ ! -f "$JAR" ]; then
    echo "[jar] 找不到 jar，先跑 ./gradlew build" >&2
    exit 1
fi

echo "[jar] 检查 $JAR"
FAIL=0
LIST=$(unzip -l "$JAR")

require() {
    if printf '%s\n' "$LIST" | grep -q " $1\$"; then
        echo "  [OK]   $1"
    else
        echo "  [FAIL] 缺少 $1" >&2
        FAIL=1
    fi
}

# 元数据与资源：缺任何一个都会让玩家侧体验残缺（无图标 / 无语言 / 无示例包）
require "fabric.mod.json"
require "vmfashion.mixins.json"
require "assets/vmfashion/icon.png"
require "assets/vmfashion/lang/en_us.json"
require "assets/vmfashion/lang/zh_cn.json"
require "assets/vmfashion/default_pack/pack.json"
require "assets/vmfashion/default_pack/cosmetics/crown/model.geo.json"
require "assets/vmfashion/default_pack/cosmetics/crown/texture.png"
require "assets/vmfashion/default_pack/cosmetics/crown/anim.json"
require "assets/vmfashion/default_pack/cosmetics/backboard/anim.json"

# 版本占位符必须被 processResources 展开
if unzip -p "$JAR" fabric.mod.json | grep -q '\${version}'; then
    echo "  [FAIL] fabric.mod.json 里的 \${version} 没有被展开" >&2
    FAIL=1
else
    echo "  [OK]   fabric.mod.json 版本号已展开"
fi

# 主类必须真的在 jar 里
if printf '%s\n' "$LIST" | grep -q 'com/venus/vmfashion/VMFashionClient.class'; then
    echo "  [OK]   入口类存在"
else
    echo "  [FAIL] 入口类 com/venus/vmfashion/VMFashionClient.class 不存在" >&2
    FAIL=1
fi

# 依赖不能被打进 jar（GeckoLib / Fabric API 由玩家自己装）
if printf '%s\n' "$LIST" | grep -q 'META-INF/jars/'; then
    echo "  [WARN] jar 内嵌了依赖（META-INF/jars/），确认这是有意为之"
fi

if [ "$FAIL" -ne 0 ]; then
    echo "[jar] 结构检查失败" >&2
    exit 1
fi
echo "[jar] 结构检查通过"
