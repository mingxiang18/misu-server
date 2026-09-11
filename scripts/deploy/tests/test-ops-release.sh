#!/usr/bin/env bash
# Offline deployment harness. It replaces ssh/scp only; no cluster, registry,
# Docker daemon, or production host is contacted.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
EVIDENCE_FILE="${EVIDENCE_FILE:-/tmp/misu-ops-release-test-evidence.txt}"
TRACE_FILE="${EVIDENCE_FILE%.txt}.ssh.log"
TMP_DIR="$(mktemp -d /tmp/misu-ops-release-test.XXXXXX)"
FAKE_BIN="${TMP_DIR}/bin"
CAPTURE_DIR="${TMP_DIR}/capture"
SSH_LOG="${TMP_DIR}/ssh.log"
mkdir -p "${FAKE_BIN}" "${CAPTURE_DIR}" "${TMP_DIR}/remote" "${TMP_DIR}/backups" "${TMP_DIR}/html" "${TMP_DIR}/key"
touch "${TMP_DIR}/key/id_ed25519"
trap 'rm -rf "${TMP_DIR}"' EXIT

cat >"${FAKE_BIN}/ssh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${OPS_TEST_SSH_LOG}"
case "$*" in
  *jsonpath=*) printf '%s\n' '10.8.0.26:30500/misuaa/misu-ops@sha256:deadbeef' ;;
  *'get deployment/misu-ops -o json'* ) [[ "${FAKE_SSH_MODE:-ok}" == export ]] && exit 1 ;;
  *'kubectl apply -f'*'misu-ops-nginx-config.yaml'* ) [[ "${FAKE_SSH_MODE:-ok}" == normal-config-apply || "${FAKE_SSH_MODE:-ok}" == apply ]] && exit 1 ;;
  *'kubectl apply -f'*'misu-ops.yaml'* ) [[ "${FAKE_SSH_MODE:-ok}" == normal-deployment-apply ]] && exit 1 ;;
  *'kubectl apply -f'* ) [[ "${FAKE_SSH_MODE:-ok}" == apply ]] && exit 1 ;;
  *'patch deployment/misu-ops'* ) [[ "${FAKE_SSH_MODE:-ok}" == patch ]] && exit 1 ;;
  *'rollout status'*) [[ "${FAKE_SSH_MODE:-ok}" == fail ]] && exit 1 ;;
esac
exit 0
SH
cat >"${FAKE_BIN}/scp" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
for arg in "$@"; do
  if [[ -f "${arg}" && "${arg}" == *.yaml ]]; then
    cp "${arg}" "${OPS_TEST_CAPTURE_DIR}/$(basename "${arg}")"
  fi
done
SH
chmod +x "${FAKE_BIN}/ssh" "${FAKE_BIN}/scp"

cat >"${TMP_DIR}/deploy.conf" <<EOF
SSH_KEY=${TMP_DIR}/key/id_ed25519
MASTER_SSH=test@master
WORKER_SSH=test@worker
REGISTRY_PUSH=registry.push
REGISTRY_PULL=registry.pull
MASTER_K8S_DIR=${TMP_DIR}/remote
MASTER_BACKUP_DIR=${TMP_DIR}/backups
WORKER_HTML_DIR=${TMP_DIR}/html
WORKER_BACKUP_DIR=${TMP_DIR}/backups
NAMESPACE=misu-server
MVN=true
MAVEN_REPO=${TMP_DIR}/m2
EOF

run_release() {
  PATH="${FAKE_BIN}:${PATH}" \
  OPS_TEST_CAPTURE_DIR="${CAPTURE_DIR}" \
  OPS_TEST_SSH_LOG="${SSH_LOG}" \
  DEPLOY_CONF="${TMP_DIR}/deploy.conf" \
  DEPLOY_LOG_FILE="${TMP_DIR}/release.log" \
  FAKE_SSH_MODE="${FAKE_SSH_MODE:-ok}" \
  "${ROOT_DIR}/scripts/deploy/release.sh" "$@"
}

sha="$(git -C "${ROOT_DIR}" rev-parse --short HEAD)"

# Frontend publishing must be independent of the caller's umask.  Keep these
# source-level guards in the offline harness so a future refactor cannot
# reintroduce nginx 403s caused by rsync preserving mode 0600/0700 artifacts.
rg -q 'type d -exec chmod 755' "${ROOT_DIR}/scripts/deploy/release.sh"
rg -q 'type f -exec chmod 644' "${ROOT_DIR}/scripts/deploy/release.sh"
rg -q 'type f ! -perm -o\+r' "${ROOT_DIR}/scripts/deploy/release.sh"
rg -q 'type d ! -perm -o\+x' "${ROOT_DIR}/scripts/deploy/release.sh"
echo 'frontend permission normalization/preflight: PASS'

run_release --skip-build misu-ops
cp "${CAPTURE_DIR}/misu-ops.yaml" "${TMP_DIR}/normal-misu-ops.yaml"
cp "${CAPTURE_DIR}/misu-ops-nginx-config.yaml" "${TMP_DIR}/normal-nginx.yaml"

rg -q 'name: misu-ops-nacos-auth' "${TMP_DIR}/normal-misu-ops.yaml"
rg -q 'key: username' "${TMP_DIR}/normal-misu-ops.yaml"
rg -q 'key: password' "${TMP_DIR}/normal-misu-ops.yaml"
ruby - "${TMP_DIR}/normal-misu-ops.yaml" <<'RB'
require 'yaml'
deployment = YAML.load_stream(File.read(ARGV.fetch(0))).first
env = deployment.dig('spec', 'template', 'spec', 'containers').first.fetch('env')
auth_refs = env.select { |entry| entry['valueFrom']&.dig('secretKeyRef', 'name') == 'misu-ops-nacos-auth' }
raise 'expected both optional Nacos auth Secret refs' unless auth_refs.size == 2
raise 'Nacos auth Secret is not optional' unless auth_refs.all? { |entry| entry.dig('valueFrom', 'secretKeyRef', 'optional') == true }
puts 'Nacos auth Secret absent: optional refs allow Pod startup: PASS'
RB
if rg -q 'sub_filter|ops-nacos\.misu\.chat|ops-k8s\.misu\.chat' "${TMP_DIR}/normal-nginx.yaml"; then
  echo 'legacy console subdomain/body-rewrite route remains in sidecar' >&2
  exit 1
fi
rg -q 'server_name api\.misu\.chat' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'location = /nacos/_ops/exchange' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'location = /ops/headlamp/_ops/exchange' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Ops-Target nacos' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Ops-Target headlamp' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_hide_header Set-Cookie' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'map \$request_uri \$ops_original_uri' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header Authorization \$ops_upstream_authorization' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'https://server\.misu\.chat/nacos/' "${TMP_DIR}/normal-misu-ops.yaml"
rg -q 'https://server\.misu\.chat/ops/headlamp/' "${TMP_DIR}/normal-misu-ops.yaml"
rg -q 'location \^~ /nacos/' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'location \^~ /ops/headlamp/' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'location \^~ /ops/ws/ssh/' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'proxy_set_header Host server\.misu\.chat' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'server_name api\.misu\.chat server\.misu\.chat' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'Path=/nacos/\*\*,/ops/headlamp/\*\*,/ops/api/\*\*' "${ROOT_DIR}/misu-gateway/src/main/resources/application-prod.yml"
rg -q 'PreserveHostHeader' "${ROOT_DIR}/misu-gateway/src/main/resources/application-prod.yml"
# Production mounts this ConfigMap over the packaged profile file. Keep the
# console routes in the effective production source as well as the source
# profile so --config misu-gateway cannot silently omit them.
rg -q 'id: misu-ops-console-ws' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-gateway-config.yaml"
rg -q 'id: misu-ops-console' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-gateway-config.yaml"
rg -q 'Path=/nacos/\*\*,/ops/headlamp/\*\*,/ops/ws/\*\*' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-gateway-config.yaml"
rg -q 'Path=/nacos/\*\*,/ops/headlamp/\*\*,/ops/api/\*\*' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-gateway-config.yaml"
rg -q 'PreserveHostHeader' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-gateway-config.yaml"
rg -q -- '-base-url' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/headlamp-base-url-patch.yaml"
rg -q -- '-proxy-auth=true' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/headlamp-base-url-patch.yaml"
rg -q 'proxy_set_header X-Forwarded-User \$ops_headlamp_identity' "${TMP_DIR}/normal-nginx.yaml"
echo 'Nacos server-side auth Secret and same-origin path proxy contract: PASS'

ruby - "${TMP_DIR}/normal-misu-ops.yaml" "${TMP_DIR}/normal-nginx.yaml" "${sha}" <<'RB'
require 'yaml'
deployment = YAML.load_stream(File.read(ARGV[0])).first
config = YAML.load_stream(File.read(ARGV[1])).first
sha = ARGV[2]
deployment_name = deployment.dig('spec', 'template', 'spec', 'volumes').find { |v| v['name'] == 'nginx-config' }.dig('configMap', 'name')
raise "normal ConfigMap tag mismatch: #{deployment_name}" unless deployment_name == "misu-ops-nginx-config-#{sha}"
raise 'normal ConfigMap name was not rendered' unless config.dig('metadata', 'name') == deployment_name
puts "normal tag=#{deployment_name}"
RB

rm -f "${CAPTURE_DIR}"/*.yaml
run_release --config misu-ops
cp "${CAPTURE_DIR}/misu-ops-nginx-config.yaml" "${TMP_DIR}/config-nginx.yaml"

config_name="$(ruby - "${TMP_DIR}/config-nginx.yaml" <<'RB'
require 'yaml'
config = YAML.load_stream(File.read(ARGV[0])).first
name = config.dig('metadata', 'name')
raise "invalid ConfigMap name: #{name}" unless name.match?(/\Amisu-ops-nginx-config-cfg-[a-z0-9-]+\z/)
puts name
RB
  )"
echo "config-only tag=${config_name}"
rg -q "${config_name}" "${SSH_LOG}"

if rg -q "get deployment/misu-ops -o json" "${SSH_LOG}" \
  && rg -q "jq -e" "${SSH_LOG}" \
  && rg -q 'test -s' "${SSH_LOG}" \
  && rg -q 'mv -f' "${SSH_LOG}" \
  && rg -q "${TMP_DIR}/remote/misu-ops.yaml" "${SSH_LOG}"; then
  echo 'live Deployment export command recorded'
else
  echo 'live Deployment export command missing' >&2
  exit 1
fi

set +e
FAKE_SSH_MODE=fail run_release --config misu-ops >"${TMP_DIR}/failure.log" 2>&1
failure_rc=$?
set -e
[[ "${failure_rc}" -ne 0 ]]
rg -q 'ConfigMap 下发失败并已回滚' "${TMP_DIR}/failure.log"
rg -q 'backups/.*/k8s/misu-ops-nginx-config.yaml' "${SSH_LOG}"
rg -q 'backups/.*/k8s/misu-ops.yaml' "${SSH_LOG}"

for mode in apply patch; do
  set +e
  FAKE_SSH_MODE="${mode}" run_release --config misu-ops >"${TMP_DIR}/${mode}-failure.log" 2>&1
  failure_rc=$?
  set -e
  [[ "${failure_rc}" -ne 0 ]]
  rg -q '回滚配置和 Deployment|ConfigMap 下发失败并已回滚' "${TMP_DIR}/${mode}-failure.log"
done

set +e
FAKE_SSH_MODE=export run_release --config misu-ops >"${TMP_DIR}/export-failure.log" 2>&1
export_failure_rc=$?
set -e
[[ "${export_failure_rc}" -ne 0 ]]
rg -q '无法保存当前 misu-ops Deployment' "${TMP_DIR}/export-failure.log"

for mode in normal-config-apply normal-deployment-apply; do
  set +e
  FAKE_SSH_MODE="${mode}" run_release --skip-build misu-ops >"${TMP_DIR}/${mode}-failure.log" 2>&1
  normal_failure_rc=$?
  set -e
  [[ "${normal_failure_rc}" -ne 0 ]]
  rg -q '回滚|发布失败' "${TMP_DIR}/${mode}-failure.log"
  cp "${SSH_LOG}" "${TMP_DIR}/${mode}.ssh.log"
  rg -q 'backups/.*/k8s/misu-ops-nginx-config.yaml' "${TMP_DIR}/${mode}.ssh.log"
  rg -q 'backups/.*/k8s/misu-ops.yaml' "${TMP_DIR}/${mode}.ssh.log"
done

{
  cp "${SSH_LOG}" "${TRACE_FILE}"
  echo "release harness: PASS"
  echo "normal ConfigMap and Deployment tag: ${sha}"
  echo 'config-only ConfigMap: lowercase cfg-* DNS name'
  echo 'live Deployment export: kubectl get -o json | jq del(server fields/status)'
  echo 'kubectl export failure injection: PASS'
  echo 'config apply/patch/rollout and normal ops ConfigMap/Deployment apply rollback: PASS'
  echo 'scope: fake SSH proves rollback commands were invoked; it does not prove a real cluster recovered'
  echo "ssh trace: ${TRACE_FILE}"
} >"${EVIDENCE_FILE}"
cat "${EVIDENCE_FILE}"
