#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""VMFashion 时装包独立审计（纯 Python 标准库，无第三方依赖）。

用法::

    python3 tools/verify/audit_pack.py <包目录或 zip> [选项]

    # 例：审计内置示例包（应当全绿）
    python3 tools/verify/audit_pack.py src/main/resources/assets/vmfashion/default_pack

    # 例：审计待发布的新时装包，并断言 packId / 分类
    python3 tools/verify/audit_pack.py build/packs/dragon_wings.zip \\
        --expect-id dragon_wings --expect-category wings

    # 例：比对线上下载的 zip 与本地目录内容是否逐字节一致（第二轮发布核验用）
    python3 tools/verify/audit_pack.py /tmp/dl/dragon_wings.zip \\
        --compare src/main/resources/assets/vmfashion/default_pack/cosmetics/dragon_wings

退出码：0 = 无 ERROR（可能带 WARN）；1 = 有 ERROR；2 = 用法 / IO 错误。

审计口径全部来自仓库里的**代码**而不是二手描述，主要出处：

* ``docs/pack-format.md`` §2 目录约定 / §4 pack.json 字段 / §6 分类与 attachedBone /
  §8 emissive / §9 动画 / §10.2 zip 结构 / §10.3 安全限制 / §12 自检清单
* ``docs/fashion-api.md`` §1 清单字段 / §2 下载与 zip 限制 / §3 cosmeticId 契约
* ``src/client/java/com/venus/vmfashion/cosmetic/PackLoader.java``（解析与自动发现、
  MAX_MODEL_BYTES=4MB）
* ``src/client/java/com/venus/vmfashion/render/CosmeticAssets.java``（MAX_QUADS=20000）
* ``src/client/java/com/venus/vmfashion/net/SafeZip.java``（zip 限制与条目名规则）
* ``src/client/java/com/venus/vmfashion/net/PackCatalog.java``（packId 规则）
* ``src/client/java/com/venus/vmfashion/render/anim/BedrockAnimations.java``（动画解析）
* ``/workspace/vmtools/vmtools-next/backend/src/vmtools_next/core/fashion_service.py``
  （后端 PACK_ID_RE / FORBIDDEN_PACK_EXTS / ZIP_* / validate_pack_zip / store_asset）

脚本**只读**输入，不写任何文件（``--json`` 只打到 stdout）。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import os
import re
import struct
import sys
import zipfile
import zlib
from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# ══════════════════════════════════════════════════════════════════════════
# 契约常量
# ══════════════════════════════════════════════════════════════════════════

# packId：客户端 PackCatalog.isSafePackId / 后端 fashion_service.py:82 同规则
PACK_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
PACK_ID_RESERVED = {"server"}
# version：后端 PACK_VERSION_RE（fashion_service.py:85）
PACK_VERSION_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")
# cosmeticId：后端 COSMETIC_ID_RE（fashion_service.py:66）；localId：LOCAL_ID_RE（:68）
COSMETIC_ID_RE = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
LOCAL_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,96}$")

# 单模型上限：PackLoader.MAX_MODEL_BYTES（:66）
MAX_MODEL_BYTES = 4 * 1024 * 1024
# 单模型四边形上限：CosmeticAssets.MAX_QUADS（:171）
MAX_QUADS = 20_000
# pack.json 上限：后端 ZIP_MAX_MANIFEST_BYTES（fashion_service.py:99）
MAX_MANIFEST_BYTES = 1024 * 1024

# zip 限制：docs/pack-format.md §10.3 / fashion_service.py:89-99 / SafeZip.Limits
ZIP_MAX_ENTRIES = 4096
ZIP_MAX_ENTRY_BYTES = 64 * 1024 * 1024
ZIP_MAX_TOTAL_BYTES = 512 * 1024 * 1024
ZIP_MAX_ARCHIVE_BYTES = 128 * 1024 * 1024
ZIP_MAX_NAME_LENGTH = 255
ZIP_RATIO_LIMIT = 100.0
ZIP_RATIO_MIN_UNCOMPRESSED = 10 * 1024 * 1024
ZIP_ALLOWED_METHODS = {0, 8}  # stored / deflate

# 禁止的可执行后缀：后端 FORBIDDEN_PACK_EXTS（fashion_service.py:102-106）
FORBIDDEN_EXTS = {
    ".exe", ".dll", ".so", ".dylib", ".bat", ".cmd", ".com", ".msi", ".scr",
    ".ps1", ".vbs", ".sh", ".bash", ".zsh", ".py", ".pyc", ".jar", ".class",
    ".php", ".asp", ".aspx", ".jsp", ".cgi", ".lnk", ".url", ".reg",
}

# Windows 保留设备名（SafeZip.isWindowsReservedName / fashion_service.py:107-109）
_WINDOWS_RESERVED = {"CON", "PRN", "AUX", "NUL", "CLOCK$"} | {
    "COM%d" % i for i in range(1, 10)
} | {"LPT%d" % i for i in range(1, 10)}

# 分类 → 默认挂载骨骼：CosmeticCategory.java / docs/pack-format.md §6.1
CATEGORY_DEFAULT_BONE = {
    "cape": "body", "wings": "body", "hat": "head", "back": "body",
    "pet": "body", "aura": "body", "other": "body",
}
VALID_BONES = {"body", "head", "left_arm", "right_arm", "left_leg", "right_leg"}
# HiddenParts.java ofOne() 接受的全部写法（大小写不敏感）
VALID_HIDE_PARTS = {
    "all", "head", "hat", "helmet", "body", "torso", "chest", "jacket",
    "right_arm", "rightarm", "left_arm", "leftarm",
    "right_leg", "rightleg", "left_leg", "leftleg",
}
# PackLoader.buildDefinition 的 material 解析（:364-377）
VALID_MATERIALS = {"cutout", "translucent", "blend", "opaque"}

# 几何格式：docs/pack-format.md §4.4「mod 只认 Bedrock format_version 1.12.0」
# （GeckoLib 5.5.7 的 ModelFormatVersion 还认 1.14.0/1.16.0/1.19.30/1.21.0/1.21.20，
#   但 VMFashion 的契约是 1.12.0；非 1.12.0 记为 ERROR 并在证据里注明。）
SUPPORTED_GEO_FORMAT = "1.12.0"
GECKOLIB_KNOWN_GEO_FORMATS = {"1.12.0", "1.14.0", "1.16.0", "1.19.30", "1.21.0", "1.21.20"}

# 动画文件名的三种写法：PackLoader.isAnimationFileName（:561-569）
def is_animation_filename(name: str) -> bool:
    lowered = name.lower()
    return (lowered == "anim.json"
            or lowered.endswith(".anim.json")
            or lowered.endswith(".animation.json"))

ANIM_CHANNELS = ("rotation", "position", "scale")

# 合法 PNG 颜色类型（有 alpha 的是 4 / 6；3 可能带 tRNS）
PNG_COLOR_TYPE_NAMES = {
    0: "灰度", 2: "真彩", 3: "索引色", 4: "灰度+alpha", 6: "真彩+alpha",
}


# ══════════════════════════════════════════════════════════════════════════
# 结果收集
# ══════════════════════════════════════════════════════════════════════════

ERROR = "ERROR"
WARN = "WARN"
INFO = "INFO"


@dataclass
class Finding:
    level: str
    check: str
    message: str
    evidence: str = ""

    def to_dict(self) -> Dict[str, str]:
        d = {"level": self.level, "check": self.check, "message": self.message}
        if self.evidence:
            d["evidence"] = self.evidence
        return d


class Report:
    """审计结果：事件流（分节 + findings）+ 结构化指标。"""

    def __init__(self) -> None:
        self.events: List[Tuple[str, Any]] = []
        self.findings: List[Finding] = []
        self.metrics: Dict[str, Any] = {}
        self._in_section = False

    # ── 输出 ──
    def section(self, title: str) -> None:
        self.events.append(("section", title))
        self._in_section = True

    def line(self, text: str) -> None:
        self.events.append(("text", text))

    def error(self, check: str, message: str, evidence: str = "") -> None:
        self._add(ERROR, check, message, evidence)

    def warn(self, check: str, message: str, evidence: str = "") -> None:
        self._add(WARN, check, message, evidence)

    def info(self, check: str, message: str, evidence: str = "") -> None:
        self._add(INFO, check, message, evidence)

    def _add(self, level: str, check: str, message: str, evidence: str) -> None:
        finding = Finding(level, check, message, evidence)
        self.findings.append(finding)
        self.events.append(("finding", finding))

    # ── 统计 ──
    def count(self, level: str) -> int:
        return sum(1 for f in self.findings if f.level == level)

    @property
    def verdict(self) -> str:
        return "FAIL" if self.count(ERROR) else "PASS"

    def render(self, quiet: bool = False) -> str:
        out: List[str] = []
        for kind, payload in self.events:
            if kind == "section":
                if quiet:
                    continue
                out.append("")
                out.append("── %s ──" % payload)
            elif kind == "text":
                if not quiet:
                    out.append(payload)
            else:
                finding: Finding = payload
                if quiet and finding.level == INFO:
                    continue
                marker = {"ERROR": "✗ ERROR", "WARN": "⚠ WARN ", "INFO": "· INFO "}[finding.level]
                out.append("%s [%s] %s" % (marker, finding.check, finding.message))
                if finding.evidence and not quiet:
                    for line in finding.evidence.splitlines():
                        out.append("          " + line)
        return "\n".join(out)


# ══════════════════════════════════════════════════════════════════════════
# 通用工具
# ══════════════════════════════════════════════════════════════════════════

def is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def finite_number(value: Any) -> bool:
    return is_number(value) and math.isfinite(float(value))


def vec3(value: Any) -> Optional[List[float]]:
    """[x,y,z] 三个有限数字；否则 None。"""
    if not isinstance(value, list) or len(value) < 3:
        return None
    out = []
    for item in value[:3]:
        if not finite_number(item):
            return None
        out.append(float(item))
    return out


def fmt_vec(values: Sequence[float], digits: int = 2) -> str:
    return "[" + ", ".join(("%." + str(digits) + "f") % v for v in values) + "]"


def load_json(data: bytes) -> Tuple[Optional[Any], Optional[str], List[str]]:
    """解析 JSON（UTF-8，允许 BOM）。返回 (对象, 错误, 重复键路径列表)。"""
    duplicates: List[str] = []

    def hook(pairs):
        seen = {}
        for key, value in pairs:
            if key in seen:
                duplicates.append(key)
            seen[key] = value
        return seen

    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        return None, "不是 UTF-8 文本：%s" % exc, duplicates
    try:
        return json.loads(text, object_pairs_hook=hook), None, duplicates
    except json.JSONDecodeError as exc:
        return None, "JSON 语法错误：%s（行 %d 列 %d）" % (exc.msg, exc.lineno, exc.colno), duplicates


def png_header(data: bytes) -> Tuple[Optional[Dict[str, int]], Optional[str]]:
    """读 PNG 的 IHDR，返回 (信息, 错误)。"""
    if len(data) < 33:
        return None, "文件太短（%d 字节），不是合法 PNG" % len(data)
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None, "缺少 PNG 魔数（不是 PNG 文件，可能是 JPEG/WEBP 改名）"
    length = struct.unpack(">I", data[8:12])[0]
    chunk_type = data[12:16]
    if chunk_type != b"IHDR" or length != 13:
        return None, "第一个 chunk 不是 IHDR（实际 %r）" % chunk_type
    width, height = struct.unpack(">II", data[16:24])
    bit_depth = data[24]
    color_type = data[25]
    interlace = data[28]
    return {
        "width": width, "height": height,
        "bit_depth": bit_depth, "color_type": color_type,
        "interlace": interlace,
    }, None


def png_has_alpha(info: Dict[str, int]) -> bool:
    return info["color_type"] in (4, 6)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


# ── PNG 像素统计（只为了判断「发光层是不是全透明」这类语义问题） ──────────
_PNG_CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def _png_unfilter(raw: bytes, width: int, height: int, bpp: int, stride: int) -> Optional[bytearray]:
    """还原 PNG 扫描线（支持 filter 0-4，8/16 位）。"""
    out = bytearray()
    prev = bytearray(stride)
    pos = 0
    for _ in range(height):
        if pos >= len(raw):
            return None
        ftype = raw[pos]
        pos += 1
        line = bytearray(raw[pos:pos + stride])
        if len(line) < stride:
            return None
        pos += stride
        if ftype == 0:
            pass
        elif ftype == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        else:
            return None
        out += line
        prev = line
    return out


def png_alpha_stats(data: bytes) -> Optional[Dict[str, int]]:
    """统计 alpha > 8 的像素数（docs §8 的发光阈值）。

    返回 ``{"width","height","opaque","total"}``；格式不支持（隔行扫描 / 索引色
    位深 < 8 / 数据损坏）时返回 None（调用方跳过该检查，不误报）。
    """
    header, err = png_header(data)
    if err or header is None:
        return None
    if header.get("interlace"):
        return None
    width, height = header["width"], header["height"]
    color_type = header["color_type"]
    bit_depth = header["bit_depth"]
    if color_type not in _PNG_CHANNELS or bit_depth not in (8, 16):
        return None
    channels = _PNG_CHANNELS[color_type]
    if color_type == 3 and bit_depth != 8:
        return None
    bytes_per_sample = bit_depth // 8
    bpp = channels * bytes_per_sample
    stride = width * bpp
    # 收集 IDAT
    idat = bytearray()
    pos = 8
    palette_alpha: List[int] = []
    trns = None
    while pos + 8 <= len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IDAT":
            idat += chunk
        elif ctype == b"tRNS":
            trns = chunk
        elif ctype == b"IEND":
            break
    try:
        raw = zlib.decompress(bytes(idat))
    except Exception:
        return None
    pixels = _png_unfilter(raw, width, height, bpp, stride)
    if pixels is None:
        return None
    if color_type == 3 and trns:
        palette_alpha = list(trns)
    total = width * height
    opaque = 0
    if color_type in (4, 6):
        offset = (channels - 1) * bytes_per_sample
        for i in range(total):
            alpha = pixels[i * bpp + offset]
            if alpha > 8:
                opaque += 1
    elif color_type == 3:
        for i in range(total):
            index = pixels[i * bpp]
            alpha = palette_alpha[index] if index < len(palette_alpha) else 255
            if alpha > 8:
                opaque += 1
    else:
        opaque = total  # 没有 alpha 通道 = 全不透明
    return {"width": width, "height": height, "opaque": opaque, "total": total}


# ══════════════════════════════════════════════════════════════════════════
# 路径规范化（镜像 PackLoader.resolveSafe：允许 a/../b，拒绝逃出包根）
# ══════════════════════════════════════════════════════════════════════════

def normalize_rel_path(raw: str) -> Tuple[Optional[str], Optional[str]]:
    """把声明的相对路径规范化。返回 (规范化路径, 错误)。"""
    if not isinstance(raw, str) or not raw.strip():
        return None, "不是非空字符串"
    text = raw.replace("\\", "/")
    if text.startswith("/"):
        return None, "绝对路径（%s）" % raw
    if len(text) >= 2 and text[1] == ":" and text[0].isalpha():
        return None, "Windows 盘符（%s）" % raw
    segments: List[str] = []
    for part in text.split("/"):
        if part in ("", "."):
            continue
        if part == "..":
            if not segments:
                return None, "用 .. 逃出了包根目录（%s）" % raw
            segments.pop()
            continue
        segments.append(part)
    if not segments:
        return None, "规范化后为空（%s）" % raw
    return "/".join(segments), None


def is_hidden_path(name: str) -> bool:
    return any(part.startswith(".") for part in name.split("/") if part)


# ══════════════════════════════════════════════════════════════════════════
# 包源：目录 / zip
# ══════════════════════════════════════════════════════════════════════════

class PackSource:
    """统一抽象：包目录或 zip 包。

    ``files`` 是「规范化相对路径 → 字节」；``sizes`` 是同一批路径 → 字节数。
    对 zip 输入，先做安全校验再读内容；校验失败也尽量保留可读条目以便继续审计
    （但结论一定是 FAIL）。
    """

    def __init__(self, path: str) -> None:
        self.path = path
        self.kind = "zip" if zipfile.is_zipfile(path) and os.path.isfile(path) else "dir"
        self.files: Dict[str, bytes] = {}
        self.sizes: Dict[str, int] = {}
        self.symlinks: List[str] = []
        self.zip_bytes: Optional[bytes] = None
        self.zip_infos: List[zipfile.ZipInfo] = []
        self.raw_names: Dict[str, str] = {}  # 规范化名 → zip 里的原始名

    # ── 目录 ──
    @classmethod
    def from_dir(cls, root: str) -> "PackSource":
        source = cls(root)
        source.kind = "dir"
        root = os.path.abspath(root)
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames.sort()
            for name in sorted(dirnames):
                full = os.path.join(dirpath, name)
                if os.path.islink(full):
                    source.symlinks.append(os.path.relpath(full, root).replace(os.sep, "/"))
            for name in sorted(filenames):
                full = os.path.join(dirpath, name)
                rel = os.path.relpath(full, root).replace(os.sep, "/")
                if os.path.islink(full):
                    source.symlinks.append(rel)
                    continue
                try:
                    size = os.path.getsize(full)
                except OSError:
                    continue
                source.sizes[rel] = size
                if size <= ZIP_MAX_ENTRY_BYTES:
                    try:
                        with open(full, "rb") as fh:
                            source.files[rel] = fh.read()
                    except OSError:
                        pass
        return source

    # ── zip ──
    @classmethod
    def from_zip(cls, path: str, report: Report) -> "PackSource":
        source = cls(path)
        source.kind = "zip"
        with open(path, "rb") as fh:
            data = fh.read()
        source.zip_bytes = data
        source.sizes["\x00zip"] = len(data)
        try:
            archive = zipfile.ZipFile(io.BytesIO(data))
        except Exception as exc:  # pragma: no cover - is_zipfile 已挡掉大多数
            report.error("zip.open", "zip 结构损坏，无法打开：%s" % exc)
            return source
        with archive:
            source.zip_infos = list(archive.infolist())
            for info in source.zip_infos:
                if info.is_dir():
                    continue
                raw = info.filename
                norm, err = normalize_rel_path(raw)
                key = norm if norm else raw.replace("\\", "/").lstrip("/")
                source.raw_names[key] = raw
                source.sizes[key] = info.file_size
                if info.flag_bits & 0x1:
                    # 加密条目读不出来是预期行为，zip.encrypted 会给出明确错误
                    continue
                if info.file_size <= ZIP_MAX_ENTRY_BYTES:
                    try:
                        source.files[key] = archive.read(info)
                    except Exception as exc:
                        report.warn("zip.read", "读取 zip 条目失败：%s（%s）" % (raw, exc))
        return source

    # ── 查询 ──
    def exists(self, rel: str) -> bool:
        return rel in self.files or rel in self.sizes

    def read(self, rel: str) -> Optional[bytes]:
        return self.files.get(rel)

    def size(self, rel: str) -> int:
        if rel in self.sizes:
            return self.sizes[rel]
        return len(self.files.get(rel, b""))

    def list_dir(self, prefix: str) -> Tuple[List[str], List[str]]:
        """返回 (子目录名, 直接文件名)。prefix 形如 ``cosmetics`` 或 ``""``。"""
        prefix = prefix.strip("/")
        base = (prefix + "/") if prefix else ""
        subdirs: set = set()
        files: set = set()
        for name in list(self.sizes.keys()) + list(self.files.keys()):
            if name == "\x00zip" or not name.startswith(base):
                continue
            rest = name[len(base):]
            if not rest:
                continue
            if "/" in rest:
                subdirs.add(rest.split("/", 1)[0])
            else:
                files.add(rest)
        return sorted(subdirs), sorted(files)


# ══════════════════════════════════════════════════════════════════════════
# zip 安全校验（镜像 SafeZip / validate_pack_zip）
# ══════════════════════════════════════════════════════════════════════════

def _zip64_or_broken(data: bytes) -> bool:
    """EOCD 里出现 0xFFFF/0xFFFFFFFF 说明用了 ZIP64（客户端/后端都拒绝）。"""
    idx = data.rfind(b"PK\x05\x06")
    if idx < 0 or idx + 22 > len(data):
        return True
    eocd = data[idx:idx + 22]
    if eocd[8:10] == b"\xff\xff" or eocd[10:12] == b"\xff\xff":
        return True
    if eocd[12:16] == b"\xff\xff\xff\xff" or eocd[16:20] == b"\xff\xff\xff\xff":
        return True
    return False


def _entry_name_problem(raw: str) -> Optional[str]:
    """单条目名安全校验（fashion_service.py:1202-1234 / SafeZip.normalizeEntryName）。"""
    if not raw:
        return "条目名为空"
    if len(raw) > ZIP_MAX_NAME_LENGTH:
        return "条目名过长（%d > %d）" % (len(raw), ZIP_MAX_NAME_LENGTH)
    if "\x00" in raw:
        return "条目名含 NUL 字符"
    name = raw.replace("\\", "/")
    if name.startswith("/"):
        return "使用绝对路径"
    if len(name) >= 2 and name[1] == ":" and name[0].isalpha():
        return "含 Windows 盘符"
    for part in name.split("/"):
        if not part or part == ".":
            continue
        if part == "..":
            return "路径含 ..（路径穿越）"
        if ":" in part:
            return "含 NTFS 数据流分隔符"
        base = part.split(".", 1)[0].upper()
        if base in _WINDOWS_RESERVED:
            return "是 Windows 保留设备名"
        if part.endswith(".") or part.endswith(" "):
            return "以点/空格结尾（Windows 会裁剪）"
    if not name.strip("/"):
        return "规范化后为空"
    return None


def audit_zip(source: PackSource, report: Report, expect_id: Optional[str]) -> None:
    """zip 结构与安全校验（docs/fashion-api.md §2 + §6.8.1）。"""
    report.section("zip 结构与安全")
    data = source.zip_bytes or b""
    infos = source.zip_infos

    report.line("  zip 文件：%s（%d 字节，%d 个条目）" % (
        source.path, len(data), len(infos)))

    if len(data) > ZIP_MAX_ARCHIVE_BYTES:
        report.error("zip.archive_size",
                     "zip 本身 %d 字节超过上限 %d MB" % (len(data), ZIP_MAX_ARCHIVE_BYTES // 1024 // 1024))
    else:
        report.info("zip.archive_size", "zip 大小 %d 字节 ≤ %d MB" % (len(data), ZIP_MAX_ARCHIVE_BYTES // 1024 // 1024))

    if _zip64_or_broken(data):
        report.error("zip.zip64", "EOCD 含 ZIP64 标记或 EOCD 损坏（客户端/后端都拒绝 ZIP64）")
    if len(infos) > ZIP_MAX_ENTRIES:
        report.error("zip.entries", "条目数 %d 超过上限 %d" % (len(infos), ZIP_MAX_ENTRIES))
    else:
        report.info("zip.entries", "条目数 %d ≤ %d" % (len(infos), ZIP_MAX_ENTRIES))

    seen_lower: Dict[str, str] = {}
    file_paths: set = set()
    dir_paths: set = set()
    total_uncompressed = 0
    manifest_present = False
    forbidden_hits: List[str] = []
    hidden_hits: List[str] = []
    link_hits: List[str] = []
    name_problems: List[str] = []
    encrypted: List[str] = []
    bad_methods: List[str] = []

    for info in infos:
        raw = info.filename or ""
        problem = _entry_name_problem(raw)
        if problem:
            name_problems.append("%s → %s" % (raw, problem))
        norm, err = normalize_rel_path(raw)
        key = norm if norm else raw
        lowered = key.lower()

        if lowered in seen_lower and seen_lower[lowered] != raw:
            report.error("zip.case_collision",
                         "条目仅大小写不同（Windows/macOS 会互相覆盖）：%s 与 %s"
                         % (seen_lower[lowered], raw))
        else:
            seen_lower[lowered] = raw

        unix_mode = (info.external_attr >> 16) & 0xFFFF
        file_type = unix_mode & 0o170000
        if file_type and file_type not in (0o100000, 0o040000):
            link_hits.append("%s（mode=%o）" % (raw, file_type))
        if info.flag_bits & 0x1:
            encrypted.append(raw)
        if info.compress_type not in ZIP_ALLOWED_METHODS:
            bad_methods.append("%s（method=%d）" % (raw, info.compress_type))

        if info.is_dir():
            dir_paths.add(lowered)
            continue
        file_paths.add(lowered)
        total_uncompressed += info.file_size
        if info.file_size > ZIP_MAX_ENTRY_BYTES:
            report.error("zip.entry_size",
                         "条目解压后 %d 字节超过上限 %d MB：%s"
                         % (info.file_size, ZIP_MAX_ENTRY_BYTES // 1024 // 1024, raw))
        if norm and is_hidden_path(norm):
            hidden_hits.append(raw)
        ext = os.path.splitext(key)[1].lower()
        if ext in FORBIDDEN_EXTS:
            forbidden_hits.append(raw)
        if key == "pack.json":
            manifest_present = True

    if name_problems:
        report.error("zip.entry_name", "有 %d 个条目名不合法（路径穿越/绝对路径/盘符/NTFS 流/保留名等）"
                     % len(name_problems), "\n".join(name_problems[:20]))
    if link_hits:
        report.error("zip.symlink", "含非普通文件条目（符号链接/设备/FIFO）：%s" % ", ".join(link_hits[:10]))
    if encrypted:
        report.error("zip.encrypted", "含加密条目：%s" % ", ".join(encrypted[:10]))
    if bad_methods:
        report.error("zip.method", "含未知压缩算法（只允许 stored/deflate）：%s" % ", ".join(bad_methods[:10]))
    if hidden_hits:
        report.error("zip.hidden", "含隐藏文件/目录（点开头）：%s" % ", ".join(hidden_hits[:10]))
    if forbidden_hits:
        report.error("zip.forbidden_ext", "含可执行/脚本后缀文件：%s" % ", ".join(forbidden_hits[:10]))

    # 文件与目录同名冲突（a 与 a/b）
    conflicts = []
    for path in sorted(file_paths):
        segments = path.split("/")
        for idx in range(1, len(segments)):
            parent = "/".join(segments[:idx])
            if parent in file_paths:
                conflicts.append(parent)
    for path in sorted(file_paths):
        if path in dir_paths:
            conflicts.append(path)
    if conflicts:
        report.error("zip.file_dir_conflict", "文件与目录同名：%s" % ", ".join(sorted(set(conflicts))[:10]))

    if total_uncompressed > ZIP_MAX_TOTAL_BYTES:
        report.error("zip.total_size", "解压后总大小 %d 字节超过上限 %d MB"
                     % (total_uncompressed, ZIP_MAX_TOTAL_BYTES // 1024 // 1024))
    if total_uncompressed > ZIP_RATIO_MIN_UNCOMPRESSED and len(data) > 0:
        ratio = total_uncompressed / len(data)
        if ratio > ZIP_RATIO_LIMIT:
            report.error("zip.ratio", "压缩比 %.1f:1 超过上限 %.0f:1（疑似 zip bomb）" % (ratio, ZIP_RATIO_LIMIT))
        else:
            report.info("zip.ratio", "压缩比 %.2f:1 ≤ %.0f:1" % (ratio, ZIP_RATIO_LIMIT))

    # 根目录必须是 pack.json（大小写敏感）
    if not manifest_present:
        report.error("zip.root_manifest", "zip 根目录没有 pack.json（文件名大小写敏感）")
        # 常见错误：多套了一层目录
        tops = {name.split("/", 1)[0] for name in source.sizes if name != "\x00zip" and "/" in name}
        roots = {name for name in source.sizes if name != "\x00zip" and "/" not in name}
        if not roots and len(tops) == 1:
            report.error("zip.nested_root",
                         "zip 里所有内容都在单层目录 %r 下（多套了一层文件夹，客户端要求根目录直接是包内容）"
                         % next(iter(tops)))
    else:
        report.info("zip.root_manifest", "根目录有 pack.json")

    # 期望 packId（发布链路：清单 id == pack.json id == 下载路径段）
    if expect_id:
        manifest = _load_manifest(source, report)
        if manifest is not None:
            actual = str(manifest.get("id") or "").strip()
            if actual.lower() != expect_id.strip().lower():
                report.error("zip.pack_id_mismatch",
                             "pack.json 的 id（%r）与期望 packId（%r）不一致" % (actual, expect_id))
            else:
                report.info("zip.pack_id", "pack.json id 与期望 packId 一致：%s" % actual)


def _load_manifest(source: PackSource, report: Report) -> Optional[Dict[str, Any]]:
    data = source.read("pack.json")
    if data is None:
        return None
    obj, err, dups = load_json(data)
    if err:
        return None
    if not isinstance(obj, dict):
        return None
    if dups:
        report.warn("manifest.duplicate_keys", "pack.json 有重复键（JSON 语义不明确）：%s" % ", ".join(sorted(set(dups))))
    return obj


# ══════════════════════════════════════════════════════════════════════════
# pack.json 校验（docs/pack-format.md §4）
# ══════════════════════════════════════════════════════════════════════════

def audit_manifest(source: PackSource, report: Report, expect_id: Optional[str],
                   expect_category: Optional[str]) -> Dict[str, Any]:
    report.section("pack.json")
    info: Dict[str, Any] = {"declared": [], "pack_id": "", "version": "", "format_version": None}

    data = source.read("pack.json")
    if data is None:
        report.error("manifest.missing", "包根目录没有 pack.json（整个包会被忽略）")
        return info
    size = source.size("pack.json")
    if size > MAX_MANIFEST_BYTES:
        report.error("manifest.size", "pack.json %d 字节超过上限 %d KB" % (size, MAX_MANIFEST_BYTES // 1024))
    else:
        report.line("  pack.json：%d 字节" % size)

    obj, err, dups = load_json(data)
    if err:
        report.error("manifest.json", "pack.json 解析失败：%s" % err)
        return info
    if not isinstance(obj, dict):
        report.error("manifest.type", "pack.json 顶层必须是 JSON 对象（实际是 %s）" % type(obj).__name__)
        return info
    if dups:
        report.warn("manifest.duplicate_keys", "pack.json 有重复键：%s" % ", ".join(sorted(set(dups))))

    # formatVersion
    fv = obj.get("formatVersion")
    if fv is None:
        report.info("manifest.formatVersion", "未写 formatVersion，按默认 1 处理")
        info["format_version"] = 1
    elif not isinstance(fv, int) or isinstance(fv, bool):
        report.error("manifest.formatVersion", "formatVersion 必须是整数（实际 %r）" % (fv,))
    else:
        info["format_version"] = fv
        if fv > 1:
            report.warn("manifest.formatVersion",
                        "formatVersion=%d 高于本 mod 支持的 1（只警告不拒绝，但可能有字段读不懂）" % fv)
        elif fv < 1:
            report.error("manifest.formatVersion", "formatVersion=%d 非法（必须 ≥1）" % fv)

    # id
    pack_id = obj.get("id")
    if not isinstance(pack_id, str) or not pack_id.strip():
        report.error("manifest.id", "pack.json 缺少 id（后端 validate_pack_zip 会直接拒绝，无法发布）")
        pack_id = ""
    else:
        pack_id = pack_id.strip()
        info["pack_id"] = pack_id
        if not PACK_ID_RE.match(pack_id):
            report.error("manifest.id", "id=%r 不符合 packId 规则 ^[A-Za-z0-9._-]{1,64}$" % pack_id)
        elif pack_id in (".", "..") or pack_id.startswith("."):
            report.error("manifest.id", "id=%r 不能是 . / .. / 点开头（客户端会当临时目录清掉）" % pack_id)
        elif pack_id.lower() in PACK_ID_RESERVED:
            report.error("manifest.id", "id=%r 是保留名（server/ 是客户端下发命名空间）" % pack_id)
        if expect_id and source.kind != "zip" and pack_id.lower() != expect_id.strip().lower():
            report.error("manifest.id_mismatch",
                         "pack.json id（%s）与期望 packId（%s）不一致" % (pack_id, expect_id))

    # version
    version = obj.get("version")
    if version is None:
        report.warn("manifest.version", "未写 version（默认 1.0.0；发布清单里的版本号取自上传参数）")
        version = "1.0.0"
    elif not isinstance(version, str) or not PACK_VERSION_RE.match(version.strip()):
        report.error("manifest.version", "version=%r 不合法（只允许字母/数字/._-，长度 1-64）" % (version,))
        version = str(version)
    else:
        version = version.strip()
    info["version"] = version

    # 可选元数据
    for key, default in (("name", pack_id), ("author", "unknown"), ("license", "unknown")):
        value = obj.get(key)
        if value is None:
            report.info("manifest.%s" % key, "未写 %s（默认 %r）" % (key, default))
        elif not isinstance(value, str):
            report.warn("manifest.%s" % key, "%s 建议用字符串（实际 %s）" % (key, type(value).__name__))

    report.line("  包信息：id=%s version=%s name=%r author=%r license=%r"
                % (info.get("pack_id") or "?",
                   version,
                   obj.get("name", ""),
                   obj.get("author", ""),
                   obj.get("license", "")))

    # cosmetics 数组
    cosmetics = obj.get("cosmetics")
    declared: List[Dict[str, Any]] = []
    if cosmetics is None:
        report.info("manifest.cosmetics", "未写 cosmetics 数组，将自动扫描 cosmetics/*/ 目录")
    elif not isinstance(cosmetics, list):
        report.error("manifest.cosmetics", "cosmetics 必须是数组（实际 %s）" % type(cosmetics).__name__)
    else:
        seen: Dict[str, int] = {}
        for index, entry in enumerate(cosmetics):
            if not isinstance(entry, dict):
                report.error("manifest.cosmetics", "cosmetics[%d] 不是对象（已跳过）" % index)
                continue
            local_id = entry.get("id")
            if not isinstance(local_id, str) or not local_id.strip():
                report.error("manifest.cosmetics", "cosmetics[%d] 缺少 id（这件时装会被跳过）" % index)
                continue
            local_id = local_id.strip()
            if local_id.lower() in seen:
                report.error("manifest.cosmetics",
                             "cosmetics[%d] 的 id=%r 与 cosmetics[%d] 重复（大小写不敏感，后者被忽略）"
                             % (index, local_id, seen[local_id.lower()]))
                continue
            seen[local_id.lower()] = index
            declared.append({"id": local_id, "index": index, "raw": entry})

    info["declared"] = declared
    if declared:
        report.line("  cosmetics 声明：%d 件（%s）"
                    % (len(declared), ", ".join(d["id"] for d in declared)))

    # 期望分类（发布核验用）
    if expect_category:
        cats = []
        for item in declared:
            cat = item["raw"].get("category")
            if isinstance(cat, str) and cat.strip():
                cats.append(cat.strip().lower())
        info["declared_categories"] = cats
        if declared and all(c != expect_category for c in cats):
            report.error("manifest.category_mismatch",
                         "pack.json 声明里没有任何一件 category=%s（实际 %s）"
                         % (expect_category, cats or "未声明"))
    return info


# ══════════════════════════════════════════════════════════════════════════
# 元数据解析（镜像 PackLoader.buildDefinition / 三级优先级）
# ══════════════════════════════════════════════════════════════════════════

def read_meta(source: PackSource, entry_dir: Optional[str], report: Report) -> Optional[Dict[str, Any]]:
    if not entry_dir:
        return None
    path = entry_dir + "/meta.json"
    data = source.read(path)
    if data is None:
        return None
    obj, err, dups = load_json(data)
    if err:
        report.warn("meta.json", "%s 解析失败（已忽略）：%s" % (path, err))
        return None
    if not isinstance(obj, dict):
        report.warn("meta.json", "%s 顶层不是对象（已忽略）" % path)
        return None
    if dups:
        report.warn("meta.json", "%s 有重复键：%s" % (path, ", ".join(sorted(set(dups)))))
    return obj


def merge_meta(base: Optional[Dict[str, Any]], overlay: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    merged: Dict[str, Any] = dict(base or {})
    for key, value in (overlay or {}).items():
        merged[key] = value
    return merged


def first_file(source: PackSource, directory: str, predicate) -> Optional[str]:
    """目录下第一个匹配的文件（镜像 PackLoader.findFirst：名字转小写后匹配，按名排序）。"""
    _, files = source.list_dir(directory)
    candidates = sorted(name for name in files if predicate(name.lower()))
    return (directory + "/" + candidates[0]) if candidates else None


def standard_file(source: PackSource, directory: str, filename: str) -> Optional[str]:
    """目录下指定的标准文件名（镜像 PackLoader.standardFile：精确大小写）。"""
    path = directory + "/" + filename
    return path if source.exists(path) else None


def build_definition(source: PackSource, pack_id: str, entry_dir: Optional[str],
                     meta: Dict[str, Any], fallback_id: str,
                     report: Report) -> Optional[Dict[str, Any]]:
    """把一份元数据解析成时装定义（镜像 PackLoader.buildDefinition，:278-384）。"""
    meta = meta or {}
    context = "%s/%s" % (pack_id or "?", fallback_id)

    raw_id = meta.get("id")
    local_id = (raw_id.strip() if isinstance(raw_id, str) else fallback_id)
    if not local_id:
        report.error("cosmetic.id", "%s：缺少 id，这件时装会被跳过" % context)
        return None
    if any(ch in local_id for ch in ("/", "\\", "\x00")) or local_id in (".", ".."):
        report.error("cosmetic.id", "%s：id 含非法字符（不允许 / \\ NUL 或 . / ..）" % context)
        return None

    definition: Dict[str, Any] = {
        "localId": local_id,
        "entryDir": entry_dir,
        "meta": meta,
        "model": None, "texture": None, "emissive": None, "animation": None,
        "category": "", "attachedBone": "", "hideParts": [], "scale": 1.0,
        "material": "cutout", "animationName": "",
        "declaredPaths": {},
    }

    # ── 模型 ──
    declared_model = meta.get("model")
    if isinstance(declared_model, str) and declared_model.strip():
        definition["declaredPaths"]["model"] = declared_model
        norm, err = normalize_rel_path(declared_model)
        if err:
            report.error("cosmetic.path", "%s：model 路径非法（%s）" % (context, err))
        elif not source.exists(norm):
            report.error("cosmetic.path", "%s：声明的 model 文件不存在：%s" % (context, declared_model))
        else:
            definition["model"] = norm
    if definition["model"] is None and entry_dir:
        definition["model"] = first_file(source, entry_dir, lambda n: n.endswith(".geo.json"))
    if definition["model"] is None:
        report.error("cosmetic.model_missing", "%s：找不到模型文件（*.geo.json），这件时装会被跳过" % context)
        return None

    # ── 主贴图 ──
    declared_texture = meta.get("texture")
    if isinstance(declared_texture, str) and declared_texture.strip():
        definition["declaredPaths"]["texture"] = declared_texture
        norm, err = normalize_rel_path(declared_texture)
        if err:
            report.error("cosmetic.path", "%s：texture 路径非法（%s）" % (context, err))
        elif not source.exists(norm):
            report.error("cosmetic.path", "%s：声明的 texture 文件不存在：%s" % (context, declared_texture))
        else:
            definition["texture"] = norm
    if definition["texture"] is None and entry_dir:
        definition["texture"] = standard_file(source, entry_dir, "texture.png")
        if definition["texture"] is None:
            definition["texture"] = first_file(
                source, entry_dir,
                lambda n: n.endswith(".png") and n != "emissive.png")
    if definition["texture"] is None:
        report.error("cosmetic.texture_missing",
                     "%s：找不到主贴图（texture.png 或第一个非 emissive 的 .png），这件时装会被跳过" % context)
        return None

    # ── emissive ──
    declared_emissive = meta.get("emissive")
    if isinstance(declared_emissive, str) and declared_emissive.strip():
        norm, err = normalize_rel_path(declared_emissive)
        if err:
            report.error("cosmetic.path", "%s：emissive 路径非法（%s）" % (context, err))
        elif source.exists(norm):
            definition["emissive"] = norm
        else:
            report.error("cosmetic.path", "%s：声明的 emissive 文件不存在：%s" % (context, declared_emissive))
    elif entry_dir:
        definition["emissive"] = standard_file(source, entry_dir, "emissive.png")

    # ── 动画 ──
    declared_animation = meta.get("animation")
    if isinstance(declared_animation, str) and declared_animation.strip():
        norm, err = normalize_rel_path(declared_animation)
        if err:
            report.error("cosmetic.path", "%s：animation 路径非法（%s）" % (context, err))
        elif source.exists(norm):
            definition["animation"] = norm
        else:
            report.error("cosmetic.path", "%s：声明的 animation 文件不存在：%s" % (context, declared_animation))
    elif entry_dir:
        definition["animation"] = first_file(source, entry_dir, is_animation_filename)

    # ── 分类 / 骨骼 / hideParts / scale / material / animationName ──
    category_key = meta.get("category")
    if isinstance(category_key, str) and category_key.strip():
        category = category_key.strip().lower()
        if category not in CATEGORY_DEFAULT_BONE:
            report.warn("cosmetic.category",
                        "%s：category=%r 不认识，会回退 other（合法值：%s）"
                        % (context, category_key, "/".join(CATEGORY_DEFAULT_BONE)))
            category = "other"
    else:
        category = guess_category(local_id)
    definition["category"] = category

    bone_raw = meta.get("attachedBone")
    if isinstance(bone_raw, str) and bone_raw.strip():
        bone = bone_raw.strip().lower()
        if bone not in VALID_BONES:
            report.warn("cosmetic.attachedBone",
                        "%s：attachedBone=%r 不认识（渲染层会退回 body 行为）" % (context, bone_raw))
    else:
        bone = CATEGORY_DEFAULT_BONE.get(category, "body")
    definition["attachedBone"] = bone

    hide_raw = meta.get("hideParts")
    hide_parts: List[str] = []
    if hide_raw is not None:
        if not isinstance(hide_raw, list):
            report.warn("cosmetic.hideParts", "%s：hideParts 必须是字符串数组（已忽略）" % context)
        else:
            for item in hide_raw:
                if not isinstance(item, str):
                    report.warn("cosmetic.hideParts", "%s：hideParts 里有非字符串元素（已忽略）" % context)
                    continue
                value = item.strip().lower()
                if value not in VALID_HIDE_PARTS:
                    report.warn("cosmetic.hideParts",
                                "%s：hideParts 里的 %r 不认识，会被忽略" % (context, item))
                    continue
                hide_parts.append(value)
    definition["hideParts"] = hide_parts

    scale_raw = meta.get("scale", 1.0)
    if not finite_number(scale_raw):
        report.warn("cosmetic.scale", "%s：scale=%r 非法（NaN/字符串/缺失），mod 会回退 1.0" % (context, scale_raw))
        scale = 1.0
    else:
        scale = float(scale_raw)
        if scale <= 0.0:
            report.warn("cosmetic.scale", "%s：scale=%r ≤ 0，mod 会回退 1.0" % (context, scale_raw))
            scale = 1.0
    scale = max(0.01, min(100.0, scale))
    definition["scale"] = scale

    material = meta.get("material")
    if material is not None:
        if not isinstance(material, str) or material.strip().lower() not in VALID_MATERIALS:
            report.warn("cosmetic.material",
                        "%s：material=%r 只支持 cutout/translucent（回退 cutout）" % (context, material))
        else:
            definition["material"] = material.strip().lower()

    animation_name = meta.get("animationName")
    if isinstance(animation_name, str) and animation_name.strip():
        definition["animationName"] = animation_name.strip()

    return definition


def guess_category(local_id: str) -> str:
    """镜像 PackLoader.guessCategory（:600-615）。"""
    lowered = local_id.lower()
    if "wing" in lowered:
        return "wings"
    if "cape" in lowered or "cloak" in lowered:
        return "cape"
    if "hat" in lowered or "helm" in lowered or "halo" in lowered:
        return "hat"
    if "pet" in lowered:
        return "pet"
    return "other"


def resolve_cosmetics(source: PackSource, manifest_info: Dict[str, Any], report: Report,
                      expect_category: Optional[str]) -> List[Dict[str, Any]]:
    """镜像 PackLoader.loadPack：声明优先、目录自动发现补齐（:224-259）。"""
    report.section("资源发现（pack.json 声明 vs cosmetics/ 目录）")
    pack_id = manifest_info.get("pack_id") or "?"
    declared = manifest_info.get("declared") or []
    subdirs, _ = source.list_dir("cosmetics")

    report.line("  cosmetics/ 目录：%s" % (", ".join(subdirs) if subdirs else "（不存在）"))

    definitions: List[Dict[str, Any]] = []
    seen: set = set()

    for item in declared:
        local_id = item["id"]
        entry_dir = None
        if local_id in subdirs:
            entry_dir = "cosmetics/" + local_id
        else:
            # 声明 id 与目录名大小写不同：Linux/macOS 上 mod 找不到目录（资源必须显式声明）
            match = [d for d in subdirs if d.lower() == local_id.lower()]
            if match:
                report.warn("discover.case_mismatch",
                            "声明 id=%r 与目录名 %r 大小写不同；mod 在 Linux/macOS 上不会把它当成本件时装的目录"
                            % (local_id, match[0]))
        meta = merge_meta(item["raw"], read_meta(source, entry_dir, report))
        definition = build_definition(source, pack_id, entry_dir, meta, local_id, report)
        if definition is None:
            continue
        if definition["localId"].lower() in seen:
            report.error("discover.duplicate_id",
                         "时装 ID 重复（大小写不敏感）：%s，后一件被跳过" % definition["localId"])
            continue
        seen.add(definition["localId"].lower())
        definition["declared"] = True
        definitions.append(definition)

    for dirname in subdirs:
        entry_dir = "cosmetics/" + dirname
        if dirname.lower() in seen:
            continue
        meta = read_meta(source, entry_dir, report) or {}
        definition = build_definition(source, pack_id, entry_dir, meta, dirname, report)
        if definition is None:
            report.warn("discover.orphan_dir",
                        "目录 %s 没有解析出可用的时装（缺模型/贴图），会被 mod 跳过" % entry_dir)
            continue
        if definition["localId"].lower() in seen:
            report.error("discover.duplicate_id",
                         "时装 ID 重复（大小写不敏感）：%s，后一件被跳过" % definition["localId"])
            continue
        seen.add(definition["localId"].lower())
        definition["declared"] = False
        definitions.append(definition)

    if not definitions:
        report.error("discover.empty", "包里没有任何可用时装（mod 会记「没有解析出任何时装」并跳过整包）")

    # 声明了但没目录、资源也走自动发现 → 必然失败，已经在上面的 build_definition 报错了
    undeclared_dirs = [d for d in subdirs if d.lower() not in {x["localId"].lower() for x in definitions}]
    if undeclared_dirs:
        report.warn("discover.unused_dirs",
                    "以下目录没有被解析成时装（多半缺模型或贴图）：%s" % ", ".join(undeclared_dirs))

    # 期望分类
    if expect_category:
        cats = [d["category"] for d in definitions]
        if definitions and all(c != expect_category for c in cats):
            report.error("catalog.category",
                         "没有任何一件时装的 category 是 %r（实际 %s）" % (expect_category, cats))
        elif definitions:
            report.info("catalog.category", "category 命中期望值 %r" % expect_category)

    return definitions


# ══════════════════════════════════════════════════════════════════════════
# 几何解析与校验
# ══════════════════════════════════════════════════════════════════════════

# ── 4x4 变换用 (3x3 旋转, 平移) 表示：x → R·x + t ──

def mat_identity():
    return ([[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]], [0.0, 0.0, 0.0])


def mat_mul(a, b):
    ra, ta = a
    rb, tb = b
    r = [[sum(ra[i][k] * rb[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
    t = [sum(ra[i][k] * tb[k] for k in range(3)) + ta[i] for i in range(3)]
    return (r, t)


def mat_apply(m, p):
    r, t = m
    return [sum(r[i][k] * p[k] for k in range(3)) + t[i] for i in range(3)]


def rot_x(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return ([[1, 0, 0], [0, c, -s], [0, s, c]], [0.0, 0.0, 0.0])


def rot_y(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return ([[c, 0, s], [0, 1, 0], [-s, 0, c]], [0.0, 0.0, 0.0])


def rot_z(deg):
    c, s = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return ([[c, -s, 0], [s, c, 0], [0, 0, 1]], [0.0, 0.0, 0.0])


def rot_zyx(rotation):
    """Bedrock 旋转顺序 ZYX（GeckoLib RenderUtil.optionalRotateZYX）：R = Rx·Ry·Rz。"""
    rx, ry, rz = rotation
    return mat_mul(rot_x(rx), mat_mul(rot_y(ry), rot_z(rz)))


def pivot_rotation(pivot, rotation):
    """绕 pivot 旋转：T(p) · R · T(-p)。"""
    r, _ = rot_zyx(rotation)
    t = [pivot[i] - sum(r[i][k] * pivot[k] for k in range(3)) for i in range(3)]
    return (r, t)


def cube_effective(origin, size, inflate):
    """inflate 向外扩张立方体（GeckoLib VertexSet 构造时把 inflate 加进 size）。"""
    if not inflate:
        return origin, size
    return ([origin[i] - inflate for i in range(3)],
            [size[i] + 2.0 * inflate for i in range(3)])


def quad_count_of_cube(size) -> int:
    """GeckoLib 会跳过零尺寸面（GeometryCube.isZeroSizeFace），按非零维度计 quad。"""
    dx, dy, dz = size
    count = 0
    if dy > 0 and dz > 0:
        count += 2  # east / west（YZ 面）
    if dx > 0 and dz > 0:
        count += 2  # north / south（XY 面）
    if dx > 0 and dy > 0:
        count += 2  # up / down（XZ 面）
    return count


def uv_boxes_of_cube(cube, texture_width: int, texture_height: int) -> List[Tuple[str, float, float, float, float]]:
    """算出这个 cube 每个面在贴图上的 [u0,v0,u1,v1]（Bedrock box UV 布局）。"""
    origin = vec3(cube.get("origin")) or [0.0, 0.0, 0.0]
    size = vec3(cube.get("size")) or [0.0, 0.0, 0.0]
    dx, dy, dz = size
    uv = cube.get("uv")
    boxes: List[Tuple[str, float, float, float, float]] = []
    if uv is None:
        return boxes

    if isinstance(uv, list) and len(uv) >= 2 and is_number(uv[0]) and is_number(uv[1]):
        u, v = float(uv[0]), float(uv[1])
        # 整块 box：宽 2(dx+dz)，高 (dy+dz)
        boxes.append(("box", u, v, u + 2.0 * (dx + dz), v + dy + dz))
        return boxes

    if isinstance(uv, dict):
        face_size = {
            "north": (dx, dy), "south": (dx, dy),
            "east": (dz, dy), "west": (dz, dy),
            "up": (dx, dz), "down": (dx, dz),
        }
        for face in ("north", "south", "east", "west", "up", "down"):
            value = uv.get(face)
            if value is None:
                continue
            if isinstance(value, list) and len(value) >= 2 and is_number(value[0]) and is_number(value[1]):
                u, v = float(value[0]), float(value[1])
                w, h = face_size[face]
            elif isinstance(value, dict):
                pair = value.get("uv")
                if not (isinstance(pair, list) and len(pair) >= 2):
                    continue
                u, v = float(pair[0]), float(pair[1])
                uv_size = value.get("uv_size")
                if isinstance(uv_size, list) and len(uv_size) >= 2 and all(is_number(x) for x in uv_size[:2]):
                    w, h = float(uv_size[0]), float(uv_size[1])
                else:
                    w, h = face_size[face]
            else:
                continue
            boxes.append((face, u, v, u + w, v + h))
    return boxes


def audit_geometry(source: PackSource, definition: Dict[str, Any], report: Report) -> Dict[str, Any]:
    """模型几何审计：格式 / 骨骼树 / cube / quad / UV / 包围盒。"""
    model_path = definition["model"]
    metrics: Dict[str, Any] = {"model": model_path, "bytes": source.size(model_path)}
    report.line("  模型：%s（%d 字节）" % (model_path, metrics["bytes"]))

    if metrics["bytes"] > MAX_MODEL_BYTES:
        report.error("model.size", "模型文件 %d 字节超过 4MB 上限（PackLoader.MAX_MODEL_BYTES，会被跳过）"
                     % metrics["bytes"])

    data = source.read(model_path)
    if data is None:
        report.error("model.read", "读不到模型文件内容：%s" % model_path)
        return metrics
    obj, err, dups = load_json(data)
    if err:
        report.error("model.json", "模型不是合法 JSON：%s" % err)
        return metrics
    if not isinstance(obj, dict):
        report.error("model.json", "模型顶层必须是 JSON 对象")
        return metrics
    if dups:
        report.warn("model.duplicate_keys", "模型 JSON 有重复键：%s" % ", ".join(sorted(set(dups))))

    # format_version
    fmt = obj.get("format_version")
    metrics["formatVersion"] = fmt
    if fmt is None:
        report.error("model.format_version", "模型缺少 format_version（必须是 %r）" % SUPPORTED_GEO_FORMAT)
    elif fmt != SUPPORTED_GEO_FORMAT:
        hint = ""
        if fmt in GECKOLIB_KNOWN_GEO_FORMATS:
            hint = "（GeckoLib 也认这个版本，但 VMFashion 契约只保证 1.12.0 的语义）"
        report.error("model.format_version",
                     "format_version=%r 不是 %r%s" % (fmt, SUPPORTED_GEO_FORMAT, hint))
    else:
        report.info("model.format_version", "format_version = %r" % fmt)

    geometry = obj.get("minecraft:geometry")
    if geometry is None:
        report.error("model.geometry", "缺少 minecraft:geometry（不是 Bedrock 几何，烘焙会失败）")
        return metrics
    if not isinstance(geometry, list) or not geometry:
        report.error("model.geometry", "minecraft:geometry 必须是非空数组")
        return metrics
    if len(geometry) > 1:
        report.warn("model.geometry",
                    "文件里有 %d 个 geometry 定义，mod 只取第一个（其余会被忽略）" % len(geometry))
    definition_json = geometry[0]
    if not isinstance(definition_json, dict):
        report.error("model.geometry", "minecraft:geometry[0] 不是对象")
        return metrics

    description = definition_json.get("description")
    if not isinstance(description, dict):
        report.error("model.description", "缺少 description 对象（texture_width/texture_height 在这里）")
        description = {}
    identifier = description.get("identifier")
    metrics["identifier"] = identifier
    if not isinstance(identifier, str) or not identifier.strip():
        report.warn("model.description", "description.identifier 缺失（不影响加载，但不利于排查）")
    texture_width = description.get("texture_width")
    texture_height = description.get("texture_height")
    if not isinstance(texture_width, int) or isinstance(texture_width, bool) or texture_width <= 0:
        report.error("model.texture_size", "description.texture_width 缺失或非法：%r" % (texture_width,))
        texture_width = 0
    if not isinstance(texture_height, int) or isinstance(texture_height, bool) or texture_height <= 0:
        report.error("model.texture_size", "description.texture_height 缺失或非法：%r" % (texture_height,))
        texture_height = 0
    metrics["textureWidth"] = texture_width
    metrics["textureHeight"] = texture_height

    bones = definition_json.get("bones")
    if not isinstance(bones, list) or not bones:
        report.error("model.bones", "缺少 bones 数组或为空（烘焙不出任何骨骼）")
        return metrics

    # ── 骨骼树 ──
    bone_map: Dict[str, Dict[str, Any]] = {}
    for index, bone in enumerate(bones):
        if not isinstance(bone, dict):
            report.error("model.bones", "bones[%d] 不是对象" % index)
            continue
        name = bone.get("name")
        if not isinstance(name, str) or not name.strip():
            report.error("model.bones", "bones[%d] 缺少 name" % index)
            continue
        key = name.strip().lower()
        if key in bone_map:
            report.error("model.bones", "骨骼名重复：%r（大小写不敏感）" % name)
            continue
        bone_map[key] = bone

    if not bone_map:
        report.error("model.bones", "没有可用骨骼")
        return metrics

    # 父骨骼存在性 + 环检测
    children: Dict[str, List[str]] = {key: [] for key in bone_map}
    roots: List[str] = []
    for key, bone in bone_map.items():
        parent = bone.get("parent")
        if parent is None or (isinstance(parent, str) and not parent.strip()):
            roots.append(key)
            continue
        if not isinstance(parent, str):
            report.error("model.bones", "骨骼 %r 的 parent 不是字符串：%r" % (key, parent))
            roots.append(key)
            continue
        parent_key = parent.strip().lower()
        if parent_key not in bone_map:
            report.error("model.bones", "骨骼 %r 的 parent=%r 不存在（GeckoLib 会当根骨骼处理，层级可能不对）"
                         % (key, parent))
            roots.append(key)
        else:
            children[parent_key].append(key)

    # 环检测（从根做 DFS，统计可达节点）
    reachable: set = set()

    def walk(node: str, stack: set) -> None:
        if node in stack:
            report.error("model.bones", "骨骼父子关系成环：%s" % " → ".join(list(stack) + [node]))
            return
        if node in reachable:
            return
        reachable.add(node)
        for child in children.get(node, []):
            walk(child, stack | {node})

    for root in roots:
        walk(root, set())
    if len(reachable) != len(bone_map):
        missing = sorted(set(bone_map) - reachable)
        report.error("model.bones", "有 %d 根骨骼从根不可达（父子关系成环或父骨骼缺失）：%s"
                     % (len(missing), ", ".join(missing[:10])))

    # ── cube / quad / UV / 包围盒 ──
    cube_count = 0
    quad_count = 0
    uv_violations: List[str] = []
    uv_missing: List[str] = []
    cube_boxes: List[Tuple[str, List[float], List[float]]] = []
    bbox_min = [float("inf")] * 3
    bbox_max = [float("-inf")] * 3
    naive_min = [float("inf")] * 3
    naive_max = [float("-inf")] * 3
    has_rotation = False
    cube_rotations = 0

    def bone_transform(key: str, cache: Dict[str, Any], stack: set) -> Any:
        if key in cache:
            return cache[key]
        bone = bone_map[key]
        pivot = vec3(bone.get("pivot")) or [0.0, 0.0, 0.0]
        rotation = vec3(bone.get("rotation"))
        if rotation and any(abs(v) > 1e-9 for v in rotation):
            nonlocal has_rotation
            has_rotation = True
            local = pivot_rotation(pivot, rotation)
        else:
            # 无旋转时 T(pivot)·T(-pivot) = 单位矩阵：骨骼 pivot 本身不产生位移
            local = mat_identity()
        parent = bone.get("parent")
        parent_key = parent.strip().lower() if isinstance(parent, str) else ""
        if parent_key and parent_key in bone_map and parent_key not in stack:
            world = mat_mul(bone_transform(parent_key, cache, stack | {key}), local)
        else:
            world = local
        cache[key] = world
        return world

    transform_cache: Dict[str, Any] = {}

    for key, bone in bone_map.items():
        cubes = bone.get("cubes")
        if cubes is None:
            continue
        if not isinstance(cubes, list):
            report.error("model.cubes", "骨骼 %r 的 cubes 不是数组" % key)
            continue
        world = bone_transform(key, transform_cache, set())
        for cube_index, cube in enumerate(cubes):
            if not isinstance(cube, dict):
                report.error("model.cubes", "骨骼 %r 的 cubes[%d] 不是对象" % (key, cube_index))
                continue
            cube_count += 1
            origin = vec3(cube.get("origin"))
            size = vec3(cube.get("size"))
            if origin is None:
                report.error("model.cubes", "骨骼 %r 的 cubes[%d] origin 非法：%r"
                             % (key, cube_index, cube.get("origin")))
                continue
            if size is None:
                report.error("model.cubes", "骨骼 %r 的 cubes[%d] size 非法：%r"
                             % (key, cube_index, cube.get("size")))
                continue
            if any(v < 0 for v in size):
                report.error("model.cubes", "骨骼 %r 的 cubes[%d] size 含负值：%r（几何会翻面）"
                             % (key, cube_index, size))
            inflate = cube.get("inflate")
            inflate_value = float(inflate) if finite_number(inflate) else 0.0
            eff_origin, eff_size = cube_effective(origin, size, inflate_value)
            quad_count += quad_count_of_cube(eff_size)

            # UV 校验（用未 inflate 的尺寸，与 Blockbench 一致）
            boxes = uv_boxes_of_cube(cube, texture_width, texture_height)
            if not boxes:
                uv_missing.append("骨骼 %r cubes[%d]" % (key, cube_index))
            for face, u0, v0, u1, v1 in boxes:
                if texture_width and texture_height:
                    if u0 < -1e-6 or v0 < -1e-6 or u1 > texture_width + 1e-6 or v1 > texture_height + 1e-6:
                        uv_violations.append(
                            "骨骼 %r cubes[%d] 面 %s：u[%.2f, %.2f] v[%.2f, %.2f] 超出 %dx%d"
                            % (key, cube_index, face, u0, u1, v0, v1, texture_width, texture_height))

            # 包围盒（cube 自身旋转很少见，单独处理）
            cube_world = world
            rotation = vec3(cube.get("rotation"))
            if rotation and any(abs(v) > 1e-9 for v in rotation):
                cube_rotations += 1
                pivot = vec3(cube.get("pivot")) or [origin[i] + size[i] / 2.0 for i in range(3)]
                cube_world = mat_mul(world, pivot_rotation(pivot, rotation))

            cube_min = [float("inf")] * 3
            cube_max = [float("-inf")] * 3
            for sx in (0, 1):
                for sy in (0, 1):
                    for sz in (0, 1):
                        corner = [eff_origin[0] + eff_size[0] * sx,
                                  eff_origin[1] + eff_size[1] * sy,
                                  eff_origin[2] + eff_size[2] * sz]
                        point = mat_apply(cube_world, corner)
                        for axis in range(3):
                            bbox_min[axis] = min(bbox_min[axis], point[axis])
                            bbox_max[axis] = max(bbox_max[axis], point[axis])
                            cube_min[axis] = min(cube_min[axis], point[axis])
                            cube_max[axis] = max(cube_max[axis], point[axis])
                        for axis in range(3):
                            naive_min[axis] = min(naive_min[axis], corner[axis])
                            naive_max[axis] = max(naive_max[axis], corner[axis])
            cube_boxes.append((key, cube_min, cube_max))

    metrics.update({
        "bones": len(bone_map),
        "roots": len(roots),
        "cubes": cube_count,
        "quads": quad_count,
        "uvViolations": uv_violations,
        "uvMissing": uv_missing,
    })

    report.line("  骨骼：%d 根（根骨骼 %d，名称：%s）"
                % (len(bone_map), len(roots), ", ".join(sorted(bone_map)[:12])
                   + ("…" if len(bone_map) > 12 else "")))
    report.line("  几何：%d 个 cube / %d 个 quad（上限 %d）" % (cube_count, quad_count, MAX_QUADS))

    if quad_count > MAX_QUADS:
        report.error("model.quads",
                     "quad 数 %d 超过上限 %d（CosmeticAssets.MAX_QUADS，模型会被跳过）"
                     % (quad_count, MAX_QUADS))
    if cube_count == 0:
        report.error("model.cubes", "模型没有任何 cube（渲染不出东西）")
    if uv_missing:
        report.warn("model.uv_missing", "%d 个 cube 没有可解析的 uv（会贴到贴图左上角）" % len(uv_missing),
                    "\n".join(uv_missing[:10]))
    if uv_violations:
        report.error("model.uv_bounds", "%d 个面的 UV 超出 texture_width/height（贴图会错位）"
                     % len(uv_violations), "\n".join(uv_violations[:20]))

    if cube_count:
        metrics["bbox"] = {"min": bbox_min, "max": bbox_max}
        metrics["bboxNaive"] = {"min": naive_min, "max": naive_max}
        report.line("  包围盒（模型单位）：x%s y%s z%s"
                    % (fmt_vec([bbox_min[0], bbox_max[0]]),
                       fmt_vec([bbox_min[1], bbox_max[1]]),
                       fmt_vec([bbox_min[2], bbox_max[2]])))
        if has_rotation or cube_rotations:
            report.line("  注：骨骼/cube 带静态旋转（骨骼 %s，cube %d 个），包围盒按旋转后的世界坐标计算"
                        % ("有" if has_rotation else "无", cube_rotations))
        audit_body_overlap(cube_boxes, definition, report, metrics)

    # ── 贴图尺寸一致性 ──
    audit_texture(source, definition, metrics, report, texture_width, texture_height)

    # ── 位置合理性 ──
    if cube_count:
        check_position(definition, metrics["bbox"], report)

    return metrics


def audit_texture(source: PackSource, definition: Dict[str, Any], metrics: Dict[str, Any],
                  report: Report, texture_width: int, texture_height: int) -> None:
    """主贴图 / emissive：PNG 合法性 + 尺寸与 texture_width/height 一致（§3.2 / §8）。"""
    texture_path = definition["texture"]
    data = source.read(texture_path)
    if data is None:
        report.error("texture.read", "读不到主贴图：%s" % texture_path)
        return
    header, err = png_header(data)
    if err:
        report.error("texture.png", "主贴图 %s 不是合法 PNG：%s（mod 会加载失败）" % (texture_path, err))
        return
    metrics["texture"] = {"path": texture_path, "width": header["width"],
                          "height": header["height"], "bytes": len(data),
                          "colorType": header["color_type"]}
    report.line("  贴图：%s（%dx%d，%d 字节，%s）"
                % (texture_path, header["width"], header["height"], len(data),
                   PNG_COLOR_TYPE_NAMES.get(header["color_type"], "未知")))
    if not png_has_alpha(header):
        report.warn("texture.alpha",
                    "主贴图没有 alpha 通道（color_type=%d %s）；cutout 材质会退化为全不透明"
                    % (header["color_type"], PNG_COLOR_TYPE_NAMES.get(header["color_type"], "?")))
    stats = png_alpha_stats(data)
    if stats is not None:
        metrics["texture"]["opaquePixels"] = stats["opaque"]
        metrics["texture"]["totalPixels"] = stats["total"]
        if stats["opaque"] == 0:
            report.warn("texture.transparent",
                        "主贴图整张全透明（alpha>8 的像素 0/%d），穿上后什么都看不到" % stats["total"])
        else:
            report.info("texture.opaque",
                        "主贴图不透明像素 %d/%d（%.1f%%）"
                        % (stats["opaque"], stats["total"], 100.0 * stats["opaque"] / stats["total"]))
    if texture_width and texture_height:
        if (header["width"], header["height"]) != (texture_width, texture_height):
            report.error("texture.size_mismatch",
                         "贴图实际 %dx%d 与模型 texture_width/height %dx%d 不一致（贴图会错位）"
                         % (header["width"], header["height"], texture_width, texture_height))
        else:
            report.info("texture.size_match", "贴图尺寸 %dx%d 与 texture_width/height 一致"
                        % (header["width"], header["height"]))

    emissive_path = definition.get("emissive")
    if not emissive_path:
        return
    emissive_data = source.read(emissive_path)
    if emissive_data is None:
        report.error("emissive.read", "读不到发光层贴图：%s" % emissive_path)
        return
    emissive_header, emissive_err = png_header(emissive_data)
    if emissive_err:
        report.error("emissive.png", "发光层 %s 不是合法 PNG：%s" % (emissive_path, emissive_err))
        return
    metrics["emissive"] = {"path": emissive_path, "width": emissive_header["width"],
                           "height": emissive_header["height"], "bytes": len(emissive_data)}
    report.line("  发光层：%s（%dx%d）" % (emissive_path, emissive_header["width"], emissive_header["height"]))
    if (emissive_header["width"], emissive_header["height"]) != (header["width"], header["height"]):
        report.error("emissive.size_mismatch",
                     "发光层 %dx%d 与主贴图 %dx%d 尺寸不一致（§8 要求完全一致）"
                     % (emissive_header["width"], emissive_header["height"],
                        header["width"], header["height"]))
    if not png_has_alpha(emissive_header):
        report.warn("emissive.alpha",
                    "发光层没有 alpha 通道（color_type=%d）：所有被 UV 覆盖的面都会发光"
                    % emissive_header["color_type"])
    emissive_stats = png_alpha_stats(emissive_data)
    if emissive_stats is not None:
        metrics["emissive"]["opaquePixels"] = emissive_stats["opaque"]
        metrics["emissive"]["totalPixels"] = emissive_stats["total"]
        if emissive_stats["opaque"] == 0:
            report.warn("emissive.empty",
                        "发光层整张全透明（alpha>8 的像素 0/%d）：等于没有发光层，不会产生任何发光效果"
                        % emissive_stats["total"])
        else:
            report.info("emissive.opaque",
                        "发光层不透明像素 %d/%d（%.1f%%）"
                        % (emissive_stats["opaque"], emissive_stats["total"],
                           100.0 * emissive_stats["opaque"] / emissive_stats["total"]))


# 原版玩家各部位在模型空间（脚底原点 / Y 向上 / 1/16 格）的包围盒：
# 头 8x8x8 @ y24..32，身体 8x12x4 @ y12..24，手臂 4x12x4 @ x±4..±8，腿 4x12x4 @ y0..12。
# 用来回答「模型有没有埋进玩家身体里」（hideParts 没设时最容易出问题的点）。
VANILLA_PART_BOXES = {
    "head": ([-4.0, 24.0, -4.0], [4.0, 32.0, 4.0]),
    "body": ([-4.0, 12.0, -2.0], [4.0, 24.0, 2.0]),
    "right_arm": ([-8.0, 12.0, -2.0], [-4.0, 24.0, 2.0]),
    "left_arm": ([4.0, 12.0, -2.0], [8.0, 24.0, 2.0]),
    "right_leg": ([-4.0, 0.0, -2.0], [0.0, 12.0, 2.0]),
    "left_leg": ([0.0, 0.0, -2.0], [4.0, 12.0, 2.0]),
}


def audit_body_overlap(cube_boxes, definition: Dict[str, Any], report: Report,
                       metrics: Dict[str, Any]) -> None:
    """统计有多少 cube 埋进原版玩家身体、最大穿入深度（INFO，不判失败）。

    hideParts 为空时这是最值得看的一个数：穿入越深，越容易在背部/肩部出现
    「模型被身体切掉一块」的观感。
    """
    if not cube_boxes:
        return
    hit_cubes = 0
    worst = 0.0
    worst_desc = ""
    for bone_name, cmin, cmax in cube_boxes:
        hit = False
        for part, (pmin, pmax) in VANILLA_PART_BOXES.items():
            overlaps = []
            for axis in range(3):
                lo = max(cmin[axis], pmin[axis])
                hi = min(cmax[axis], pmax[axis])
                if hi <= lo:
                    overlaps = []
                    break
                overlaps.append(hi - lo)
            if overlaps:
                hit = True
                depth = min(overlaps)
                if depth > worst:
                    worst = depth
                    worst_desc = "%s 与 %s" % (bone_name, part)
        if hit:
            hit_cubes += 1
    if hit_cubes:
        metrics["bodyOverlapCubes"] = hit_cubes
        metrics["bodyOverlapWorst"] = round(worst, 2)
        report.info("position.body_overlap",
                    "%d/%d 个 cube 与原版玩家体积相交，最大穿入 %.2f 单位（%s）；"
                    "hideParts=%s 时被身体挡住的部分不可见，留意接缝"
                    % (hit_cubes, len(cube_boxes), worst, worst_desc,
                       definition.get("hideParts") or "[]"))


# 分类 → 期望位置（docs/pack-format.md §3.4 坐标系速查，单位 1/16 格）
POSITION_RULES = {
    "wings": {"y": (8.0, 36.0), "z_min": 0.0, "symmetric": True, "label": "翅膀"},
    "cape": {"y": (8.0, 34.0), "z_min": 0.0, "symmetric": True, "label": "披风"},
    "back": {"y": (6.0, 34.0), "z_min": 0.0, "symmetric": True, "label": "背饰"},
    "hat": {"y": (26.0, 44.0), "z_min": -16.0, "symmetric": True, "label": "头饰"},
    "pet": {"y": (-2.0, 16.0), "z_min": -16.0, "symmetric": False, "label": "宠物"},
}


def check_position(definition: Dict[str, Any], bbox: Dict[str, List[float]], report: Report) -> None:
    """位置合理性（软约束）：背后 z>0、肩高附近、左右对称。"""
    category = definition["category"]
    rule = POSITION_RULES.get(category)
    if not rule:
        return
    minv, maxv = bbox["min"], bbox["max"]
    label = rule["label"]
    width = maxv[0] - minv[0]

    # 背后：z > 0（翅膀/披风/背饰）
    if category in ("wings", "cape", "back"):
        if maxv[2] <= 0.0:
            report.error("position.behind",
                         "%s的几何全部在 z≤0（正面）：docs §3.1 规定 +Z 是背后，模型会显示在胸前"
                         % label)
        elif minv[2] < -0.01:
            report.warn("position.behind",
                        "%s有部分几何越过 z=0 到正面（z_min=%.2f）" % (label, minv[2]))

    # 高度区间
    low, high = rule["y"]
    if maxv[1] < low or minv[1] > high:
        report.warn("position.height",
                    "%s的高度 y[%.1f, %.1f] 与建议区间 [%.0f, %.0f] 没有交集（脚底=0、脖子=24、头顶=32）"
                    % (label, minv[1], maxv[1], low, high))
    elif category in ("wings", "cape", "back") and maxv[1] < 20.0:
        report.warn("position.height",
                    "%s最高点 y=%.1f 低于肩部（24），穿上后会贴在腰以下" % (label, maxv[1]))

    # 左右对称
    if rule["symmetric"] and width > 0.5:
        center = (minv[0] + maxv[0]) / 2.0
        tolerance = max(1.0, 0.05 * width)
        if abs(center) > tolerance:
            report.warn("position.symmetry",
                        "%s左右不对称：x 中心 %.2f（宽度 %.1f，容差 %.1f）；确认不是单边模型"
                        % (label, center, width, tolerance))
        else:
            report.info("position.symmetry", "%s左右对称（x 中心 %.2f，宽度 %.1f）" % (label, center, width))

    # 尺寸合理性
    for axis, name in enumerate("xyz"):
        span = maxv[axis] - minv[axis]
        if span > 96.0:
            report.warn("position.size",
                        "%s在 %s 轴跨度 %.1f 单位（> 6 格），可能过大" % (label, name, span))


def audit_style_hints(definition: Dict[str, Any], report: Report) -> None:
    """非契约、但影响观感/穿模的提示（docs/pack-format.md §7.3 / §4.2 material）。"""
    category = definition["category"]
    hide_parts = set(definition.get("hideParts") or [])
    if category in ("wings", "cape", "back") and "body" not in hide_parts and "all" not in hide_parts:
        report.info("style.hide_parts",
                    "%s没有 hideParts:[\"body\"]，贴身穿戴时可能与躯干/披风层穿模（§7.3 建议写法）" % category)
    if category == "wings" and definition.get("material") == "cutout":
        report.info("style.material",
                    "翅膀用的是 cutout 材质（alpha 二值）；如果贴图有半透明膜结构，建议 material: \"translucent\"")
    if category == "hat" and "head" not in hide_parts and "all" not in hide_parts:
        report.info("style.hide_parts", "头饰没有 hideParts:[\"head\"]，可能会盖住原版头发/头盔（按需设置）")


# ══════════════════════════════════════════════════════════════════════════
# 动画校验（docs/pack-format.md §9 / BedrockAnimations.java）
# ══════════════════════════════════════════════════════════════════════════

def audit_animation(source: PackSource, definition: Dict[str, Any],
                    model_bone_names: set, report: Report) -> Dict[str, Any]:
    """anim.json：骨骼名必须对得上模型（大小写不敏感）、关键帧必须是纯数字。"""
    path = definition.get("animation")
    metrics: Dict[str, Any] = {"path": path}
    if not path:
        report.line("  动画：（无）")
        return metrics

    data = source.read(path)
    if data is None:
        report.error("anim.read", "读不到动画文件：%s" % path)
        return metrics
    report.line("  动画：%s（%d 字节）" % (path, len(data)))
    obj, err, dups = load_json(data)
    if err:
        report.error("anim.json", "动画文件不是合法 JSON：%s（模型仍可用，动画会被忽略）" % err)
        return metrics
    if not isinstance(obj, dict):
        report.error("anim.json", "动画文件顶层必须是 JSON 对象")
        return metrics
    if dups:
        report.warn("anim.duplicate_keys", "动画 JSON 有重复键：%s" % ", ".join(sorted(set(dups))))

    fmt = obj.get("format_version")
    if fmt is None:
        report.warn("anim.format_version", "动画文件没有 format_version（建议 \"1.8.0\"）")
    elif fmt != "1.8.0":
        report.info("anim.format_version", "动画 format_version=%r（Blockbench 常见 1.8.0）" % fmt)

    animations = obj.get("animations")
    if not isinstance(animations, dict) or not animations:
        report.error("anim.animations", "缺少 animations 对象或为空（动画不会被播放）")
        return metrics

    metrics["animations"] = list(animations.keys())
    report.line("  动画条目：%s" % ", ".join(animations.keys()))

    unknown_bones: List[str] = []
    bad_keyframes: List[str] = []
    non_numeric: List[str] = []
    animated_bones: set = set()

    for anim_name, anim in animations.items():
        if not isinstance(anim, dict):
            report.error("anim.animations", "动画 %r 不是对象" % anim_name)
            continue
        length = anim.get("animation_length")
        if length is not None and not finite_number(length):
            report.warn("anim.length", "动画 %r 的 animation_length 不是数字：%r" % (anim_name, length))
        elif isinstance(length, (int, float)) and length <= 0:
            report.warn("anim.length", "动画 %r 的 animation_length=%r ≤ 0" % (anim_name, length))
        loop = anim.get("loop")
        if loop is not None and not isinstance(loop, bool):
            report.warn("anim.loop", "动画 %r 的 loop 不是布尔值：%r" % (anim_name, loop))

        bones = anim.get("bones")
        if not isinstance(bones, dict) or not bones:
            report.warn("anim.bones", "动画 %r 没有 bones 通道（播放不出效果）" % anim_name)
            continue
        for bone_name, channels in bones.items():
            key = bone_name.lower()
            animated_bones.add(key)
            if key not in model_bone_names:
                unknown_bones.append("%s（动画 %r）" % (bone_name, anim_name))
            if not isinstance(channels, dict):
                report.warn("anim.bones", "动画 %r 骨骼 %r 的通道不是对象" % (anim_name, bone_name))
                continue
            for channel, value in channels.items():
                if channel not in ANIM_CHANNELS:
                    if channel not in ("timeline", "particle_effects", "sound_effects"):
                        report.info("anim.channel",
                                    "动画 %r 骨骼 %r 有非标准通道 %r（会被忽略）"
                                    % (anim_name, bone_name, channel))
                    continue
                problem = _track_problem(value)
                if problem == "bad":
                    bad_keyframes.append("%s/%s.%s" % (anim_name, bone_name, channel))
                elif problem == "non_numeric":
                    non_numeric.append("%s/%s.%s" % (anim_name, bone_name, channel))

    if unknown_bones:
        report.error("anim.bone_match",
                     "动画里有 %d 个骨骼名在模型中不存在（会被忽略、动画不生效）：%s"
                     % (len(unknown_bones), ", ".join(unknown_bones[:10])))
    elif animated_bones:
        report.info("anim.bone_match", "%d 个动画骨骼全部能在模型里找到" % len(animated_bones))

    if non_numeric:
        report.warn("anim.keyframe_type",
                    "有 %d 条轨道用了非纯数字关键帧（molang/字符串，mod 会跳过这些关键帧）：%s"
                    % (len(non_numeric), ", ".join(non_numeric[:10])))
    if bad_keyframes:
        report.error("anim.keyframe",
                     "有 %d 条轨道的值不是长度 3 的数字数组（整条轨道会被丢弃）：%s"
                     % (len(bad_keyframes), ", ".join(bad_keyframes[:10])))

    # animationName 是否命中
    wanted = definition.get("animationName")
    if wanted:
        if wanted in animations:
            report.info("anim.name", "animationName=%r 命中" % wanted)
        else:
            report.warn("anim.name",
                        "animationName=%r 不在动画列表里（会回退播第一个并记一次警告）" % wanted)

    metrics["animatedBones"] = sorted(animated_bones)
    return metrics


def _track_problem(value: Any) -> Optional[str]:
    """检查一条通道（rotation/position/scale）的写法。返回 None / "bad" / "non_numeric"。"""
    if value is None:
        return None
    if isinstance(value, list):
        if len(value) < 3:
            return "bad"
        for item in value[:3]:
            if not finite_number(item):
                return "non_numeric" if isinstance(item, str) else "bad"
        return None
    if isinstance(value, dict):
        worst: Optional[str] = None
        for key, frame in value.items():
            try:
                time_value = float(str(key).strip())
                if not math.isfinite(time_value) or time_value < 0:
                    return "bad"
            except (TypeError, ValueError):
                return "bad"
            target = frame
            if isinstance(frame, dict):
                target = frame.get("post", frame.get("pre"))
            if not isinstance(target, list) or len(target) < 3:
                return "bad"
            for item in target[:3]:
                if not finite_number(item):
                    worst = "non_numeric" if isinstance(item, str) else "bad"
        return worst
    return "bad"


# ══════════════════════════════════════════════════════════════════════════
# 发布契约（cosmeticId / catalog 字段）
# ══════════════════════════════════════════════════════════════════════════

def audit_publish_contract(manifest_info: Dict[str, Any], definitions: List[Dict[str, Any]],
                           report: Report) -> None:
    """cosmeticId 必须是 ``<packId>:<localId>``（docs/fashion-api.md §3，后端 validate_cosmetic_id）。"""
    report.section("发布契约（cosmeticId / catalog 字段）")
    pack_id = manifest_info.get("pack_id") or ""
    if not pack_id:
        report.warn("catalog.pack_id", "packId 为空，无法校验 cosmeticId 前缀")
        return
    for definition in definitions:
        local_id = definition["localId"]
        cosmetic_id = "%s:%s" % (pack_id, local_id)
        definition["cosmeticId"] = cosmetic_id
        if not COSMETIC_ID_RE.match(cosmetic_id):
            report.error("catalog.cosmetic_id",
                         "cosmeticId=%r 不符合后端 COSMETIC_ID_RE（^[A-Za-z0-9._:-]{1,128}$）" % cosmetic_id)
        if not LOCAL_ID_RE.match(local_id):
            report.error("catalog.local_id",
                         "localId=%r 不符合后端 LOCAL_ID_RE（^[A-Za-z0-9._-]{1,96}$），管理端会拒绝写入目录"
                         % local_id)
        if not cosmetic_id.lower().startswith(pack_id.lower() + ":"):
            report.error("catalog.prefix", "cosmeticId 前缀不是 packId：%r" % cosmetic_id)
        report.line("  cosmeticId = %s（category=%s，attachedBone=%s）"
                    % (cosmeticId_label(cosmetic_id), definition["category"], definition["attachedBone"]))

    report.info("catalog.asset_key",
                "前端缩略图要求 assetKey 指向 PNG（管理端上传单件资产时用）；本脚本只能校验包内 texture.png，"
                "线上条目需另行 GET /api/fashion/catalog 核对")


def cosmeticId_label(value: str) -> str:
    return value


# ══════════════════════════════════════════════════════════════════════════
# 文件清单 / 体积
# ══════════════════════════════════════════════════════════════════════════

def audit_files(source: PackSource, definitions: List[Dict[str, Any]], report: Report) -> None:
    report.section("文件清单与体积")
    names = sorted(name for name in source.sizes if name != "\x00zip")
    total = sum(source.size(name) for name in names)
    report.line("  共 %d 个文件，合计 %d 字节（%.1f KB）" % (len(names), total, total / 1024.0))
    for name in names:
        size = source.size(name)
        flag = ""
        if size > ZIP_MAX_ENTRY_BYTES:
            flag = "  ← 超过单文件 %d MB 上限" % (ZIP_MAX_ENTRY_BYTES // 1024 // 1024)
        elif name.lower().endswith(".geo.json") and size > MAX_MODEL_BYTES:
            flag = "  ← 超过模型 4 MB 上限"
        report.line("    %-52s %9d 字节%s" % (name, size, flag))

    if source.kind == "zip":
        report.info("files.total", "解压后合计 %d 字节 ≤ %d MB" % (total, ZIP_MAX_TOTAL_BYTES // 1024 // 1024))
    else:
        if total > ZIP_MAX_TOTAL_BYTES:
            report.error("files.total", "包总大小 %d 字节超过 zip 解压上限 %d MB，打成 zip 后会被客户端拒绝"
                         % (total, ZIP_MAX_TOTAL_BYTES // 1024 // 1024))

    if source.symlinks:
        report.error("files.symlink", "包里有符号链接（mod 与后端都拒绝）：%s" % ", ".join(source.symlinks[:10]))

    if source.kind == "dir":
        hidden = [name for name in names if is_hidden_path(name)]
        if hidden:
            report.warn("files.hidden",
                        "包里有隐藏文件（点开头，打成 zip 后后端会拒绝整个包）：%s" % ", ".join(hidden[:10]))
        forbidden = [name for name in names if os.path.splitext(name)[1].lower() in FORBIDDEN_EXTS]
        if forbidden:
            report.error("files.forbidden_ext",
                         "包里有可执行/脚本后缀文件（后端会拒绝）：%s" % ", ".join(forbidden[:10]))
        bad_names = []
        for name in names:
            problem = _entry_name_problem(name)
            if problem:
                bad_names.append("%s → %s" % (name, problem))
        if bad_names:
            report.warn("files.name", "有文件名在打包成 zip 时会被后端拒绝：", "\n".join(bad_names[:10]))

    # 未被引用的文件（info）
    referenced = set()
    for definition in definitions:
        for key in ("model", "texture", "emissive", "animation"):
            if definition.get(key):
                referenced.add(definition[key])
        if definition.get("entryDir"):
            referenced.add(definition["entryDir"] + "/meta.json")
    referenced.add("pack.json")
    unused = [name for name in names if name not in referenced]
    if unused:
        report.info("files.unused", "未被引用的文件（可能是有意保留的素材）：%s"
                    % ", ".join(unused[:15]) + ("…" if len(unused) > 15 else ""))


# ══════════════════════════════════════════════════════════════════════════
# 两个包源的内容比对（发布核验：本地 zip / 线上下载 / 本地目录）
# ══════════════════════════════════════════════════════════════════════════

def compare_sources(primary: PackSource, other: PackSource, report: Report,
                    other_label: str) -> Dict[str, Any]:
    report.section("内容比对（%s）" % other_label)
    left = {name: sha256(data) for name, data in primary.files.items()}
    right = {name: sha256(data) for name, data in other.files.items()}
    # 只比对内容，不比目录项
    only_left = sorted(set(left) - set(right))
    only_right = sorted(set(right) - set(left))
    differ = sorted(name for name in set(left) & set(right) if left[name] != right[name])

    report.line("  本地 %d 个文件 / 对照 %d 个文件" % (len(left), len(right)))
    if only_left:
        report.error("compare.missing", "对照方缺少 %d 个文件：%s"
                     % (len(only_left), ", ".join(only_left[:15])))
    if only_right:
        report.warn("compare.extra", "对照方多出 %d 个文件：%s"
                    % (len(only_right), ", ".join(only_right[:15])))
    if differ:
        report.error("compare.differs", "%d 个文件内容不同：%s" % (len(differ), ", ".join(differ[:15])))
    if not (only_left or only_right or differ):
        report.info("compare.identical", "两侧文件集合与内容逐字节一致（%d 个文件）" % len(left))
    return {"onlyLeft": only_left, "onlyRight": only_right, "differ": differ}


# ══════════════════════════════════════════════════════════════════════════
# 主流程
# ══════════════════════════════════════════════════════════════════════════

def audit(source: PackSource, report: Report, expect_id: Optional[str],
          expect_category: Optional[str]) -> Dict[str, Any]:
    manifest_info: Dict[str, Any] = {}
    definitions: List[Dict[str, Any]] = []
    result: Dict[str, Any] = {}

    if source.kind == "zip":
        audit_zip(source, report, expect_id)

    report.section("输入")
    report.line("  路径：%s" % source.path)
    report.line("  类型：%s（%d 个文件）" % ("zip 包" if source.kind == "zip" else "目录", len(source.files)))

    manifest_info = audit_manifest(source, report, expect_id, expect_category)
    result["pack"] = {
        "id": manifest_info.get("pack_id", ""),
        "version": manifest_info.get("version", ""),
        "formatVersion": manifest_info.get("format_version"),
    }

    if not source.files:
        report.error("input.empty", "包是空的（没有任何文件）")
        return result

    definitions = resolve_cosmetics(source, manifest_info, report, expect_category)

    cosmetics_metrics = []
    for definition in definitions:
        report.section("时装 %s" % definition["localId"])
        report.line("  category=%s attachedBone=%s hideParts=%s scale=%s material=%s"
                    % (definition["category"], definition["attachedBone"],
                       definition["hideParts"], definition["scale"], definition["material"]))
        if definition["entryDir"]:
            report.line("  目录：%s（%s）"
                        % (definition["entryDir"], "pack.json 声明" if definition["declared"] else "自动发现"))

        metrics = audit_geometry(source, definition, report)

        # 模型骨骼名集合 → 动画骨骼名对照
        bone_names = set()
        if definition.get("model"):
            model_data = source.read(definition["model"])
            if model_data:
                model_json, _, _ = load_json(model_data)
                try:
                    for bone in model_json["minecraft:geometry"][0]["bones"]:
                        name = bone.get("name")
                        if isinstance(name, str):
                            bone_names.add(name.strip().lower())
                except (KeyError, IndexError, TypeError, AttributeError):
                    pass
        metrics.update(audit_animation(source, definition, bone_names, report))
        audit_style_hints(definition, report)
        cosmetics_metrics.append(metrics)
        result.setdefault("cosmetics", []).append({
            "id": definition["localId"],
            "category": definition["category"],
            "attachedBone": definition["attachedBone"],
            "declared": definition["declared"],
            "metrics": metrics,
        })

    audit_publish_contract(manifest_info, definitions, report)
    audit_files(source, definitions, report)
    result["cosmeticsMetrics"] = cosmetics_metrics
    return result


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="audit_pack.py",
        description="VMFashion 时装包独立审计（pack.json / 模型 / 贴图 / 动画 / zip 安全 / 发布契约）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("退出码")[0] if __doc__ else None,
    )
    parser.add_argument("path", help="包目录或 zip 文件")
    parser.add_argument("--expect-id", metavar="ID", help="期望的 packId（发布清单里的 id）")
    parser.add_argument("--expect-category", metavar="CAT",
                        help="期望的分类（如 wings）；会检查 pack.json 声明与解析结果")
    parser.add_argument("--compare", metavar="PATH",
                        help="另一个包（目录或 zip）：逐文件比对内容是否一致（发布核验用）")
    parser.add_argument("--json", action="store_true", help="输出结构化 JSON（不打印人类可读报告）")
    parser.add_argument("--quiet", action="store_true", help="只打印 WARN/ERROR 与汇总")
    parser.add_argument("--strict", action="store_true", help="把 WARN 也算失败（退出码 1）")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    path = args.path
    if not os.path.exists(path):
        print("错误：路径不存在：%s" % path, file=sys.stderr)
        return 2

    report = Report()
    try:
        if os.path.isdir(path):
            source = PackSource.from_dir(path)
        elif zipfile.is_zipfile(path):
            source = PackSource.from_zip(path, report)
        else:
            print("错误：既不是目录也不是合法 zip：%s" % path, file=sys.stderr)
            return 2
    except OSError as exc:
        print("错误：读取失败：%s" % exc, file=sys.stderr)
        return 2

    result = audit(source, report, args.expect_id, args.expect_category)

    compare_result = None
    if args.compare:
        other_path = args.compare
        if not os.path.exists(other_path):
            print("错误：--compare 路径不存在：%s" % other_path, file=sys.stderr)
            return 2
        if os.path.isdir(other_path):
            other = PackSource.from_dir(other_path)
        else:
            other = PackSource.from_zip(other_path, report)
        compare_result = compare_sources(source, other, report, other_path)
        result["compare"] = compare_result

    errors = report.count(ERROR)
    warnings = report.count(WARN)
    verdict = "FAIL" if (errors or (args.strict and warnings)) else "PASS"

    if args.json:
        payload = {
            "path": path,
            "kind": source.kind,
            "result": result,
            "findings": [f.to_dict() for f in report.findings],
            "summary": {
                "errors": errors, "warnings": warnings, "infos": report.count(INFO),
                "verdict": verdict,
            },
        }
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0 if verdict == "PASS" else 1

    print(report.render(quiet=args.quiet))
    print("")
    print("════════════════════════════════════════════════════════")
    print("汇总：ERROR %d / WARN %d / INFO %d" % (errors, warnings, report.count(INFO)))
    print("结论：%s" % verdict)
    if args.compare:
        print("比对：%s" % ("一致" if compare_result and not (
            compare_result["onlyLeft"] or compare_result["onlyRight"] or compare_result["differ"])
            else "有差异（见上）"))
    print("════════════════════════════════════════════════════════")
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
