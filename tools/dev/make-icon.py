#!/usr/bin/env python3
"""生成模组图标 assets/vmfashion/icon.png（128x128）。

不依赖 Pillow：纯 zlib + struct 手写 PNG。这样 CI / 无图形环境也能重新生成。
图形：深色圆角底 + 紫色皇冠 + 高光，和 ModConfig.UI 的配色一致。

用法：python3 tools/dev/make-icon.py
"""
import struct
import zlib
from pathlib import Path

SIZE = 128
BG_TOP = (0x2D, 0x2D, 0x44)
BG_BOTTOM = (0x1E, 0x1E, 0x2E)
BORDER = (0x4A, 0x4A, 0x6A)
CROWN = (0x7C, 0x3A, 0xED)
CROWN_HI = (0xA7, 0x8B, 0xFA)
GEM = (0x4A, 0xDE, 0x80)


def lerp(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def rounded_mask(x, y, radius):
    """圆角矩形内部判定（角落用圆方程裁掉）。"""
    r = radius
    cx = min(max(x, r), SIZE - 1 - r)
    cy = min(max(y, r), SIZE - 1 - r)
    dx, dy = x - cx, y - cy
    return dx * dx + dy * dy <= r * r


def in_triangle(px, py, a, b, c):
    def sign(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])

    d1 = sign((px, py), a, b)
    d2 = sign((px, py), b, c)
    d3 = sign((px, py), c, a)
    has_neg = d1 < 0 or d2 < 0 or d3 < 0
    has_pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (has_neg and has_pos)


def build_pixels():
    rows = []
    crown_base_y = 84
    crown_top_y = 40
    # 皇冠：底边 + 三个尖
    left, right = 28, 100
    for y in range(SIZE):
        row = bytearray()
        for x in range(SIZE):
            if not rounded_mask(x, y, 18):
                row.extend((0, 0, 0, 0))
                continue
            # 底：垂直渐变
            color = lerp(BG_TOP, BG_BOTTOM, y / (SIZE - 1))
            # 内描边
            if not rounded_mask(x, y, 18) or (
                not rounded_mask(x - 1, y, 18)
                or not rounded_mask(x + 1, y, 18)
                or not rounded_mask(x, y - 1, 18)
                or not rounded_mask(x, y + 1, 18)
            ):
                color = BORDER
            # 皇冠底带
            if left <= x <= right and crown_base_y - 16 <= y <= crown_base_y:
                color = CROWN
                if y <= crown_base_y - 13:
                    color = CROWN_HI
            # 三个尖
            for apex_x in (34, 64, 94):
                if in_triangle(
                    x,
                    y,
                    (apex_x, crown_top_y + (14 if apex_x != 64 else 0)),
                    (apex_x - 15, crown_base_y - 16),
                    (apex_x + 15, crown_base_y - 16),
                ):
                    color = CROWN if apex_x != 64 else CROWN_HI
            # 中间宝石
            if (x - 64) ** 2 + (y - (crown_base_y - 8)) ** 2 <= 25:
                color = GEM
            row.extend((color[0], color[1], color[2], 255))
        rows.append(bytes(row))
    return rows


def write_png(path, rows):
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag, data):
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


if __name__ == "__main__":
    target = Path(__file__).resolve().parents[2] / "src/main/resources/assets/vmfashion/icon.png"
    write_png(target, build_pixels())
    print(f"wrote {target} ({target.stat().st_size} bytes)")
