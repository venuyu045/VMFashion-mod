#!/usr/bin/env bash
# 无图形环境下的渲染链路自检。
# 用法：先 ./gradlew build，再 bash tools/verify/run.sh
set -euo pipefail

MOD_DIR=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/jdk25/current}
GRADLE_USER_HOME=${GRADLE_USER_HOME:-/tmp/gradle-home}
export JAVA_HOME GRADLE_USER_HOME

cd "$MOD_DIR"

if [ ! -d "$MOD_DIR/build/classes/java/client" ]; then
  echo "先执行 ./gradlew build" >&2
  exit 1
fi

OFFLINE_FLAG=${VMF_OFFLINE_FLAG---offline}
CP=$("$MOD_DIR/gradlew" -q -I tools/verify/print-classpath.gradle printClientCp $OFFLINE_FLAG \
      | grep '^CLIENT_CP=' | sed 's/^CLIENT_CP=//')
CP="$CP:$MOD_DIR/build/classes/java/client"

OUT="$MOD_DIR/build/verify-classes"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$OUT" tools/verify/Verify.java tools/verify/VerifyMixins.java

echo "=== 1/2 渲染链路（烘焙 / 顶点 / attachedBone）==="
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$CP:$OUT" Verify \
  "$MOD_DIR/src/main/resources/assets/vmfashion/default_pack"

echo "=== 2/2 mixin 目标方法 ==="
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -cp "$CP:$OUT" VerifyMixins \
  "$MOD_DIR/build/classes/java/client" "$MOD_DIR/src/main/resources/vmfashion.mixins.json" "$CP"
