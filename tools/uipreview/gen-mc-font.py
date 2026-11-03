#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Minecraft 客户端 jar 重新生成 ``McAsciiFont.java``（内嵌 ASCII 字形数据）。

用法::

    python3 tools/uipreview/gen-mc-font.py [客户端 jar 路径]

不给路径时按 Loom 缓存的默认位置找 ``26.3/minecraft-client-only.jar``
（``$GRADLE_USER_HOME`` 或 ``~/.gradle`` 下的 ``caches/fabric-loom``）。

生成的文件是 ``tools/uipreview/stubs/net/minecraft/client/gui/font/McAsciiFont.java``，
内容为 256 码位 × 8 行 × 1 字节的位掩码 + 每码位的 advance。
宽度算法复刻游戏内 ``FontTexture``：从右往左找第一个有像素的列，``advance = 列号 + 1``；
空格被 ``minecraft:include/space`` 覆盖成 4（见 ``space.json``）。

正常情况下**不需要**重跑这个脚本 —— 字体数据是 MC 的固定资源，只有换 MC 版本才需要。
"""
from __future__ import annotations

import os
import struct
import sys
import zlib
from pathlib import Path

MOD_DIR = Path(__file__).resolve().parent.parent.parent
OUT = MOD_DIR / "tools/uipreview/stubs/net/minecraft/client/gui/font/McAsciiFont.java"
TARGET = "26.3"


def find_jar() -> Path:
    for root in (os.environ.get("GRADLE_USER_HOME"), Path.home() / ".gradle", "/tmp/gradle-home"):
        if not root:
            continue
        candidate = Path(root) / "caches/fabric-loom" / TARGET / "minecraft-client-only.jar"
        if candidate.is_file():
            return candidate
    raise SystemExit(
        f"找不到 {TARGET}/minecraft-client-only.jar；请显式传入路径，"
        "或先跑一次 ./gradlew build 让 Loom 下载客户端 jar。"
    )


def read_png_1bit_palette(path: Path):
    """解 1 位调色板 PNG（ascii.png 就是这种），返回 (w, h, 像素函数)。"""
    data = path.read_bytes()
    pos, idat, w, h, bd = 8, b"", 0, 0, 0
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        tag = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if tag == b"IHDR":
            w, h, bd, ct = struct.unpack(">IIBB", payload[:10])
            if ct != 3 or bd != 1:
                raise SystemExit(f"ascii.png 期望 1 位调色板，实际 bitdepth={bd} colortype={ct}")
        elif tag == b"IDAT":
            idat += payload
    raw = zlib.decompress(idat)
    stride = (w * bd + 7) // 8
    rows, prev = [], bytearray(stride)
    for y in range(h):
        off = y * (stride + 1)
        ft = raw[off]
        line = bytearray(raw[off + 1:off + 1 + stride])
        if ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft != 0:
            raise SystemExit(f"不支持的 PNG 行过滤器 {ft}（行 {y}）")
        rows.append(line)
        prev = line

    def px(x: int, y: int) -> int:
        return (rows[y][x >> 3] >> (7 - (x & 7))) & 1

    return w, h, px


def main(argv: list[str]) -> int:
    jar = Path(argv[1]) if len(argv) > 1 else find_jar()
    print(f"从 {jar} 读取字体")
    import zipfile

    with zipfile.ZipFile(jar) as zf:
        png = zf.read("assets/minecraft/textures/font/ascii.png")
    tmp = Path("/tmp/_mc_ascii.png")
    tmp.write_bytes(png)

    w, h, px = read_png_1bit_palette(tmp)
    if (w, h) != (128, 128):
        raise SystemExit(f"ascii.png 期望 128x128，实际 {w}x{h}")

    advance = [0] * 256
    glyphs: list[int] = []
    for ch in range(256):
        col, row = ch & 15, ch >> 4
        gx, gy = col * 8, row * 8
        for yy in range(8):
            bits = 0
            for xx in range(8):
                if px(gx + xx, gy + yy):
                    bits |= 0x80 >> xx
            glyphs.append(bits & 0xFF)
        wid = 0
        for xx in range(7, -1, -1):
            if any(px(gx + xx, gy + yy) for yy in range(8)):
                wid = xx + 1
                break
        advance[ch] = wid + 1 if wid > 0 else 0

    advance[0x20] = 4  # space provider 覆盖

    def fmt(values, per, render):
        return "\n".join(
            "            " + ", ".join(render(v) for v in values[i:i + per]) + ","
            for i in range(0, len(values), per)
        )

    source = f"""package net.minecraft.client.gui.font;

/**
 * Minecraft 默认 ASCII 字体的原始字形数据（从 MC {TARGET} 客户端 jar 的
 * {{@code assets/minecraft/textures/font/ascii.png}} 提取，由
 * {{@code tools/uipreview/gen-mc-font.py}} 生成 —— 别手改，改脚本重生成）。
 *
 * <p><b>为什么内嵌而不是运行时读 jar：</b>UI 预览器要在"没有 Minecraft 资源包栈"的
 * 环境里工作。内嵌这 2KB 数据后整个预览器只依赖 JDK，换机器 / CI 里都能直接跑出图，
 * 不会因为找不到 client jar 而画不出字。
 *
 * <p><b>数据布局：</b>{{@link #GLYPHS}} 是 256 个码位 × 8 行 × 1 字节，每字节的**最高位**
 * 对应最左边的像素（与 PNG 扫描线同序）。码位 {{@code c}} 的第 {{@code r}} 行是
 * {{@code GLYPHS[c * 8 + r]}}。宽度算法与游戏内 {{@code FontTexture}} 一致：
 * 从右往左找第一个有像素的列，{{@code advance = 列号 + 1}}。
 */
public final class McAsciiFont {{

    /** 字形位图：256 码位 × 8 行，每行 1 字节位掩码（最高位 = 最左像素）。 */
    public static final int[] GLYPHS = {{
{fmt(glyphs, 16, lambda v: '0x%02X' % v)}
    }};

    /**
     * 每个码位的水平步进（像素）。
     *
     * <p>{{@code 0}} = 该码位没有字形（游戏里会落到 unifont / 空格处理）。
     * 空格 {{@code U+0020}} 由 {{@code minecraft:include/space}} 覆盖成 4，
     * 与游戏内 {{@code space.json}} 的 {{@code " ": 4}} 一致。
     */
    public static final int[] ADVANCE = {{
{fmt(advance, 16, str)}
    }};

    /** 字形行数（ASCII 字体固定 8 行，ascent 7）。 */
    public static final int ROWS = 8;

    /** 字形格宽度（像素）。 */
    public static final int CELL = 8;

    private McAsciiFont() {{
    }}

    /**
     * 取某个码位某一行的位掩码。
     *
     * @param codepoint 0..255
     * @param row       0..7
     * @return 位掩码，最高位是最左像素
     */
    public static int row(int codepoint, int row) {{
        if (codepoint < 0 || codepoint > 255 || row < 0 || row >= ROWS) {{
            return 0;
        }}
        return GLYPHS[codepoint * ROWS + row];
    }}

    /** 该码位是否有内嵌字形（决定要不要回退到 unifont / AWT）。 */
    public static boolean hasGlyph(int codepoint) {{
        return codepoint >= 0 && codepoint <= 255 && ADVANCE[codepoint] > 0;
    }}
}}
"""
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(source, encoding="utf-8")
    print(f"写出 {OUT.relative_to(MOD_DIR)}（{len(source)} 字节）")
    print(f"抽样：'A'={advance[0x41]} 'i'={advance[0x69]} ' '={advance[0x20]} '.'={advance[0x2e]}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
