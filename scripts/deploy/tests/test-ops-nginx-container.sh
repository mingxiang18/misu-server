#!/usr/bin/env bash
# Real Nginx container route test. It uses the checked-in sidecar template and
# local mock upstreams; it never contacts a cluster or production endpoint.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
TMP_DIR="$(mktemp -d /tmp/misu-ops-nginx-container.XXXXXX)"
MOCK_LOG="${TMP_DIR}/mock.log"
CONTAINER="misu-ops-nginx-test-$$"
MAIN_CONTAINER="misu-server-nginx-test-$$"
NGINX_PORT="${OPS_NGINX_TEST_PORT:-18081}"
MAIN_NGINX_PORT="${OPS_MAIN_NGINX_TEST_PORT:-18082}"
trap 'docker rm -f "${MAIN_CONTAINER}" "${CONTAINER}" >/dev/null 2>&1 || true; kill "${MOCK_PID:-0}" >/dev/null 2>&1 || true; rm -rf "${TMP_DIR}"' EXIT

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

ruby - "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml" "${TMP_DIR}/main-nginx.conf" "${NGINX_PORT}" <<'RB'
require 'yaml'
source, destination, sidecar_port = ARGV
config = YAML.load_stream(File.read(source)).first.dig('data', 'nginx.conf')
raise 'missing main nginx config' unless config
config = config.sub('server misu-ops:30264;', "server host.docker.internal:#{sidecar_port};")
File.write(destination, config)
RB

docker run --rm -d --name "${CONTAINER}" --add-host host.docker.internal:host-gateway -p "${NGINX_PORT}:8080" \
  -v "${TMP_DIR}/nginx.conf:/etc/nginx/nginx.conf:ro" nginx:1.27-alpine nginx -g 'daemon off;' >/dev/null
docker run --rm -d --name "${MAIN_CONTAINER}" --add-host host.docker.internal:host-gateway -p "${MAIN_NGINX_PORT}:30110" \
  -v "${TMP_DIR}/main-nginx.conf:/etc/nginx/nginx.conf:ro" nginx:1.27-alpine nginx -g 'daemon off;' >/dev/null
for _ in {1..30}; do
  if curl -sS -o /dev/null -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/_ops/healthz"; then
    break
  fi
  sleep 0.2
done

code=$(curl -sS -o "${TMP_DIR}/unauthorized" -w '%{http_code}' -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/nacos/")
[[ "${code}" == 401 ]]
code=$(curl -sS -o "${TMP_DIR}/nacos" -w '%{http_code}' -H 'Host: api.misu.chat' -H 'Cookie: MISU_OPS_SESSION=nacos-session' \
  -H 'X-Forwarded-User: attacker' -H 'X-Forwarded-Groups: attacker-group' \
  -H 'X-Forwarded-Group: attacker-group-compat' -H 'X-Forwarded-Email: attacker@example.com' \
  -H 'X-Forwarded-Id-Token: attacker-token' \
  "http://127.0.0.1:${NGINX_PORT}/nacos/v1/console/server/state?format=json")
[[ "${code}" == 200 ]]
rg -q 'NACOS_UPSTREAM path=/nacos/v1/console/server/state\?format=json' "${TMP_DIR}/nacos"
rg -q 'NACOS_UPSTREAM.*user= groups= group= email= id_token=' "${TMP_DIR}/nacos"
rg -q 'AUTH_REQUEST original_uri_present=True target_present=True original_uri_valid=True target_valid=True forwarding_headers_cleared=True' "${MOCK_LOG}"
code=$(curl -sS -o "${TMP_DIR}/headlamp" -w '%{http_code}' -H 'Host: api.misu.chat' -H 'Cookie: MISU_OPS_SESSION=headlamp-session' \
  -H 'X-Forwarded-User: attacker' -H 'X-Forwarded-Groups: attacker-group' \
  -H 'X-Forwarded-Group: attacker-group-compat' -H 'X-Forwarded-Email: attacker@example.com' \
  -H 'X-Forwarded-Id-Token: attacker-token' \
  "http://127.0.0.1:${NGINX_PORT}/ops/headlamp/c/main/pods")
[[ "${code}" == 200 ]]
rg -q 'HEADLAMP_UPSTREAM path=/ops/headlamp/c/main/pods' "${TMP_DIR}/headlamp"
rg -q 'HEADLAMP_UPSTREAM.*user=admin groups= group= email= id_token=' "${TMP_DIR}/headlamp"
code=$(curl -sS -o "${TMP_DIR}/headlamp-token" -w '%{http_code}' -H 'Host: api.misu.chat' -H 'Cookie: MISU_OPS_SESSION=headlamp-session' "http://127.0.0.1:${NGINX_PORT}/ops/headlamp/c/main/token")
[[ "${code}" == 200 ]]
rg -q 'HEADLAMP_UPSTREAM path=/ops/headlamp/c/main/token.*user=admin' "${TMP_DIR}/headlamp-token"
code=$(curl --no-buffer -sS -o "${TMP_DIR}/headlamp-log" -w '%{http_code}' -H 'Host: api.misu.chat' \
  -H 'Cookie: MISU_OPS_SESSION=headlamp-session' \
  "http://127.0.0.1:${MAIN_NGINX_PORT}/ops/headlamp/clusters/main/api/v1/namespaces/default/pods/demo/log?container=app&follow=true")
[[ "${code}" == 200 ]]
rg -q '^log-line-1$' "${TMP_DIR}/headlamp-log"
rg -q '^log-line-2$' "${TMP_DIR}/headlamp-log"
rg -q 'HEADLAMP_LOG_STREAM path=/ops/headlamp/clusters/main/api/v1/namespaces/default/pods/demo/log\?container=app&follow=true' "${MOCK_LOG}"

# Headlamp's /wsMultiplexer carries live resource/log streams over a real
# WebSocket. Exercise both Nginx hops and use mixed-case Upgrade to prove the
# sidecar dispatch is case-insensitive before the Java bridge receives it.
check_ws() {
  local target="$1" cookie="$2" original_uri="$3"
  python3 - "${MAIN_NGINX_PORT}" "${target}" "${cookie}" "${original_uri}" <<'PY'
import socket
import sys

port = int(sys.argv[1])
target = sys.argv[2]
cookie = sys.argv[3]
original_uri = sys.argv[4]
request = (
    f"GET {original_uri} HTTP/1.1\r\n"
    "Host: api.misu.chat\r\n"
    f"Cookie: MISU_OPS_SESSION={cookie}\r\n"
    "Origin: https://server.misu.chat\r\n"
    "Upgrade: WebSocket\r\n"
    "Connection: Upgrade\r\n"
    "Sec-WebSocket-Version: 13\r\n"
    "Sec-WebSocket-Key: dGVzdC1vcHMtd3MtaGFuZHNoYWtl\r\n"
    "\r\n"
).encode("ascii")
with socket.create_connection(("127.0.0.1", port), timeout=5) as client:
    client.sendall(request)
    response = b""
    while b"\r\n\r\n" not in response:
        chunk = client.recv(4096)
        if not chunk:
            break
        response += chunk
    headers = response.decode("latin1")
    assert headers.startswith("HTTP/1.1 101 "), headers
    normalized = headers.lower()
    assert "\r\nupgrade: websocket\r\n" in normalized, headers
    assert "\r\nconnection: upgrade\r\n" in normalized, headers
PY
}
check_ws headlamp headlamp-session /ops/headlamp/wsMultiplexer
check_ws nacos nacos-session /nacos/wsMultiplexer
rg -q 'WS_BACKEND path=/ops/ws/console/headlamp original=/ops/headlamp/wsMultiplexer target=headlamp cookie=MISU_OPS_SESSION=headlamp-session upstream-cookie=UPSTREAM_TOKEN=clean upstream-auth=Bearer upstream user=admin groups= group= email= id_token=' "${MOCK_LOG}"
rg -q 'WS_BACKEND path=/ops/ws/console/nacos original=/nacos/wsMultiplexer target=nacos cookie=MISU_OPS_SESSION=nacos-session upstream-cookie=UPSTREAM_TOKEN=clean upstream-auth=Bearer upstream user= groups= group= email= id_token=' "${MOCK_LOG}"
code=$(curl -sS -D "${TMP_DIR}/exchange-headers" -o "${TMP_DIR}/exchange" -w '%{http_code}' -X POST -H 'Host: api.misu.chat' \
  -H 'Origin: https://server.misu.chat' -H 'Forwarded: for=203.0.113.9' \
  -H 'X-Forwarded-For: 203.0.113.9' -H 'X-Real-IP: 203.0.113.9' \
  -d 'ticket=valid' "http://127.0.0.1:${NGINX_PORT}/nacos/_ops/exchange")
[[ "${code}" == 303 ]]
rg -qi '^Set-Cookie: MISU_OPS_SESSION=nacos-session; Path=/nacos/;' "${TMP_DIR}/exchange-headers"
rg -qi '^Set-Cookie: MISU_OPS_SESSION=; Path=/; Max-Age=0;' "${TMP_DIR}/exchange-headers"
rg -qi 'SameSite=None' "${TMP_DIR}/exchange-headers"
rg -qi 'Secure' "${TMP_DIR}/exchange-headers"
rg -qi 'HttpOnly' "${TMP_DIR}/exchange-headers"
if rg -qi '^X-Frame-Options:' "${TMP_DIR}/exchange-headers"; then
  echo 'upstream X-Frame-Options was not hidden' >&2
  exit 1
fi
rg -qi '^Content-Security-Policy: frame-ancestors https://server\.misu\.chat' "${TMP_DIR}/exchange-headers"
if rg -qi 'evil\.example' "${TMP_DIR}/exchange-headers"; then
  echo 'upstream Content-Security-Policy was not hidden' >&2
  exit 1
fi
if rg -q 'EXCHANGE headers=.*203\.0\.113\.9' "${MOCK_LOG}"; then
  echo 'exchange forwarded headers were not cleared' >&2
  exit 1
fi
rg -q 'EXCHANGE headers=,,,' "${MOCK_LOG}"
code=$(curl -sS -o "${TMP_DIR}/nacos-after-exchange" -w '%{http_code}' -H 'Host: api.misu.chat' \
  -H 'Cookie: MISU_OPS_SESSION=nacos-session' \
  "http://127.0.0.1:${NGINX_PORT}/nacos/v1/console/server/state?format=json")
[[ "${code}" == 200 ]]
rg -q 'NACOS_UPSTREAM path=/nacos/v1/console/server/state\?format=json' "${TMP_DIR}/nacos-after-exchange"
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/nacos")
[[ "${code}" == 308 ]]
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: api.misu.chat' "http://127.0.0.1:${NGINX_PORT}/unknown")
[[ "${code}" == 404 ]]
code=$(curl -sS -o /dev/null -w '%{http_code}' -H 'Host: attacker.example' "http://127.0.0.1:${NGINX_PORT}/nacos/")
[[ "${code}" == 421 ]]

echo 'real nginx container same-host Nacos/Headlamp paths, auth, exchange header clearing, redirect, unknown path/Host: PASS'
