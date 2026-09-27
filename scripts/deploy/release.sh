#!/usr/bin/env bash
# release.sh —— 开发机一键自动部署（可按需选择服务）。
#
# 用法：
#   scripts/deploy/release.sh                       # 全部：4 个 Java 服务 + 前端 + ffmpeg-worker
#   scripts/deploy/release.sh misu-gateway          # 只发布单个服务
#   scripts/deploy/release.sh misu-account frontend # 发布多个指定目标
#   scripts/deploy/release.sh frontend              # 只发布前端
#   scripts/deploy/release.sh ffmpeg-worker         # 只发布转码 worker
#   scripts/deploy/release.sh --config              # 只下发 ConfigMap（不发镜像）
#
# 目标名（可用别名）：
#   misu-gateway      (gateway)
#   misu-account      (account)
#   misu-file-server  (file-server)
#   misu-ops          (ops)
#   frontend          (front / ui)
#   ffmpeg-worker     (worker)
#
# 选项：
#   --dry-run        # 只构建，不推送、不碰服务器
#   --skip-build     # 不重新构建镜像，直接用已推送的 tag 部署
#   --config         # 只下发配置清单并重启服务，不构建/发镜像
#   --rollback <ts>  # 回滚到某次备份时间戳
#   --list-backups   # 列出可回滚的备份时间戳
#
# ConfigMap 与 Deployment 解耦：日常 Java 发布只覆盖 misu-<svc>.yaml（Deployment+Service），
# 不动 Java 服务 ConfigMap；运维代理配置使用 --config misu-ops 单独下发。
# 每次正常发布的 tag 是短 SHA 加 UTC 数字时间戳；前端发布还会同步外层
# misu-server-nginx 的同 tag versioned ConfigMap 引用。
#
# 单步流程：构建镜像→推私有 registry→SSH 主节点备份+覆盖清单+apply+rollout；
#           前端：vite build→SSH 工作节点备份+覆盖 html。任一步失败自动回滚。
# 所有 IP / 路径 / 密钥 读自 scripts/deploy/deploy.conf（由 deploy.conf.example 拷贝）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"
CONF="${DEPLOY_CONF:-${ROOT_DIR}/scripts/deploy/deploy.conf}"
LOG_FILE="${DEPLOY_LOG_FILE:-${ROOT_DIR}/scripts/deploy/deploy.log}"

log() { local m; m="$(date '+%F %T') $*"; printf '\033[1;34m[release]\033[0m %s\n' "${m}"; echo "${m}" >>"${LOG_FILE}"; }
err() { local m; m="$(date '+%F %T') ERROR $*"; printf '\033[1;31m[release]\033[0m %s\n' "${m}" >&2; echo "${m}" >>"${LOG_FILE}"; }
die() { err "$*"; exit 1; }

# ---- --help 优先处理 -------------------------------------------------------
for a in "$@"; do
  case "${a}" in -h|--help) grep -E '^#( |$)' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;; esac
done

# ---- 加载配置 --------------------------------------------------------------
[[ -f "${CONF}" ]] || die "缺少 ${CONF}，请由 scripts/deploy/deploy.conf.example 拷贝填写。"
# shellcheck disable=SC1090
source "${CONF}"
: "${SSH_KEY:?deploy.conf 缺 SSH_KEY}"
: "${MASTER_SSH:?deploy.conf 缺 MASTER_SSH}"
: "${WORKER_SSH:?deploy.conf 缺 WORKER_SSH}"
: "${REGISTRY_PUSH:?deploy.conf 缺 REGISTRY_PUSH}"
: "${REGISTRY_PULL:?deploy.conf 缺 REGISTRY_PULL}"
: "${MASTER_K8S_DIR:?}" "${MASTER_BACKUP_DIR:?}" "${WORKER_HTML_DIR:?}" "${WORKER_BACKUP_DIR:?}"
NAMESPACE="${NAMESPACE:-misu-server}"
PLATFORMS="${PLATFORMS:-linux/amd64}"
ROLLOUT_TIMEOUT="${ROLLOUT_TIMEOUT:-180s}"
KEEP_BACKUPS="${KEEP_BACKUPS:-20}"
MVN="${MVN:-mvn}"
[[ -f "${SSH_KEY}" ]] || die "SSH_KEY 不存在：${SSH_KEY}"

# ---- 服务清单：name|module|dockerfile --------------------------------------
SERVICES=(
  "misu-gateway|misu-gateway|DockerfileLocal"
  "misu-account|misu-account/misu-account-biz|DockerfileLocal"
  "misu-file-server|misu-file-server/misu-file-server-biz|DockerfileLocal"
  "misu-ops|misu-ops/misu-ops-biz|DockerfileLocal"
)

# ---- SSH 封装 --------------------------------------------------------------
SSH_OPTS=(-i "${SSH_KEY}" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=15)
mssh() { ssh "${SSH_OPTS[@]}" "${MASTER_SSH}" "$@"; }
wssh() { ssh "${SSH_OPTS[@]}" "${WORKER_SSH}" "$@"; }

# 目标别名 → 规范服务名（非服务名则输出空）
canon_service() {
  case "$1" in
    misu-gateway|gateway)                 echo misu-gateway ;;
    misu-account|account)                 echo misu-account ;;
    misu-file-server|file-server|fileserver) echo misu-file-server ;;
    misu-ops|ops)                         echo misu-ops ;;
    *) echo "" ;;
  esac
}
in_selected() { local x; for x in "${SEL_SVCS[@]:-}"; do [[ "${x}" == "$1" ]] && return 0; done; return 1; }

config_manifest_for() {
  case "$1" in
    misu-ops) echo misu-ops-nginx-config.yaml ;;
    *)        echo "$1-config.yaml" ;;
  esac
}

# ============================================================================
# 构建
# ============================================================================
build_images() {
  local push="$1" modules="" entry name module dockerfile image
  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r name module dockerfile <<<"${entry}"
    in_selected "${name}" || continue
    modules+="${modules:+,}${module}"
  done
  log "Maven 构建：${modules}"
  "${MVN}" clean package -pl "${modules}" -am -f pom.xml \
    -Dmaven.repo.local="${MAVEN_REPO}" -DskipTests=true -P prod

  for entry in "${SERVICES[@]}"; do
    IFS='|' read -r name module dockerfile <<<"${entry}"
    in_selected "${name}" || continue
    image="${REGISTRY_PUSH}/misuaa/${name}:${TAG}"
    log "镜像构建：${image}"
    if [[ "${push}" == "1" ]]; then
      docker buildx build --platform "${PLATFORMS}" -t "${image}" --push \
        "${module}" -f "${module}/${dockerfile}"
    else
      docker buildx build --platform "${PLATFORMS}" -t "${image}" \
        "${module}" -f "${module}/${dockerfile}"
    fi
  done
}

# ffmpeg-worker 不是 Maven 模块，构建上下文是 tools/local-ffmpeg-worker。
build_worker() {
  local push="$1" image
  image="${REGISTRY_PUSH}/misuaa/misu-ffmpeg-worker:${TAG}"
  log "ffmpeg-worker 镜像构建：${image}"
  if [[ "${push}" == "1" ]]; then
    docker buildx build --platform "${PLATFORMS}" -t "${image}" --push \
      "${ROOT_DIR}/tools/local-ffmpeg-worker" \
      -f "${ROOT_DIR}/tools/local-ffmpeg-worker/Dockerfile"
  else
    docker buildx build --platform "${PLATFORMS}" -t "${image}" \
      "${ROOT_DIR}/tools/local-ffmpeg-worker" \
      -f "${ROOT_DIR}/tools/local-ffmpeg-worker/Dockerfile"
  fi
}

build_frontend() {
  log "前端构建（vite build）..."
  ( cd "${ROOT_DIR}/misu-file-server-ui" \
    && npm ci --proxy=null --https-proxy=null \
         --registry=https://registry.npmjs.org/ --ignore-scripts \
    && npm run build )
  [[ -d "${ROOT_DIR}/misu-file-server-ui/dist" ]] || die "前端构建未产出 dist/"
  # The caller may use a restrictive umask for release logs and temporary
  # files.  Static assets must still be readable by the nginx worker after
  # rsync preserves source modes, so normalize the published tree explicitly.
  find "${ROOT_DIR}/misu-file-server-ui/dist" -type d -exec chmod 755 {} +
  find "${ROOT_DIR}/misu-file-server-ui/dist" -type f -exec chmod 644 {} +
}

# ============================================================================
# 部署 —— 主节点 k8s（仅选中的服务）
# ============================================================================
deploy_k8s() {
  local entry name tmp
  log "主节点：备份旧清单 → ${MASTER_BACKUP_DIR}/${TS}/k8s"
  mssh "mkdir -p '${MASTER_BACKUP_DIR}/${TS}/k8s'"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    mssh "cp -a '${MASTER_K8S_DIR}/${name}.yaml' '${MASTER_BACKUP_DIR}/${TS}/k8s/' 2>/dev/null || true"
    if [[ "${name}" == "misu-ops" ]]; then
      mssh "cp -a '${MASTER_K8S_DIR}/misu-ops-nginx-config.yaml' '${MASTER_BACKUP_DIR}/${TS}/k8s/' 2>/dev/null || true"
    fi
  done

  tmp="$(mktemp -d)"
  export REGISTRY_PULL IMAGE_TAG="${TAG}" OPS_CONFIG_TAG="${TAG}"
  log "主节点：覆盖清单 + kubectl apply（tag=${TAG}）"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    if [[ "${name}" == "misu-ops" ]]; then
      envsubst '${OPS_CONFIG_TAG}' \
        <"${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-ops-nginx-config.yaml" \
        >"${tmp}/misu-ops-nginx-config.yaml"
      if ! scp "${SSH_OPTS[@]}" "${tmp}/misu-ops-nginx-config.yaml" \
        "${MASTER_SSH}:${MASTER_K8S_DIR}/misu-ops-nginx-config.yaml"; then
        err "misu-ops ConfigMap 上传失败 —— 回滚配置和 Deployment"
        restore_k8s "${TS}"
        die "misu-ops 发布失败并已回滚。"
      fi
      if ! mssh "kubectl apply -f '${MASTER_K8S_DIR}/misu-ops-nginx-config.yaml'"; then
        err "misu-ops ConfigMap apply 失败 —— 回滚配置和 Deployment"
        restore_k8s "${TS}"
        die "misu-ops 发布失败并已回滚。"
      fi
    fi
    envsubst '${REGISTRY_PULL} ${IMAGE_TAG} ${OPS_CONFIG_TAG}' \
      <"${ROOT_DIR}/scripts/deploy/k8s/misu-server/${name}.yaml" >"${tmp}/${name}.yaml"
    if [[ "${name}" == "misu-ops" ]]; then
      if ! scp "${SSH_OPTS[@]}" "${tmp}/${name}.yaml" "${MASTER_SSH}:${MASTER_K8S_DIR}/${name}.yaml"; then
        err "misu-ops Deployment 上传失败 —— 回滚配置和 Deployment"
        restore_k8s "${TS}"
        die "misu-ops 发布失败并已回滚。"
      fi
      if ! mssh "kubectl apply -f '${MASTER_K8S_DIR}/${name}.yaml'"; then
        err "misu-ops Deployment apply 失败 —— 回滚配置和 Deployment"
        restore_k8s "${TS}"
        die "misu-ops 发布失败并已回滚。"
      fi
    else
      scp "${SSH_OPTS[@]}" "${tmp}/${name}.yaml" "${MASTER_SSH}:${MASTER_K8S_DIR}/${name}.yaml"
      mssh "kubectl apply -f '${MASTER_K8S_DIR}/${name}.yaml'"
    fi
  done
  rm -rf "${tmp}"

  log "主节点：等待 rollout..."
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    if ! mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/${name}' --timeout='${ROLLOUT_TIMEOUT}'"; then
      err "${name} rollout 失败 —— 从 ${TS} 备份回滚 k8s"
      restore_k8s "${TS}"
      die "部署失败并已回滚。"
    fi
  done
}

restore_k8s() {
  local ts="$1" entry name
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"
    if [[ "${name}" == "misu-ops" ]] && mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ops-nginx-config.yaml'"; then
      mssh "cp -a '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ops-nginx-config.yaml' '${MASTER_K8S_DIR}/misu-ops-nginx-config.yaml' && \
        kubectl apply -f '${MASTER_K8S_DIR}/misu-ops-nginx-config.yaml'"
    fi
    if mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/${name}.yaml'"; then
      mssh "cp -a '${MASTER_BACKUP_DIR}/${ts}/k8s/${name}.yaml' '${MASTER_K8S_DIR}/${name}.yaml' && \
        kubectl apply -f '${MASTER_K8S_DIR}/${name}.yaml'" || err "回滚 ${name} 失败，请人工介入"
    else
      log "${name} 没有旧清单备份，跳过回滚。"
    fi
    if [[ "${name}" == "misu-ops" ]]; then
      if ! mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ops-nginx-config.yaml'"; then
        log "misu-ops 没有旧 Nginx 配置备份，跳过回滚。"
      fi
    fi
  done
}

restore_config_only() {
  local ts="$1" entry name config_file
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    config_file="$(config_manifest_for "${name}")"
    if mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/${config_file}'"; then
      mssh "cp -a '${MASTER_BACKUP_DIR}/${ts}/k8s/${config_file}' '${MASTER_K8S_DIR}/${config_file}' && \
        kubectl apply -f '${MASTER_K8S_DIR}/${config_file}'" \
        || err "回滚 ${name} 配置失败，请人工介入"
      if [[ "${name}" != "misu-ops" ]]; then
        mssh "kubectl -n '${NAMESPACE}' rollout restart 'deployment/${name}'" \
          || err "回滚 ${name} 后重启失败，请人工介入"
      fi
    else
      log "${name} 没有旧配置备份，跳过配置回滚。"
    fi
  done
  if in_selected misu-ops && mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ops.yaml'"; then
    mssh "cp -a '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ops.yaml' '${MASTER_K8S_DIR}/misu-ops.yaml' && \
      kubectl apply -f '${MASTER_K8S_DIR}/misu-ops.yaml'" \
      || err "回滚 misu-ops Deployment 失败，请人工介入"
  fi
}

export_live_ops_deployment() {
  local destination="$1"
  local temporary="${destination}.tmp.$$"
  local cleaned="${destination}.cleaned.$$"
  # Export through temporary files so a kubectl/jq failure cannot leave a
  # truncated manifest that looks like a successful backup. jq -e plus the
  # non-empty check also rejects a different resource or empty JSON output.
  mssh "set -eu; tmp='${temporary}'; clean='${cleaned}'; trap 'rm -f \"\$tmp\" \"\$clean\"' EXIT; kubectl -n '${NAMESPACE}' get deployment/misu-ops -o json >\"\$tmp\" && jq -e 'if .kind == \"Deployment\" and .metadata.name == \"misu-ops\" then del(.metadata.creationTimestamp, .metadata.generation, .metadata.managedFields, .metadata.resourceVersion, .metadata.uid, .metadata.selfLink, .status) else empty end' \"\$tmp\" >\"\$clean\" && test -s \"\$clean\" && mv -f \"\$clean\" '${destination}'"
}

# ============================================================================
# 部署 —— ffmpeg-worker（k8s 清单在 tools/local-ffmpeg-worker/k8s/，与 Java 服务分开）
# 只覆盖 Deployment；configmap/service 是一次性的，改了再单独 apply。
# ============================================================================
deploy_worker() {
  local tmp
  log "主节点：备份旧 ffmpeg-worker 清单 → ${MASTER_BACKUP_DIR}/${TS}/k8s"
  mssh "mkdir -p '${MASTER_BACKUP_DIR}/${TS}/k8s'"
  mssh "cp -a '${MASTER_K8S_DIR}/misu-ffmpeg-worker.yaml' '${MASTER_BACKUP_DIR}/${TS}/k8s/' 2>/dev/null || true"

  tmp="$(mktemp -d)"
  export REGISTRY_PULL IMAGE_TAG="${TAG}"
  log "主节点：覆盖 ffmpeg-worker 清单 + kubectl apply（tag=${TAG}）"
  envsubst '${REGISTRY_PULL} ${IMAGE_TAG}' \
    <"${ROOT_DIR}/tools/local-ffmpeg-worker/k8s/deployment.yaml" >"${tmp}/misu-ffmpeg-worker.yaml"
  scp "${SSH_OPTS[@]}" "${tmp}/misu-ffmpeg-worker.yaml" "${MASTER_SSH}:${MASTER_K8S_DIR}/misu-ffmpeg-worker.yaml"
  mssh "kubectl apply -f '${MASTER_K8S_DIR}/misu-ffmpeg-worker.yaml'"
  rm -rf "${tmp}"

  log "主节点：等待 ffmpeg-worker rollout..."
  if ! mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/misu-ffmpeg-worker' --timeout='${ROLLOUT_TIMEOUT}'"; then
    err "misu-ffmpeg-worker rollout 失败 —— 从 ${TS} 备份回滚"
    restore_worker "${TS}"
    die "ffmpeg-worker 部署失败并已回滚。"
  fi
}

restore_worker() {
  local ts="$1"
  mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ffmpeg-worker.yaml' && \
    cp -a '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ffmpeg-worker.yaml' '${MASTER_K8S_DIR}/misu-ffmpeg-worker.yaml' && \
    kubectl apply -f '${MASTER_K8S_DIR}/misu-ffmpeg-worker.yaml'" || err "回滚 misu-ffmpeg-worker 失败，请人工介入"
}

# ============================================================================
# 部署 —— 工作节点 前端
# ============================================================================
deploy_frontend() {
  local unreadable_file unsearchable_dir
  [[ -r "${ROOT_DIR}/misu-file-server-ui/dist/index.html" ]] \
    || die "前端 dist/index.html 不可读，拒绝发布。"
  unreadable_file="$(find "${ROOT_DIR}/misu-file-server-ui/dist" -type f ! -perm -o+r -print -quit)"
  [[ -z "${unreadable_file}" ]] \
    || die "前端静态文件缺少 other-read 权限：${unreadable_file}"
  unsearchable_dir="$(find "${ROOT_DIR}/misu-file-server-ui/dist" -type d ! -perm -o+x -print -quit)"
  [[ -z "${unsearchable_dir}" ]] \
    || die "前端静态目录缺少 other-execute 权限：${unsearchable_dir}"
  log "工作节点：备份旧 html → ${WORKER_BACKUP_DIR}/${TS}/html"
  wssh "mkdir -p '${WORKER_BACKUP_DIR}/${TS}' && \
    { [ -d '${WORKER_HTML_DIR}' ] && cp -a '${WORKER_HTML_DIR}' '${WORKER_BACKUP_DIR}/${TS}/html' || true; }"
  log "工作节点：覆盖前端静态文件 → ${WORKER_HTML_DIR}"
  wssh "mkdir -p '${WORKER_HTML_DIR}'"
  # --exclude .DS_Store：不推 macOS 垃圾文件；--delete-excluded：连带清掉服务器上已有的
  rsync -az --delete --delete-excluded --exclude='.DS_Store' \
    -e "ssh -i ${SSH_KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" \
    "${ROOT_DIR}/misu-file-server-ui/dist/" "${WORKER_SSH}:${WORKER_HTML_DIR}/" \
    || { err "前端静态文件发布失败 —— 回滚 html"; restore_frontend "${TS}"; die "前端发布失败并已回滚。"; }
  if ! deploy_frontend_nginx; then
    err "外层 misu-server-nginx 发布失败 —— 回滚 html"
    restore_frontend "${TS}" || true
    die "前端发布失败并已回滚。"
  fi
}

restore_frontend() {
  local ts="$1"
  wssh "test -d '${WORKER_BACKUP_DIR}/${ts}/html' && \
    rsync -a --delete '${WORKER_BACKUP_DIR}/${ts}/html/' '${WORKER_HTML_DIR}/'" \
    || err "回滚 html 失败，请人工介入"
}

export_live_frontend_nginx() {
  local destination="$1"
  local deployment="${destination}/misu-server-nginx.yaml"
  local configmap="${destination}/misu-server-nginx-config.yaml"
  local configmap_name="${destination}/misu-server-nginx-config.name"
  local live_config_map

  live_config_map="$(mssh "kubectl -n '${NAMESPACE}' get deployment/misu-server-nginx -o jsonpath='{.spec.template.spec.volumes[?(@.name==\"nginx-config\")].configMap.name}'")" \
    || return 1
  [[ "${live_config_map}" =~ ^misu-server-nginx-config-[a-z0-9-]+$ ]] || return 1

  # Save the live Deployment and the exact versioned ConfigMap referenced by
  # its nginx-config volume. kubectl emits the backups directly as YAML so a
  # jq installation on the control-plane node is not required.
  mssh "set -eu; kubectl -n '${NAMESPACE}' get deployment/misu-server-nginx -o yaml > '${deployment}'; test -s '${deployment}'" \
    || return 1
  mssh "set -eu; kubectl -n '${NAMESPACE}' get configmap/${live_config_map} -o yaml > '${configmap}'; printf '%s\\n' '${live_config_map}' > '${configmap_name}'; test -s '${configmap}'; test -s '${configmap_name}'" \
    || return 1
}

restore_frontend_nginx() {
  local ts="$1"
  local backup_dir="${MASTER_BACKUP_DIR}/${ts}/k8s"
  local old_config_map
  local status=0

  old_config_map="$(mssh "cat '${backup_dir}/misu-server-nginx-config.name'")" \
    || old_config_map=""
  if [[ ! "${old_config_map}" =~ ^misu-server-nginx-config-[a-z0-9-]+$ ]]; then
    err "无法读取外层 misu-server-nginx 原 ConfigMap 名称，请人工介入"
    status=1
  elif ! mssh "kubectl -n '${NAMESPACE}' patch deployment/misu-server-nginx --type='strategic' -p '{\"spec\":{\"template\":{\"spec\":{\"volumes\":[{\"name\":\"nginx-config\",\"configMap\":{\"name\":\"${old_config_map}\"}}]}}}}'"; then
    err "回滚外层 misu-server-nginx Deployment ConfigMap 引用失败，请人工介入"
    status=1
  elif ! mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/misu-server-nginx' --timeout='${ROLLOUT_TIMEOUT}'"; then
    err "外层 misu-server-nginx 回滚后 rollout 未就绪，请人工介入"
    status=1
  fi
  return "${status}"
}

deploy_frontend_nginx() {
  local tmp config_file
  tmp="$(mktemp -d)"
  config_file="${tmp}/misu-server-nginx-config.yaml"

  log "主节点：备份 live misu-server-nginx ConfigMap 和 Deployment → ${MASTER_BACKUP_DIR}/${TS}/k8s"
  if ! mssh "mkdir -p '${MASTER_BACKUP_DIR}/${TS}/k8s'" \
    || ! export_live_frontend_nginx "${MASTER_BACKUP_DIR}/${TS}/k8s"; then
    rm -rf "${tmp}"
    err "无法保存当前外层 misu-server-nginx 状态，拒绝前端发布"
    return 1
  fi

  # Keep the checked-in ConfigMap content as the source of truth while making
  # each published ConfigMap immutable by name. TAG is unique per release.
  sed "s/^  name: misu-server-nginx-config$/  name: misu-server-nginx-config-${TAG}/" \
    "${ROOT_DIR}/scripts/deploy/k8s/misu-server/misu-server-nginx-config.yaml" >"${config_file}"
  if ! grep -Fq "  name: misu-server-nginx-config-${TAG}" "${config_file}"; then
    rm -rf "${tmp}"
    err "外层 misu-server-nginx ConfigMap 名称渲染失败"
    return 1
  fi
  if ! scp "${SSH_OPTS[@]}" "${config_file}" \
      "${MASTER_SSH}:${MASTER_K8S_DIR}/misu-server-nginx-config.yaml"; then
    rm -rf "${tmp}"
    err "外层 misu-server-nginx ConfigMap 上传失败 —— 回滚"
    restore_frontend_nginx "${TS}" || true
    return 1
  fi
  if ! mssh "kubectl -n '${NAMESPACE}' apply -f '${MASTER_K8S_DIR}/misu-server-nginx-config.yaml'"; then
    rm -rf "${tmp}"
    err "外层 misu-server-nginx ConfigMap apply 失败 —— 回滚"
    restore_frontend_nginx "${TS}" || true
    return 1
  fi
  if ! mssh "kubectl -n '${NAMESPACE}' patch deployment/misu-server-nginx --type='strategic' -p '{\"spec\":{\"template\":{\"spec\":{\"volumes\":[{\"name\":\"nginx-config\",\"configMap\":{\"name\":\"misu-server-nginx-config-${TAG}\"}}]}}}}'"; then
    rm -rf "${tmp}"
    err "外层 misu-server-nginx Deployment patch 失败 —— 回滚"
    restore_frontend_nginx "${TS}" || true
    return 1
  fi
  if ! mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/misu-server-nginx' --timeout='${ROLLOUT_TIMEOUT}'"; then
    rm -rf "${tmp}"
    err "外层 misu-server-nginx rollout 失败 —— 回滚"
    restore_frontend_nginx "${TS}" || true
    return 1
  fi
  rm -rf "${tmp}"
  log "外层 misu-server-nginx 已切换到 ConfigMap misu-server-nginx-config-${TAG}"
}

# ============================================================================
# 备份清理 / 回滚 / 列表
# ============================================================================
prune_backups() {
  mssh "ls -1d '${MASTER_BACKUP_DIR}'/*Z 2>/dev/null | sort | head -n -${KEEP_BACKUPS} | xargs -r rm -rf" || true
  wssh "ls -1d '${WORKER_BACKUP_DIR}'/*Z 2>/dev/null | sort | head -n -${KEEP_BACKUPS} | xargs -r rm -rf" || true
}

cmd_list_backups() {
  echo "[主节点 ${MASTER_BACKUP_DIR}]"; mssh "ls -1 '${MASTER_BACKUP_DIR}' 2>/dev/null" || true
  echo "[工作节点 ${WORKER_BACKUP_DIR}]"; wssh "ls -1 '${WORKER_BACKUP_DIR}' 2>/dev/null" || true
}

cmd_rollback() {
  local ts="$1" entry name
  [[ -n "${ts}" ]] || die "--rollback 需要时间戳参数（用 --list-backups 查看）"
  log "回滚到 ${ts}"
  restore_k8s "${ts}"
  restore_worker "${ts}"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"
    mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/${name}' --timeout='${ROLLOUT_TIMEOUT}'" \
      || err "${name} 回滚后 rollout 未就绪"
  done
  mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/misu-ffmpeg-worker' --timeout='${ROLLOUT_TIMEOUT}'" \
    || err "misu-ffmpeg-worker 回滚后 rollout 未就绪"
  restore_frontend "${ts}"
  # Backups created by newer frontend releases include the outer nginx
  # ConfigMap name. Older backups remain valid and simply skip this step.
  if mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/misu-server-nginx-config.name'"; then
    restore_frontend_nginx "${ts}" \
      || err "回滚外层 misu-server-nginx 失败，请人工介入"
  fi
  log "回滚完成。"
}

# ============================================================================
# 单独下发 ConfigMap（与日常镜像发布解耦）
# ============================================================================
cmd_config() {
  local entry name ts config_file current_tag config_tag tmp
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  current_tag="$(git rev-parse --short HEAD)"
  # ConfigMap metadata.name is a DNS subdomain; lower-case the UTC marker
  # before using it in Kubernetes, while retaining the upper-case timestamp
  # for the human-readable backup directory.
  config_tag="$(printf 'cfg-%s-%s' "${current_tag}" "${ts}" | tr '[:upper:]' '[:lower:]')"
  export OPS_CONFIG_TAG="${config_tag}"
  tmp="$(mktemp -d)"
  log "下发 ConfigMap：服务=[${SEL_SVCS[*]}]  config=${config_tag}  ts=${ts}"
  mssh "mkdir -p '${MASTER_BACKUP_DIR}/${ts}/k8s'"
  if in_selected misu-ops; then
    if ! mssh "command -v jq >/dev/null 2>&1"; then
      die "config-only 需要主节点安装 jq，用于保存 live misu-ops Deployment"
    fi
    # A config-only rollout changes the Deployment's ConfigMap reference. Keep
    # the live Deployment (including its current image) in the same backup so
    # --rollback restores the image and config reference together.
    if ! export_live_ops_deployment "${MASTER_BACKUP_DIR}/${ts}/k8s/misu-ops.yaml"; then
      die "无法保存当前 misu-ops Deployment，拒绝 config-only 发布"
    fi
  fi
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    config_file="$(config_manifest_for "${name}")"
    if ! mssh "cp -a '${MASTER_K8S_DIR}/${config_file}' '${MASTER_BACKUP_DIR}/${ts}/k8s/'"; then
      die "无法保存 ${name} 旧配置，拒绝 config-only 发布"
    fi
  done

  log "主节点：覆盖 ConfigMap 清单 + kubectl apply"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    config_file="$(config_manifest_for "${name}")"
    if [[ "${name}" == "misu-ops" ]]; then
      envsubst '${OPS_CONFIG_TAG}' \
        <"${ROOT_DIR}/scripts/deploy/k8s/misu-server/${config_file}" \
        >"${tmp}/${config_file}"
    else
      cp "${ROOT_DIR}/scripts/deploy/k8s/misu-server/${config_file}" "${tmp}/${config_file}"
    fi
    if ! scp "${SSH_OPTS[@]}" "${tmp}/${config_file}" \
      "${MASTER_SSH}:${MASTER_K8S_DIR}/${config_file}"; then
      err "${name} 配置清单上传失败 —— 回滚配置和 Deployment"
      restore_config_only "${ts}"
      die "${name} 配置下发失败并已回滚。"
    fi
    if ! mssh "kubectl apply -f '${MASTER_K8S_DIR}/${config_file}'"; then
      err "${name} ConfigMap apply 失败 —— 回滚配置和 Deployment"
      restore_config_only "${ts}"
      die "${name} ConfigMap 下发失败并已回滚。"
    fi
    if [[ "${name}" == "misu-ops" ]]; then
      if ! mssh "kubectl -n '${NAMESPACE}' patch deployment/misu-ops --type='strategic' -p '{\"spec\":{\"template\":{\"spec\":{\"volumes\":[{\"name\":\"nginx-config\",\"configMap\":{\"name\":\"misu-ops-nginx-config-${config_tag}\"}}]}}}}'"; then
        err "misu-ops Deployment patch 失败 —— 回滚配置和 Deployment"
        restore_config_only "${ts}"
        die "misu-ops 配置下发失败并已回滚。"
      fi
      # Persist the exact live Deployment after the patch. This preserves the
      # real image (including a digest or a different registry), env, probes,
      # and any cluster-side fields that the repository template does not know.
      if ! export_live_ops_deployment "${MASTER_K8S_DIR}/misu-ops.yaml"; then
        err "misu-ops live Deployment 备份失败 —— 回滚配置和 Deployment"
        restore_config_only "${ts}"
        die "misu-ops 配置下发失败并已回滚。"
      fi
    fi
  done

  # ConfigMap 走 subPath 挂载，kubelet 不会热更新，必须重启 pod 才能生效
  log "主节点：重启 deployment 让新配置生效"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    [[ "${name}" == "misu-ops" ]] && continue
    mssh "kubectl -n '${NAMESPACE}' rollout restart 'deployment/${name}'"
  done
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    if ! mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/${name}' --timeout='${ROLLOUT_TIMEOUT}'"; then
      err "${name} 配置更新后 rollout 未就绪 —— 回滚配置和 Deployment"
      restore_config_only "${ts}"
      die "${name} ConfigMap 下发失败并已回滚。"
    fi
  done
  rm -rf "${tmp}"
  log "ConfigMap 下发完成（备份时间戳 ${ts}）。"
}

# ============================================================================
# 主流程
# ============================================================================
main() {
  local dry=0 skip_build=0 action="deploy" rb_ts="" any_target=0 c=""
  SEL_SVCS=(); DO_FRONTEND=0; DO_WORKER=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dry-run)      dry=1 ;;
      --skip-build)   skip_build=1 ;;
      --config)       action="config" ;;
      --list-backups) action="list" ;;
      --rollback)     action="rollback"; rb_ts="${2:-}"; shift ;;
      --*)            die "未知选项：$1（-h 看帮助）" ;;
      frontend|front|ui) DO_FRONTEND=1; any_target=1 ;;
      ffmpeg-worker|ffmpegworker|worker) DO_WORKER=1; any_target=1 ;;
      *)
        c="$(canon_service "$1")"
        [[ -n "${c}" ]] || die "未知目标：$1（可选 misu-gateway/misu-account/misu-file-server/misu-ops/frontend/ffmpeg-worker）"
        in_selected "${c}" || SEL_SVCS+=("${c}")
        any_target=1 ;;
    esac
    shift
  done

  if [[ "${action}" == "list" ]];     then cmd_list_backups; exit 0; fi
  if [[ "${action}" == "rollback" ]]; then cmd_rollback "${rb_ts}"; exit 0; fi

  # 未指定目标 → 全部
  if [[ "${any_target}" -eq 0 ]]; then
    SEL_SVCS=(misu-gateway misu-account misu-file-server misu-ops); DO_FRONTEND=1; DO_WORKER=1
  fi

  if [[ "${action}" == "config" ]]; then
    [[ ${#SEL_SVCS[@]} -gt 0 ]] || die "--config 仅适用于 Java 服务（frontend 无 ConfigMap）"
    cmd_config; exit 0
  fi

  TS="$(date -u +%Y%m%dT%H%M%SZ)"
  # Keep the human-readable backup timestamp, but use only lower-case
  # letters and digits in the image/ConfigMap tag. This prevents a repeated
  # release of the same HEAD from reusing an IfNotPresent image tag.
  SHORT_SHA="$(git rev-parse --short HEAD)"
  TAG="${SHORT_SHA}-$(printf '%s' "${TS}" | tr -d 'TZ')"
  local branch; branch="$(git rev-parse --abbrev-ref HEAD)"
  [[ "${branch}" == "master" ]] || err "当前分支是 ${branch}（非 master）—— 将按该 HEAD 发布，请确认。"
  log "发布目标：服务=[${SEL_SVCS[*]:-无}] 前端=$([[ ${DO_FRONTEND} -eq 1 ]] && echo 是 || echo 否) worker=$([[ ${DO_WORKER} -eq 1 ]] && echo 是 || echo 否)  tag=${TAG}  ts=${TS}"

  # 构建
  if [[ ${#SEL_SVCS[@]} -gt 0 ]]; then
    if [[ "${skip_build}" -eq 1 ]]; then
      log "跳过镜像构建（--skip-build）"
    else
      build_images "$([[ "${dry}" -eq 1 ]] && echo 0 || echo 1)"
    fi
  fi
  if [[ "${DO_WORKER}" -eq 1 ]]; then
    if [[ "${skip_build}" -eq 1 ]]; then
      log "跳过 ffmpeg-worker 镜像构建（--skip-build）"
    else
      build_worker "$([[ "${dry}" -eq 1 ]] && echo 0 || echo 1)"
    fi
  fi
  [[ "${DO_FRONTEND}" -eq 1 ]] && build_frontend

  if [[ "${dry}" -eq 1 ]]; then
    log "[dry-run] 构建完成、未推送、未触碰服务器。正式发布请去掉 --dry-run。"
    exit 0
  fi

  # 部署
  [[ ${#SEL_SVCS[@]} -gt 0 ]] && deploy_k8s
  [[ "${DO_WORKER}" -eq 1 ]] && deploy_worker
  [[ "${DO_FRONTEND}" -eq 1 ]] && deploy_frontend
  prune_backups
  log "发布成功：${TAG} 已上线（备份时间戳 ${TS}）"
}

main "$@"
