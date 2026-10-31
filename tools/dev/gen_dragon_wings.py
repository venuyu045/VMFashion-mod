#!/usr/bin/env python3
"""生成官方时装包里的「深渊龙翼」（dragon_wings）。

产物（写入 src/main/resources/assets/vmfashion/official_pack/cosmetics/dragon_wings/）：

    model.geo.json    Bedrock 几何（format_version 1.12.0）
    texture.png       256x256 主贴图（逐面 UV，翼膜靠 alpha 裁出扇形轮廓）
    emissive.png      256x256 发光层（只有 GLOW / SIGIL 区域不透明）
    anim.json         Bedrock 动画（flap 主循环 + idle 待机）

设计要点
--------
1. **翼膜是一整块贴图板**，不是一个一个方块拼的扇骨：翼膜形状（扇形 + 指尖之间的
   扇贝弧 + 拖尾）直接画在贴图的 alpha 通道里，材质走 `cutout`，透明像素被丢弃 ——
   这样外缘是平滑的弧线，而不是方块拼出来的锯齿。方块拼膜在 1/16 格精度下永远像
   "一把扇子"，这是 Minecraft 时装翅膀的通行做法。
2. 翼骨（肱骨 / 前臂 / 4 根指骨）是独立的细长方块，夹在膜板两侧各露一点，
   正反两面都看得见；前缘还有一条 emissive 发光棱。
3. 左右翼由左翼镜像生成（X 取反、绕 Y/Z 的旋转取反），保证严格对称。

坐标系（docs/pack-format.md §3.4）：脚底原点、Y 向上、+Z 背后、+X 玩家左手边。
单位 1/16 格：身高 32，肩高约 22，躯干背面 z≈+2。

符号坑：GeckoLib 烘焙时会把 X 取反，所以「设计空间」里正值 = 抬升 / 后掠的角度，
写进 JSON 时要按实测规则换算（json_raise_z / json_sweep_y / json_fan_z 三个函数）。

不依赖 Pillow：纯 zlib + struct 手写 PNG（与 tools/dev/make-icon.py 一致）。

用法：
    python3 tools/dev/gen_dragon_wings.py
"""

from __future__ import annotations

import json
import math
import struct
import zlib
from pathlib import Path

# ── 贴图布局（256x256，逐面 UV 手工分配） ───────────────────────────────
TEX = 256
REGION = {
    # 膜板两面各一块：Bedrock 的 north / south 面 u 轴方向相反，想让正反两面
    # 看到同一个扇形，就得把图镜像画两份（比用负 uv_size 安全，审计脚本也不会报越界）
    "membrane_n": (0, 0, 64, 128),     # 翼膜（贴 north 面：u 0=腕 → 1=膜缘）
    "membrane_s": (64, 0, 128, 128),   # 翼膜（贴 south 面：u 0=膜缘 → 1=腕）
    "bone": (128, 0, 224, 64),         # 翼骨
    "claw": (128, 64, 224, 112),       # 爪尖
    "glow": (128, 112, 224, 160),      # 前缘发光棱（emissive）
    "plate": (224, 0, 256, 64),        # 肩背挂板
    "sigil": (224, 64, 256, 96),       # 挂板符文（emissive）
    "edge": (224, 96, 256, 128),       # 暗边
    "clear": (0, 128, 64, 160),        # 全透明区：给膜板侧边，cutout 直接丢掉
}

# ── 骨架尺寸（设计空间：+X 向外、+Y 向上、+Z 背后） ────────────────────
SHOULDER = (1.4, 22.4, 2.2)
ARM_LEN = 8.0
FORE_LEN = 7.0
ELBOW_U = (SHOULDER[0] + ARM_LEN, SHOULDER[1], SHOULDER[2])
WRIST_U = (ELBOW_U[0] + FORE_LEN, ELBOW_U[1], ELBOW_U[2])

ROOT_SWEEP_Y = 20.0     # 整翼后掠（设计空间，正 = 向背后）
ARM_RAISE_Z = 22.0      # 肱骨抬起
FORE_BEND_Z = 6.0       # 前臂再抬
MEM_UNBEND_Z = -28.0    # 膜骨把扇面转回水平（22 + 6 - 28 = 0，手指角度才好写）

RIB_THICK = 0.62        # 指骨厚度（Z）
PLATE_THICK = 0.42      # 膜板厚度（Z）

# 手指（相对水平的设计角度，负 = 向下）+ 指尖半径。
FINGERS = [
    (12.0, 15.4),
    (-18.0, 13.8),
    (-46.0, 10.6),
    (-72.0, 7.6),
]
# 拖尾：从最下一根指骨继续往下内收，收在胯侧（不画这一段翼形会像被一刀切断）
TRAIL = [
    (-88.0, 6.2),
]
# 两指之间外缘的下凹量（"扇贝"弧），单位 1/16 格
SCALLOP = 1.45

IDENT = "geometry.vmfashion_dragon_wings"


# ── 角度符号换算（实测自 GeckoLib 烘焙 + 生产渲染链路） ────────────────
def json_raise_z(degrees: float) -> float:
    """设计空间「抬升角」→ JSON 的 Z 旋转（左翼：抬升 = 负 Z）。"""
    return -degrees


def json_sweep_y(degrees: float) -> float:
    """设计空间「后掠角」→ JSON 的 Y 旋转（左翼：后掠 = 负 Y）。"""
    return -degrees


def json_fan_z(degrees: float) -> float:
    """设计空间扇面角（负 = 下垂）→ JSON 的 Z 旋转。"""
    return -degrees


# ── 小工具 ──────────────────────────────────────────────────────────────
def r2(value: float) -> float:
    return round(value + 0.0, 2)


def lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def clamp(v: float, lo: float, hi: float):
    return lo if v < lo else hi if v > hi else v


def face_uv(region: str, face_w: float, face_h: float, scale: float,
            u_off: float = 0.0, v_off: float = 0.0):
    """把面尺寸映射到贴图区域里的一块（texel），返回 (uv, uv_size)。"""
    x0, y0, x1, y1 = REGION[region]
    rw, rh = x1 - x0, y1 - y0
    w = min(face_w * scale, rw - 1.0)
    h = min(face_h * scale, rh - 1.0)
    u = x0 + clamp(u_off, 0.0, rw - w)
    v = y0 + clamp(v_off, 0.0, rh - h)
    return [r2(u), r2(v)], [r2(w), r2(h)]


def uv_all(region: str, w: float, h: float, d: float, scale: float,
           u_off: float = 0.0, v_off: float = 0.0):
    return {
        "north": face_uv(region, w, h, scale, u_off, v_off),
        "south": face_uv(region, w, h, scale, u_off, v_off),
        "east": face_uv(region, d, h, scale, u_off, v_off),
        "west": face_uv(region, d, h, scale, u_off, v_off),
        "up": face_uv(region, w, d, scale, u_off, v_off),
        "down": face_uv(region, w, d, scale, u_off, v_off),
    }


def box(origin, size, uv_faces, *, rotation=None, pivot=None):
    cube = {
        "origin": [r2(v) for v in origin],
        "size": [r2(v) for v in size],
        "uv": {face: {"uv": uv, "uv_size": uv_size} for face, (uv, uv_size) in uv_faces.items()},
    }
    if rotation is not None:
        cube["rotation"] = [r2(v) for v in rotation]
    if pivot is not None:
        cube["pivot"] = [r2(v) for v in pivot]
    return cube


def mirror_x(value):
    return [-value[0], value[1], value[2]]


def mirror_cube(cube):
    """沿 YZ 平面镜像一个 cube（origin 用 -(x+w)，绕 Y/Z 的旋转取反，pivot 的 x 取反）。

    **同时把 north/south 两个面的 UV 互换**：Bedrock 逐面 UV 里，north 面的 u=0 在
    JSON 的 x 最小端、south 面的 u=0 在 x 最大端；镜像后 x 方向整体翻转，如果不换 UV，
    同一张贴图在右翼上会左右颠倒（翼膜的扇形会变成"尖端朝外"，两翼看起来一上一下）。
    """
    out = json.loads(json.dumps(cube))
    ox, oy, oz = out["origin"]
    sx, sy, sz = out["size"]
    out["origin"] = [r2(-(ox + sx)), oy, oz]
    if "rotation" in out:
        rx, ry, rz = out["rotation"]
        out["rotation"] = [rx, -ry, -rz]
    if "pivot" in out:
        px, py, pz = out["pivot"]
        out["pivot"] = [r2(-px), py, pz]
    uv = out.get("uv")
    if isinstance(uv, dict) and "north" in uv and "south" in uv:
        uv["north"], uv["south"] = uv["south"], uv["north"]
    return out


# ── 翼膜轮廓（贴图 alpha 与几何板共用同一套数学） ──────────────────────
def _finger_radius(design_angle: float) -> float:
    """某个角度上的翼膜外缘半径：在相邻指尖之间线性插值，再减去扇贝下凹。"""
    points = FINGERS + TRAIL
    if design_angle >= points[0][0]:
        return points[0][1]
    if design_angle <= points[-1][0]:
        return points[-1][1]
    for (a1, l1), (a2, l2) in zip(points, points[1:]):
        if a2 <= design_angle <= a1:
            t = (a1 - design_angle) / (a1 - a2)
            base = lerp(l1, l2, t)
            # 扇贝：只有"手指之间"才下凹，指尖本身保持长度
            return base - SCALLOP * math.sin(math.pi * t)
    return points[-1][1]


ANGLE_TOP = FINGERS[0][0]          # +10°
ANGLE_BOTTOM = TRAIL[-1][0]        # -112°
BISECTOR = (ANGLE_TOP + ANGLE_BOTTOM) / 2.0

# 膜板在"以 bisector 为 +X"的局部坐标里的尺寸
_PLATE_POINTS = [(ANGLE_TOP, 0.0)] + [(a, _finger_radius(a)) for a, _ in FINGERS + TRAIL] + \
                [(ANGLE_BOTTOM, 0.0)]
PLATE_L = 0.0
PLATE_W = 0.0
for _a, _r in _PLATE_POINTS:
    _x = _r * math.cos(math.radians(_a - BISECTOR))
    _y = _r * math.sin(math.radians(_a - BISECTOR))
    PLATE_L = max(PLATE_L, _x)
    PLATE_W = max(PLATE_W, abs(_y) * 2.0)
PLATE_L = r2(PLATE_L + 0.8)
PLATE_W = r2(PLATE_W + 0.8)


def membrane_inside(local_x: float, local_y: float):
    """膜板局部坐标 (x 沿 bisector, y 垂直) → (是否在翼膜内, 半径, 设计角度)。"""
    r = math.hypot(local_x, local_y)
    if r < 0.35:
        return False, r, 0.0
    angle = math.degrees(math.atan2(local_y, local_x)) + BISECTOR
    if angle > ANGLE_TOP or angle < ANGLE_BOTTOM:
        return False, r, angle
    return r <= _finger_radius(angle), r, angle


# ── 左翼几何 ────────────────────────────────────────────────────────────
def left_wing_bones():
    bones = []

    # 1) 肩背挂板：贴在躯干背面，上下各一根刺 + 一枚发光符文
    bones.append({
        "name": "wing_root_L",
        "parent": "root",
        "pivot": [r2(SHOULDER[0] - 0.2), r2(SHOULDER[1]), r2(SHOULDER[2] - 0.3)],
        "rotation": [0.0, json_sweep_y(ROOT_SWEEP_Y), 0.0],
        "cubes": [
            box([0.0, 20.2, 2.05], [2.6, 4.6, 1.5], uv_all("plate", 2.6, 4.6, 1.5, 8.0)),
            box([0.55, 24.7, 2.2], [1.1, 2.3, 0.9], uv_all("claw", 1.1, 2.3, 0.9, 12.0)),
            box([0.35, 18.3, 2.2], [0.9, 2.0, 0.8], uv_all("claw", 0.9, 2.0, 0.8, 12.0)),
            # 发光符文：挂在挂板背面（+Z 外侧）
            box([0.55, 21.3, 3.58], [1.5, 1.5, 0.14], uv_all("sigil", 1.5, 1.5, 0.14, 16.0)),
        ],
    })

    # 2) 肱骨：骨条（夹在膜板两侧）+ 前缘发光棱 + 肘刺
    arm_z = SHOULDER[2]
    bones.append({
        "name": "wing_arm_L",
        "parent": "wing_root_L",
        "pivot": [r2(SHOULDER[0]), r2(SHOULDER[1]), r2(SHOULDER[2])],
        "rotation": [0.0, 0.0, json_raise_z(ARM_RAISE_Z)],
        "cubes": [
            box([SHOULDER[0], SHOULDER[1] - 0.95, arm_z - 0.55], [ARM_LEN, 1.9, 1.1],
                uv_all("bone", ARM_LEN, 1.9, 1.1, 5.0)),
            box([SHOULDER[0], SHOULDER[1] + 0.95, arm_z - 0.42], [ARM_LEN, 0.5, 0.84],
                uv_all("glow", ARM_LEN, 0.5, 0.84, 5.0)),
            box([ELBOW_U[0] - 1.0, ELBOW_U[1] + 0.6, arm_z - 0.45], [1.6, 1.8, 0.9],
                uv_all("claw", 1.6, 1.8, 0.9, 10.0)),
        ],
    })

    # 3) 前臂：骨条 + 前缘发光棱 + 腕爪
    bones.append({
        "name": "wing_fore_L",
        "parent": "wing_arm_L",
        "pivot": [r2(ELBOW_U[0]), r2(ELBOW_U[1]), r2(ELBOW_U[2])],
        "rotation": [0.0, 0.0, json_raise_z(FORE_BEND_Z)],
        "cubes": [
            box([ELBOW_U[0], ELBOW_U[1] - 0.9, arm_z - 0.5], [FORE_LEN, 1.8, 1.0],
                uv_all("bone", FORE_LEN, 1.8, 1.0, 5.0)),
            box([ELBOW_U[0], ELBOW_U[1] + 0.9, arm_z - 0.42], [FORE_LEN, 0.5, 0.84],
                uv_all("glow", FORE_LEN, 0.5, 0.84, 5.0)),
            box([WRIST_U[0] - 1.1, WRIST_U[1] - 0.1, arm_z - 0.5], [1.8, 1.5, 1.0],
                uv_all("claw", 1.8, 1.5, 1.0, 10.0)),
        ],
    })

    # 4) 膜骨：一整块贴图板（alpha 裁形）+ 4 根指骨 + 拖尾细骨
    pivot = [r2(WRIST_U[0]), r2(WRIST_U[1]), r2(WRIST_U[2])]
    membrane_cubes = [
        box(
            [WRIST_U[0], WRIST_U[1] - PLATE_W / 2.0, arm_z - PLATE_THICK / 2.0],
            [PLATE_L, PLATE_W, PLATE_THICK],
            {
                # 两个大面各贴一张镜像的膜图；侧边贴透明区（cutout 丢弃 → 膜看起来是零厚的）
                "north": ([0.0, 0.0], [64.0, 128.0]),
                "south": ([64.0, 0.0], [64.0, 128.0]),
                "east": ([0.0, 128.0], [2.0, 2.0]),
                "west": ([0.0, 128.0], [2.0, 2.0]),
                "up": ([0.0, 128.0], [2.0, 2.0]),
                "down": ([0.0, 128.0], [2.0, 2.0]),
            },
            rotation=[0.0, 0.0, json_fan_z(BISECTOR)], pivot=pivot,
        ),
    ]
    for idx, (angle, length) in enumerate(FINGERS):
        membrane_cubes.append(box(
            [WRIST_U[0], WRIST_U[1] - RIB_THICK / 2.0, arm_z - RIB_THICK / 2.0],
            [length, RIB_THICK, RIB_THICK],
            uv_all("bone", length, RIB_THICK, RIB_THICK, 6.0, u_off=idx * 2.0),
            rotation=[0.0, 0.0, json_fan_z(angle)], pivot=pivot,
        ))
        membrane_cubes.append(box(
            [WRIST_U[0] + length - 0.5, WRIST_U[1] - RIB_THICK / 2.0 - 0.08, arm_z - RIB_THICK / 2.0],
            [1.2, RIB_THICK + 0.16, RIB_THICK + 0.16],
            uv_all("claw", 1.2, RIB_THICK, RIB_THICK, 14.0),
            rotation=[0.0, 0.0, json_fan_z(angle)], pivot=pivot,
        ))
    # 拖尾那根细骨，让下缘有个收口
    trail_angle, trail_len = TRAIL[0]
    membrane_cubes.append(box(
        [WRIST_U[0], WRIST_U[1] - 0.32, arm_z - 0.32],
        [trail_len, 0.64, 0.64],
        uv_all("bone", trail_len, 0.64, 0.64, 8.0),
        rotation=[0.0, 0.0, json_fan_z(trail_angle)], pivot=pivot,
    ))

    bones.append({
        "name": "wing_membrane_L",
        "parent": "wing_fore_L",
        "pivot": pivot,
        "rotation": [0.0, 0.0, json_raise_z(MEM_UNBEND_Z)],
        "cubes": membrane_cubes,
    })
    return bones


def build_bones():
    bones = [{"name": "root", "pivot": [0.0, 24.0, 0.0]}]
    left = left_wing_bones()
    bones.extend(left)
    for bone in left:
        bones.append({
            "name": bone["name"].replace("_L", "_R"),
            "parent": bone["parent"].replace("_L", "_R") if bone["parent"] != "root" else "root",
            "pivot": mirror_x(bone["pivot"]),
            "rotation": [bone["rotation"][0], -bone["rotation"][1], -bone["rotation"][2]],
            "cubes": [mirror_cube(c) for c in bone.get("cubes", [])],
        })
    return bones


# ── 模型 JSON ───────────────────────────────────────────────────────────
def build_model():
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [
            {
                "description": {
                    "identifier": IDENT,
                    "texture_width": TEX,
                    "texture_height": TEX,
                    "visible_bounds_width": 8,
                    "visible_bounds_height": 5,
                    "visible_bounds_offset": [0.0, 1.0, 0.0],
                },
                "bones": build_bones(),
            }
        ],
    }


# ── 动画 ────────────────────────────────────────────────────────────────
def _flap_track(peak_json_z: float, length: float = 2.6):
    return {
        "0.0": [0.0, 0.0, 0.0],
        f"{length * 0.25:.2f}": [0.0, 0.0, peak_json_z],
        f"{length * 0.50:.2f}": [0.0, 0.0, 0.0],
        f"{length * 0.75:.2f}": [0.0, 0.0, -peak_json_z],
        f"{length:.2f}": [0.0, 0.0, 0.0],
    }


def _mirror_track(track):
    return {t: [rx, -ry, -rz] for t, (rx, ry, rz) in track.items()}


def build_animations():
    """flap（主循环，默认播）与 idle（轻微呼吸）。

    GeckoLib 的动画旋转是**叠加在骨骼 rest rotation 之上**的，所以这里写增量，
    0 度 = 保持模型原姿势；设计空间角度统一走 json_raise_z / json_sweep_y 换算。
    """
    length = 2.6
    arm = _flap_track(json_raise_z(14.0), length)
    fore = _flap_track(json_raise_z(7.0), length)
    mem = _flap_track(json_raise_z(-3.5), length)      # 膜骨反相，像膜被兜住
    sweep = {"0.0": [0.0, 0.0, 0.0], f"{length / 2:.2f}": [0.0, json_sweep_y(-3.0), 0.0],
             f"{length:.2f}": [0.0, 0.0, 0.0]}

    flap = {
        "loop": True,
        "animation_length": length,
        "bones": {
            "wing_root_L": {"rotation": sweep},
            "wing_root_R": {"rotation": _mirror_track(sweep)},
            "wing_arm_L": {"rotation": arm},
            "wing_arm_R": {"rotation": _mirror_track(arm)},
            "wing_fore_L": {"rotation": fore},
            "wing_fore_R": {"rotation": _mirror_track(fore)},
            "wing_membrane_L": {"rotation": mem},
            "wing_membrane_R": {"rotation": _mirror_track(mem)},
        },
    }

    def breathe(peak_design: float, length2: float = 4.0):
        return {
            "0.0": [0.0, 0.0, 0.0],
            f"{length2 / 2:.2f}": [0.0, 0.0, json_raise_z(peak_design)],
            f"{length2:.2f}": [0.0, 0.0, 0.0],
        }

    arm_idle = breathe(3.0)
    mem_idle = breathe(-2.0)
    sweep_idle = {"0.0": [0.0, 0.0, 0.0], "2.0": [0.0, json_sweep_y(1.5), 0.0], "4.0": [0.0, 0.0, 0.0]}
    idle = {
        "loop": True,
        "animation_length": 4.0,
        "bones": {
            "wing_root_L": {"rotation": sweep_idle},
            "wing_root_R": {"rotation": _mirror_track(sweep_idle)},
            "wing_arm_L": {"rotation": arm_idle},
            "wing_arm_R": {"rotation": _mirror_track(arm_idle)},
            "wing_membrane_L": {"rotation": mem_idle},
            "wing_membrane_R": {"rotation": _mirror_track(mem_idle)},
        },
    }

    return {
        "format_version": "1.8.0",
        "animations": {
            "animation.vmfashion.dragon_wings.flap": flap,
            "animation.vmfashion.dragon_wings.idle": idle,
        },
    }


# ── 贴图 ────────────────────────────────────────────────────────────────
def _hash_noise(x: int, y: int, seed: int = 0) -> float:
    n = (x * 374761393 + y * 668265263 + seed * 1442695040888963407) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def _mix(a, b, t: float):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def _in(rect, x, y) -> bool:
    x0, y0, x1, y1 = rect
    return x0 <= x < x1 and y0 <= y < y1


GLOW_A = (0x7C, 0x3A, 0xED)   # 和模组 UI 主色一致
GLOW_B = (0x22, 0xD3, 0xEE)
SIGIL_A = (0xA7, 0x8B, 0xFA)
SIGIL_B = (0xF0, 0xAB, 0xFC)

MEMBRANE_ROOT = (0x0C, 0x05, 0x12)    # 膜根（靠腕部）近黑
MEMBRANE_MID = (0x36, 0x0E, 0x28)
MEMBRANE_EDGE = (0x7A, 0x18, 0x3E)    # 膜缘：暗红
MEMBRANE_HOT = (0xC4, 0x40, 0x66)     # 血线
MEMBRANE_RIM = (0xFF, 0xA8, 0xC0)     # 最外一圈的亮边


def glow_pixel(x: int, y: int):
    x0, _, x1, _ = REGION["glow"]
    t = (x - x0) / (x1 - x0 - 1)
    base = _mix(GLOW_A, GLOW_B, t)
    if ((x - x0) // 6) % 3 == 0:
        base = _mix(base, (0xFF, 0xFF, 0xFF), 0.22)
    return base


def sigil_pixel(x: int, y: int):
    x0, y0, x1, y1 = REGION["sigil"]
    cx, cy = (x0 + x1 - 1) / 2.0, (y0 + y1 - 1) / 2.0
    d = abs(x - cx) + abs(y - cy)
    base = (0x14, 0x0C, 0x1E)
    if 9.0 <= d <= 12.0:
        base = _mix(SIGIL_A, SIGIL_B, (x - x0) / (x1 - x0 - 1))
    elif d <= 3.5:
        base = (0xFF, 0xFF, 0xFF)
    return base


def membrane_pixel(tx: int, ty: int, region: str):
    """翼膜贴图：把扇形（含扇贝外缘与拖尾）画进 alpha 通道。

    局部坐标：贴图 u 沿 bisector 向外、v 是横向。
    实测（/tmp/probe/UvMap.java）：north 面 u=0 在 x 最小端（腕），v=0 在 y 最大端（上）；
    south 面 u 轴相反，所以两张图镜像绘制，映射关系才一致。
    """
    x0, y0, x1, y1 = REGION[region]
    u = (tx - x0 + 0.5) / (x1 - x0)
    if region == "membrane_s":
        u = 1.0 - u          # south 面 u 轴反向
    v = (ty - y0 + 0.5) / (y1 - y0)
    local_x = u * PLATE_L
    local_y = (0.5 - v) * PLATE_W
    inside, radius, angle = membrane_inside(local_x, local_y)
    if not inside:
        return None

    edge = _finger_radius(angle)
    t_r = clamp(radius / max(edge, 0.001), 0.0, 1.0)

    # 底色：根部近黑 → 中段酒红 → 膜缘暗红
    if t_r < 0.55:
        base = _mix(MEMBRANE_ROOT, MEMBRANE_MID, t_r / 0.55)
    else:
        base = _mix(MEMBRANE_MID, MEMBRANE_EDGE, (t_r - 0.55) / 0.45)

    # 血线：沿半径方向的细线（贴图上就是从腕部放射出去的线），每 ~14° 一条、断续
    line = (angle - ANGLE_BOTTOM) / 14.0
    if abs(line - round(line)) < 0.055 and t_r > 0.22 and _hash_noise(tx // 4, ty, 5) > 0.25:
        base = _mix(base, MEMBRANE_HOT, 0.22 + 0.18 * t_r)

    # 最外一圈亮边（远看像膜在发光）
    if t_r > 0.94:
        base = _mix(base, MEMBRANE_RIM, (t_r - 0.94) / 0.06 * 0.75)

    # 噪声 + 稀疏破洞（alpha=0，cutout 直接镂空）
    n = _hash_noise(tx, ty, 2)
    if n > 0.972 and t_r > 0.25:
        return None
    if n > 0.93:
        base = _mix(base, (0x8A, 0x1B, 0x40), 0.5)
    elif n < 0.07:
        base = _mix(base, (0x08, 0x04, 0x0E), 0.55)
    return (*base, 255)


def paint_texture() -> list[bytes]:
    bone_base = (0xE2, 0xDC, 0xCE)
    bone_dark = (0x9E, 0x96, 0x88)
    bone_crack = (0x5A, 0x52, 0x48)
    claw_base = (0x24, 0x1C, 0x2E)
    claw_tip = (0xD2, 0xCB, 0xDE)
    plate_base = (0x22, 0x1B, 0x2E)
    plate_hi = (0x4C, 0x3E, 0x62)
    edge_base = (0x16, 0x0F, 0x1E)
    edge_hi = (0x54, 0x1C, 0x38)

    rows = []
    for y in range(TEX):
        row = bytearray()
        for x in range(TEX):
            rgba = (0, 0, 0, 0)

            if _in(REGION["membrane_n"], x, y):
                pixel = membrane_pixel(x, y, "membrane_n")
                if pixel is not None:
                    rgba = pixel
            elif _in(REGION["membrane_s"], x, y):
                pixel = membrane_pixel(x, y, "membrane_s")
                if pixel is not None:
                    rgba = pixel

            elif _in(REGION["bone"], x, y):
                x0, y0, x1, y1 = REGION["bone"]
                v = (y - y0) / (y1 - y0 - 1)
                base = _mix(bone_base, bone_dark, v * 0.55)
                if (y - y0) % 7 == 0:
                    base = _mix(base, bone_crack, 0.45)
                n = _hash_noise(x, y, 7)
                if n > 0.94:
                    base = _mix(base, bone_crack, 0.6)
                elif n < 0.05:
                    base = _mix(base, (0xFF, 0xFA, 0xF0), 0.5)
                rgba = (*base, 255)

            elif _in(REGION["claw"], x, y):
                x0, y0, x1, y1 = REGION["claw"]
                v = (y - y0) / (y1 - y0 - 1)
                base = _mix(claw_base, claw_tip, clamp((v - 0.35) * 1.6, 0.0, 1.0))
                if _hash_noise(x, y, 3) > 0.9:
                    base = _mix(base, (0x6A, 0x5E, 0x78), 0.5)
                rgba = (*base, 255)

            elif _in(REGION["glow"], x, y):
                rgba = (*glow_pixel(x, y), 255)

            elif _in(REGION["plate"], x, y):
                x0, y0, x1, y1 = REGION["plate"]
                v = (y - y0) / (y1 - y0 - 1)
                base = _mix(plate_base, plate_hi, 0.15 + 0.5 * v)
                for cx, cy in ((8, 8), (56, 8), (8, 56), (56, 56)):
                    if (x - x0 - cx) ** 2 + (y - y0 - cy) ** 2 <= 4:
                        base = _mix(base, (0x8A, 0x76, 0xA8), 0.85)
                rgba = (*base, 255)

            elif _in(REGION["sigil"], x, y):
                rgba = (*sigil_pixel(x, y), 255)

            elif _in(REGION["edge"], x, y):
                x0, y0, x1, y1 = REGION["edge"]
                v = (y - y0) / (y1 - y0 - 1)
                base = _mix(edge_base, edge_hi, 0.2 + 0.4 * v)
                if _hash_noise(x, y, 11) > 0.92:
                    base = _mix(base, (0x8B, 0x1E, 0x46), 0.6)
                rgba = (*base, 255)

            row.extend(rgba)
        rows.append(bytes(row))
    return rows


def paint_emissive() -> list[bytes]:
    """发光层：只有 glow / sigil 两个区域不透明。

    mod 的 EmissiveMask 是逐 quad 判定（UV 包围盒里只要有不透明 texel 这个 quad 就发光），
    所以发光件整块 UV 都必须落在不透明区域里；翼膜区域保持全透明，否则整块膜板会一起发光。
    emissive 走 RenderTypes.eyes，颜色直接出画，所以这里画的是真正的发光色而不是白色遮罩。
    """
    rows = []
    for y in range(TEX):
        row = bytearray()
        for x in range(TEX):
            if _in(REGION["glow"], x, y):
                row.extend((*glow_pixel(x, y), 255))
            elif _in(REGION["sigil"], x, y):
                row.extend((*sigil_pixel(x, y), 255))
            else:
                row.extend((0, 0, 0, 0))
        rows.append(bytes(row))
    return rows


def write_png(path: Path, rows: list[bytes]) -> None:
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    height = len(rows)
    width = len(rows[0]) // 4
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    out = root / "src/main/resources/assets/vmfashion/official_pack/cosmetics/dragon_wings"
    out.mkdir(parents=True, exist_ok=True)

    model = build_model()
    (out / "model.geo.json").write_text(
        json.dumps(model, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "anim.json").write_text(
        json.dumps(build_animations(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_png(out / "texture.png", paint_texture())
    write_png(out / "emissive.png", paint_emissive())

    cubes = sum(len(b.get("cubes", [])) for b in model["minecraft:geometry"][0]["bones"])
    print(f"wrote {out}")
    print(f"  骨骼 {len(model['minecraft:geometry'][0]['bones'])} 根 / 立方体 {cubes} 个 / "
          f"四边形约 {cubes * 6} 个")
    print(f"  膜板局部尺寸 L={PLATE_L} W={PLATE_W}（bisector={BISECTOR:.1f}°）")
    for name in ("model.geo.json", "texture.png", "emissive.png", "anim.json"):
        print(f"  {name}: {(out / name).stat().st_size} bytes")


if __name__ == "__main__":
    main()
