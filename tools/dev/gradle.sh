#!/usr/bin/env bash
# 串行化执行 Gradle 任务（本机 cgroup 内存上限 1.2GB，多个 Gradle 并行会 OOM）。
#
# 用法：
#   bash tools/dev/gradle.sh build --offline
#   bash tools/dev/gradle.sh test --offline
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export JAVA_HOME=${JAVA_HOME:-/opt/jdk25/current}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-/tmp/gradle-home}
cd "$ROOT" || exit 1
exec flock /tmp/vmf-gradle.lock ./gradlew "$@"
