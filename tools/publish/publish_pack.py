#!/usr/bin/env python3
"""把一个时装包发布到 VMTools 站点（时装包 + 缩略图资产 + 目录条目）。

背景：网站（`/workspace/vmtools/vmtools-next`）的时装子系统有两条链路 ——
「包级下发」（`GET /api/fashion/packs`，mod 自动下载整包）和「目录」
（`GET /api/fashion/catalog`，网站商店 / 衣柜展示）。要让一件新时装真正"上线"，
三样东西缺一不可：

1. `POST /api/fashion/admin/packs`  上传整包 zip（服务端会按 mod 契约校验结构）；
2. `POST /api/fashion/admin/assets` 上传一张 PNG 缩略图（网站商店靠 assetKey 显示预览）；
3. `POST /api/fashion/admin/catalog` 写目录条目（cosmeticId 的冒号前缀必须等于 packId）。

脚本做的事就是这三步 + 发布后回读校验，全程只碰自己这一个包 / 这一件时装，
不修改站点上其它数据。凭据走环境变量，**不写进仓库**：

    export VMF_SITE_USER=xxx VMF_SITE_PASSWORD=yyy
    python3 tools/publish/publish_pack.py \
        --pack-dir src/main/resources/assets/vmfashion/official_pack \
        --pack-id official --version 1.0.0 \
        --thumb build/preview/dragon_wings/thumb.png \
        --cosmetic-id official:dragon_wings --name 深渊龙翼 --category wings

    # 先看要发什么、不真发：
    python3 tools/publish/publish_pack.py ... --dry-run

    # 回滚（删目录条目 + 下架包）：
    python3 tools/publish/publish_pack.py ... --revert

只用标准库（urllib），不依赖 requests；zip 用固定时间戳，保证同一份内容
每次打出来的 sha256 一致（服务端按 sha256 内容寻址去重）。
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from pathlib import Path

DEFAULT_SITE = "https://www.venusyu045.cn"

# zip 里所有条目用同一个固定时间戳：同一份内容重复打包 → sha256 稳定，
# 服务端按 sha256 内容寻址去重时不会因为"每次 mtime 不同"产生一堆副本。
FIXED_ZIP_TIME = (2026, 1, 1, 0, 0, 0)

# 时装包 zip 里不允许出现的东西（与后端 FORBIDDEN_PACK_EXTS / 隐藏文件规则对齐，
# 在本地先挡一次，避免把坏包传上去才被 400 打回）
FORBIDDEN_SUFFIXES = {
    ".exe", ".dll", ".so", ".dylib", ".bat", ".cmd", ".com", ".msi", ".scr",
    ".ps1", ".vbs", ".sh", ".bash", ".zsh", ".py", ".pyc", ".jar", ".class",
    ".php", ".asp", ".aspx", ".jsp", ".cgi", ".lnk", ".url", ".reg",
}


# ── HTTP ────────────────────────────────────────────────────────────────
class Site:
    """站点 API 的最小客户端（登录 + 带 token 的 JSON / multipart 请求）。"""

    def __init__(self, base: str, user: str, password: str):
        self.base = base.rstrip("/")
        self.user = user
        self.password = password
        self.token = ""
        self.user_info: dict = {}

    def _request(self, method: str, path: str, *, body: bytes | None = None,
                 headers: dict | None = None, auth: bool = True) -> tuple[int, bytes, dict]:
        url = self.base + path
        hdrs = {"Accept": "application/json"}
        if headers:
            hdrs.update(headers)
        if auth and self.token:
            hdrs["Authorization"] = "Bearer " + self.token
        req = urllib.request.Request(url, data=body, headers=hdrs, method=method)
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.status, resp.read(), dict(resp.headers)
        except urllib.error.HTTPError as exc:
            return exc.code, exc.read(), dict(exc.headers or {})

    def login(self) -> None:
        body = json.dumps({"game_id": self.user, "password": self.password}).encode()
        status, data, _ = self._request(
            "POST", "/api/auth/login", body=body,
            headers={"Content-Type": "application/json"}, auth=False)
        if status != 200:
            raise SystemExit(f"登录失败 HTTP {status}: {data[:300]!r}")
        payload = json.loads(data)
        self.token = payload.get("token") or ""
        self.user_info = payload
        if not self.token:
            raise SystemExit(f"登录响应里没有 token: {payload}")

    def json(self, method: str, path: str, payload: dict | None = None) -> tuple[int, dict]:
        body = None if payload is None else json.dumps(payload).encode()
        headers = {"Content-Type": "application/json"} if payload is not None else {}
        status, data, _ = self._request(method, path, body=body, headers=headers)
        try:
            return status, json.loads(data) if data else {}
        except json.JSONDecodeError:
            return status, {"raw": data[:400].decode("utf-8", "replace")}

    def upload(self, path: str, filename: str, content: bytes,
               fields: dict[str, str]) -> tuple[int, dict]:
        boundary = "----vmfashion" + uuid.uuid4().hex
        buf = io.BytesIO()
        for key, value in fields.items():
            buf.write(f"--{boundary}\r\n".encode())
            buf.write(f'Content-Disposition: form-data; name="{key}"\r\n\r\n'.encode())
            buf.write(str(value).encode("utf-8"))
            buf.write(b"\r\n")
        ctype = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode())
        buf.write(f"Content-Type: {ctype}\r\n\r\n".encode())
        buf.write(content)
        buf.write(b"\r\n")
        buf.write(f"--{boundary}--\r\n".encode())
        status, data, _ = self._request(
            "POST", path, body=buf.getvalue(),
            headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
        try:
            return status, json.loads(data) if data else {}
        except json.JSONDecodeError:
            return status, {"raw": data[:400].decode("utf-8", "replace")}


# ── 打包 ────────────────────────────────────────────────────────────────
def build_pack_zip(pack_dir: Path, pack_id: str) -> tuple[bytes, str, int]:
    """把包目录打成 zip（根目录直接是包内容），返回 (字节, sha256, 文件数)。"""
    manifest = pack_dir / "pack.json"
    if not manifest.is_file():
        raise SystemExit(f"包目录缺少 pack.json: {pack_dir}")
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if str(data.get("id") or "").strip() != pack_id:
        raise SystemExit(
            f"pack.json 的 id={data.get('id')!r} 与 --pack-id={pack_id!r} 不一致（后端会拒绝）")

    files = sorted(p for p in pack_dir.rglob("*") if p.is_file())
    problems = []
    for path in files:
        rel = path.relative_to(pack_dir).as_posix()
        if path.suffix.lower() in FORBIDDEN_SUFFIXES:
            problems.append(f"{rel}: 可执行/脚本后缀")
        if any(part.startswith(".") for part in rel.split("/")):
            problems.append(f"{rel}: 隐藏文件/目录")
    if problems:
        raise SystemExit("包里有不允许的文件：\n  " + "\n  ".join(problems))

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for path in files:
            rel = path.relative_to(pack_dir).as_posix()
            info = zipfile.ZipInfo(rel, date_time=FIXED_ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zf.writestr(info, path.read_bytes())
    blob = buf.getvalue()
    return blob, hashlib.sha256(blob).hexdigest(), len(files)


# ── 主流程 ──────────────────────────────────────────────────────────────
def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="发布时装包到 VMTools 站点")
    parser.add_argument("--pack-dir", required=True, type=Path, help="时装包目录（含 pack.json）")
    parser.add_argument("--pack-id", required=True, help="包 ID（必须与 pack.json 的 id 一致）")
    parser.add_argument("--version", required=True, help="包版本，例如 1.0.0")
    parser.add_argument("--site", default=os.environ.get("VMF_SITE", DEFAULT_SITE))
    parser.add_argument("--cosmetic-id", default="", help="目录条目 cosmeticId，形如 <packId>:<localId>")
    parser.add_argument("--name", default="", help="展示名")
    parser.add_argument("--category", default="", help="槽位：cape/wings/hat/back/pet/aura/other")
    parser.add_argument("--thumb", default=None, type=Path, help="缩略图 PNG（网站商店预览用）")
    parser.add_argument("--sort-order", type=int, default=0)
    parser.add_argument("--dry-run", action="store_true", help="只打包并打印，不上传")
    parser.add_argument("--revert", action="store_true", help="删除目录条目并下架包")
    parser.add_argument("--out", default="", type=Path, help="把结果 JSON 写到这个文件")
    args = parser.parse_args(argv)

    pack_dir = args.pack_dir.resolve()
    blob, digest, file_count = build_pack_zip(pack_dir, args.pack_id)
    print(f"包: {pack_dir}")
    print(f"  {file_count} 个文件 / {len(blob)} 字节 / sha256={digest}")

    thumb_bytes = b""
    thumb_sha = ""
    if args.thumb:
        thumb_path = args.thumb.resolve()
        if not thumb_path.is_file():
            raise SystemExit(f"缩略图不存在: {thumb_path}")
        thumb_bytes = thumb_path.read_bytes()
        thumb_sha = hashlib.sha256(thumb_bytes).hexdigest()
        print(f"缩略图: {thumb_path} ({len(thumb_bytes)} 字节 / sha256={thumb_sha[:16]}…)")

    if args.dry_run:
        print("--dry-run：不上传")
        return 0

    user = os.environ.get("VMF_SITE_USER", "")
    password = os.environ.get("VMF_SITE_PASSWORD", "")
    if not user or not password:
        raise SystemExit("缺少凭据：请设置环境变量 VMF_SITE_USER / VMF_SITE_PASSWORD")

    site = Site(args.site, user, password)
    site.login()
    print(f"已登录: {site.user_info.get('game_id')} / role={site.user_info.get('role')}")

    report: dict = {"site": site.base, "packId": args.pack_id, "version": args.version,
                    "sha256": digest, "bytes": len(blob)}

    if args.revert:
        if args.cosmetic_id:
            status, payload = site.json(
                "DELETE", "/api/fashion/admin/catalog/" + urllib.parse.quote(args.cosmetic_id, safe=""))
            print(f"删除目录条目 {args.cosmetic_id}: HTTP {status} {payload}")
            report["catalogDelete"] = {"status": status, "body": payload}
        status, payload = site.json(
            "DELETE", f"/api/fashion/admin/packs/{args.pack_id}/{args.version}")
        print(f"下架包 {args.pack_id}@{args.version}: HTTP {status} {payload}")
        report["packDelete"] = {"status": status, "body": payload}
        _write_report(args, report)
        return 0

    # 1) 上传整包
    status, payload = site.upload(
        "/api/fashion/admin/packs", f"{args.pack_id}-{args.version}.zip", blob,
        {"pack_id": args.pack_id, "version": args.version})
    print(f"上传包: HTTP {status} {json.dumps(payload, ensure_ascii=False)[:400]}")
    report["packUpload"] = {"status": status, "body": payload}
    if status != 200:
        _write_report(args, report)
        return 1

    # 2) 上传缩略图（先不带 cosmeticId，避免目录条目还没建就 404）
    asset_key = ""
    if thumb_bytes:
        status, payload = site.upload(
            "/api/fashion/admin/assets", f"{args.pack_id}-{args.version}-thumb.png", thumb_bytes,
            {"pack_id": args.pack_id, "version": args.version})
        print(f"上传缩略图: HTTP {status} {json.dumps(payload, ensure_ascii=False)[:400]}")
        report["assetUpload"] = {"status": status, "body": payload}
        if status == 200:
            asset_key = str(payload.get("asset", {}).get("storagePath") or "")

    # 3) 目录条目
    if args.cosmetic_id:
        if not args.cosmetic_id.startswith(args.pack_id + ":"):
            raise SystemExit("--cosmetic-id 的冒号前缀必须等于 --pack-id（后端会拒绝）")
        catalog_payload = {
            "cosmeticId": args.cosmetic_id,
            "name": args.name or args.cosmetic_id,
            "category": args.category or "other",
            "packId": args.pack_id,
            "sortOrder": args.sort_order,
            "enabled": True,
        }
        if asset_key:
            catalog_payload["assetKey"] = asset_key
            catalog_payload["sha256"] = thumb_sha
            catalog_payload["bytes"] = len(thumb_bytes)
        status, payload = site.json("POST", "/api/fashion/admin/catalog", catalog_payload)
        print(f"写目录条目: HTTP {status} {json.dumps(payload, ensure_ascii=False)[:400]}")
        report["catalogUpsert"] = {"status": status, "body": payload}
        if status != 200:
            _write_report(args, report)
            return 1

    # 4) 回读校验
    status, packs = site.json("GET", "/api/fashion/packs")
    published = [p for p in (packs.get("packs") or [])
                 if p.get("id") == args.pack_id and str(p.get("version")) == args.version]
    print(f"公开清单 /api/fashion/packs: HTTP {status}，命中 {len(published)} 条")
    if published:
        item = published[0]
        print("  " + json.dumps(item, ensure_ascii=False))
        report["published"] = item
        report["sha256Match"] = str(item.get("sha256", "")).lower() == digest
        report["bytesMatch"] = int(item.get("bytes") or 0) == len(blob)

    status, catalog = site.json("GET", "/api/fashion/admin/catalog")
    hit = [c for c in (catalog.get("items") or []) if c.get("cosmeticId") == args.cosmetic_id]
    print(f"管理端目录 /api/fashion/admin/catalog: HTTP {status}，命中 {len(hit)} 条")
    if hit:
        print("  " + json.dumps(hit[0], ensure_ascii=False))
        report["catalog"] = hit[0]

    ok = report.get("sha256Match") is True and report.get("bytesMatch") is True and bool(hit)
    report["ok"] = ok
    _write_report(args, report)
    print("发布结果：" + ("OK" if ok else "FAILED（见上面输出）"))
    return 0 if ok else 1


def _write_report(args, report: dict) -> None:
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"报告已写入 {args.out}")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
