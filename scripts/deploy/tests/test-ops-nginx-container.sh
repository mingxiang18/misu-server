#!/usr/bin/env bash
# Real Nginx container route test. It uses the checked-in sidecar template and
# local mock upstreams; it never contacts a cluster or production endpoint.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TMP_DIR="$(mktemp -d /tmp/misu-ops-nginx-container.XXXXXX)"
MOCK_LOG="${TMP_DIR}/mock.log"
CONTAINER="misu-ops-nginx-test-$$"
NGINX_PORT="${OPS_NGINX_TEST_PORT:-18081}"
trap 'docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true; kill "${MOCK_PID:-0}" >/dev/null 2>&1 || true; rm -rf "${TMP_DIR}"' EXIT

docker info >/dev/null
OPS_MOCK_LOG="${MOCK_LOG}" python3 "${ROOT_DIR}/scripts/deploy/tests/mock-ops-upstreams.py" &
MOCK_PID=$!
sleep 0.2

ruby - "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-ops-nginx-config.yaml" "${TMP_DIR}/nginx.conf" <<'RB'
require 'yaml'
source, destination = ARGV
template = YAML.load_stream(File.read(source)).first.dig('data', 'nginx.conf.template')
raise 'missing nginx template' unless template
template = template.gsub('${OPS_PROXY_SHARED_SECRET}', 'test-secret')
  .gsub('127.0.0.1:30264', 'host.docker.internal:30264')
  .gsub('nacos.misu-server.svc.cluster.local:8848', 'host.docker.internal:18848')
  .gsub('headlamp.kuboard.svc.cluster.local:80', 'host.docker.internal:18080')
File.write(destination, template)
RB

docker run --rm -d --name "${CONTAINER}" --add-host host.docker.internal:host-gateway -p "${NGINX_PORT}:8080" \
  -v "${TMP_DIR}/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:1.27-alpine nginx -g 'daemon off;' >/dev/null
for _ in {1..30}; do
  if curl -sS -o /dev/null -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/_ops/healthz"; then
    break
  fi
  sleep 0.2
done

code=$(curl -sS -o "${TMP_DIR}/unauthorized" -w '%{http_code}' -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/nacos/")
[[ "${code}" == 401 ]]
code=$(curl -sS -o "${TMP_DIR}/nacos" -w '%{http_code}' -H 'Host: api.misu.chat' -H 'Cookie: MISU_OPS_SESSION=nacos-session' "http://127.0.0.1:${NGINX_PORT}/nacos/v1/console/server/state?format=json")
[[ "${code}" == 200 ]]
rg -q 'NACOS_UPSTREAM path=/nacos/v1/console/server/state\?format=json' "${TMP_DIR}/nacos"
code=$(curl -sS -o "${TMP_DIR}/headlamp" -w '%{http_code}' -H 'Host: api.misu.chat' -H 'Cookie: MISU_OPS_SESSION=headlamp-session' "http://127.0.0.1:${NGINX_PORT}/ops/headlamp/c/main/pods")
[[ "${code}" == 200 ]]
rg -q 'HEADLAMP_UPSTREAM path=/ops/headlamp/c/main/pods' "${TMP_DIR}/headlamp"
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/nacos")
[[ "${code}" == 308 ]]
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/unknown")
[[ "${code}" == 404 ]]
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: attacker.example' "http://127.0.0.1:${NGINX_PORT}/nacos/")
[[ "${code}" == 421 ]]

echo 'real nginx container same-host Nacos/Headlamp paths, auth, redirect, unknown path/Host: PASS'
