#!/usr/bin/env bash
# 快速编译检查（不启动 Gradle）。
#
# 为什么需要它：本机 cgroup 内存上限只有 1.2GB，Gradle daemon 一个就占 400MB，
# 多人/多 agent 并行时再各起一个 Gradle 会直接把机器压到 swap。这个脚本用
# javac + 预先算好的 classpath 做增量编译检查，并把 javac 串行化（flock），
# 保证同一时刻只有一个编译进程。
#
# 用法：
#   bash tools/dev/check.sh          # 编译 src/client/java
#   bash tools/dev/check.sh --cp     # 只重新生成 classpath 缓存
#
# 注意：这只是"能不能编译"的检查，正式构建仍用 ./gradlew build。
set -uo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/jdk25/current}
GRADLE_USER_HOME=${GRADLE_USER_HOME:-/tmp/gradle-home}
export JAVA_HOME GRADLE_USER_HOME
CP_FILE=${VMF_CP_FILE:-/tmp/vmf-cp.txt}
OUT=${VMF_CHECK_OUT:-/tmp/vmf-check-classes}
LOCK=${VMF_COMPILE_LOCK:-/tmp/vmf-compile.lock}

gen_cp() {
    cd "$ROOT" || exit 1
    "$ROOT/gradlew" -q -I tools/verify/print-classpath.gradle printClientCp --offline 2>/dev/null \
        | grep '^CLIENT_CP=' | sed 's/^CLIENT_CP=//' > "$CP_FILE"
    if [ ! -s "$CP_FILE" ]; then
        echo "[check] 生成 classpath 失败，试试先 ./gradlew build" >&2
        exit 1
    fi
    echo "[check] classpath -> $CP_FILE"
}

if [ "${1:-}" = "--cp" ] || [ ! -s "$CP_FILE" ]; then
    gen_cp
    [ "${1:-}" = "--cp" ] && exit 0
fi

mkdir -p "$OUT"
SOURCES=$(find "$ROOT/src/client/java" -name '*.java' | sort)
if [ -z "$SOURCES" ]; then
    echo "[check] 没找到源码" >&2
    exit 1
fi

# shellcheck disable=SC2086
flock "$LOCK" "$JAVA_HOME/bin/javac" -J-Xmx420m -encoding UTF-8 -nowarn -proc:none \
    -d "$OUT" -cp "$(cat "$CP_FILE")" $SOURCES
STATUS=$?
if [ $STATUS -eq 0 ]; then
    echo "[check] 编译通过（$(echo "$SOURCES" | wc -l) 个文件）"
else
    echo "[check] 编译失败，见上方 javac 输出" >&2
fi
exit $STATUS
