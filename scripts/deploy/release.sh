#!/usr/bin/env bash
# release.sh —— 开发机一键自动部署（可按需选择服务）。
#
# 用法：
#   scripts/deploy/release.sh                       # 全部：3 个 Java 服务 + 前端
#   scripts/deploy/release.sh misu-gateway          # 只发布单个服务
#   scripts/deploy/release.sh misu-account frontend # 发布多个指定目标
#   scripts/deploy/release.sh frontend              # 只发布前端
#   scripts/deploy/release.sh --config              # 只下发 ConfigMap（不发镜像）
#
# 目标名（可用别名）：
#   misu-gateway      (gateway)
#   misu-account      (account)
#   misu-file-server  (file-server)
#   frontend          (front / ui)
#
# 选项：
#   --dry-run        # 只构建，不推送、不碰服务器
#   --skip-build     # 不重新构建镜像，直接用已推送的 tag 部署
#   --config         # 只下发 ConfigMap（nacos 接入配置）并重启服务，不构建/发镜像
#   --rollback <ts>  # 回滚到某次备份时间戳
#   --list-backups   # 列出可回滚的备份时间戳
#
# ConfigMap 与 Deployment 解耦：日常发布只覆盖 misu-<svc>.yaml（Deployment+Service），
# 不动 ConfigMap；改了 nacos 接入配置才用 --config 单独下发 misu-<svc>-config.yaml。
#
# 单步流程：构建镜像→推私有 registry→SSH 主节点备份+覆盖清单+apply+rollout；
#           前端：vite build→SSH 工作节点备份+覆盖 html。任一步失败自动回滚。
# 所有 IP / 路径 / 密钥 读自 scripts/deploy/deploy.conf（由 deploy.conf.example 拷贝）。
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${ROOT_DIR}"
CONF="${ROOT_DIR}/scripts/deploy/deploy.conf"
LOG_FILE="${ROOT_DIR}/scripts/deploy/deploy.log"

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
    *) echo "" ;;
  esac
}
in_selected() { local x; for x in "${SEL_SVCS[@]:-}"; do [[ "${x}" == "$1" ]] && return 0; done; return 1; }

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

build_frontend() {
  log "前端构建（vite build）..."
  ( cd "${ROOT_DIR}/misu-file-server-ui" \
    && npm ci --proxy=null --https-proxy=null \
         --registry=https://registry.npmjs.org/ --ignore-scripts \
    && npm run build )
  [[ -d "${ROOT_DIR}/misu-file-server-ui/dist" ]] || die "前端构建未产出 dist/"
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
  done

  tmp="$(mktemp -d)"
  export REGISTRY_PULL IMAGE_TAG="${TAG}"
  log "主节点：覆盖清单 + kubectl apply（tag=${TAG}）"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    envsubst '${REGISTRY_PULL} ${IMAGE_TAG}' \
      <"${ROOT_DIR}/scripts/deploy/k8s/misu-server/${name}.yaml" >"${tmp}/${name}.yaml"
    scp "${SSH_OPTS[@]}" "${tmp}/${name}.yaml" "${MASTER_SSH}:${MASTER_K8S_DIR}/${name}.yaml"
    mssh "kubectl apply -f '${MASTER_K8S_DIR}/${name}.yaml'"
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
    mssh "test -f '${MASTER_BACKUP_DIR}/${ts}/k8s/${name}.yaml' && \
      cp -a '${MASTER_BACKUP_DIR}/${ts}/k8s/${name}.yaml' '${MASTER_K8S_DIR}/${name}.yaml' && \
      kubectl apply -f '${MASTER_K8S_DIR}/${name}.yaml'" || err "回滚 ${name} 失败，请人工介入"
  done
}

# ============================================================================
# 部署 —— 工作节点 前端
# ============================================================================
deploy_frontend() {
  log "工作节点：备份旧 html → ${WORKER_BACKUP_DIR}/${TS}/html"
  wssh "mkdir -p '${WORKER_BACKUP_DIR}/${TS}' && \
    { [ -d '${WORKER_HTML_DIR}' ] && cp -a '${WORKER_HTML_DIR}' '${WORKER_BACKUP_DIR}/${TS}/html' || true; }"
  log "工作节点：覆盖前端静态文件 → ${WORKER_HTML_DIR}"
  wssh "mkdir -p '${WORKER_HTML_DIR}'"
  rsync -az --delete \
    -e "ssh -i ${SSH_KEY} -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new" \
    "${ROOT_DIR}/misu-file-server-ui/dist/" "${WORKER_SSH}:${WORKER_HTML_DIR}/"
}

restore_frontend() {
  local ts="$1"
  wssh "test -d '${WORKER_BACKUP_DIR}/${ts}/html' && \
    rsync -a --delete '${WORKER_BACKUP_DIR}/${ts}/html/' '${WORKER_HTML_DIR}/'" \
    || err "回滚 html 失败，请人工介入"
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
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"
    mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/${name}' --timeout='${ROLLOUT_TIMEOUT}'" \
      || err "${name} 回滚后 rollout 未就绪"
  done
  restore_frontend "${ts}"
  log "回滚完成。"
}

# ============================================================================
# 单独下发 ConfigMap（与日常镜像发布解耦）
# ============================================================================
cmd_config() {
  local entry name ts
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  log "下发 ConfigMap：服务=[${SEL_SVCS[*]}]  ts=${ts}"
  mssh "mkdir -p '${MASTER_BACKUP_DIR}/${ts}/k8s'"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    mssh "cp -a '${MASTER_K8S_DIR}/${name}-config.yaml' '${MASTER_BACKUP_DIR}/${ts}/k8s/' 2>/dev/null || true"
  done

  log "主节点：覆盖 ConfigMap 清单 + kubectl apply"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    scp "${SSH_OPTS[@]}" "${ROOT_DIR}/scripts/deploy/k8s/misu-server/${name}-config.yaml" \
      "${MASTER_SSH}:${MASTER_K8S_DIR}/${name}-config.yaml"
    mssh "kubectl apply -f '${MASTER_K8S_DIR}/${name}-config.yaml'"
  done

  # ConfigMap 走 subPath 挂载，kubelet 不会热更新，必须重启 pod 才能生效
  log "主节点：重启 deployment 让新配置生效"
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    mssh "kubectl -n '${NAMESPACE}' rollout restart 'deployment/${name}'"
  done
  for entry in "${SERVICES[@]}"; do
    name="${entry%%|*}"; in_selected "${name}" || continue
    mssh "kubectl -n '${NAMESPACE}' rollout status 'deployment/${name}' --timeout='${ROLLOUT_TIMEOUT}'" \
      || err "${name} 重启后 rollout 未就绪"
  done
  log "ConfigMap 下发完成（备份时间戳 ${ts}）。"
}

# ============================================================================
# 主流程
# ============================================================================
main() {
  local dry=0 skip_build=0 action="deploy" rb_ts="" any_target=0 c=""
  SEL_SVCS=(); DO_FRONTEND=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dry-run)      dry=1 ;;
      --skip-build)   skip_build=1 ;;
      --config)       action="config" ;;
      --list-backups) action="list" ;;
      --rollback)     action="rollback"; rb_ts="${2:-}"; shift ;;
      --*)            die "未知选项：$1（-h 看帮助）" ;;
      frontend|front|ui) DO_FRONTEND=1; any_target=1 ;;
      *)
        c="$(canon_service "$1")"
        [[ -n "${c}" ]] || die "未知目标：$1（可选 misu-gateway/misu-account/misu-file-server/frontend）"
        in_selected "${c}" || SEL_SVCS+=("${c}")
        any_target=1 ;;
    esac
    shift
  done

  if [[ "${action}" == "list" ]];     then cmd_list_backups; exit 0; fi
  if [[ "${action}" == "rollback" ]]; then cmd_rollback "${rb_ts}"; exit 0; fi

  # 未指定目标 → 全部
  if [[ "${any_target}" -eq 0 ]]; then
    SEL_SVCS=(misu-gateway misu-account misu-file-server); DO_FRONTEND=1
  fi

  if [[ "${action}" == "config" ]]; then
    [[ ${#SEL_SVCS[@]} -gt 0 ]] || die "--config 仅适用于 Java 服务（frontend 无 ConfigMap）"
    cmd_config; exit 0
  fi

  TAG="$(git rev-parse --short HEAD)"
  TS="$(date -u +%Y%m%dT%H%M%SZ)"
  local branch; branch="$(git rev-parse --abbrev-ref HEAD)"
  [[ "${branch}" == "master" ]] || err "当前分支是 ${branch}（非 master）—— 将按该 SHA 发布，请确认。"
  log "发布目标：服务=[${SEL_SVCS[*]:-无}] 前端=$([[ ${DO_FRONTEND} -eq 1 ]] && echo 是 || echo 否)  tag=${TAG}  ts=${TS}"

  # 构建
  if [[ ${#SEL_SVCS[@]} -gt 0 ]]; then
    if [[ "${skip_build}" -eq 1 ]]; then
      log "跳过镜像构建（--skip-build）"
    else
      build_images "$([[ "${dry}" -eq 1 ]] && echo 0 || echo 1)"
    fi
  fi
  [[ "${DO_FRONTEND}" -eq 1 ]] && build_frontend

  if [[ "${dry}" -eq 1 ]]; then
    log "[dry-run] 构建完成、未推送、未触碰服务器。正式发布请去掉 --dry-run。"
    exit 0
  fi

  # 部署
  [[ ${#SEL_SVCS[@]} -gt 0 ]] && deploy_k8s
  [[ "${DO_FRONTEND}" -eq 1 ]] && deploy_frontend
  prune_backups
  log "发布成功：${TAG} 已上线（备份时间戳 ${TS}）"
}

main "$@"
