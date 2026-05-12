#!/usr/bin/env bash
# 容器入口：同时拉起 worker-linux.sh (转码主进程) 和 worker-api.py
# (控制面板的 JSON HTTP API)。任一进程退出则整个容器退出，方便 k8s 重启。
#
# SIGTERM 转发：k8s 给 pod 60s grace period，期间需要让 worker-linux.sh
# 的 trap 跑完 recover_running_tasks (把 running/*.task 退回 queue/)。
# 所以这里收到 TERM 时显式 kill -TERM 两个 child，再 wait 它们退完。
set -Eeuo pipefail
cd /app

API_PID=""
WORKER_PID=""

forward_term() {
  [[ -n "$WORKER_PID" ]] && kill -TERM "$WORKER_PID" 2>/dev/null || true
  [[ -n "$API_PID"    ]] && kill -TERM "$API_PID"    2>/dev/null || true
}
trap forward_term TERM INT

/usr/bin/env python3 /app/worker-api.py &
API_PID=$!
echo "[entrypoint] worker-api.py pid=$API_PID"

/app/worker-linux.sh "$@" &
WORKER_PID=$!
echo "[entrypoint] worker-linux.sh pid=$WORKER_PID"

# wait -n 在任一 child 退出时返回；之后再 wait 把另一个也回收掉。
wait -n "$WORKER_PID" "$API_PID" || true
forward_term
wait "$WORKER_PID" 2>/dev/null || true
wait "$API_PID"    2>/dev/null || true
