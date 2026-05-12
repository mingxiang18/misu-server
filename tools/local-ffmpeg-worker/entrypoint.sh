#!/usr/bin/env bash
# 容器入口：未来 M4 会在此处同时拉起 worker-api.py。当前只跑 worker-linux.sh。
set -Eeuo pipefail
cd /app
exec /app/worker-linux.sh "$@"
