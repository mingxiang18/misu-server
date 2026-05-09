#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$SCRIPT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"
  set +a
fi

DASHBOARD_PORT="${DASHBOARD_PORT:-18765}"

cd "$SCRIPT_DIR"
mkdir -p .work
if [[ ! -f .work/state.json ]]; then
  printf '{"updatedAt":"","workerState":"IDLE","remote":"","queueDir":"","containerRoot":"","remoteRoot":"","message":"等待 worker 启动","currentTask":null,"tasks":[]}\n' > .work/state.json
fi

printf 'Dashboard: http://127.0.0.1:%s/dashboard.html\n' "$DASHBOARD_PORT"
python3 -m http.server "$DASHBOARD_PORT" --bind 127.0.0.1
