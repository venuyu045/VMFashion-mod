#!/usr/bin/env bash
# UI 预览器：把 mod 的游戏内界面渲染成 PNG —— 无显卡 / 无客户端 / 无需启动游戏。
#
# 用法：
#   bash tools/uipreview/run.sh                        # 默认场景，640x360 逻辑 ×2 倍
#   bash tools/uipreview/run.sh --all --report         # 适配器声明的所有场景 + 布局自检
#   bash tools/uipreview/run.sh --scenario=hover_card
#   bash tools/uipreview/run.sh --width=960 --height=540 --scale=1 --locale=en_us
#
# 换项目复用：见 tools/uipreview/PORTING.md（改 ADAPTER_CLASS + 写一个适配器类）。
set -euo pipefail

MOD_DIR=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/jdk25/current}
GRADLE_USER_HOME=${GRADLE_USER_HOME:-/tmp/gradle-home}
export JAVA_HOME GRADLE_USER_HOME

# ---- 唯一需要按项目改的地方 ----
# 项目适配器（实现 uipreview.UiPreviewAdapter 的类，放在 tools/uipreview/app/ 下）
ADAPTER_CLASS=${UIPREVIEW_ADAPTER:-com.venus.vmfashion.uipreview.VmfashionAdapter}

cd "$MOD_DIR"

OFFLINE_FLAG=${VMF_OFFLINE_FLAG---offline}
CP=$("$MOD_DIR/gradlew" -q -I tools/uipreview/print-classpath.gradle printClientCp $OFFLINE_FLAG \
      | grep '^CLIENT_CP=' | sed 's/^CLIENT_CP=//')

OUT="$MOD_DIR/build/uipreview/classes"
mkdir -p "$OUT"

# sourcepath 顺序决定"同名类用谁"：
#   stubs/   : net.minecraft.* 的替身（必须最先，否则会编到真 MC 类，构造函数要 GL 上下文）
#   fake/    : 项目里碰了 MC/Fabric 的业务类的替身
#   app/     : 项目适配器（唯一知道项目类名的地方）
#   src/     : 其余全部是生产代码，原样编译
#   harness/ : 预览器内核
APP_SOURCES=$(find tools/uipreview/app -name '*.java' | tr '\n' ' ')
"$JAVA_HOME/bin/javac" -encoding UTF-8 -nowarn -d "$OUT" -cp "$CP" \
  -sourcepath "tools/uipreview/stubs:tools/uipreview/fake:tools/uipreview/app:src/client/java:tools/uipreview/harness" \
  tools/uipreview/harness/uipreview/UiPreview.java $APP_SOURCES

# 运行时 classpath 必须把预览产物放最前：否则会加载到 client jar 里真实的
# Minecraft/Screen（构造函数要 OpenGL 上下文，直接崩）
exec "$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
  -Djava.awt.headless=true -Dvmf.modDir="$MOD_DIR" -Duipreview.adapter="$ADAPTER_CLASS" \
  -cp "$OUT:$CP" uipreview.UiPreview "$@"
