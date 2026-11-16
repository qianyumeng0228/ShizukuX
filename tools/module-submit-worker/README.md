# 模块提交接收端（Cloudflare Worker）

> 这条链路已独立成项目：`https://github.com/qianyumeng0228/shizukux-adb-modules`（本地
> `F:\shizukux\shizukux-adb-modules`），本目录是历史副本，改动请以新项目为准。

模块商店的「上传模块」需要一个能写入目录仓库的接收端。这个 Worker 接收 App 提交的
ZIP，重新校验后**先入审核队列**，通过审核才写入 `qianyumeng0228/shizukux-modules`：

- 提交：ZIP 暂存到 `pending/<id>-<version>.zip`，并在 `pending/index.json` 记一条待审记录
- 通过：复制到 `modules/<id>-<version>.zip`，更新 `modules.json`（同 id 覆盖为更高 versionCode），
  删除 pending 副本
- 驳回：保留审计记录（`status: rejected` + 备注），删除 pending 副本

`pending/index.json` 本身也是可读的审计日志：每条记录含 ticket、id/version、sha256、
大小、提交时间、提交者 IP 的 SHA-256 前 16 位（不存明文 IP）、审核状态与备注。

## 为什么需要它

商店索引是纯静态文件（`modules.json` / `community.json`），App 只有读路径。没有接收端时，
「上传」只能在本机生效，无法让其他人下载。Worker 是唯一能保持「人人可传」又不把
GitHub 写权限塞进 App 的落点。

GitHub Pages 不能替代它：Pages 只托管静态文件，接不了 POST，也不能回写仓库。

## 部署

```bash
cd tools/module-submit-worker
npx wrangler login                       # 大陆网络需先 export HTTPS_PROXY
npx wrangler kv namespace create SUBMIT_KV   # 把输出的 id 填进 wrangler.toml
npx wrangler secret put GITHUB_TOKEN     # fine-grained PAT，contents: write
npx wrangler secret put ADMIN_TOKEN      # /pending 与 /review 的通行证
npx wrangler secret put SUBMIT_TOKEN     # 可选；设了就会校验 X-Submit-Token
npx wrangler deploy
```

部署完把端点填到客户端：

```
manager/src/main/java/af/shizuku/manager/module/store/ModuleUploadClient.kt
    const val ENDPOINT = "https://submit.zanzhuwang.cc.cd/submit"
```

留空时 App 不会尝试提交：上传面板会改成「导出模块信息」，把 manifest 复制出来，供人工投稿。

## 接口契约

### `POST /submit`（App 用）

`multipart/form-data`：

| part   | 内容 |
| ------ | ---- |
| `meta` | JSON：id/name/version/versionCode/author/description/usesShellBridge/minSdk/sizeBytes/sha256 |
| `file` | 模块 ZIP（≤ 32 MB） |

成功：`202 {"ok":true,"status":"pending","ticket":"t_…","message":"… 已进入审核队列","sha256":"…","sizeBytes":N}`
失败：`{"ok":false,"error":"…"}`，HTTP 400 / 401 / 409 / 429 / 502。

### `GET /pending`（管理端）

需要 `X-Admin-Token`。返回 `{"ok":true,"count":N,"submissions":[…]}`。

### `POST /review`（管理端）

需要 `X-Admin-Token`，JSON：

```json
{"action":"approve","ticket":"t_abc123"}
{"action":"reject","ticket":"t_abc123","reason":"缺少 service.sh 说明"}
```

也接受 `{"action":"approve","id":"upload.selftest","version":"1.0.1"}` 代替 ticket。

通过时会重读 pending ZIP、重算 SHA-256 与记录比对，不一致直接 409（防止审核后被掉包）。

### `GET /healthz`

返回 `{"ok":true,"reviewQueue":true,"rateLimit":true,"adminConfigured":true}`，用于探活与自查配置。

## 管理端命令行

```bash
python tools/module-submit-worker/review.py list
python tools/module-submit-worker/review.py approve t_abc123
python tools/module-submit-worker/review.py reject upload.selftest@1.0.1 --reason "重复提交"
```

令牌优先取 `--token`，其次 `ADMIN_TOKEN` 环境变量，其次 `~/.shizukux_admin_token`。
需要代理时设置 `HTTPS_PROXY`，脚本会沿用。

## 限流

按提交者 IP（`CF-Connecting-IP` 的 SHA-256）在 KV 里计数，三个窗口任一超限即 `429` +
`Retry-After`：

| 窗口 | 上限 |
| ---- | ---- |
| 每分钟 | 3 |
| 每小时 | 10 |
| 每天 | 20 |

没有绑定 `SUBMIT_KV` 时 Worker 仍可工作，但 `/healthz` 会显示 `rateLimit:false`，等于关掉限流。

## 服务端校验

客户端已经校验过一遍，但所有关键字段都**以 ZIP 内容为准重算**，`meta` 只用来补充展示字段：

- 大小 ≤ 32 MB，entry 数 ≤ 2048（读 central directory，不解压整包）
- 必须有 `module.prop`，`id` 符合 `[A-Za-z][A-Za-z0-9._-]{1,63}`，`version` 必填
- SHA-256 由服务端重算；与 `meta.sha256` 不一致直接拒绝
- 已发布同版本、已在队列里的同版本、目录里已有更高 versionCode → 409

## 已知限制

- 32 MB 的 ZIP 会走 Contents API（base64，请求体内约 43 MB）。Workers 允许，但大包会慢；
  如果以后模块变大，应改成 Git Data API 或对象存储 + 索引只存直链。
- 审核通过是同步操作：`/review` 里要读一次 32 MB 的 pending ZIP 再写一次，大包可能接近
  Worker 的 CPU/内存上限；超大模块建议手工把 ZIP 放进 `modules/` 再改索引。
- `community.json` 不由这里写入：它仍是从 GitHub `shevery-modules` topic 汇聚的快照。
- 审核只有「通过 / 驳回」两种结果，没有二次编辑（作者信息等以 `module.prop` 为准）。
