#!/usr/bin/env bash
# 校验 fabric.mod.json 的元数据是否满足 Fabric Loader 的要求 + 本项目的发布规范。
#
# 用法：bash tools/dev/check-mod-json.sh [jar 或 fabric.mod.json 路径]
#
# 为什么单独一个脚本：check-jar.sh 只查"文件在不在"，这里查"字段对不对"。
# 元数据写错的表现很隐蔽：Mod Menu 里显示不全、依赖检查不生效、发布后玩家装不上。
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)

TARGET=${1:-}
if [ -z "$TARGET" ]; then
    JAR=$(ls -t "$ROOT"/build/libs/*.jar 2>/dev/null | grep -v -- '-sources' | head -1)
    if [ -n "$JAR" ] && [ -f "$JAR" ]; then
        TARGET=$JAR
    else
        TARGET="$ROOT/src/main/resources/fabric.mod.json"
    fi
fi

if [ ! -f "$TARGET" ]; then
    echo "[mod-json] 找不到 $TARGET" >&2
    exit 1
fi

python3 - "$TARGET" <<'PY'
import json
import sys
import zipfile
from pathlib import Path

target = Path(sys.argv[1])
failures = []


def fail(message):
    failures.append(message)
    print(f"  [FAIL] {message}")


def ok(message):
    print(f"  [OK]   {message}")


if target.suffix == ".jar":
    with zipfile.ZipFile(target) as jar:
        names = set(jar.namelist())
        raw = jar.read("fabric.mod.json").decode("utf-8")
    source = f"{target.name}:fabric.mod.json"
else:
    names = None
    raw = target.read_text(encoding="utf-8")
    source = str(target)

try:
    data = json.loads(raw)
except Exception as exc:
    print(f"  [FAIL] {source} 不是合法 JSON: {exc}")
    sys.exit(1)
ok(f"{source} 是合法 JSON")

# ---- Fabric Loader 必需字段 ----
if data.get("schemaVersion") != 1:
    fail(f"schemaVersion 必须是 1，实际 {data.get('schemaVersion')!r}")
else:
    ok("schemaVersion = 1")

for key in ("id", "version", "name"):
    value = data.get(key)
    if not isinstance(value, str) or not value.strip():
        fail(f"缺少字符串字段 {key}")
    else:
        ok(f"{key} = {value}")

mod_id = data.get("id")
if isinstance(mod_id, str) and not mod_id.replace("_", "").replace("-", "").isalnum():
    fail(f"id 只能包含小写字母/数字/下划线/连字符: {mod_id}")
if isinstance(mod_id, str) and mod_id.lower() != mod_id:
    fail(f"id 必须是小写: {mod_id}")

version = data.get("version", "")
if isinstance(version, str):
    if "${" in version:
        fail(f"version 里的占位符没有被展开: {version}")
    elif not any(ch.isdigit() for ch in version):
        fail(f"version 看起来不是版本号: {version}")
    else:
        ok(f"version 已展开为 {version}")

# ---- 发布规范：这些字段缺了会让玩家侧体验残缺 ----
if data.get("environment") != "client":
    fail(f"这是纯客户端模组，environment 必须是 client，实际 {data.get('environment')!r}")
else:
    ok("environment = client")

if not data.get("license"):
    fail("缺少 license 字段")
else:
    ok(f"license = {data.get('license')}")

icon = data.get("icon")
if not icon:
    fail("缺少 icon 字段（Mod Menu / Modrinth 会显示空白）")
elif names is not None and icon not in names:
    fail(f"icon 指向的文件不在 jar 里: {icon}")
else:
    ok(f"icon = {icon}")

contact = data.get("contact") or {}
for key in ("homepage", "sources", "issues"):
    if not contact.get(key):
        fail(f"contact.{key} 缺失（发布时用来填 Modrinth 链接）")
if all(contact.get(k) for k in ("homepage", "sources", "issues")):
    ok("contact.homepage / sources / issues 齐全")

authors = data.get("authors") or []
if not authors:
    fail("缺少 authors")
else:
    ok(f"authors = {', '.join(authors)}")

# ---- entrypoints：类必须真的在 jar 里 ----
entrypoints = data.get("entrypoints") or {}
client_entries = entrypoints.get("client") or []
if not client_entries:
    fail("缺少 entrypoints.client")
for entry in client_entries:
    if names is not None:
        class_path = entry.replace(".", "/") + ".class"
        if class_path not in names:
            fail(f"entrypoint 类不在 jar 里: {entry}")
        else:
            ok(f"entrypoint 存在: {entry}")

# modmenu 是可选集成，只检查它有没有声明 suggests
if "modmenu" in entrypoints and "modmenu" not in (data.get("suggests") or {}):
    fail("声明了 modmenu entrypoint，但 depends/suggests 里没有 modmenu（应为 suggests）")

# ---- mixins：文件必须存在 ----
mixins = data.get("mixins") or []
for mixin in mixins:
    if names is not None and mixin not in names:
        fail(f"mixins 指向的文件不在 jar 里: {mixin}")
    else:
        ok(f"mixin 配置存在: {mixin}")

# ---- depends：必需的运行期依赖 ----
depends = data.get("depends") or {}
for required in ("fabricloader", "minecraft", "java"):
    if required not in depends:
        fail(f"depends 缺少 {required}")
for expected in ("fabric-api", "geckolib"):
    if expected not in depends:
        fail(f"depends 缺少 {expected}（缺了玩家装了也起不来，报错信息还很难懂）")
if all(k in depends for k in ("fabricloader", "minecraft", "java", "fabric-api", "geckolib")):
    ok("depends 覆盖 fabricloader / minecraft / java / fabric-api / geckolib")

if failures:
    print(f"[mod-json] {len(failures)} 项不通过")
    sys.exit(1)
print("[mod-json] 检查通过")
PY
