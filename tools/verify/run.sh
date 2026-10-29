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
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$OUT" \
  tools/verify/Verify.java tools/verify/VerifyMixins.java \
  tools/verify/VerifyScale.java tools/verify/VerifyEmissive.java

echo "=== 1/4 渲染链路（烘焙 / 顶点 / attachedBone）==="
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP:$OUT" Verify \
  "$MOD_DIR/src/main/resources/assets/vmfashion/default_pack"

echo "=== 2/4 mixin 目标方法 ==="
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP:$OUT" VerifyMixins \
  "$MOD_DIR/build/classes/java/client" "$MOD_DIR/src/main/resources/vmfashion.mixins.json" "$CP"

# 3/4：缩放必须绕 attachedBone 的 pivot，而不是绕脚底（路线图 §10 #12 的回归测试）
echo "=== 3/4 缩放原点（绕 pivot，不绕脚底）==="
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP:$OUT" VerifyScale

# 4/4：emissive 只让"贴图上确实不透明"的四边形发光（§3.3 的回归测试）
echo "=== 4/4 emissive 遮罩（逐 quad 过滤）==="
"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$CP:$OUT" VerifyEmissive
