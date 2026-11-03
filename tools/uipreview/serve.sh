#!/usr/bin/env bash
# 可交互 UI 预览服务：在浏览器里"点"真实的 mod 界面（点击 / 滚动 / 打字都走生产代码）。
#
# 用法：
#   bash tools/uipreview/serve.sh                 # 默认 640x360 逻辑 ×2 倍，端口 8765
#   bash tools/uipreview/serve.sh --port=9000 --width=960 --height=540 --scale=1
#
# 换项目复用：见 tools/uipreview/PORTING.md。
set -euo pipefail

MOD_DIR=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/jdk25/current}
GRADLE_USER_HOME=${GRADLE_USER_HOME:-/tmp/gradle-home}
export JAVA_HOME GRADLE_USER_HOME

# ---- 唯一需要按项目改的地方 ----
ADAPTER_CLASS=${UIPREVIEW_ADAPTER:-com.venus.vmfashion.uipreview.VmfashionAdapter}

cd "$MOD_DIR"

OFFLINE_FLAG=${VMF_OFFLINE_FLAG---offline}
CP=$("$MOD_DIR/gradlew" -q -I tools/uipreview/print-classpath.gradle printClientCp $OFFLINE_FLAG \
      | grep '^CLIENT_CP=' | sed 's/^CLIENT_CP=//')

OUT="$MOD_DIR/build/uipreview/classes"
mkdir -p "$OUT"

APP_SOURCES=$(find tools/uipreview/app -name '*.java' | tr '\n' ' ')
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" \
  -sourcepath "tools/uipreview/stubs:tools/uipreview/fake:tools/uipreview/app:src/client/java:tools/uipreview/harness" \
  tools/uipreview/harness/uipreview/UiPreview.java \
  tools/uipreview/harness/uipreview/PreviewServer.java $APP_SOURCES

exec "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -Djava.awt.headless=true -Dvmf.modDir="$MOD_DIR" -Duipreview.adapter="$ADAPTER_CLASS" \
  -cp "$OUT:$CP" uipreview.PreviewServer "$@"
