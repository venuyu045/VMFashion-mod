#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 ``model.geo.json`` 反解成「贴图上每张脸占哪块矩形」，并画一张对位向导图。

为什么需要它
------------
给时装画贴图时，真正的难点不是画，而是 **画对格子**：一个 9×1×9 的方块展开在 64×64
的图集上会散成 6 个互不相邻的矩形（见下），哪个矩形是「上面」、哪个是「背面」，
光看代码很难记住，让 AI 直接生成贴图更是必错。

本脚本做两件事：

1. 输出 ``uv_layout.json`` —— **机器可读**的矩形表。每个 (骨骼, 方块, 面) 一行，
   带 ``rect: [x, y, w, h]``。想用代码生成贴图时直接读它，不用再推一遍 UV 数学。
2. 输出 ``uv_guide.png`` —— **人/AI 可看**的对位向导图。原贴图压暗作底，每张脸盖上
   半透明色块并描边，图例（颜色 → 骨骼/方块/面/矩形）打到 stdout。放大后能一眼看出
   「这块红色是皇冠的顶面」。

盒子 UV 的排布（Minecraft / Blockbench 通用，尺寸 w×h×d，起点 [u, v]）::

        +-------+-------+             up    (u+d,     v,     w, d)
        |  up   | down  |             down  (u+d+w,   v,     w, d)
        +---+---+---+---+             east  (u,       v+d,   d, h)
        | e | n | w | s |             north (u+d,     v+d,   w, h)
        +---+---+---+---+             west  (u+d+w,   v+d,   d, h)
                                      south (u+d+w+d, v+d,   w, h)

用法::

    # 最省事：直接给时装目录（自动找 model.geo.json 和 texture.png）
    python3 tools/dev/uv_layout.py src/main/resources/assets/vmfashion/official_pack/cosmetics/dragon_wings

    # 或显式指定
    python3 tools/dev/uv_layout.py path/to/model.geo.json --texture path/to/texture.png \\
        --out build/uv/crown --scale 8

退出码：0 = 无问题（可能带 WARN）；1 = 有越界 / 重叠等 ERROR；2 = 用法或 IO 错误。

不依赖 Pillow，PNG 读写走同目录的 ``pnglib``（纯标准库）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from pnglib import PngError, read_png, write_png  # noqa: E402

#: 6 个面，顺序固定（图例、颜色、JSON 都按这个顺序，便于 diff）
FACES = ("up", "down", "east", "north", "west", "south")

#: 每张脸在向导图里的亮度系数。6 张脸取 6 个互不相同的档位，
#: 这样同一个方块内部也能靠明暗分辨「哪个是顶、哪个是背」。
FACE_SHADE = {"up": 1.00, "north": 0.88, "east": 0.76, "west": 0.64, "south": 0.52, "down": 0.40}


def hsv_to_rgb(h: float, s: float, v: float) -> tuple[int, int, int]:
    """h ∈ [0,1)，s/v ∈ [0,1] → (r, g, b) 0..255。"""
    i = int(h * 6.0) % 6
    f = h * 6.0 - int(h * 6.0)
    p, q, t = v * (1 - s), v * (1 - f * s), v * (1 - (1 - f) * s)
    r, g, b = [
        (v, t, p),
        (q, v, p),
        (p, v, t),
        (p, q, v),
        (t, p, v),
        (v, p, q),
    ][i]
    return int(r * 255), int(g * 255), int(b * 255)


def box_uv_rects(offset: list[float], size: list[float]) -> dict[str, tuple[int, int, int, int]]:
    """盒子 UV → 6 个面的矩形。坐标一律取整（像素画贴图不允许半格）。"""
    u, v = int(round(offset[0])), int(round(offset[1]))
    w, h, d = (int(round(n)) for n in size)
    return {
        "up": (u + d, v, w, d),
        "down": (u + d + w, v, w, d),
        "east": (u, v + d, d, h),
        "north": (u + d, v + d, w, h),
        "west": (u + d + w, v + d, d, h),
        "south": (u + d + w + d, v + d, w, h),
    }


def per_face_rects(uv_obj: dict, size: list[float]) -> dict[str, tuple[int, int, int, int]]:
    """逐面 UV → 6 个面的矩形。``uv_size`` 缺省时按面的物理尺寸推断。"""
    w, h, d = (int(round(n)) for n in size)
    # 缺省 uv_size：按该面在世界里的宽高（Bedrock 的约定）
    default_size = {
        "up": (w, d),
        "down": (w, d),
        "east": (d, h),
        "north": (w, h),
        "west": (d, h),
        "south": (w, h),
    }
    out: dict[str, tuple[int, int, int, int]] = {}
    for face in FACES:
        entry = uv_obj.get(face)
        if entry is None:
            continue
        if isinstance(entry, dict):
            uv = entry.get("uv")
            uv_size = entry.get("uv_size") or default_size[face]
            if uv is None:
                continue
            u, v = int(round(uv[0])), int(round(uv[1]))
            rw, rh = int(round(uv_size[0])), int(round(uv_size[1]))
        else:  # 允许直接写成 [u, v]
            u, v = int(round(entry[0])), int(round(entry[1]))
            rw, rh = default_size[face]
        # 负尺寸 = 镜像贴图；这里保留绝对值，镜像状态单独记录
        out[face] = (u, v, abs(rw), abs(rh))
    return out


def collect_regions(geo: dict, warnings: list[str]) -> list[dict]:
    """遍历 geometry 里的骨骼与方块，收集所有 (骨骼, 方块, 面) → 矩形。"""
    regions: list[dict] = []
    geometry = geo.get("minecraft:geometry")
    if not isinstance(geometry, list) or not geometry:
        raise ValueError("缺少 minecraft:geometry 数组")

    for bone in geometry[0].get("bones", []):
        bone_name = bone.get("name", "?")
        # 方块编号按"骨骼内第几个 cube"算，不能按区域数算 —— 否则同一个方块的 6 张脸
        # 会被编成 6 个不同的方块名，图例就没法读了。
        for cube_index, cube in enumerate(bone.get("cubes", [])):
            size = cube.get("size", [0, 0, 0])
            uv = cube.get("uv")
            cube_name = cube.get("name") or f"cube#{cube_index}"
            if uv is None:
                warnings.append(f"{bone_name}/{cube_name}: 方块没有 uv，跳过")
                continue
            if isinstance(uv, list):
                rects = box_uv_rects(uv, size)
                mode = "box"
            else:
                rects = per_face_rects(uv, size)
                mode = "per-face"
                if cube.get("mirror"):
                    warnings.append(f"{bone_name}/{cube_name}: 方块带 mirror=true，逐面 UV 可能左右反了")
            for face, rect in rects.items():
                regions.append(
                    {
                        "bone": bone_name,
                        "cube": cube_name,
                        "face": face,
                        "rect": list(rect),
                        "mode": mode,
                    }
                )
    return regions


def find_problems(
    regions: list[dict], tex_w: int, tex_h: int
) -> tuple[list[str], list[str], list[str]]:
    """返回 (越界列表, 完全共用列表, 部分重叠列表)。

    「完全共用」= 多个面用**同一个矩形**（同一块贴图复用给多个方块，是常见且合法的做法，
    比如四根一模一样的尖刺）；「部分重叠」= 矩形互相压了一半，基本一定是 UV 写错。
    """
    out_of_bounds: list[str] = []
    exact: dict[tuple[int, int, int, int], list[str]] = {}
    boxes: list[tuple[tuple[int, int, int, int], str]] = []

    for r in regions:
        x, y, w, h = r["rect"]
        tag = f"{r['bone']}/{r['cube']}.{r['face']}"
        if w <= 0 or h <= 0:
            out_of_bounds.append(f"{tag}: 尺寸为 0（{w}x{h}）")
        if x < 0 or y < 0 or x + w > tex_w or y + h > tex_h:
            out_of_bounds.append(f"{tag}: [{x}, {y}, {w}, {h}] 超出 {tex_w}x{tex_h} 贴图")
        exact.setdefault((x, y, w, h), []).append(tag)
        boxes.append(((x, y, w, h), tag))

    shared = [
        f"矩形 {list(key)} 被 {len(tags)} 张脸共用：{', '.join(tags)}"
        for key, tags in exact.items()
        if len(tags) > 1
    ]

    # 部分重叠：矩形不同但相交，且不是包含关系（包含往往是故意的"整块底图"）
    partial: list[str] = []
    for i in range(len(boxes)):
        (ax, ay, aw, ah), atag = boxes[i]
        for j in range(i + 1, len(boxes)):
            (bx, by, bw, bh), btag = boxes[j]
            if (ax, ay, aw, ah) == (bx, by, bw, bh):
                continue
            ix = min(ax + aw, bx + bw) - max(ax, bx)
            iy = min(ay + ah, by + bh) - max(ay, by)
            if ix <= 0 or iy <= 0:
                continue
            # 完全包含（一个矩形整块盖住另一个）通常是底图复用，不报
            a_covers_b = ax <= bx and ay <= by and ax + aw >= bx + bw and ay + ah >= by + bh
            b_covers_a = bx <= ax and by <= ay and bx + bw >= ax + aw and by + bh >= ay + ah
            if a_covers_b or b_covers_a:
                continue
            partial.append(
                f"{atag} 与 {btag} 部分重叠（相交 {ix}x{iy}）"
                " —— 故意的共用可忽略，否则多半是 uv 起点写错了"
            )

    return out_of_bounds, shared, partial


def render_guide(
    regions: list[dict],
    tex_w: int,
    tex_h: int,
    scale: int,
    base_rows: list[bytearray] | None,
    dim: float,
) -> list[bytearray]:
    """画向导图：原贴图压暗作底 + 每张脸半透明色块 + 描边 + 8 texel 网格。"""
    cw, ch = tex_w * scale, tex_h * scale
    rows = [bytearray(cw * 4) for _ in range(ch)]

    # 1) 底色：有贴图就压暗铺上，没有就深灰棋盘
    for y in range(ch):
        row = rows[y]
        for x in range(cw):
            if base_rows is not None:
                sy, sx = y // scale, x // scale
                src = base_rows[sy]
                i = sx * 4
                r, g, b, a = src[i], src[i + 1], src[i + 2], src[i + 3]
                # 透明像素铺棋盘格，方便看出哪里还没画
                if a < 128:
                    cell = ((sx // 8) + (sy // 8)) & 1
                    r = g = b = 60 if cell else 44
                    a = 255
                else:
                    r, g, b = int(r * dim), int(g * dim), int(b * dim)
            else:
                cell = ((x // (8 * scale)) + (y // (8 * scale))) & 1
                r = g = b = 58 if cell else 42
                a = 255
            i = x * 4
            row[i : i + 4] = bytes((r, g, b, a))

    # 2) 8 texel 网格线（画在色块**下面**，否则会横穿色块干扰读数）
    for tx in range(0, tex_w, 8):
        _vline(rows, cw, ch, tx * scale, (72, 72, 80))
    for ty in range(0, tex_h, 8):
        _hline(rows, cw, ch, ty * scale, (72, 72, 80))

    # 3) 每个面盖半透明色 + 描边
    #    描边画两遍：先一圈暗色打底（压住底图，保证任何底色上都看得见），再一圈亮色。
    for r in regions:
        x, y, w, h = r["rect"]
        tint = hsv_to_rgb(r["hue"], 0.75, FACE_SHADE.get(r["face"], 1.0))
        edge = hsv_to_rgb(r["hue"], 0.95, 1.0)
        for py in range(y * scale, (y + h) * scale):
            if not (0 <= py < ch):
                continue
            row = rows[py]
            for px in range(x * scale, (x + w) * scale):
                if not (0 <= px < cw):
                    continue
                i = px * 4
                # 55% 混合：色块归属一眼可见，同时还能透出底图的笔触
                row[i] = (row[i] * 45 + tint[0] * 55) // 100
                row[i + 1] = (row[i + 1] * 45 + tint[1] * 55) // 100
                row[i + 2] = (row[i + 2] * 45 + tint[2] * 55) // 100
        px0, py0, px1, py1 = x * scale, y * scale, (x + w) * scale - 1, (y + h) * scale - 1
        _stroke(rows, cw, ch, px0, py0, px1, py1, (18, 18, 22))
        _stroke(rows, cw, ch, px0 + 1, py0 + 1, px1 - 1, py1 - 1, edge)

    return rows


def _put(rows: list[bytearray], cw: int, ch: int, x: int, y: int, rgb: tuple[int, int, int]) -> None:
    if 0 <= x < cw and 0 <= y < ch:
        i = x * 4
        rows[y][i : i + 3] = bytes(rgb)


def _stroke(rows, cw, ch, x0, y0, x1, y1, rgb) -> None:
    """按对角坐标画 1px 矩形边框（闭区间）。"""
    if x1 < x0 or y1 < y0:
        return
    for px in range(x0, x1 + 1):
        _put(rows, cw, ch, px, y0, rgb)
        _put(rows, cw, ch, px, y1, rgb)
    for py in range(y0, y1 + 1):
        _put(rows, cw, ch, x0, py, rgb)
        _put(rows, cw, ch, x1, py, rgb)


def _vline(rows, cw, ch, x, rgb) -> None:
    for py in range(ch):
        _put(rows, cw, ch, x, py, rgb)


def _hline(rows, cw, ch, y, rgb) -> None:
    for px in range(cw):
        _put(rows, cw, ch, px, y, rgb)


def resolve_inputs(target: Path) -> tuple[Path, Path | None]:
    """接受「时装目录」或「geo.json 路径」，返回 (模型路径, 贴图路径或 None)。"""
    if target.is_dir():
        models = sorted(target.glob("*.geo.json"))
        if not models:
            raise SystemExit(f"错误：{target} 里没有 *.geo.json")
        textures = [p for p in sorted(target.glob("*.png")) if p.name != "emissive.png"]
        return models[0], (textures[0] if textures else None)
    if not target.is_file():
        raise SystemExit(f"错误：{target} 不存在")
    return target, None


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(
        description="从 model.geo.json 反解 UV 矩形表，并画对位向导图",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    ap.add_argument("target", help="时装目录，或 model.geo.json 路径")
    ap.add_argument("--texture", help="主贴图 PNG（默认取目录里的第一个非 emissive PNG）")
    ap.add_argument("--out", help="输出目录（默认 <模型目录>/uv/）")
    ap.add_argument("--scale", type=int, default=8, help="向导图放大倍数（默认 8）")
    ap.add_argument("--dim", type=float, default=0.35, help="底图压暗系数（默认 0.35）")
    ap.add_argument(
        "--no-base",
        action="store_true",
        help="不铺原贴图，只画纯示意图（看布局更清楚）",
    )
    ap.add_argument("--json", action="store_true", help="只输出 JSON 到 stdout，不写文件")
    args = ap.parse_args(argv)

    model_path, auto_tex = resolve_inputs(Path(args.target))
    tex_path = Path(args.texture) if args.texture else auto_tex

    try:
        geo = json.loads(model_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        print(f"错误：{model_path} 不是合法 JSON：{exc}", file=sys.stderr)
        return 2

    geometry = geo.get("minecraft:geometry")
    if not isinstance(geometry, list) or not geometry:
        print("错误：缺少 minecraft:geometry 数组", file=sys.stderr)
        return 2
    desc = geometry[0].get("description", {})
    tex_w = int(desc.get("texture_width", 0))
    tex_h = int(desc.get("texture_height", 0))
    if tex_w <= 0 or tex_h <= 0:
        print("错误：description 里缺少 texture_width / texture_height", file=sys.stderr)
        return 2

    warnings: list[str] = []
    regions = collect_regions(geo, warnings)
    if not regions:
        print("错误：模型里没有任何带 uv 的方块", file=sys.stderr)
        return 2

    # 按方块分配色相（黄金角步进，相邻方块颜色差异大）
    cubes: list[str] = []
    for r in regions:
        key = f"{r['bone']}/{r['cube']}"
        if key not in cubes:
            cubes.append(key)
    for r in regions:
        key = f"{r['bone']}/{r['cube']}"
        idx = cubes.index(key)
        r["hue"] = (idx * 0.618033988749895) % 1.0
        r["color"] = "#%02x%02x%02x" % hsv_to_rgb(r["hue"], 0.62, FACE_SHADE.get(r["face"], 1.0))

    oob, shared, partial = find_problems(regions, tex_w, tex_h)
    extent_w = max(r["rect"][0] + r["rect"][2] for r in regions)
    extent_h = max(r["rect"][1] + r["rect"][3] for r in regions)

    layout = {
        "model": str(model_path),
        "identifier": desc.get("identifier", ""),
        "texture": str(tex_path) if tex_path else None,
        "texture_size": [tex_w, tex_h],
        "used_extent": [extent_w, extent_h],
        "regions": [
            {
                "bone": r["bone"],
                "cube": r["cube"],
                "face": r["face"],
                "rect": r["rect"],
                "mode": r["mode"],
                "color": r["color"],
            }
            for r in regions
        ],
        "warnings": warnings,
        "out_of_bounds": oob,
        "shared_regions": shared,
        "partial_overlaps": partial,
    }

    if args.json:
        print(json.dumps(layout, ensure_ascii=False, indent=2))
        return 0 if not oob else 1

    out_dir = Path(args.out) if args.out else model_path.parent / "uv"
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "uv_layout.json"
    json_path.write_text(json.dumps(layout, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 底图：贴图尺寸必须和 description 声明一致，否则不铺底（避免误导）
    base_rows = None
    if args.no_base:
        pass
    elif tex_path and tex_path.exists():
        try:
            tw, th, base_rows = read_png(tex_path)
            if (tw, th) != (tex_w, tex_h):
                warnings.append(
                    f"贴图实际 {tw}x{th} 与 description 声明的 {tex_w}x{tex_h} 不一致，不铺底图"
                )
                base_rows = None
        except PngError as exc:
            warnings.append(f"读不了贴图（{exc}），不铺底图")
            base_rows = None

    guide_rows = render_guide(regions, tex_w, tex_h, args.scale, base_rows, args.dim)
    png_path = out_dir / "uv_guide.png"
    write_png(png_path, guide_rows, tex_w * args.scale)

    # ── 报告 ──
    print(f"模型     : {model_path}")
    print(f"identifier: {desc.get('identifier', '(未声明)')}")
    print(f"贴图     : {tex_w}x{tex_h}" + (f"（{tex_path.name}）" if tex_path else "（无）"))
    print(f"区域     : {len(regions)} 个（{len(cubes)} 个方块）")
    print(f"实际占用 : {extent_w}x{extent_h}，贴图剩余 {tex_w - extent_w}x{tex_h - extent_h}")
    print()
    print("── 图例（颜色 → 骨骼/方块.面 → 矩形 x,y,w,h）──")
    for key in cubes:
        print(f"  {key}")
        for r in regions:
            if f"{r['bone']}/{r['cube']}" != key:
                continue
            x, y, w, h = r["rect"]
            print(f"    {r['color']}  {r['face']:<5}  [{x:>3}, {y:>3}, {w:>3}, {h:>3}]  ({r['mode']})")
    print()
    for wmsg in warnings:
        print(f"WARN  {wmsg}")
    for msg in oob:
        print(f"ERROR {msg}")
    for msg in partial:
        print(f"WARN  {msg}")
    for msg in shared:
        print(f"INFO  {msg}")
    print()
    print(f"输出     : {json_path}")
    print(f"           {png_path}（{tex_w * args.scale}x{tex_h * args.scale}）")

    return 0 if not oob else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
