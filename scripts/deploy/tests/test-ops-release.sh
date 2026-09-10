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
rg -Fq "sub_filter '\"login_page_enabled\":true'" "${TMP_DIR}/normal-nginx.yaml"
rg -Fq "sub_filter '\"login_page_enabled\": true'" "${TMP_DIR}/normal-nginx.yaml"
rg -Fq "sub_filter '\"login_page_enabled\":\"true\"'" "${TMP_DIR}/normal-nginx.yaml"
rg -Fq "sub_filter '\"login_page_enabled\": \"true\"'" "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header Authorization \$ops_upstream_authorization' "${TMP_DIR}/normal-nginx.yaml"
echo 'Nacos server-side auth Secret and login bootstrap: PASS'

ruby - "${TMP_DIR}/normal-nginx.yaml" <<'RB'
config = File.read(ARGV.fetch(0))
patterns = [
  ['"login_page_enabled":true', '"login_page_enabled":false'],
  ['"login_page_enabled": true', '"login_page_enabled": false'],
  ['"login_page_enabled":"true"', '"login_page_enabled":"false"'],
  ['"login_page_enabled": "true"', '"login_page_enabled": "false"']
]
patterns.each do |from, to|
  abort "missing sub_filter #{from}" unless config.include?("sub_filter '#{from}' '#{to}'")
  transformed = %({#{from}:ignored}).sub(from, to)
  abort "bootstrap did not disable #{from}" unless transformed.include?(to)
end
puts 'Nacos Boolean/string state bootstrap fixtures: PASS'
RB

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
