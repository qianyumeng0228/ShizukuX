#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ShizukuX 模块提交流程的管理端：查看待审队列、通过或驳回。

用法：
  python review.py list
  python review.py approve t_abc123        # 也可以写 upload.selftest@1.0.1
  python review.py reject  upload.selftest@1.0.1 --reason "缺少 service.sh 说明"

管理员令牌来源（按顺序）：
  --token 参数 > 环境变量 ADMIN_TOKEN > 文件 ~/.shizukux_admin_token

默认端点为 https://submit.zanzhuwang.cc.cd，可用 --endpoint 或 SHIZUKUX_SUBMIT_ENDPOINT 覆盖。
网络需要代理时，设置 HTTPS_PROXY（脚本会沿用环境变量）。
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_ENDPOINT = "https://submit.zanzhuwang.cc.cd"
TOKEN_FILE = Path.home() / ".shizukux_admin_token"


def load_token(explicit):
    if explicit:
        return explicit
    env = os.environ.get("ADMIN_TOKEN")
    if env:
        return env.strip()
    if TOKEN_FILE.exists():
        return TOKEN_FILE.read_text(encoding="utf-8").strip()
    return ""


def request(method, url, token, payload=None):
    data = json.dumps(payload).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("X-Admin-Token", token)
    req.add_header("User-Agent", "ShizukuX-ReviewCLI/1.0")
    if data:
        req.add_header("Content-Type", "application/json")
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    handlers = [urllib.request.ProxyHandler({"https": proxy, "http": proxy})] if proxy else []
    opener = urllib.request.build_opener(*handlers)
    try:
        with opener.open(req, timeout=30) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(body)
        except Exception:
            parsed = {"ok": False, "error": body}
        return e.code, parsed


def cmd_list(endpoint, token):
    status, body = request("GET", f"{endpoint}/pending", token)
    if status != 200 or not body.get("ok"):
        print(f"读取队列失败 HTTP {status}: {body.get('error')}", file=sys.stderr)
        return 1
    items = body.get("submissions") or []
    print(f"待审 {len(items)} 条")
    for s in items:
        print(
            "  {ticket}  {id} v{version} (code {code})  {size} B  "
            "{name}  sha={sha8}…  提交于 {when}".format(
                ticket=s.get("ticket"),
                id=s.get("id"),
                version=s.get("version"),
                code=s.get("versionCode"),
                size=s.get("sizeBytes"),
                name=s.get("name"),
                sha8=(s.get("sha256") or "")[:8],
                when=s.get("submittedAt"),
            )
        )
    return 0


def cmd_review(action, target, endpoint, token, reason):
    if "@" in target and not target.startswith("t_"):
        module_id, version = target.split("@", 1)
        payload = {"action": action, "id": module_id, "version": version}
    else:
        payload = {"action": action, "ticket": target}
    if reason:
        payload["reason"] = reason
    status, body = request("POST", f"{endpoint}/review", token, payload)
    if status != 200 or not body.get("ok"):
        print(f"审核失败 HTTP {status}: {body.get('error')}", file=sys.stderr)
        return 1
    print(json.dumps(body, ensure_ascii=False, indent=2))
    return 0


def main():
    parser = argparse.ArgumentParser(description="ShizukuX 模块提交审核")
    parser.add_argument("command", choices=["list", "approve", "reject"])
    parser.add_argument("target", nargs="?", help="ticket 或 <id>@<version>")
    parser.add_argument("--reason", default="", help="通过/驳回备注")
    parser.add_argument("--token", default="", help="管理员令牌")
    parser.add_argument(
        "--endpoint",
        default=os.environ.get("SHIZUKUX_SUBMIT_ENDPOINT", DEFAULT_ENDPOINT),
    )
    args = parser.parse_args()

    token = load_token(args.token)
    if not token:
        print("缺少管理员令牌：用 --token、ADMIN_TOKEN 或写入 ~/.shizukux_admin_token", file=sys.stderr)
        return 2
    endpoint = args.endpoint.rstrip("/")

    if args.command == "list":
        return cmd_list(endpoint, token)
    if not args.target:
        print(f"{args.command} 需要 ticket 或 <id>@<version>", file=sys.stderr)
        return 2
    return cmd_review(args.command, args.target, endpoint, token, args.reason)


if __name__ == "__main__":
    sys.exit(main())
