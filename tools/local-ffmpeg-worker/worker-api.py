#!/usr/bin/env python3
"""
Worker control API — small JSON HTTP server inside the worker pod.

设计原则：
- stdlib-only，不引依赖，让 Dockerfile 只装 apk python3 就能跑。
- 监听 0.0.0.0:18765，仅暴露在 k8s ClusterIP Service 上；不做鉴权，
  鉴权交给 misu-file-server 的 proxy controller (有 JWT + admin 校验)。
- 状态信息读自 /work/state/state.json，由 worker-linux.sh 持续写入。
- 控制接口（recover-running / retry/<id>）直接操作 hostPath 上的
  transcode-queue 目录，与 worker-linux.sh 使用相同的契约。

Endpoints:
  GET  /api/health                          → 200 {"status":"ok"}
  GET  /api/state                           → state.json 透传
  GET  /api/tasks?bucket=queue|running|done|failed&limit=N
                                            → list .task filenames in bucket
  POST /api/recover-running                 → 把 running/*.task 全部移回 queue/
  POST /api/retry/<task_id>                 → 从 failed/ 把指定 task 移回 queue/
"""

import json
import os
import re
import shutil
import sys
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

USER_FILE_ROOT = Path(os.environ.get("USER_FILE_ROOT", "/data/user-file"))
STATE_DIR = Path(os.environ.get("STATE_DIR", "/work/state"))
LISTEN_PORT = int(os.environ.get("WORKER_API_PORT", "18765"))

QUEUE_DIR = USER_FILE_ROOT / "transcode-queue"
RUNNING_DIR = QUEUE_DIR / "running"
DONE_DIR = QUEUE_DIR / "done"
FAILED_DIR = QUEUE_DIR / "failed"

BUCKET_MAP = {
    "queue": QUEUE_DIR,
    "running": RUNNING_DIR,
    "done": DONE_DIR,
    "failed": FAILED_DIR,
}

STATE_FILE = STATE_DIR / "state.json"
ENCODER_INFO_FILE = STATE_DIR / "encoder.txt"

# task_id 限制为常见的字母数字 / 短横 / 下划线，避免路径穿越
TASK_ID_RE = re.compile(r"^[A-Za-z0-9_\-]+$")


def _list_bucket(bucket: str, limit: int = 50):
    bucket_dir = BUCKET_MAP.get(bucket)
    if bucket_dir is None or not bucket_dir.is_dir():
        return []
    files = []
    for p in bucket_dir.iterdir():
        if p.is_file() and p.suffix == ".task":
            try:
                files.append({
                    "taskId": p.stem,
                    "filename": p.name,
                    "mtime": p.stat().st_mtime,
                    "size": p.stat().st_size,
                })
            except OSError:
                continue
    files.sort(key=lambda x: x["mtime"], reverse=True)
    return files[:limit]


def _recover_running():
    if not RUNNING_DIR.is_dir():
        return []
    moved = []
    QUEUE_DIR.mkdir(parents=True, exist_ok=True)
    for p in RUNNING_DIR.iterdir():
        if p.is_file() and p.suffix == ".task":
            dest = QUEUE_DIR / p.name
            try:
                shutil.move(str(p), str(dest))
                moved.append(p.name)
            except OSError as e:
                sys.stderr.write(f"[worker-api] recover {p.name} failed: {e}\n")
    return moved


def _retry_task(task_id: str):
    if not TASK_ID_RE.match(task_id):
        return False, "invalid task id"
    src = FAILED_DIR / f"{task_id}.task"
    if not src.is_file():
        return False, "task not found in failed/"
    dest = QUEUE_DIR / src.name
    QUEUE_DIR.mkdir(parents=True, exist_ok=True)
    try:
        shutil.move(str(src), str(dest))
    except OSError as e:
        return False, str(e)
    return True, None


class Handler(BaseHTTPRequestHandler):
    server_version = "misu-ffmpeg-worker-api/1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[worker-api] %s - %s\n" % (self.address_string(), fmt % args))

    def _send_json(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_404(self):
        self._send_json(HTTPStatus.NOT_FOUND, {"error": "not found"})

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/health":
            return self._send_json(HTTPStatus.OK, {
                "status": "ok",
                "encoder": ENCODER_INFO_FILE.read_text().strip() if ENCODER_INFO_FILE.is_file() else "unknown",
            })

        if path == "/api/state":
            if not STATE_FILE.is_file():
                return self._send_json(HTTPStatus.OK, {
                    "workerState": "STARTING",
                    "message": "state file not yet written",
                })
            try:
                with STATE_FILE.open("r", encoding="utf-8") as f:
                    body = json.load(f)
            except (OSError, json.JSONDecodeError) as e:
                return self._send_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": f"state parse: {e}"})
            return self._send_json(HTTPStatus.OK, body)

        if path == "/api/tasks":
            qs = parse_qs(parsed.query)
            bucket = (qs.get("bucket") or ["queue"])[0]
            try:
                limit = max(1, min(200, int((qs.get("limit") or ["50"])[0])))
            except ValueError:
                limit = 50
            return self._send_json(HTTPStatus.OK, {
                "bucket": bucket,
                "tasks": _list_bucket(bucket, limit),
            })

        return self._send_404()

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/api/recover-running":
            moved = _recover_running()
            return self._send_json(HTTPStatus.OK, {"recovered": moved, "count": len(moved)})

        if path.startswith("/api/retry/"):
            task_id = path[len("/api/retry/"):]
            ok, err = _retry_task(task_id)
            if not ok:
                return self._send_json(HTTPStatus.BAD_REQUEST, {"error": err})
            return self._send_json(HTTPStatus.OK, {"retried": task_id})

        return self._send_404()


def main():
    server = ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler)
    sys.stderr.write(f"[worker-api] listening on 0.0.0.0:{LISTEN_PORT}\n")
    sys.stderr.write(f"[worker-api] user-file-root={USER_FILE_ROOT} state-dir={STATE_DIR}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        server.shutdown()


if __name__ == "__main__":
    main()
