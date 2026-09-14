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

grep -Fq 'image: ${REGISTRY_PULL}/misuaa/misu-ops:${IMAGE_TAG}' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-ops.yaml"
grep -Fq "envsubst '\${REGISTRY_PULL} \${IMAGE_TAG} \${OPS_CONFIG_TAG}'" "${ROOT_DIR}/scripts/deploy/release.sh"
echo 'misu-ops image tag placeholder remains release-compatible: PASS'

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
rg -q 'name: misu-ops-qbittorrent' "${TMP_DIR}/normal-misu-ops.yaml"
# The scoped YAML assertion below requires exactly username/password for the
# qBittorrent Secret and rejects any URL override in the deployment env.
ruby - "${TMP_DIR}/normal-misu-ops.yaml" <<'RB'
require 'yaml'
deployment = YAML.load_stream(File.read(ARGV.fetch(0))).first
pod = deployment.dig('spec', 'template', 'spec')
seccomp = pod.dig('securityContext', 'seccompProfile', 'type')
raise "expected RuntimeDefault seccomp profile, got #{seccomp.inspect}" unless seccomp == 'RuntimeDefault'
raise 'expected nginx emptyDir fsGroup' unless pod.dig('securityContext', 'fsGroup') == 101
raise 'expected OnRootMismatch fsGroup policy' unless pod.dig('securityContext', 'fsGroupChangePolicy') == 'OnRootMismatch'
containers = pod.fetch('containers')
raise 'expected misu-ops and nginx containers' unless containers.map { |entry| entry['name'] }.sort == %w[misu-ops nginx]
containers.each do |container|
  context = container.fetch('securityContext')
  raise "#{container['name']} allows privilege escalation" unless context['allowPrivilegeEscalation'] == false
  raise "#{container['name']} does not drop all capabilities" unless context.dig('capabilities', 'drop') == ['ALL']
end
puts 'RuntimeDefault + no privilege escalation + drop ALL on both containers: PASS'
nginx = containers.find { |entry| entry['name'] == 'nginx' }
nginx_context = nginx.fetch('securityContext')
raise 'nginx must run as non-root' unless nginx_context['runAsNonRoot'] == true
raise 'nginx must run as uid 101' unless nginx_context['runAsUser'] == 101
raise 'nginx must run as gid 101' unless nginx_context['runAsGroup'] == 101
raise 'nginx must use a read-only root filesystem' unless nginx_context['readOnlyRootFilesystem'] == true
raise 'nginx must not add capabilities' if nginx_context.dig('capabilities', 'add')
raise 'nginx must bypass the root entrypoint' unless nginx['command'] == ['/bin/sh', '-c'] && nginx['args'].join.include?("envsubst '${OPS_PROXY_SHARED_SECRET}'") && nginx['args'].join.include?("exec nginx -c /tmp/nginx/nginx.conf -g 'daemon off;'")
raise 'nginx must not use entrypoint envsubst output override' if nginx.fetch('env', []).any? { |entry| entry['name'] == 'NGINX_ENVSUBST_OUTPUT_DIR' }
tmp_volume = pod.fetch('volumes').find { |entry| entry['name'] == 'nginx-tmp' }
raise 'nginx tmp volume must be emptyDir' unless tmp_volume&.key?('emptyDir')
raise 'nginx must mount writable tmp volume' unless nginx.fetch('volumeMounts').any? { |entry| entry['name'] == 'nginx-tmp' && entry['mountPath'] == '/tmp/nginx' && entry['readOnly'] != true }
puts 'nginx uid/gid 101 + read-only root + tmp emptyDir + direct envsubst startup: PASS'
env = deployment.dig('spec', 'template', 'spec', 'containers').first.fetch('env')
auth_refs = env.select { |entry| entry['valueFrom']&.dig('secretKeyRef', 'name') == 'misu-ops-nacos-auth' }
raise 'expected both optional Nacos auth Secret refs' unless auth_refs.size == 2
raise 'Nacos auth Secret is not optional' unless auth_refs.all? { |entry| entry.dig('valueFrom', 'secretKeyRef', 'optional') == true }
puts 'Nacos auth Secret absent: optional refs allow Pod startup: PASS'
qbit_refs = env.select { |entry| entry['valueFrom']&.dig('secretKeyRef', 'name') == 'misu-ops-qbittorrent' }
raise 'qBittorrent Secret must contain credentials only' unless qbit_refs.map { |entry| entry.dig('valueFrom', 'secretKeyRef', 'key') }.sort == %w[password username]
raise 'qBittorrent credential refs must be optional' unless qbit_refs.all? { |entry| entry.dig('valueFrom', 'secretKeyRef', 'optional') == true }
raise 'qBittorrent upstream URL must not come from a Secret' if env.any? { |entry| entry['name'] == 'OPS_QBITTORRENT_UPSTREAM_URL' }
database_refs = env.select { |entry| entry['valueFrom']&.dig('secretKeyRef', 'name') == 'misu-ops-database' }
raise 'expected database enabled/username/password/allowed-schemas Secret refs' unless database_refs.map { |entry| entry.dig('valueFrom', 'secretKeyRef', 'key') }.sort == %w[allowed-schemas enabled password username]
raise 'database Secret refs must be optional' unless database_refs.all? { |entry| entry.dig('valueFrom', 'secretKeyRef', 'optional') == true }
raise 'database capability must be Secret-controlled' unless database_refs.any? { |entry| entry['name'] == 'OPS_DATABASE_ENABLED' }
raise 'database URL must not be configurable from the deployment environment' if env.any? { |entry| entry['name'] == 'OPS_DATABASE_URL' }
puts 'qBittorrent fixed target credentials + optional database Secret refs: PASS'
RB
if rg -q 'sub_filter|ops-nacos\.misu\.chat|ops-k8s\.misu\.chat' "${TMP_DIR}/normal-nginx.yaml"; then
  echo 'legacy console subdomain/body-rewrite route remains in sidecar' >&2
  exit 1
fi
rg -q 'server_name api\.misu\.chat' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'location = /nacos/_ops/exchange' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'location = /ops/headlamp/_ops/exchange' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'location = /ops/qbittorrent/_ops/exchange' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'location \^~ /ops/qbittorrent/' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_pass http://misu_ops_qbittorrent/' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_hide_header X-CSRF-Token' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Ops-Target nacos' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Ops-Target headlamp' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_hide_header Set-Cookie' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'map \$request_uri \$ops_original_uri' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'map \$http_upgrade \$ops_is_websocket' "${TMP_DIR}/normal-nginx.yaml"
rg -q '~\*\^websocket\$ 1' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header Authorization \$ops_upstream_authorization' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'location = /ops/qbittorrent/_ops/exchange' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-ops-nginx-config.yaml"
rg -q 'proxy_pass http://misu_ops_backend/ops/api/console-sessions/exchange;' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-ops-nginx-config.yaml"
rg -q 'FIXED_UPSTREAM_URL' "${ROOT_DIR}/misu-ops/misu-ops-biz/src/main/java/com/misu/ops/console/QBittorrentUpstreamAuthService.java"
rg -q 'server q-bit-torrent-pi\.misu-server\.svc\.cluster\.local:30120;' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-ops-nginx-config.yaml"
rg -q 'enabled: \$\{OPS_DATABASE_ENABLED:false\}' "${ROOT_DIR}/misu-ops/misu-ops-biz/src/main/resources/application.yml"
rg -q 'url: jdbc:mysql://mysql-inner\.mysql\.svc\.cluster\.local:3316/' "${ROOT_DIR}/misu-ops/misu-ops-biz/src/main/resources/application.yml"
rg -q 'FIXED_UPSTREAM_URL' "${ROOT_DIR}/misu-ops/misu-ops-biz/src/main/java/com/misu/ops/console/NacosUpstreamAuthService.java"
rg -q 'OPS_NACOS_UPSTREAM_URL:http://nacos\.misu-server\.svc\.cluster\.local:8848/nacos/' "${ROOT_DIR}/misu-ops/misu-ops-biz/src/main/resources/application.yml"
if rg -q 'OPS_QBITTORRENT_UPSTREAM_URL|qbittorrent-upstream-url' "${ROOT_DIR}/misu-ops" "${ROOT_DIR}/docs/ops-qbittorrent-secret.example.yaml"; then
  echo 'qBittorrent upstream URL must remain code-pinned and absent from configuration' >&2
  exit 1
fi
rg -q 'https://server\.misu\.chat/nacos/' "${TMP_DIR}/normal-misu-ops.yaml"
rg -q 'https://server\.misu\.chat/ops/headlamp/' "${TMP_DIR}/normal-misu-ops.yaml"
rg -q 'location \^~ /nacos/' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'location \^~ /ops/headlamp/' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'location \^~ /ops/qbittorrent/' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'proxy_read_timeout 3600s' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
rg -q 'log_format main .*\$request_method \$uri' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"
if rg -q 'log_format main .*\"\$request\"' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml"; then
  echo 'main nginx access log must not include query-bearing $request' >&2
  exit 1
fi
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
rg -q -- '-unsafe-use-service-account-token' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/headlamp-base-url-patch.yaml"
rg -q 'image: ghcr\.io/headlamp-k8s/headlamp:v0\.45\.0@sha256:db3f0e0fc58d358d41daa3fe7fc852437552c7ee873c3645470f7b86a8e0db49' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/headlamp-base-url-patch.yaml"
rg -q 'imagePullPolicy: IfNotPresent' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/headlamp-base-url-patch.yaml"
if rg -q 'c9754bae|headlamp-k8s/headlamp:latest' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/headlamp-base-url-patch.yaml"; then
  echo 'Headlamp patch must not use the old v0.42 digest or a floating tag' >&2
  exit 1
fi
HEADLAMP_SERVICE_PATCH="${ROOT_DIR}/scripts/deploy/k8s/misu-server/headlamp-service-clusterip-patch.json"
ruby -rjson -e '
  patch = JSON.parse(File.read(ARGV.fetch(0)))
  expected = [
    ["test", "/metadata/name", "headlamp"],
    ["test", "/spec/type", "NodePort"],
    ["test", "/spec/ports/0/nodePort", 30087],
    ["replace", "/spec/type", "ClusterIP"],
    ["remove", "/spec/ports/0/nodePort", nil]
  ]
  actual = patch.map { |op| [op.fetch("op"), op.fetch("path"), op["value"]] }
  abort "unexpected Headlamp Service patch" unless actual == expected
  puts "Headlamp Service patch removes NodePort 30087 and keeps selectors/ports: PASS"
' "${HEADLAMP_SERVICE_PATCH}"
rg -q 'headlamp-service-clusterip-patch\.json' "${ROOT_DIR}/scripts/deploy/k8s/misu-server/apply-headlamp-base-url.sh"
rg -q 'proxy_set_header X-Forwarded-User \$ops_headlamp_identity' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Forwarded-Groups ""' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Forwarded-Group ""' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Forwarded-Email ""' "${TMP_DIR}/normal-nginx.yaml"
rg -q 'proxy_set_header X-Forwarded-Id-Token ""' "${TMP_DIR}/normal-nginx.yaml"
ruby - "${TMP_DIR}/normal-nginx.yaml" <<'RB'
require 'yaml'
config = YAML.load_stream(File.read(ARGV.fetch(0))).first.fetch('data').fetch('nginx.conf.template')
ws = config.split('location = /_ops/ws {', 2).fetch(1).split('location ', 2).first
required = ['proxy_set_header Forwarded "";', 'proxy_set_header X-Forwarded-For "";',
            'proxy_set_header X-Real-IP "";', 'proxy_set_header X-Forwarded-Port "";']
abort 'WS proxy must clear forwarded remote-address headers' unless required.all? { |line| ws.include?(line) }
puts 'WS proxy clears forwarded remote-address headers: PASS'
RB
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
