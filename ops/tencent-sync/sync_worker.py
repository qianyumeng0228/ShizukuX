#!/usr/bin/env python3
"""Poll GitHub releases, pull the six assets from the R2 CDN, and mirror them
from the Tencent Cloud host to Gitee / CNB / JihuLab / GitCode / COS.

This worker runs on a China-side host. GitHub Actions only uploads the assets
to Cloudflare R2; it never uploads large files to the China platforms itself.
"""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import requests
import urllib.request
from urllib.parse import quote
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

GH_REPO = os.environ.get("SHIZUKUX_GH_REPO", "qianyumeng0228/ShizukuX")
CDN = os.environ.get("SHIZUKUX_CDN", "https://cdn.shizukux.xyz")
ROOT = Path(os.environ.get("SHIZUKUX_SYNC_ROOT", "/opt/shizukux-sync"))
STATE = Path(os.environ.get("SHIZUKUX_SYNC_STATE", "/var/lib/shizukux-sync/state.json"))
CACHE = Path(os.environ.get("SHIZUKUX_SYNC_CACHE", "/var/lib/shizukux-sync/assets"))
MANIFESTS = Path(os.environ.get("SHIZUKUX_SYNC_MANIFESTS", "/var/lib/shizukux-sync/manifests"))
LOCK = Path(os.environ.get("SHIZUKUX_SYNC_LOCK", "/var/lib/shizukux-sync/worker.lock"))
RCLONE_CONF = Path(os.environ.get("SHIZUKUX_RCLONE_CONF", str(ROOT / "rclone.conf")))
SYNC_SCRIPT = Path(os.environ.get("SHIZUKUX_SYNC_SCRIPT", str(ROOT / "sync_release.py")))
PLATFORMS = ("gitee", "cnb", "jihulab", "gitcode")
CHUNK = 4 * 1024 * 1024
UA = "ShizukuX-cn-sync/1.0"


def log(message: str) -> None:
    stamp = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S")
    print(f"[{stamp}] {message}", flush=True)


def fail(message: str) -> "NoReturn":
    raise RuntimeError(message)


def http_json(url: str) -> Any:
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    token = os.environ.get("SHIZUKUX_GH_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    for attempt in range(1, 5):
        try:
            response = requests.get(url, headers=headers, timeout=(20, 90))
            response.raise_for_status()
            return response.json()
        except Exception as error:
            if attempt == 4:
                fail(f"GitHub API failed: {url}: {error}")
            time.sleep(3 * attempt)


def cdn_size(name: str) -> int | None:
    url = f"{CDN}/{name}"
    for attempt in range(1, 4):
        try:
            request = urllib.request.Request(
                url,
                method="HEAD",
                headers={"User-Agent": "Mozilla/5.0", "Cache-Control": "no-cache"},
            )
            with urllib.request.urlopen(request, timeout=25) as response:
                value = response.headers.get("Content-Length")
                return int(value) if value is not None else None
        except Exception:
            if attempt == 3:
                return None
            time.sleep(2 * attempt)
    return None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def md5_file(path: Path) -> str:
    digest = hashlib.md5()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def expected_names(tag: str) -> list[str]:
    return [
        f"ShizukuX-{tag}.apk",
        f"ShizukuX-Drop-In-{tag}.apk",
        f"ShizukuX-Compat-Hub-{tag}.apk",
        f"ShizukuX-OPPO-Drop-In-{tag}.apk",
        f"{tag}.zip",
        f"{tag}.tar.gz",
    ]


def download_one(tag: str, name: str, size: int, expected_sha: str | None) -> Path:
    destination = CACHE / tag / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.is_file() and destination.stat().st_size == size:
        if not expected_sha or sha256_file(destination).lower() == expected_sha.lower():
            log(f"cache hit {name}")
            return destination

    url = f"{CDN}/{name}"
    part = destination.with_suffix(destination.suffix + ".part")
    digest = hashlib.sha256()
    written = 0
    try:
        with part.open("wb") as output:
            for start in range(0, size, CHUNK):
                end = min(start + CHUNK - 1, size - 1)
                request = urllib.request.Request(
                    url,
                    headers={
                        "Range": f"bytes={start}-{end}",
                        "User-Agent": "Mozilla/5.0",
                        "Cache-Control": "no-cache",
                    },
                )
                with urllib.request.urlopen(request, timeout=120) as response:
                    data = response.read()
                    if response.status == 200:
                        # CDN ignored Range and returned the whole object.
                        output.seek(0)
                        output.truncate(0)
                        output.write(data)
                        digest = hashlib.sha256(data)
                        written = len(data)
                        break
                    if len(data) != end - start + 1:
                        fail(f"short range for {name}: {start}-{end} got {len(data)}")
                    output.write(data)
                    digest.update(data)
                    written += len(data)
        if written != size:
            fail(f"incomplete download {name}: {written} != {size}")
        actual = digest.hexdigest()
        if expected_sha and actual.lower() != expected_sha.lower():
            fail(f"SHA-256 mismatch for {name}: {actual} != {expected_sha}")
        os.replace(part, destination)
        log(f"downloaded {name} {size} bytes sha256={actual}")
        return destination
    finally:
        if part.exists():
            part.unlink()


def download_assets(tag: str, release: dict[str, Any], assets: dict[str, dict[str, Any]]) -> dict[str, Path]:
    names = expected_names(tag)
    by_name = {str(item.get("name")): item for item in release.get("assets") or []}
    jobs = []
    for name in names:
        asset = by_name.get(name)
        if asset is None:
            fail(f"GitHub release {tag} is missing {name}")
        size = int(asset.get("size") or 0)
        raw_digest = str(asset.get("digest") or "")
        expected_sha = raw_digest.split(":", 1)[1] if raw_digest.startswith("sha256:") else None
        assets[name] = {"size": size, "sha256": expected_sha or ""}
        jobs.append((name, size, expected_sha))

    paths: dict[str, Path] = {}
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = {pool.submit(download_one, tag, *job): job[0] for job in jobs}
        for future in as_completed(futures):
            name = futures[future]
            path = future.result()
            if not assets[name]["sha256"]:
                assets[name]["sha256"] = sha256_file(path)
            paths[name] = path
    return paths


def ready_on_cdn(tag: str, release: dict[str, Any]) -> bool:
    by_name = {str(item.get("name")): item for item in release.get("assets") or []}
    for name in expected_names(tag):
        item = by_name.get(name)
        if item is None:
            log(f"{tag}: GitHub is missing {name}")
            return False
        size = int(item.get("size") or 0)
        remote = cdn_size(name)
        if remote is None:
            log(f"{tag}: CDN not ready: {name}")
            return False
        if remote != size:
            log(f"{tag}: CDN size mismatch: {name}: {remote} != {size}")
            return False
    return True


def git_commit(tag: str) -> str:
    ref = http_json(
        f"https://api.github.com/repos/{GH_REPO}/git/ref/tags/{quote(tag, safe='')}"
    )
    target = ref.get("object") or {}
    sha = str(target.get("sha") or "")
    if not sha:
        fail(f"tag {tag} has no GitHub ref object")
    if target.get("type") == "tag":
        annotated = http_json(str(target.get("url") or ""))
        nested = annotated.get("object") or {}
        sha = str(nested.get("sha") or "")
        if not sha:
            fail(f"tag {tag} has no peeled commit")
    return sha


def write_manifest(tag: str, release: dict[str, Any], assets: dict[str, dict[str, Any]]) -> Path:
    path = MANIFESTS / f"{tag}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "tag": tag,
        "repository": GH_REPO,
        "prerelease": bool(release.get("prerelease", False)),
        "body": str(release.get("body") or f"Release {tag}"),
        "assets": assets,
    }
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def platform_env() -> dict[str, str]:
    env = os.environ.copy()
    env["GIT_TERMINAL_PROMPT"] = "0"
    env.setdefault("GITCODE_TAG_TIMEOUT", "180")
    return env


def run_platform(platform: str, tag: str, manifest: Path, asset_dir: Path, commit: str, dry_run: bool = False) -> None:
    command = [
        sys.executable,
        str(SYNC_SCRIPT),
        platform,
        "--manifest",
        str(manifest),
        "--assets",
        str(asset_dir),
        "--commit",
        commit,
    ]
    if dry_run:
        command.append("--dry-run")
    log(f"platform {platform}: starting")
    result = subprocess.run(
        command,
        cwd=str(ROOT),
        text=True,
        capture_output=True,
        env=platform_env(),
        timeout=3600,
    )
    for line in (result.stdout + result.stderr).splitlines():
        print(f"  {platform}: {line}", flush=True)
    if result.returncode != 0:
        fail(f"platform {platform} failed with exit {result.returncode}")


def run_rclone(args: list[str], check: bool = True) -> subprocess.CompletedProcess[str]:
    command = ["rclone", *args, "--config", str(RCLONE_CONF)]
    result = subprocess.run(command, text=True, capture_output=True, timeout=1800)
    for line in (result.stdout + result.stderr).splitlines():
        print(f"  rclone: {line}", flush=True)
    if check and result.returncode != 0:
        fail(f"rclone failed with exit {result.returncode}")
    return result


def sync_cos(asset_dir: Path) -> None:
    bucket = os.environ.get("COS_BUCKET")
    if not bucket:
        fail("COS_BUCKET is empty")
    run_rclone(
        [
            "copy",
            str(asset_dir),
            f"cos:{bucket}/",
            "--ignore-times",
            "--s3-no-check-bucket",
            "--s3-upload-cutoff",
            "5Mi",
            "--s3-upload-concurrency",
            "4",
            "--transfers",
            "2",
            "--retries",
            "3",
            "--low-level-retries",
            "5",
            "--timeout",
            "60s",
            "--contimeout",
            "20s",
            "-v",
        ]
    )
    for path in sorted(asset_dir.iterdir()):
        if not path.is_file():
            continue
        local = md5_file(path)
        remote = run_rclone(
            ["md5sum", f"cos:{bucket}/{path.name}", "--s3-no-check-bucket"],
            check=False,
        )
        if remote.returncode != 0:
            fail(f"COS md5sum failed for {path.name}")
        value = (remote.stdout or "").split()
        if not value or value[0].lower() != local.lower():
            fail(f"COS MD5 mismatch for {path.name}: local={local} remote={value[0] if value else 'none'}")
        log(f"COS OK {path.name} md5={local}")


def atomic_write(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def load_state() -> dict[str, Any]:
    if STATE.is_file():
        try:
            return json.loads(STATE.read_text(encoding="utf-8"))
        except ValueError:
            fail(f"state file is not JSON: {STATE}")
    return {"baseline": datetime.now(timezone.utc).timestamp(), "tags": {}}


def process_release(tag: str, release: dict[str, Any], state: dict[str, Any], dry_run: bool = False) -> bool:
    if not ready_on_cdn(tag, release):
        return False
    entry = state.setdefault("tags", {}).setdefault(tag, {"platforms": {}, "cos": False})
    asset_dir = CACHE / tag
    asset_dir.mkdir(parents=True, exist_ok=True)

    assets: dict[str, dict[str, Any]] = {}
    paths = download_assets(tag, release, assets)
    manifest = write_manifest(tag, release, assets)
    commit = git_commit(tag)
    log(f"{tag}: commit={commit} manifest={manifest}")

    if dry_run:
        for platform in PLATFORMS:
            run_platform(platform, tag, manifest, asset_dir, commit, dry_run=True)
        log(f"{tag}: dry-run complete; no uploads and no state changes")
        return False

    changed = False
    for platform in PLATFORMS:
        platforms = entry.setdefault("platforms", {})
        if platforms.get(platform) == "ok":
            continue
        retry_after = float(platforms.get(f"{platform}_retry_after") or 0)
        if retry_after > time.time():
            log(f"{tag}: {platform} retry delayed until {datetime.fromtimestamp(retry_after).isoformat()}")
            continue
        try:
            run_platform(platform, tag, manifest, asset_dir, commit)
            platforms[platform] = "ok"
            platforms.pop(f"{platform}_retry_after", None)
            changed = True
        except Exception as error:
            platforms[platform] = "failed"
            platforms[f"{platform}_retry_after"] = time.time() + 600
            log(f"{tag}: {platform} FAILED: {error}")
        entry["updated"] = datetime.now(timezone.utc).isoformat()
        atomic_write(STATE, state)

    if not entry.get("cos"):
        try:
            sync_cos(asset_dir)
            entry["cos"] = True
            changed = True
        except Exception as error:
            log(f"{tag}: COS FAILED: {error}")
        entry["updated"] = datetime.now(timezone.utc).isoformat()
        atomic_write(STATE, state)

    if entry.get("cos") and all(entry.get("platforms", {}).get(p) == "ok" for p in PLATFORMS):
        log(f"{tag}: all platforms and COS are complete")
        return True
    log(f"{tag}: pending platforms={entry.get('platforms')} cos={entry.get('cos')}")
    return changed


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", help="process only this tag")
    parser.add_argument("--dry-run", action="store_true", help="download and render the plan without uploading")
    args = parser.parse_args()

    STATE.parent.mkdir(parents=True, exist_ok=True)
    LOCK.parent.mkdir(parents=True, exist_ok=True)
    with LOCK.open("w") as lock_handle:
        try:
            fcntl.flock(lock_handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            log("another worker is already running; exiting")
            return 0

        state = load_state()
        releases = http_json(
            f"https://api.github.com/repos/{GH_REPO}/releases?per_page=20"
        )
        releases = [item for item in releases if not item.get("draft")]
        if args.tag:
            releases = [item for item in releases if item.get("tag_name") == args.tag]
        else:
            baseline = float(state.get("baseline") or 0)
            releases = [
                item
                for item in releases
                if float(item.get("published_at") and datetime.fromisoformat(
                    item["published_at"].replace("Z", "+00:00")
                ).timestamp() or 0) > baseline
            ]
        log(f"releases to inspect={len(releases)}")

        for release in sorted(releases, key=lambda item: item.get("published_at") or ""):
            tag = str(release.get("tag_name") or "")
            if not tag:
                continue
            try:
                process_release(tag, release, state, dry_run=args.dry_run)
            except Exception as error:
                log(f"{tag}: processing failed: {error}")
            atomic_write(STATE, state)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except RuntimeError as error:
        print(f"::error::{error}", flush=True)
        raise SystemExit(1)
