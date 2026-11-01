#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""纯标准库 PNG 读写（无 Pillow / 无第三方依赖）。

为什么自己写：本仓库的构建环境刻意不引入图像库（见 ``tools/dev/gen_dragon_wings.py``
和 ``tools/dev/make-icon.py`` 都是手写 PNG）。贴图是 64×64 / 256×256 的像素画，
用不到 Pillow 的 1% 功能，多一个依赖反而让 CI 和容器镜像变重。

支持范围（够用即止）：

* **读**：8 位深、非隔行的 PNG，颜色类型 0（灰度）/ 2（RGB）/ 4（灰度+alpha）/ 6（RGBA）。
  调色板（类型 3）和 16 位深会明确报错，不静默猜。
* **写**：8 位 RGBA（颜色类型 6）。

像素一律以 **RGBA 4 元组** 的 ``bytearray`` 行表示，方便直接按坐标改。

    >>> w, h, rows = read_png("texture.png")
    >>> rows[y][x * 4 + 3] = 0          # 把 (x, y) 改成全透明
    >>> write_png("out.png", rows)

命令行（自检 / 查看信息）::

    python3 tools/dev/pnglib.py texture.png          # 打印尺寸与颜色类型
    python3 tools/dev/pnglib.py a.png b.png          # 逐字节比对两张图
"""

from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

#: 颜色类型 → 每像素通道数
_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}

#: 颜色类型 → 人类可读名
_COLOR_NAMES = {0: "灰度", 2: "真彩", 3: "调色板", 4: "灰度+alpha", 6: "真彩+alpha"}


class PngError(ValueError):
    """PNG 结构不符合预期。消息里带足够定位信息，不吞异常。"""


def _chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def write_png(path: str | Path, rows: list[bytes], width: int | None = None) -> None:
    """写 8 位 RGBA PNG。

    :param rows: 每行 ``width * 4`` 字节的 RGBA 数据，自上而下。
    :param width: 行宽（像素）；省略时按第一行长度 / 4 推断。空图必须显式给。
    """
    if width is None:
        if not rows:
            raise PngError("空图像必须显式给 width")
        width = len(rows[0]) // 4
    expected = width * 4
    for i, row in enumerate(rows):
        if len(row) != expected:
            raise PngError(f"第 {i} 行长度 {len(row)}，应为 {expected}（width={width}）")

    raw = bytearray()
    for row in rows:
        raw.append(0)  # 过滤器类型 0（None）
        raw.extend(row)

    header = struct.pack(">IIBBBBB", width, len(rows), 8, 6, 0, 0, 0)
    blob = (
        PNG_SIGNATURE
        + _chunk(b"IHDR", header)
        + _chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + _chunk(b"IEND", b"")
    )
    Path(path).write_bytes(blob)


def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def _unfilter(data: bytes, width: int, height: int, bpp: int, stride: int) -> list[bytearray]:
    """按 PNG 规范逐行还原过滤器，返回每行的原始字节。"""
    rows: list[bytearray] = []
    pos = 0
    prev = bytearray(stride)
    for y in range(height):
        if pos >= len(data):
            raise PngError(f"IDAT 数据在还原第 {y} 行时提前结束")
        ftype = data[pos]
        pos += 1
        line = bytearray(data[pos : pos + stride])
        if len(line) != stride:
            raise PngError(f"第 {y} 行数据不足：{len(line)} < {stride}")
        pos += stride

        if ftype == 0:
            pass
        elif ftype == 1:  # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                upleft = prev[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + _paeth(left, prev[i], upleft)) & 0xFF
        else:
            raise PngError(f"第 {y} 行过滤器类型 {ftype} 不合法（0..4）")

        rows.append(line)
        prev = line
    return rows


def read_png(path: str | Path) -> tuple[int, int, list[bytearray]]:
    """读 PNG，返回 ``(width, height, rows)``，每行是 RGBA 的 ``bytearray``。"""
    blob = Path(path).read_bytes()
    if not blob.startswith(PNG_SIGNATURE):
        raise PngError(f"{path}: 不是 PNG（签名不匹配）")

    pos = len(PNG_SIGNATURE)
    width = height = depth = color_type = interlace = None
    idat = bytearray()

    while pos + 8 <= len(blob):
        (length,) = struct.unpack(">I", blob[pos : pos + 4])
        tag = blob[pos + 4 : pos + 8]
        data = blob[pos + 8 : pos + 8 + length]
        pos += 12 + length  # 4 长度 + 4 tag + data + 4 crc

        if tag == b"IHDR":
            width, height, depth, color_type, _comp, _filt, interlace = struct.unpack(
                ">IIBBBBB", data
            )
        elif tag == b"IDAT":
            idat.extend(data)
        elif tag == b"IEND":
            break

    if width is None:
        raise PngError(f"{path}: 缺少 IHDR")
    if interlace != 0:
        raise PngError(f"{path}: 不支持隔行扫描（interlace=1）")
    if depth != 8:
        raise PngError(f"{path}: 只支持 8 位深，实际 {depth} 位")
    if color_type == 3:
        raise PngError(f"{path}: 不支持调色板 PNG，请先转成 RGBA")
    if color_type not in _CHANNELS:
        raise PngError(f"{path}: 未知颜色类型 {color_type}")

    channels = _CHANNELS[color_type]
    stride = width * channels
    raw = zlib.decompress(bytes(idat))
    lines = _unfilter(raw, width, height, channels, stride)

    # 统一扩成 RGBA
    rows: list[bytearray] = []
    for line in lines:
        if color_type == 6:
            rows.append(bytearray(line))
            continue
        out = bytearray(width * 4)
        for x in range(width):
            base = x * channels
            dst = x * 4
            if color_type == 0:
                g = line[base]
                out[dst : dst + 4] = bytes((g, g, g, 255))
            elif color_type == 4:
                g, a = line[base], line[base + 1]
                out[dst : dst + 4] = bytes((g, g, g, a))
            else:  # color_type == 2
                out[dst : dst + 4] = bytes(line[base : base + 3]) + b"\xff"
        rows.append(out)
    return width, height, rows


def describe(path: str | Path) -> str:
    """人类可读的一行摘要（尺寸 / 颜色类型 / 透明度占比）。"""
    w, h, rows = read_png(path)
    total = w * h
    opaque = sum(1 for y in range(h) for x in range(w) if rows[y][x * 4 + 3] == 255)
    return f"{w}x{h}  {_COLOR_NAMES.get(6, '真彩+alpha')}  不透明 {opaque}/{total}（{opaque * 100.0 / total:.1f}%）"


def _main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    if len(argv) == 1:
        try:
            print(describe(argv[0]))
        except (PngError, OSError) as exc:
            print(f"错误：{exc}", file=sys.stderr)
            return 1
        return 0

    # 两个文件 → 逐字节比对（贴图回归时用）
    try:
        wa, ha, ra = read_png(argv[0])
        wb, hb, rb = read_png(argv[1])
    except (PngError, OSError) as exc:
        print(f"错误：{exc}", file=sys.stderr)
        return 1
    if (wa, ha) != (wb, hb):
        print(f"尺寸不同：{wa}x{ha} vs {wb}x{hb}")
        return 1
    diff = sum(
        1
        for y in range(ha)
        for x in range(wa * 4)
        if ra[y][x] != rb[y][x]
    )
    if diff == 0:
        print(f"完全一致（{wa}x{ha}）")
        return 0
    print(f"不一致：{diff} 个通道值不同（{wa}x{ha}）")
    return 1


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
