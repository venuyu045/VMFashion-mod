#!/usr/bin/env bash
# 无头时装预览渲染器：把时装包里的 Bedrock 模型渲染成 PNG 效果图（无显卡 / 无客户端）。
#
# 用法：bash tools/preview/render.sh <包目录> <cosmeticId> <输出目录> [额外参数...]
# 例子：bash tools/preview/render.sh src/main/resources/assets/vmfashion/default_pack crown build/preview/crown
#
# 额外参数（都可选）：
#   --ss=2              超采样倍数（1..4，默认 2，越大越抗锯齿但越慢）
#   --size=1280x960     视角图尺寸
#   --thumb=512         缩略图边长（1:1）
#   --fov=32            竖直视场角
#   --no-label          不画左下角标注
#   --no-silhouette     不画原版玩家比例参考剪影
#
# 类路径取法与 tools/verify/run.sh 完全一致（同一套 gradle 任务 + 同一个 client 类路径），
# 因此前提也一样：先 ./gradlew build（build/classes/java/client 已存在就够，不必每次重建）。
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

OUT="$MOD_DIR/build/preview/classes"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" -encoding UTF-8 -cp "$CP" -d "$OUT" \
  tools/preview/PreviewRender.java tools/preview/CosmeticSource.java \
  tools/preview/QuadRecorder.java tools/preview/SoftRasterizer.java \
  tools/preview/Bounds.java tools/preview/SelfCheck.java

# java.awt.headless=true：这台机器没有 X server，AWT 只用 BufferedImage / ImageIO / 字体
exec "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -Djava.awt.headless=true -cp "$CP:$OUT" PreviewRender "$@"
