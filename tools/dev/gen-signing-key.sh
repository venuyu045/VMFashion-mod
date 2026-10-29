#!/usr/bin/env bash
# 生成时装包签名用的 Ed25519 密钥对（纯 JDK，无第三方依赖）。
#
# 用法：
#   bash tools/dev/gen-signing-key.sh            # 输出到 build/signing/（build/ 已 gitignore）
#   bash tools/dev/gen-signing-key.sh <目录>
#
# 产出：
#   ed25519-public-raw.b64   裸 32 字节公钥（填进 config/vmfashion/trusted_keys.json 或内置常量）
#   ed25519-public-spki.b64  X.509 SPKI 公钥（另一种写法）
#   ed25519-private-pkcs8.b64  PKCS#8 私钥 —— **只能放后端**，泄露等于谁都能签发时装包
#
# 安全提醒：
#   1. 私钥不要进 git、不要贴聊天记录、不要放进客户端 jar；
#   2. 后端应该把私钥放 KMS / 环境变量，签名在服务端完成；
#   3. 怀疑泄露就重新生成一对，并把新公钥下发给客户端（trusted_keys.json 支持多把密钥，
#      旧客户端仍认旧公钥 → 轮换要保留旧公钥一段时间）。
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
JAVA_HOME=${JAVA_HOME:-/opt/jdk25/current}
OUT=${1:-"$ROOT/build/signing"}

mkdir -p "$OUT"
# 单文件源码执行（JEP 330）会在源文件所在目录里按"未命名包"扫描，
# 仓库根目录有中文文件名（审查报告.md / 开发路线图.md），locale 不是 UTF-8 时会报
# "unmappable characters" —— 所以这里强制 UTF-8 的 locale 与 jnu 编码
export LANG=${LANG:-C.UTF-8}
export LC_ALL=${LC_ALL:-C.UTF-8}
"$JAVA_HOME/bin/java" -Dsun.jnu.encoding=UTF-8 -Dfile.encoding=UTF-8 \
    "$ROOT/tools/dev/GenSigningKey.java" "$OUT"

cat <<EOF

已写入 $OUT
  ed25519-public-raw.b64      裸 32 字节公钥（填 trusted_keys.json 的 publicKey 字段）
  ed25519-public-spki.b64     SPKI 公钥（二选一）
  ed25519-private-pkcs8.b64   PKCS#8 私钥 —— 移到后端密钥存储后立刻删掉本地副本

下一步：
  1) 后端用私钥按 docs/fashion-api.md §6 的规范化字节串给每个包签名；
  2) 客户端在 config/vmfashion/trusted_keys.json 里配置公钥；
  3) 想强制校验就同时设置 "requireSignature": true（未签名的包会被拒绝）。
EOF
