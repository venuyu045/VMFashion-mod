#!/usr/bin/env bash
# 校验语言文件：key 集合一致 + JSON 合法 + 没有空值。
#
# 用法：bash tools/dev/check-lang.sh
#
# 为什么需要：en_us / zh_cn 少一个 key，英文玩家就会看到 "vmfashion.gui.foo" 这种原始 key。
# 这个脚本在 CI 里跑，防止新增文案时漏翻译。
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LANG_DIR="$ROOT/src/main/resources/assets/vmfashion/lang"

if [ ! -d "$LANG_DIR" ]; then
    echo "[lang] 没有语言目录，跳过"
    exit 0
fi

python3 - "$LANG_DIR" <<'PY'
import json
import sys
from pathlib import Path

lang_dir = Path(sys.argv[1])
files = sorted(lang_dir.glob("*.json"))
if not files:
    print("[lang] 没有语言文件")
    sys.exit(0)

data = {}
failed = False
for path in files:
    try:
        with path.open(encoding="utf-8") as handle:
            parsed = json.load(handle)
    except Exception as exc:  # noqa: BLE001 - 只要报错就失败
        print(f"  [FAIL] {path.name} JSON 解析失败: {exc}")
        failed = True
        continue
    if not isinstance(parsed, dict):
        print(f"  [FAIL] {path.name} 顶层不是对象")
        failed = True
        continue
    empty = [key for key, value in parsed.items() if not str(value).strip()]
    if empty:
        print(f"  [FAIL] {path.name} 有空值: {', '.join(sorted(empty)[:5])}")
        failed = True
    data[path.stem] = parsed
    print(f"  [OK]   {path.name} ({len(parsed)} keys)")

if len(data) >= 2:
    base_name = "en_us" if "en_us" in data else sorted(data)[0]
    base = data[base_name]
    for name, table in data.items():
        if name == base_name:
            continue
        missing = sorted(set(base) - set(table))
        extra = sorted(set(table) - set(base))
        if missing:
            print(f"  [FAIL] {name}.json 缺少 {len(missing)} 个 key（以 {base_name} 为准）: "
                  f"{', '.join(missing[:5])}")
            failed = True
        if extra:
            print(f"  [WARN] {name}.json 多出 {len(extra)} 个 key: {', '.join(extra[:5])}")

if failed:
    print("[lang] 检查失败")
    sys.exit(1)
print("[lang] 检查通过")
PY
