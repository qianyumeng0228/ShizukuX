#!/usr/bin/env bash
set -euo pipefail

set -a
# shellcheck disable=SC1091
. /etc/shizukux-sync.env
set +a

cd /opt/shizukux-sync
exec /opt/shizukux-sync/venv/bin/python /opt/shizukux-sync/sync_worker.py "$@"
