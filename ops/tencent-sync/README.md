# ShizukuX 国内同步 Worker（腾讯云）

GitHub Actions 只负责发布 GitHub Release、上传 Cloudflare R2 和清理 COS 孤儿对象；国内平台的大文件同步由这台腾讯云 Ubuntu 主机完成。这样 Gitee / CNB / JihuLab / GitCode / COS 的慢速上传不再占用美国 runner。

## 工作流

1. GitHub Release 发布后，`sync-cos.yml` 的 `r2` job 把 6 个资产上传到 R2。
2. 腾讯云上的 `shizukux-sync.timer` 每 2 分钟运行一次 worker。
3. Worker 通过 GitHub Releases API 发现新 Release，先对 `cdn.shizukux.xyz` 逐个资产发 `HEAD`，确认 6 个资产都已在 R2 可见。
4. 用 GitHub API 的 `assets[].digest` 做 SHA-256 校验，从 CDN 以 4 MB Range 分片、3 线程下载到 `/var/lib/shizukux-sync/assets/<tag>/`。已存在且大小、SHA-256 匹配的文件直接复用，不重复下载。
5. 调用 `.github/scripts/sync_release.py` 依次同步 Gitee、CNB、JihuLab、GitCode；每个平台成功后写入状态，失败后 10 分钟再试。
6. 用 rclone 把 6 个资产上传到 COS，并逐个比对本地 MD5 与 COS MD5。
7. 状态写入 `/var/lib/shizukux-sync/state.json`；同一个 tag 的平台和 COS 都完成后不再重复执行。

首次部署时如果 `state.json` 不存在，worker 会把当前时间记为 baseline，只处理 baseline 之后的新 Release，避免把历史几十个 Release 重新同步一遍。测试单个已有 Release 用：

```bash
/opt/shizukux-sync/run.sh --tag v13.7.0.k2018-e2e2
```

只渲染计划、不下载、不上传：

```bash
/opt/shizukux-sync/run.sh --tag v13.7.0.k2018-e2e2 --dry-run
```

## 服务器目录

| 路径 | 用途 |
| --- | --- |
| `/opt/shizukux-sync/sync_worker.py` | 轮询、下载、校验、状态机 |
| `/opt/shizukux-sync/sync_release.py` | 各平台发布逻辑 |
| `/opt/shizukux-sync/venv` | Python 虚拟环境，只装 `requests` |
| `/opt/shizukux-sync/rclone.conf` | COS 的 rclone 配置，权限 600 |
| `/etc/shizukux-sync.env` | 平台与 COS 凭据，权限 640，owner `root:ubuntu` |
| `/var/lib/shizukux-sync/assets` | 资产缓存，可安全清理；下次会重新下载 |
| `/var/lib/shizukux-sync/manifests` | 每个 tag 的 manifest，供 `sync_release.py` 使用 |
| `/var/lib/shizukux-sync/state.json` | 每个 tag / 平台的完成状态 |

## 部署

以 `ubuntu` 用户执行，凭据只放在 `/etc/shizukux-sync.env`：

```bash
sudo mkdir -p /opt/shizukux-sync /var/lib/shizukux-sync/assets /var/lib/shizukux-sync/manifests
sudo install -m 755 ops/tencent-sync/sync_worker.py /opt/shizukux-sync/sync_worker.py
sudo install -m 755 .github/scripts/sync_release.py /opt/shizukux-sync/sync_release.py
sudo install -m 755 ops/tencent-sync/run.sh /opt/shizukux-sync/run.sh
sudo install -m 600 ops/tencent-sync/rclone.conf.example /opt/shizukux-sync/rclone.conf
# 编辑 /opt/shizukux-sync/rclone.conf，填入 COS 凭据
sudo install -m 640 -o root -g ubuntu ops/tencent-sync/env.example /etc/shizukux-sync.env
# 编辑 /etc/shizukux-sync.env，填入真实 token
sudo install -m 644 ops/tencent-sync/shizukux-sync.service /etc/systemd/system/
sudo install -m 644 ops/tencent-sync/shizukux-sync.timer /etc/systemd/system/
sudo chown -R ubuntu:ubuntu /opt/shizukux-sync /var/lib/shizukux-sync
sudo chmod 600 /opt/shizukux-sync/rclone.conf

cd /opt/shizukux-sync
python3 -m venv venv
./venv/bin/pip install requests

sudo apt-get install -y rclone
sudo systemctl daemon-reload
sudo systemctl enable --now shizukux-sync.timer
```

检查：

```bash
systemctl list-timers shizukux-sync.timer
sudo journalctl -u shizukux-sync.service -n 100 --no-pager
```

## 已处理的平台问题

- **Gitee 大文件**：`requests` 的 multipart 上传 14 MB APK 会在写 socket 时超时，GitHub runner 和腾讯云都会复现。改用 `curl --http1.1` 后，14 MB 资产约 40 秒上传成功；`sync_release.py` 已改为 curl，并在上传后回读 SHA-256。
- **COS**：rclone 必须加 `--s3-no-check-bucket`；使用 5 Mi 分片、2 路并发；上传后逐个 `rclone md5sum` 比对。
- **GitHub runner 直传 COS**：已从 `sync-cos.yml` 删除 `cos` job，只保留 R2 和每日孤儿清理。
- **GitHub runner 直传国内平台**：`publish-platforms.yml` 已移除 `release` 触发，只保留手动 `workflow_dispatch` 作为应急回退。

## GitCode 的限制

GitCode 仓库是 Pull Mirror，服务端禁止直接 `git push`（会返回 403 “repository is an image repository”），也没有公开的强制同步 API。Worker 只能轮询等待镜像交付 tag，超时后标记失败并在 10 分钟后重试。

如果 GitCode 设置页显示“更新失败”，需要在 `https://gitcode.com/gcw_FQOFyAur/ShizukuX/setting/mirror` 点“立即同步”并确认；镜像成功后再运行一次 worker 即可补发 Release 和 6 个附件。

## 运维

强制重跑一个 tag：

```bash
sudo -u ubuntu /opt/shizukux-sync/run.sh --tag v13.7.0.k2018-e2e2
```

清除某个 tag 的失败状态：

```bash
sudo -u ubuntu python3 - <<'PY'
import json
p='/var/lib/shizukux-sync/state.json'
d=json.load(open(p))
d['tags'].pop('v13.7.0.k2018-e2e2',None)
json.dump(d,open(p,'w'),indent=2,ensure_ascii=False)
PY
```

不要删除 `/etc/shizukux-sync.env`；它包含平台 token 和 COS 密钥，权限应保持 `640 root:ubuntu`。
