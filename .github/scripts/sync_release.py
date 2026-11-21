#!/usr/bin/env python3
"""Publish a GitHub Release to the ShizukuX mirror platforms.

The script is intentionally self-contained: GitHub Actions installs only
`requests`, and every platform token is read from the environment.

Platform coverage:
  * Gitee      - git tag push + release body + APK attachments
  * CNB        - git tag push + release body + all six assets
  * JihuLab    - git tag push + Generic Package APKs + release asset links
  * GitCode    - waits for the Pull Mirror tag, then release + six attachments

The script is idempotent. Existing releases are updated, assets with the
expected byte size are skipped, and every uploaded APK is downloaded back and
SHA-256 checked unless --no-deep-verify is supplied.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote

import requests

GH_REPO = os.environ.get("SHIZUKUX_GH_REPO", "qianyumeng0228/ShizukuX")
GITEE_REPO = os.environ.get("SHIZUKUX_GITEE_REPO", "qianyumeng0228/ShizukuX")
CNB_REPO = os.environ.get("SHIZUKUX_CNB_REPO", "ShizukuX/ShizukuX")
JL_PROJECT = os.environ.get("SHIZUKUX_JL_PROJECT", "388741")
JL_REPO = os.environ.get("SHIZUKUX_JL_REPO", "qianyumeng0228/ShizukuX")
GC_OWNER = os.environ.get("SHIZUKUX_GC_OWNER", "gcw_FQOFyAur")
GC_REPO = os.environ.get("SHIZUKUX_GC_REPO", "ShizukuX")
GC_SITE = f"https://gitcode.com/{GC_OWNER}/{GC_REPO}"

UA = "ShizukuX-release-sync/1.0"
PLATFORMS = ("gitee", "cnb", "jihulab", "gitcode")

GC_PRE_QUOTA_NOTE = (
    "\n\n---\n\n"
    "> **GitCode 平台说明**：GitCode 当前预发布名额已满（上限 20 个），"
    "本 Release 在 GitCode 以正式状态展示；构建本身仍按 GitHub 的预发布通道发布。\n"
)


def log(message: str) -> None:
    stamp = datetime.now(timezone.utc).strftime("%H:%M:%S")
    print(f"[{stamp}] {message}", flush=True)


def fail(message: str) -> "NoReturn":
    raise RuntimeError(message)


def env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        fail(f"missing environment variable: {name}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_url(url: str, headers: dict[str, str] | None = None, timeout: int = 300) -> str:
    digest = hashlib.sha256()
    request_headers = {"User-Agent": UA, "Accept": "*/*"}
    if headers:
        request_headers.update(headers)
    with requests.get(
        url,
        headers=request_headers,
        stream=True,
        allow_redirects=True,
        timeout=(30, timeout),
    ) as response:
        if response.status_code != 200:
            fail(f"download verification failed: HTTP {response.status_code} {url}")
        for chunk in response.iter_content(chunk_size=1024 * 1024):
            if chunk:
                digest.update(chunk)
    return digest.hexdigest()


def head_size(url: str, headers: dict[str, str] | None = None) -> int | None:
    request_headers = {"User-Agent": UA, "Accept": "*/*"}
    if headers:
        request_headers.update(headers)
    try:
        response = requests.head(
            url,
            headers=request_headers,
            allow_redirects=True,
            timeout=(20, 90),
        )
        if response.status_code == 200:
            length = response.headers.get("Content-Length")
            if length and length.isdigit():
                return int(length)
        # Some object stores do not answer HEAD correctly; try a one-byte range.
        response = requests.get(
            url,
            headers={**request_headers, "Range": "bytes=0-0"},
            stream=True,
            allow_redirects=True,
            timeout=(20, 90),
        )
        try:
            if response.status_code not in (200, 206):
                return None
            content_range = response.headers.get("Content-Range", "")
            if "/" in content_range:
                total = content_range.rsplit("/", 1)[1]
                if total.isdigit():
                    return int(total)
            length = response.headers.get("Content-Length")
            if response.status_code == 200 and length and length.isdigit():
                return int(length)
            return None
        finally:
            response.close()
    except requests.RequestException:
        return None


class Http:
    def __init__(self, attempts: int = 4):
        self.attempts = attempts

    def request(self, method: str, url: str, **kwargs: Any) -> requests.Response:
        last_error: Exception | None = None
        for attempt in range(1, self.attempts + 1):
            try:
                response = requests.request(method, url, timeout=kwargs.pop("timeout", 180), **kwargs)
                if response.status_code in (429, 500, 502, 503, 504) and attempt < self.attempts:
                    delay = min(30, 2 ** attempt)
                    log(f"HTTP {response.status_code} from {url.split('?', 1)[0]}; retry in {delay}s")
                    time.sleep(delay)
                    continue
                return response
            except requests.RequestException as error:
                last_error = error
                if attempt < self.attempts:
                    delay = min(30, 2 ** attempt)
                    log(f"HTTP error ({error}); retry in {delay}s")
                    time.sleep(delay)
                    continue
                raise
        raise RuntimeError(f"HTTP request failed: {last_error}")

    def json(self, method: str, url: str, **kwargs: Any) -> Any:
        response = self.request(method, url, **kwargs)
        if response.status_code >= 400:
            fail(f"{method} {url.split('?', 1)[0]} -> HTTP {response.status_code}: {response.text[:1200]}")
        try:
            return response.json()
        except ValueError:
            fail(f"{method} {url.split('?', 1)[0]} returned non-JSON: {response.text[:1200]}")


HTTP = Http()


def run_git(args: list[str], token: str) -> subprocess.CompletedProcess[str]:
    environment = os.environ.copy()
    environment["GIT_TERMINAL_PROMPT"] = "0"
    result = subprocess.run(
        ["git", *args],
        text=True,
        capture_output=True,
        env=environment,
        timeout=300,
    )
    if result.returncode != 0:
        stdout = (result.stdout + "\n" + result.stderr).replace(token, "***")
        fail(f"git {' '.join(args)} failed: {stdout.strip()[:1600]}")
    return result


def push_tag(platform: str, tag: str, token: str) -> None:
    hosts = {
        "gitee": f"gitee.com/{GITEE_REPO}.git",
        "cnb": f"cnb.cool/{CNB_REPO}.git",
        "jihulab": f"jihulab.com/{JL_REPO}.git",
    }
    host = hosts[platform]
    remote = f"https://oauth2:{quote(token, safe='')}@{host}"
    # The checkout has full history, but fetch the tag explicitly so a manual
    # dispatch can also work from a branch checkout that did not include it.
    run_git(["fetch", "origin", f"refs/tags/{tag}:refs/tags/{tag}", "--force"], token)
    run_git(["push", remote, f"refs/tags/{tag}:refs/tags/{tag}"], token)
    log(f"{platform}: tag {tag} is present")


def expected_github_assets(tag: str) -> list[str]:
    return [
        f"ShizukuX-{tag}.apk",
        f"ShizukuX-Drop-In-{tag}.apk",
        f"ShizukuX-Compat-Hub-{tag}.apk",
        f"ShizukuX-OPPO-Drop-In-{tag}.apk",
        f"{tag}.zip",
        f"{tag}.tar.gz",
    ]


def platform_assets(platform: str, tag: str) -> list[str]:
    apks = [
        f"ShizukuX-{tag}.apk",
        f"ShizukuX-Drop-In-{tag}.apk",
        f"ShizukuX-Compat-Hub-{tag}.apk",
        f"ShizukuX-OPPO-Drop-In-{tag}.apk",
    ]
    if platform == "gitee":
        return apks
    if platform == "jihulab":
        return apks
    if platform == "cnb":
        return apks + [f"{tag}.zip", f"{tag}.tar.gz"]
    if platform == "gitcode":
        return apks + [f"{tag}-source.zip", f"{tag}-source.tar.gz"]
    fail(f"unknown platform: {platform}")


def transform_body(body: str, tag: str, platform: str, commit: str = "") -> str:
    github_base = f"https://github.com/{GH_REPO}/releases/download/{tag}/"
    github_archive = f"https://github.com/{GH_REPO}/archive/refs/tags/"

    if platform == "gitee":
        base = f"https://gitee.com/{GITEE_REPO}/releases/download/{tag}/"
        body = body.replace(github_base, base)
        for extension in ("zip", "tar.gz"):
            name = f"{tag}.{extension}"
            body = body.replace(
                f"{base}{name}",
                f"https://gitee.com/{GITEE_REPO}/archive/refs/tags/{name}",
            )
        body = body.replace(
            github_archive,
            f"https://gitee.com/{GITEE_REPO}/archive/refs/tags/",
        )
        return body

    if platform == "cnb":
        base = f"https://cnb.cool/{CNB_REPO}/-/releases/download/{tag}/"
        body = body.replace(github_base, base)
        for extension in ("zip", "tar.gz"):
            name = f"{tag}.{extension}"
            body = body.replace(
                f"{base}{name}",
                f"https://cnb.cool/{CNB_REPO}/-/archive/{name}",
            )
        body = body.replace(
            github_archive,
            f"https://cnb.cool/{CNB_REPO}/-/archive/{tag}.",
        )
        return body

    if platform == "jihulab":
        package_base = (
            f"https://jihulab.com/api/v4/projects/{JL_PROJECT}"
            f"/packages/generic/ShizukuX/{tag}/"
        )
        body = body.replace(github_base, package_base)
        for extension in ("zip", "tar.gz"):
            name = f"{tag}.{extension}"
            body = body.replace(
                f"{package_base}{name}",
                f"https://jihulab.com/{JL_REPO}/-/archive/{tag}/ShizukuX-{name}",
            )
        body = body.replace(
            github_archive,
            f"https://jihulab.com/{JL_REPO}/-/archive/{tag}/",
        )
        body = body.replace(
            "- 本次 Release 共 **6 个资产**：4 个 APK + 2 个源码包",
            "- 本次 Release 提供 **4 个 APK**；平台另自动生成 4 个源码包"
            "（zip / tar.gz / tar.bz2 / tar）",
        )
        body = body.replace(
            "- This release has **6 assets**: 4 APKs plus 2 source archives.",
            "- This release provides **4 APKs**; the platform additionally generates "
            "4 source archives (zip / tar.gz / tar.bz2 / tar).",
        )
        return body

    if platform == "gitcode":
        base = f"https://gitcode.com/{GC_OWNER}/{GC_REPO}/releases/download/{tag}/"
        body = body.replace(github_base, base)
        for extension in ("zip", "tar.gz"):
            name = f"{tag}.{extension}"
            source_name = f"{tag}-source.{extension}"
            body = body.replace(
                f"{base}{name}",
                f"{base}{source_name}",
            )
            body = body.replace(f"[{name}](", f"[{source_name}](")
        body = body.replace(
            github_archive,
            f"https://gitcode.com/{GC_OWNER}/{GC_REPO}/archive/refs/tags/",
        )
        body = body.replace(
            "- 本次 Release 共 **6 个资产**：4 个 APK + 2 个源码包",
            "- 本次 Release 提供 **4 个 APK** 和 **2 个源码包附件**；"
            "平台可能另自动生成源码包",
        )
        body = body.replace(
            "- This release has **6 assets**: 4 APKs plus 2 source archives.",
            "- This release provides **4 APKs** and **2 source archive attachments**; "
            "the platform additionally generates 4 source archives "
            "(zip / tar.gz / tar.bz2 / tar).",
        )
        if commit:
            short = commit[:8]
            body = body.replace(
                f"- 本版源码对应 tag `{tag}`（commit `{short}`），包含 OPPO 更新通道修复与 "
                "`UpdateCheckerAssetSelectionTest` 6 个单测",
                f"- 本版源码包附件由 GitHub tag `{tag}`（commit `{short}`）生成；"
                "GitCode 仓库已同步到同一 commit",
            )
            body = body.replace(
                f"- Source corresponds to tag `{tag}` (commit `{short}`) and includes "
                "the OPPO update-channel fix and the 6-case "
                "`UpdateCheckerAssetSelectionTest`.",
                f"- The source archive attachments were generated from GitHub tag `{tag}` "
                f"(commit `{short}`); the GitCode repository mirror may lag behind, "
                "so use the attached archives as the authoritative source.",
            )
        return body

    fail(f"unknown platform: {platform}")


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        fail(f"cannot read manifest {path}: {error}")
    for key in ("tag", "body", "assets"):
        if key not in manifest:
            fail(f"manifest is missing {key}")
    return manifest


def local_files(manifest: dict[str, Any], assets_dir: Path, names: Iterable[str], platform: str) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for name in names:
        local_name = name
        if platform == "gitcode":
            local_name = name.replace("-source.zip", ".zip").replace("-source.tar.gz", ".tar.gz")
        path = assets_dir / local_name
        if not path.is_file():
            fail(f"missing asset: {path}")
        expected = manifest["assets"].get(local_name)
        if expected and path.stat().st_size != int(expected["size"]):
            fail(
                f"asset size changed after manifest generation: {local_name} "
                f"expected {expected['size']} got {path.stat().st_size}"
            )
        result[name] = path
    return result


def summary_table(rows: list[tuple[str, str, str, str]]) -> None:
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    lines = [
        "| 平台 | Release | 结果 | 说明 |",
        "| --- | --- | --- | --- |",
    ]
    for platform, release, result, detail in rows:
        lines.append(f"| {platform} | {release} | {result} | {detail} |")
    with open(summary_path, "a", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")


# ---------------------------------------------------------------------------
# Gitee
# ---------------------------------------------------------------------------


def gitee_headers(token: str) -> dict[str, str]:
    return {"User-Agent": UA}


GITEE_API = f"https://gitee.com/api/v5/repos/{GITEE_REPO}"


def gitee_find_release(tag: str, token: str) -> dict[str, Any] | None:
    response = HTTP.request(
        "GET",
        f"{GITEE_API}/releases/tags/{tag}",
        params={"access_token": token},
        headers=gitee_headers(token),
    )
    if response.status_code == 404:
        return None
    if response.status_code != 200:
        fail(f"Gitee release lookup HTTP {response.status_code}: {response.text[:1000]}")
    return response.json()


def gitee_create_release(tag: str, body: str, prerelease: bool, commit: str, token: str) -> dict[str, Any]:
    fields = {
        "access_token": token,
        "tag_name": tag,
        "target_commitish": commit,
        "name": f"ShizukuX {tag}",
        "body": body,
        "prerelease": "true" if prerelease else "false",
    }
    # Gitee documents multipart/form-data for release creation.
    files = {key: (None, str(value)) for key, value in fields.items()}
    response = HTTP.request("POST", f"{GITEE_API}/releases", files=files, timeout=300)
    if response.status_code in (200, 201):
        return response.json()
    existing = gitee_find_release(tag, token)
    if existing:
        log("gitee: release already exists, continuing with update")
        return existing
    fail(f"Gitee release create HTTP {response.status_code}: {response.text[:1500]}")


def gitee_update_release(release: dict[str, Any], tag: str, body: str, prerelease: bool, token: str) -> dict[str, Any]:
    release_id = release["id"]
    payload = {
        "tag_name": tag,
        "name": f"ShizukuX {tag}",
        "body": body,
        "prerelease": prerelease,
    }
    response = HTTP.request(
        "PATCH",
        f"{GITEE_API}/releases/{release_id}",
        params={"access_token": token},
        json=payload,
        headers=gitee_headers(token),
    )
    if response.status_code not in (200, 201):
        # A few Gitee deployments only accept form data on this endpoint.
        form = {key: str(value).lower() if isinstance(value, bool) else str(value) for key, value in payload.items()}
        response = HTTP.request(
            "PATCH",
            f"{GITEE_API}/releases/{release_id}",
            params={"access_token": token},
            data=form,
            headers=gitee_headers(token),
        )
    if response.status_code not in (200, 201):
        fail(f"Gitee release update HTTP {response.status_code}: {response.text[:1500]}")
    return response.json()


def gitee_delete_asset(release_id: int, asset_id: int, token: str) -> None:
    response = HTTP.request(
        "DELETE",
        f"{GITEE_API}/releases/{release_id}/attach_files/{asset_id}",
        params={"access_token": token},
        headers=gitee_headers(token),
    )
    if response.status_code not in (200, 204, 404):
        fail(f"Gitee asset delete HTTP {response.status_code}: {response.text[:1000]}")


def gitee_upload_asset(release_id: int, name: str, path: Path, token: str) -> None:
    for attempt in range(1, 4):
        try:
            with path.open("rb") as handle:
                response = requests.post(
                    f"{GITEE_API}/releases/{release_id}/attach_files",
                    params={"access_token": token},
                    files={"file": (name, handle, "application/vnd.android.package-archive")},
                    headers={"User-Agent": UA},
                    timeout=(30, 600),
                )
            if response.status_code in (200, 201):
                log(f"gitee: uploaded {name}")
                return
            if attempt == 3:
                fail(f"Gitee upload {name} HTTP {response.status_code}: {response.text[:1200]}")
        except requests.RequestException as error:
            if attempt == 3:
                fail(f"Gitee upload {name} failed: {error}")
        time.sleep(5 * attempt)


def gitee_verify(release: dict[str, Any], tag: str, body: str, files: dict[str, Path], deep: bool) -> None:
    assets = release.get("assets") or []
    by_name: dict[str, list[dict[str, Any]]] = {}
    for asset in assets:
        by_name.setdefault(str(asset.get("name", "")), []).append(asset)
    for name, path in files.items():
        matches = by_name.get(name, [])
        if not matches:
            fail(f"Gitee asset missing: {name}")
        url = matches[0].get("browser_download_url") or matches[0].get("url")
        if not url:
            fail(f"Gitee asset has no download URL: {name}")
        if deep:
            expected = sha256_file(path)
            verified = False
            for asset in matches:
                candidate = asset.get("browser_download_url") or asset.get("url")
                if not candidate:
                    continue
                digest = sha256_url(candidate, headers={"Referer": "https://gitee.com/"})
                if digest.lower() == expected.lower():
                    verified = True
                    break
            if not verified:
                fail(f"Gitee SHA-256 mismatch for {name}")
            log(f"gitee: SHA-256 verified {name}")
        else:
            size = head_size(url, headers={"Referer": "https://gitee.com/"})
            if size is None or size != path.stat().st_size:
                fail(f"Gitee asset size mismatch: {name} ({size})")
    for source in (f"{tag}.zip", f"{tag}.tar.gz"):
        if source not in by_name:
            log(f"gitee: warning: platform source asset not listed: {source}")
    if str(release.get("body", "")).strip() != body.strip():
        fail("Gitee release body did not round-trip")
    log(f"gitee: verified release id={release.get('id')} assets={len(assets)}")


def sync_gitee(tag: str, body: str, prerelease: bool, commit: str, files: dict[str, Path], deep: bool, force: bool) -> str:
    token = env("GITEE_TOKEN")
    push_tag("gitee", tag, token)
    release = gitee_find_release(tag, token)
    if release is None:
        release = gitee_create_release(tag, body, prerelease, commit, token)
        log(f"gitee: created release id={release['id']}")
    release_id = int(release["id"])
    release = gitee_update_release(release, tag, body, prerelease, token)
    assets = release.get("assets") or []
    by_name: dict[str, list[dict[str, Any]]] = {}
    for asset in assets:
        by_name.setdefault(str(asset.get("name", "")), []).append(asset)
    for name, path in files.items():
        size = path.stat().st_size
        matches = by_name.get(name, [])
        if not force and matches:
            current = head_size(
                matches[0].get("browser_download_url") or matches[0].get("url") or "",
                headers={"Referer": "https://gitee.com/"},
            )
            if current == size:
                log(f"gitee: skip {name} ({size} bytes)")
                continue
            log(f"gitee: {name} exists with size={current}; re-uploading")
        gitee_upload_asset(release_id, name, path, token)
    release = gitee_find_release(tag, token)
    if not release:
        fail("Gitee release disappeared after upload")
    gitee_verify(release, tag, body, files, deep)
    return str(release.get("html_url") or f"https://gitee.com/{GITEE_REPO}/releases/tag/{tag}")


# ---------------------------------------------------------------------------
# CNB
# ---------------------------------------------------------------------------


CNB_API = f"https://api.cnb.cool/{CNB_REPO}"


def cnb_headers(token: str) -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.cnb.api+json",
        "User-Agent": UA,
    }


def cnb_find_release(tag: str, token: str) -> dict[str, Any] | None:
    response = HTTP.request("GET", f"{CNB_API}/-/releases/tags/{tag}", headers=cnb_headers(token))
    if response.status_code == 200:
        return response.json()
    if response.status_code != 404:
        fail(f"CNB release lookup HTTP {response.status_code}: {response.text[:1000]}")
    response = HTTP.request("GET", f"{CNB_API}/-/releases", params={"page_size": 100}, headers=cnb_headers(token))
    if response.status_code != 200:
        fail(f"CNB release list HTTP {response.status_code}: {response.text[:1000]}")
    for release in response.json():
        if release.get("tag_name") == tag:
            return release
    return None


def cnb_create_release(tag: str, body: str, prerelease: bool, token: str) -> dict[str, Any]:
    response = HTTP.request(
        "POST",
        f"{CNB_API}/-/releases",
        headers={**cnb_headers(token), "Content-Type": "application/json"},
        json={
            "tag_name": tag,
            "name": f"ShizukuX {tag}",
            "body": body,
            "draft": False,
            "prerelease": prerelease,
        },
        timeout=300,
    )
    if response.status_code in (200, 201):
        return response.json()
    existing = cnb_find_release(tag, token)
    if existing:
        return existing
    fail(f"CNB release create HTTP {response.status_code}: {response.text[:1500]}")


def cnb_update_release(release: dict[str, Any], tag: str, body: str, prerelease: bool, token: str) -> dict[str, Any]:
    response = HTTP.request(
        "PATCH",
        f"{CNB_API}/-/releases/{release['id']}",
        headers={**cnb_headers(token), "Content-Type": "application/json"},
        json={
            "name": f"ShizukuX {tag}",
            "body": body,
            "draft": False,
            "prerelease": prerelease,
        },
        timeout=300,
    )
    if response.status_code not in (200, 201, 204):
        fail(f"CNB release update HTTP {response.status_code}: {response.text[:1500]}")
    if response.status_code == 204 or not response.text.strip():
        refreshed = cnb_find_release(tag, token)
        if refreshed:
            return refreshed
        return release
    return response.json()


def cnb_upload_asset(release_id: str, name: str, path: Path, token: str) -> None:
    size = path.stat().st_size
    info = HTTP.json(
        "POST",
        f"{CNB_API}/-/releases/{release_id}/asset-upload-url",
        headers={**cnb_headers(token), "Content-Type": "application/json"},
        json={"asset_name": name, "size": size, "overwrite": True},
        timeout=120,
    )
    upload_url = info.get("upload_url")
    verify_url = info.get("verify_url")
    if not upload_url or not verify_url:
        fail(f"CNB upload URL missing for {name}: {info}")
    for attempt in range(1, 4):
        try:
            with path.open("rb") as handle:
                response = requests.put(
                    upload_url,
                    data=handle,
                    headers={
                        "Content-Type": "application/octet-stream",
                        "Content-Length": str(size),
                        "User-Agent": UA,
                    },
                    timeout=(30, 600),
                )
            if response.status_code == 200:
                break
            if attempt == 3:
                fail(f"CNB PUT {name} HTTP {response.status_code}: {response.text[:1000]}")
        except requests.RequestException as error:
            if attempt == 3:
                fail(f"CNB PUT {name} failed: {error}")
        time.sleep(5 * attempt)
    response = requests.post(
        verify_url,
        headers=cnb_headers(token),
        timeout=(30, 180),
    )
    if response.status_code not in (200, 201, 204):
        fail(f"CNB verify {name} HTTP {response.status_code}: {response.text[:1000]}")
    log(f"cnb: uploaded {name}")


def cnb_verify(release: dict[str, Any], tag: str, body: str, files: dict[str, Path], deep: bool) -> None:
    assets = release.get("assets") or []
    by_name: dict[str, list[dict[str, Any]]] = {}
    for asset in assets:
        by_name.setdefault(str(asset.get("name", "")), []).append(asset)
    for name, path in files.items():
        matches = by_name.get(name, [])
        if not matches:
            fail(f"CNB asset missing: {name}")
        asset = next((item for item in matches if int(item.get("size") or 0) == path.stat().st_size), None)
        if asset is None:
            fail(f"CNB asset size mismatch: {name}")
        expected = sha256_file(path)
        hash_value = str(asset.get("hash_value") or "").lower()
        if hash_value and asset.get("hash_algo") == "sha256" and hash_value != expected:
            fail(f"CNB SHA-256 mismatch for {name}: {hash_value} != {expected}")
        if deep and not hash_value:
            url = asset.get("browser_download_url") or asset.get("url")
            if not url:
                fail(f"CNB asset has no download URL: {name}")
            digest = sha256_url(url)
            if digest.lower() != expected.lower():
                fail(f"CNB SHA-256 mismatch for {name}: {digest} != {expected}")
            log(f"cnb: SHA-256 verified {name}")
    if str(release.get("body", "")).strip() != body.strip():
        fail("CNB release body did not round-trip")
    log(f"cnb: verified release id={release.get('id')} assets={len(assets)}")


def sync_cnb(tag: str, body: str, prerelease: bool, commit: str, files: dict[str, Path], deep: bool, force: bool) -> str:
    token = env("CNB_TOKEN")
    push_tag("cnb", tag, token)
    release = cnb_find_release(tag, token)
    if release is None:
        release = cnb_create_release(tag, body, prerelease, token)
        log(f"cnb: created release id={release['id']}")
    release = cnb_update_release(release, tag, body, prerelease, token)
    assets = release.get("assets") or []
    by_name: dict[str, list[dict[str, Any]]] = {}
    for asset in assets:
        by_name.setdefault(str(asset.get("name", "")), []).append(asset)
    for name, path in files.items():
        size = path.stat().st_size
        if not force and any(int(asset.get("size") or 0) == size for asset in by_name.get(name, [])):
            log(f"cnb: skip {name} ({size} bytes)")
            continue
        cnb_upload_asset(str(release["id"]), name, path, token)
    release = cnb_find_release(tag, token)
    if not release:
        fail("CNB release disappeared after upload")
    cnb_verify(release, tag, body, files, deep)
    return str(release.get("html_url") or f"https://cnb.cool/{CNB_REPO}/-/releases/{tag}")


# ---------------------------------------------------------------------------
# JihuLab
# ---------------------------------------------------------------------------


JL_API = f"https://jihulab.com/api/v4/projects/{JL_PROJECT}"


def jl_headers(token: str) -> dict[str, str]:
    return {"PRIVATE-TOKEN": token, "User-Agent": UA}


def jl_package_url(tag: str, name: str) -> str:
    return f"{JL_API}/packages/generic/ShizukuX/{tag}/{name}"


def jl_release(tag: str, token: str) -> dict[str, Any] | None:
    response = HTTP.request("GET", f"{JL_API}/releases/{tag}", headers=jl_headers(token))
    if response.status_code == 404:
        return None
    if response.status_code != 200:
        fail(f"JihuLab release lookup HTTP {response.status_code}: {response.text[:1000]}")
    return response.json()


def jl_upload_package(tag: str, name: str, path: Path, token: str) -> None:
    size = path.stat().st_size
    url = jl_package_url(tag, name)
    current = head_size(url, headers={"PRIVATE-TOKEN": token})
    if current == size:
        log(f"jihulab: skip package {name} ({size} bytes)")
        return
    for attempt in range(1, 4):
        try:
            with path.open("rb") as handle:
                response = requests.put(
                    url,
                    data=handle,
                    headers={
                        **jl_headers(token),
                        "Content-Type": "application/octet-stream",
                        "Content-Length": str(size),
                    },
                    timeout=(30, 600),
                )
            if response.status_code in (200, 201):
                log(f"jihulab: uploaded package {name}")
                return
            if attempt == 3:
                fail(f"JihuLab package {name} HTTP {response.status_code}: {response.text[:1200]}")
        except requests.RequestException as error:
            if attempt == 3:
                fail(f"JihuLab package {name} failed: {error}")
        time.sleep(5 * attempt)


def jl_update_release(tag: str, body: str, token: str) -> dict[str, Any]:
    release = jl_release(tag, token)
    if release is None:
        response = HTTP.request(
            "POST",
            f"{JL_API}/releases",
            headers={**jl_headers(token), "Content-Type": "application/json"},
            json={"name": f"ShizukuX {tag}", "tag_name": tag, "description": body},
            timeout=300,
        )
        if response.status_code not in (200, 201):
            release = jl_release(tag, token)
            if release:
                log("jihulab: release already exists, continuing with update")
            else:
                fail(f"JihuLab release create HTTP {response.status_code}: {response.text[:1500]}")
        else:
            release = response.json()
            log(f"jihulab: created release tag={tag}")
    response = HTTP.request(
        "PUT",
        f"{JL_API}/releases/{tag}",
        headers={**jl_headers(token), "Content-Type": "application/json"},
        json={"name": f"ShizukuX {tag}", "description": body},
        timeout=300,
    )
    if response.status_code not in (200, 201):
        fail(f"JihuLab release update HTTP {response.status_code}: {response.text[:1500]}")
    return response.json()


def jl_ensure_links(tag: str, files: dict[str, Path], token: str) -> None:
    release = jl_release(tag, token)
    if not release:
        fail("JihuLab release not found while adding asset links")
    links = (release.get("assets") or {}).get("links") or []
    existing = {str(link.get("name", "")): link for link in links}
    for name in files:
        url = jl_package_url(tag, name)
        link = existing.get(name)
        if link is None:
            response = HTTP.request(
                "POST",
                f"{JL_API}/releases/{tag}/assets/links",
                headers={**jl_headers(token), "Content-Type": "application/json"},
                json={"name": name, "url": url, "link_type": "package"},
                timeout=120,
            )
            if response.status_code not in (200, 201):
                fail(f"JihuLab link create {name} HTTP {response.status_code}: {response.text[:1000]}")
            log(f"jihulab: linked {name}")
        elif str(link.get("url")) != url or link.get("link_type") != "package":
            response = HTTP.request(
                "PUT",
                f"{JL_API}/releases/{tag}/assets/links/{link['id']}",
                headers={**jl_headers(token), "Content-Type": "application/json"},
                json={"name": name, "url": url, "link_type": "package"},
                timeout=120,
            )
            if response.status_code not in (200, 201):
                fail(f"JihuLab link update {name} HTTP {response.status_code}: {response.text[:1000]}")
            log(f"jihulab: updated link {name}")
        else:
            log(f"jihulab: skip link {name}")


def jl_verify(tag: str, body: str, files: dict[str, Path], deep: bool) -> dict[str, Any]:
    token = env("JIHULAB_TOKEN")
    release = jl_release(tag, token)
    if not release:
        fail("JihuLab release missing during verification")
    links = {str(link.get("name", "")): link for link in (release.get("assets") or {}).get("links") or []}
    for name, path in files.items():
        link = links.get(name)
        if not link:
            fail(f"JihuLab link missing: {name}")
        url = str(link.get("url") or "")
        size = head_size(url, headers={"PRIVATE-TOKEN": token})
        if size is not None and size != path.stat().st_size:
            fail(f"JihuLab package size mismatch for {name}: {size}")
        if deep:
            digest = sha256_url(url, headers={"PRIVATE-TOKEN": token})
            expected = sha256_file(path)
            if digest.lower() != expected.lower():
                fail(f"JihuLab SHA-256 mismatch for {name}: {digest} != {expected}")
            log(f"jihulab: SHA-256 verified {name}")
    if str(release.get("description", "")).strip() != body.strip():
        fail("JihuLab release description did not round-trip")
    sources = (release.get("assets") or {}).get("sources") or []
    if len(sources) < 4:
        log(f"jihulab: warning: expected 4 auto source assets, got {len(sources)}")
    log(f"jihulab: verified release tag={tag} links={len(links)}")
    return release


def sync_jihulab(tag: str, body: str, prerelease: bool, commit: str, files: dict[str, Path], deep: bool, force: bool) -> str:
    token = env("JIHULAB_TOKEN")
    push_tag("jihulab", tag, token)
    for name, path in files.items():
        jl_upload_package(tag, name, path, token)
    release = jl_update_release(tag, body, token)
    jl_ensure_links(tag, files, token)
    jl_verify(tag, body, files, deep)
    return str(release.get("_links", {}).get("self") or f"https://jihulab.com/{JL_REPO}/-/releases/{tag}")


# ---------------------------------------------------------------------------
# GitCode
# ---------------------------------------------------------------------------


GC_API = f"https://api.gitcode.com/api/v5/repos/{GC_OWNER}/{GC_REPO}"


def gc_headers(token: str) -> dict[str, str]:
    return {"PRIVATE-TOKEN": token, "Accept": "application/json", "User-Agent": UA}


def gc_tag_exists(tag: str) -> bool:
    result = subprocess.run(
        ["git", "ls-remote", "--tags", f"https://gitcode.com/{GC_OWNER}/{GC_REPO}.git", f"refs/tags/{tag}"],
        text=True,
        capture_output=True,
        timeout=120,
    )
    if result.returncode != 0:
        log(f"gitcode: ls-remote failed: {(result.stderr or result.stdout).strip()[:500]}")
        return False
    return f"refs/tags/{tag}" in result.stdout


def gc_wait_for_tag(tag: str) -> None:
    timeout = int(os.environ.get("GITCODE_TAG_TIMEOUT", "900"))
    interval = int(os.environ.get("GITCODE_TAG_POLL_INTERVAL", "30"))
    deadline = time.time() + timeout
    while time.time() < deadline:
        if gc_tag_exists(tag):
            log(f"gitcode: mirror tag {tag} is present")
            return
        log(f"gitcode: tag {tag} not in mirror yet; retrying in {interval}s")
        time.sleep(interval)
    fail(
        f"GitCode Pull Mirror did not deliver tag {tag} within {timeout}s. "
        f"Open {GC_SITE}/setting/mirror and click 立即同步, then re-run this workflow."
    )


def gc_release(tag: str, token: str) -> dict[str, Any] | None:
    response = HTTP.request(
        "GET",
        f"{GC_API}/releases/{tag}",
        params={"access_token": token},
        headers=gc_headers(token),
    )
    if response.status_code == 404:
        return None
    if response.status_code != 200:
        fail(f"GitCode release lookup HTTP {response.status_code}: {response.text[:1000]}")
    return response.json()


def gc_create_release(tag: str, body: str, prerelease: bool, commit: str, token: str) -> dict[str, Any]:
    for status in ("pre", "none"):
        response = HTTP.request(
            "POST",
            f"{GC_API}/releases",
            params={"access_token": token},
            headers={**gc_headers(token), "Content-Type": "application/json"},
            json={
                "tag_name": tag,
                "name": f"ShizukuX {tag}",
                "body": body,
                "target_commitish": commit,
                "release_status": status,
            },
            timeout=300,
        )
        if response.status_code in (200, 201):
            release = response.json()
            if prerelease and status == "none":
                log("gitcode: pre-release quota is full; created as a normal release")
            log(f"gitcode: created release tag={tag} status={status}")
            return release
        text = response.text[:1500]
        if status == "pre" and ("At most 20" in text or "pre-release" in text.lower()):
            log("gitcode: pre-release quota reached, retrying as normal release")
            continue
        existing = gc_release(tag, token)
        if existing:
            log("gitcode: release already exists, continuing with update")
            return existing
        fail(f"GitCode release create HTTP {response.status_code}: {text}")
    fail("GitCode release create failed after pre/normal fallback")


def gc_update_release(release: dict[str, Any], tag: str, body: str, token: str) -> dict[str, Any]:
    response = HTTP.request(
        "PATCH",
        f"{GC_API}/releases/{tag}",
        params={"access_token": token},
        headers={**gc_headers(token), "Content-Type": "application/json"},
        json={"name": f"ShizukuX {tag}", "body": body},
        timeout=300,
    )
    if response.status_code not in (200, 201):
        fail(f"GitCode release update HTTP {response.status_code}: {response.text[:1500]}")
    return response.json()


def gc_upload_asset(tag: str, name: str, path: Path, token: str) -> None:
    size = path.stat().st_size
    info = HTTP.json(
        "GET",
        f"{GC_API}/releases/{tag}/upload_url",
        params={"file_name": name, "access_token": token},
        headers=gc_headers(token),
        timeout=120,
    )
    upload_url = info.get("url")
    headers = info.get("headers") or {}
    if not upload_url:
        fail(f"GitCode upload URL missing for {name}: {info}")
    headers = {str(key): str(value) for key, value in headers.items()}
    headers.setdefault("Content-Type", "application/octet-stream")
    headers["Content-Length"] = str(size)
    headers["User-Agent"] = UA
    for attempt in range(1, 4):
        try:
            with path.open("rb") as handle:
                response = requests.put(
                    upload_url,
                    data=handle,
                    headers=headers,
                    timeout=(30, 600),
                )
            if response.status_code == 200:
                log(f"gitcode: uploaded {name}")
                return
            if attempt == 3:
                fail(f"GitCode PUT {name} HTTP {response.status_code}: {response.text[:1000]}")
        except requests.RequestException as error:
            if attempt == 3:
                fail(f"GitCode PUT {name} failed: {error}")
        time.sleep(5 * attempt)


def gc_verify(tag: str, body: str, files: dict[str, Path], deep: bool) -> dict[str, Any]:
    token = env("GITCODE_TOKEN")
    release = gc_release(tag, token)
    if not release:
        fail("GitCode release missing during verification")
    assets = release.get("assets") or []
    attachments = [asset for asset in assets if asset.get("type") == "attach"]
    by_name: dict[str, list[dict[str, Any]]] = {}
    for asset in attachments:
        by_name.setdefault(str(asset.get("name", "")), []).append(asset)
    for name, path in files.items():
        matches = by_name.get(name, [])
        asset = next((item for item in matches if int(item.get("size") or 0) == path.stat().st_size), None)
        if asset is None:
            fail(f"GitCode attachment missing or size mismatch: {name}")
        if deep:
            url = asset.get("browser_download_url") or asset.get("url")
            if not url:
                fail(f"GitCode attachment has no download URL: {name}")
            digest = sha256_url(url)
            expected = sha256_file(path)
            if digest.lower() != expected.lower():
                fail(f"GitCode SHA-256 mismatch for {name}: {digest} != {expected}")
            log(f"gitcode: SHA-256 verified {name}")
    if str(release.get("body", "")).strip() != body.strip():
        fail("GitCode release body did not round-trip")
    sources = [asset for asset in assets if asset.get("type") == "source"]
    if len(sources) < 4:
        log(f"gitcode: warning: expected 4 auto source assets, got {len(sources)}")
    log(f"gitcode: verified release tag={tag} attachments={len(attachments)}")
    return release


def sync_gitcode(tag: str, body: str, prerelease: bool, commit: str, files: dict[str, Path], deep: bool, force: bool) -> str:
    token = env("GITCODE_TOKEN")
    gc_wait_for_tag(tag)
    release = gc_release(tag, token)
    if release is None:
        release = gc_create_release(tag, body, prerelease, commit, token)
    if prerelease and release.get("release_status") != "pre":
        plain = "- 本版本为**预发布版本**（k 前缀），欢迎测试反馈"
        build_label = tag.rsplit(".", 1)[-1]
        note = (
            f"{plain}；GitCode 平台的预发布名额已满 20 个，"
            f"因此本页以正式 Release 状态展示，构建本身仍是 {build_label} 预发布包"
        )
        if plain in body:
            body = body.replace(plain, note, 1)
            log("gitcode: marked the formal release as a pre-release build in the body")
        elif "GitCode 当前预发布名额已满" not in body:
            body = body.rstrip() + GC_PRE_QUOTA_NOTE
            log("gitcode: appended pre-release quota note to body")
    release = gc_update_release(release, tag, body, token)
    attachments = [asset for asset in release.get("assets") or [] if asset.get("type") == "attach"]
    by_name: dict[str, list[dict[str, Any]]] = {}
    for asset in attachments:
        by_name.setdefault(str(asset.get("name", "")), []).append(asset)
    for name, path in files.items():
        size = path.stat().st_size
        if not force and any(int(asset.get("size") or 0) == size for asset in by_name.get(name, [])):
            log(f"gitcode: skip {name} ({size} bytes)")
            continue
        gc_upload_asset(tag, name, path, token)
        time.sleep(1.5)
    release = gc_verify(tag, body, files, deep)
    return str(release.get("html_url") or f"{GC_SITE}/releases/{tag}")


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------


def cmd_matrix(args: argparse.Namespace) -> None:
    value = (args.input or "").strip().lower()
    if not value or value == "all":
        platforms = list(PLATFORMS)
    else:
        platforms = [item.strip() for item in value.split(",") if item.strip()]
        unknown = [item for item in platforms if item not in PLATFORMS]
        if unknown:
            fail(f"unknown platform(s): {', '.join(unknown)}")
    print(json.dumps({"platform": platforms}, separators=(",", ":")))


def cmd_prepare(args: argparse.Namespace) -> None:
    tag = args.tag.strip()
    if not tag:
        fail("tag is empty")
    assets_dir = Path(args.assets)
    meta_path = Path(args.meta)
    if not meta_path.is_file():
        fail(f"release metadata not found: {meta_path}")
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    except ValueError as error:
        fail(f"release metadata is not JSON: {error}")
    body = str(meta.get("body") or "")
    if not body.strip():
        fail("GitHub release body is empty")
    prerelease = bool(meta.get("isPrerelease", meta.get("prerelease", False)))
    expected = expected_github_assets(tag)
    assets: dict[str, dict[str, Any]] = {}
    for name in expected:
        path = assets_dir / name
        if not path.is_file():
            fail(
                f"release {tag} is missing {name}; expected six assets "
                "(4 APK + zip + tar.gz)"
            )
        if path.stat().st_size <= 0:
            fail(f"release asset is empty: {path}")
        assets[name] = {
            "size": path.stat().st_size,
            "sha256": sha256_file(path),
        }
    manifest = {
        "tag": tag,
        "repository": GH_REPO,
        "prerelease": prerelease,
        "body": body,
        "assets": assets,
    }
    Path(args.out).write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    log(f"prepare: tag={tag} prerelease={prerelease} assets={len(assets)}")
    for name, info in assets.items():
        log(f"prepare: {name} {info['size']} bytes {info['sha256']}")
    summary_table(
        [
            (
                "GitHub",
                f"https://github.com/{GH_REPO}/releases/tag/{tag}",
                "manifest ready",
                f"{len(assets)} assets; prerelease={prerelease}",
            )
        ]
    )


def cmd_platform(args: argparse.Namespace) -> None:
    platform = args.platform
    manifest = load_manifest(Path(args.manifest))
    tag = str(manifest["tag"])
    commit = (args.commit or "").strip()
    body = transform_body(str(manifest["body"]), tag, platform, commit)
    prerelease = bool(manifest.get("prerelease", False))
    if not commit:
        fail("--commit is required for GitCode release creation")
    assets_dir = Path(args.assets)
    names = platform_assets(platform, tag)
    files = local_files(manifest, assets_dir, names, platform)

    log(f"{platform}: tag={tag} prerelease={prerelease} assets={len(files)} body={len(body)} chars")
    for name in files:
        log(f"{platform}: plan {name} {files[name].stat().st_size} bytes")

    if args.dry_run:
        for name in files:
            log(f"{platform}: dry-run would ensure {name}")
        if platform == "gitcode":
            log("gitcode: dry-run would wait for the Pull Mirror tag")
        else:
            log(f"{platform}: dry-run would push tag to {platform} remote")
        log(f"{platform}: dry-run body has {len(body)} chars")
        return

    if platform == "gitee":
        url = sync_gitee(tag, body, prerelease, commit, files, not args.no_deep_verify, args.force)
    elif platform == "cnb":
        url = sync_cnb(tag, body, prerelease, commit, files, not args.no_deep_verify, args.force)
    elif platform == "jihulab":
        url = sync_jihulab(tag, body, prerelease, commit, files, not args.no_deep_verify, args.force)
    elif platform == "gitcode":
        url = sync_gitcode(tag, body, prerelease, commit, files, not args.no_deep_verify, args.force)
    else:
        fail(f"unknown platform: {platform}")

    summary_table([(platform, url, "ok", f"{len(files)} assets; tag {tag}")])
    log(f"{platform}: done {url}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    matrix = subparsers.add_parser("matrix", help="emit a GitHub Actions matrix")
    matrix.add_argument("--input", default="all")
    matrix.set_defaults(func=cmd_matrix)

    prepare = subparsers.add_parser("prepare", help="validate assets and build a manifest")
    prepare.add_argument("--tag", required=True)
    prepare.add_argument("--assets", required=True)
    prepare.add_argument("--meta", required=True)
    prepare.add_argument("--out", required=True)
    prepare.set_defaults(func=cmd_prepare)

    platform = subparsers.add_parser("gitee", help="sync to Gitee")
    platform.add_argument("--manifest", required=True)
    platform.add_argument("--assets", required=True)
    platform.add_argument("--commit", required=True)
    platform.add_argument("--dry-run", action="store_true")
    platform.add_argument("--force", action="store_true")
    platform.add_argument("--no-deep-verify", action="store_true")
    platform.set_defaults(func=cmd_platform, platform="gitee")

    for name in ("cnb", "jihulab", "gitcode"):
        platform = subparsers.add_parser(name, help=f"sync to {name}")
        platform.add_argument("--manifest", required=True)
        platform.add_argument("--assets", required=True)
        platform.add_argument("--commit", required=True)
        platform.add_argument("--dry-run", action="store_true")
        platform.add_argument("--force", action="store_true")
        platform.add_argument("--no-deep-verify", action="store_true")
        platform.set_defaults(func=cmd_platform, platform=name)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        args.func(args)
    except RuntimeError as error:
        print(f"::error::{error}", flush=True)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
