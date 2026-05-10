#!/usr/bin/env bash
# misu-server 本地联调环境
#
# 用法：
#   ./dev.sh up [--no-build] [--no-frontend]   启动中间件 + Java + 前端
#   ./dev.sh down                               停掉前端 + Java + 中间件
#   ./dev.sh status                             查看各组件状态
#   ./dev.sh restart <service>                  重启单个服务（mw|all 或 gateway|account|file-server|frontend）
#   ./dev.sh build [-DskipTests]                只 mvn package 三个 Java 模块
#   ./dev.sh logs <service>                     tail -f 单个服务日志
#   ./dev.sh seed-nacos                         把 scripts/dev/nacos/*.yml 重推到本地 Nacos
#   ./dev.sh seed-sql                           执行 docs/*.sql 中的迁移脚本
#   ./dev.sh nuke                               down + 删除 docker volume + 清空 .dev/
#
# 前置依赖：Docker Desktop / Maven / JDK17 / Node 18+

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ROOT_DIR

# shellcheck source=scripts/dev/lib/common.sh
source "${ROOT_DIR}/scripts/dev/lib/common.sh"

# ---------- 服务定义 ----------
JAVA_SERVICES=("account" "file-server" "gateway")   # 启动顺序

# 用 case 函数代替关联数组，兼容 macOS 默认 bash 3.2
# （declare -A 仅 bash 4+，macOS 自带 /bin/bash 是 3.2）

# 服务 -> 模块 jar 路径（mvn package 后的产物）
service_jar_default() {
  case "$1" in
    account)     echo "${ROOT_DIR}/misu-account/misu-account-biz/target/misu-account-biz.jar" ;;
    file-server) echo "${ROOT_DIR}/misu-file-server/misu-file-server-biz/target/misu-file-server-biz.jar" ;;
    gateway)     echo "${ROOT_DIR}/misu-gateway/target/misu-gateway.jar" ;;
  esac
}

# 服务 -> 监听端口
service_port() {
  case "$1" in
    account)     echo 30261 ;;
    file-server) echo 30262 ;;
    gateway)     echo 30260 ;;
  esac
}

# 服务 -> mvn -pl 参数
service_mvn_pl() {
  case "$1" in
    account)     echo "misu-account/misu-account-biz" ;;
    file-server) echo "misu-file-server/misu-file-server-biz" ;;
    gateway)     echo "misu-gateway" ;;
  esac
}

FRONTEND_DIR="${ROOT_DIR}/misu-file-server-ui"
FRONTEND_PORT="5173"

COMPOSE_FILE="${ROOT_DIR}/docker-compose.local.yml"

# ---------- 前置依赖检查 ----------
check_prereqs() {
  local missing=0
  for cmd in docker curl java; do
    command -v "${cmd}" >/dev/null 2>&1 || { err "缺少命令：${cmd}"; missing=1; }
  done
  COMPOSE="$(detect_compose)" || missing=1
  # mvn 由 MVN 环境变量覆盖（参考 scripts/build-local-push-account.sh 的写法）
  MVN="${MVN:-mvn}"
  if ! command -v "${MVN}" >/dev/null 2>&1 && [[ ! -x "${MVN}" ]]; then
    err "缺少 mvn（可设置 MVN=/path/to/mvn 环境变量）"; missing=1
  fi
  (( missing == 0 )) || exit 1
}

# ---------- 中间件 ----------
mw_up() {
  section "Middleware: docker compose up -d"
  ${COMPOSE} -f "${COMPOSE_FILE}" up -d
  wait_for "MySQL" "${COMPOSE} -f ${COMPOSE_FILE} exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot -proot --silent" 90
  wait_for "Nacos"  "curl -fsS http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/console/health/readiness" 120
  bash "${ROOT_DIR}/scripts/dev/nacos/seed.sh"
}

mw_down() {
  section "Middleware: docker compose down"
  ${COMPOSE} -f "${COMPOSE_FILE}" down || true
}

mw_status() {
  section "Middleware"
  if ${COMPOSE} -f "${COMPOSE_FILE}" ps --status running 2>/dev/null | grep -qE "misu-mysql-local|misu-nacos-local"; then
    ${COMPOSE} -f "${COMPOSE_FILE}" ps
  else
    warn "中间件未运行"
  fi
}

# ---------- Java build ----------
java_build() {
  section "Maven build"
  local extra=("$@")
  local pl="misu-account/misu-account-biz,misu-file-server/misu-file-server-biz,misu-gateway"
  # 允许通过 MAVEN_REPO_LOCAL 自定义本地仓库（避免 ~/.m2 重复下载）
  local repo_arg=()
  if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then
    repo_arg=(-Dmaven.repo.local="${MAVEN_REPO_LOCAL}")
  fi
  # bash 3.2 在 "${arr[@]:-}" 上会产生一个空串占位（Maven 把它当成空 lifecycle phase 报错），
  # 所以使用 ${arr[@]+...} 仅在数组非空时才展开。
  log "${MVN} -pl ${pl} -am package -DskipTests${repo_arg[@]:+ ${repo_arg[*]}}${extra[@]:+ ${extra[*]}}"
  "${MVN}" -f "${ROOT_DIR}/pom.xml" -pl "${pl}" -am package -DskipTests \
    ${repo_arg[@]+"${repo_arg[@]}"} \
    ${extra[@]+"${extra[@]}"}
  ok "build 完成"
}

# ---------- Java 进程 ----------
service_jar_path() {
  local svc="$1"
  local default_jar
  default_jar="$(service_jar_default "${svc}")"
  local target_dir
  target_dir="$(dirname "${default_jar}")"
  # 优先精确名；找不到就 fallback 到 target/*.jar 中第一个真 jar
  if [[ -f "${default_jar}" ]]; then
    echo "${default_jar}"; return
  fi
  local found
  found=$(find "${target_dir}" -maxdepth 1 -type f -name "*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" 2>/dev/null | head -1)
  echo "${found}"
}

java_start_one() {
  local svc="$1"
  local jar; jar="$(service_jar_path "${svc}")"
  if [[ -z "${jar}" || ! -f "${jar}" ]]; then
    err "${svc}: 找不到 jar，先跑 ./dev.sh build"; return 1
  fi
  local port; port="$(service_port "${svc}")"
  local pid; pid="$(read_pid "${svc}")"
  if pid_alive "${pid}"; then
    warn "${svc} 已在运行 (pid=${pid})，跳过"; return 0
  fi
  if port_in_use "${port}"; then
    err "${svc}: 端口 ${port} 被其它进程占用"; return 1
  fi

  mkdir -p "${HOME}/.misu-dev/files/file-server" || true

  local logfile="${LOG_DIR}/${svc}.out"
  log "启动 ${svc}（jar=$(basename "${jar}")）-> ${logfile}"
  # 后台启动 + 三件套防止父 shell 退出时被卡住：
  # 1) </dev/null 让子进程不持有父 shell 的 stdin（否则 dev.sh 接管道会拿不到 EOF）
  # 2) >${logfile} 2>&1 把 stdout/stderr 全部写日志
  # 3) disown 把 job 从 shell 表里摘掉，bash 在某些路径下 cmd_restart 结束会等
  #    backgrounded child 退出（具体触发条件：set -e + 管道 tail 持有 stdout pipe），
  #    disown 后就不会等了。
  cd "${ROOT_DIR}"
  nohup java \
    -Xms256m -Xmx768m \
    -Dspring.profiles.active=local \
    -DNACOS_SERVER_ADDR="${NACOS_HOST}:${NACOS_PORT}" \
    -jar "${jar}" \
    </dev/null > "${logfile}" 2>&1 &
  local jpid=$!
  echo "${jpid}" > "${PID_DIR}/${svc}.pid"
  disown "${jpid}" 2>/dev/null || disown 2>/dev/null || true
  # 等到端口监听
  wait_for "${svc}@${port}" "lsof -nP -iTCP:${port} -sTCP:LISTEN >/dev/null 2>&1 || curl -sf -m 1 http://127.0.0.1:${port}/ >/dev/null 2>&1" 120 || {
    err "${svc} 启动后未监听端口 ${port}，看日志：${logfile}"
    return 1
  }
  # 子进程链 (subshell -> nohup -> java) 让 $! 拿到的可能是已经退出的中间 PID。
  # 端口起来后用 lsof 取真正的 Java 进程 PID 改写 pid 文件。
  local actual_pid
  actual_pid="$(lsof -nP -iTCP:${port} -sTCP:LISTEN -t 2>/dev/null | head -1)"
  if [[ -n "${actual_pid}" ]]; then
    echo "${actual_pid}" > "${PID_DIR}/${svc}.pid"
  fi
  ok "${svc} pid=$(read_pid "${svc}")"
}

java_stop_one() {
  local svc="$1"
  local pid; pid="$(read_pid "${svc}")"
  local port; port="$(service_port "${svc}" 2>/dev/null || true)"
  # 1) 优先用 PID 文件
  if [[ -n "${pid}" ]] && pid_alive "${pid}"; then
    log "停止 ${svc} pid=${pid}"
    kill "${pid}" 2>/dev/null || true
    local i=0
    while pid_alive "${pid}" && (( i < 20 )); do sleep 0.5; i=$((i+1)); done
    if pid_alive "${pid}"; then
      warn "${svc} 未优雅退出，SIGKILL"
      kill -9 "${pid}" 2>/dev/null || true
    fi
  fi
  # 2) 兜底：万一 pid 文件指向中间 wrapper 已退出但 java 还活着，按端口再扫一遍
  if [[ -n "${port}" ]] && port_in_use "${port}"; then
    local stray
    stray="$(lsof -nP -iTCP:${port} -sTCP:LISTEN -t 2>/dev/null | head -1)"
    if [[ -n "${stray}" ]]; then
      warn "${svc}: 端口 ${port} 仍被 pid=${stray} 占用，强杀"
      kill "${stray}" 2>/dev/null || true
      sleep 1
      kill -0 "${stray}" 2>/dev/null && kill -9 "${stray}" 2>/dev/null || true
    fi
  fi
  clear_pid "${svc}"
  ok "${svc} 已停止"
}

# ---------- 前端 ----------
frontend_start() {
  if [[ ! -d "${FRONTEND_DIR}" ]]; then
    warn "未找到前端目录 ${FRONTEND_DIR}，跳过"
    return 0
  fi
  if ! command -v npm >/dev/null 2>&1; then
    warn "未找到 npm，跳过前端启动"
    return 0
  fi
  local pid; pid="$(read_pid "frontend")"
  if pid_alive "${pid}"; then
    warn "frontend 已在运行 (pid=${pid})"; return 0
  fi
  if port_in_use "${FRONTEND_PORT}"; then
    err "前端端口 ${FRONTEND_PORT} 被占用"; return 1
  fi
  if [[ ! -d "${FRONTEND_DIR}/node_modules" ]]; then
    section "前端：npm install"
    ( cd "${FRONTEND_DIR}" && npm install --no-audit --no-fund )
  fi
  local logfile="${LOG_DIR}/frontend.out"
  log "启动前端 vite -> ${logfile}"
  # 只跑 vite，不跑 electron（dev.sh 用于纯本地联调，浏览器访问即可）
  # 同 java_start_one：</dev/null + disown 完全脱离父 shell
  pushd "${FRONTEND_DIR}" >/dev/null
  nohup npx vite --port "${FRONTEND_PORT}" --host 0.0.0.0 \
    </dev/null > "${logfile}" 2>&1 &
  local fpid=$!
  echo "${fpid}" > "${PID_DIR}/frontend.pid"
  disown "${fpid}" 2>/dev/null || disown 2>/dev/null || true
  popd >/dev/null
  ok "frontend pid=$(read_pid frontend)"
  wait_for "frontend@${FRONTEND_PORT}" "curl -sf http://127.0.0.1:${FRONTEND_PORT}/ >/dev/null 2>&1" 60 || {
    err "前端未在 ${FRONTEND_PORT} 上监听，看日志：${logfile}"
    return 1
  }
}

frontend_stop() {
  java_stop_one "frontend"
  # 兜底：vite 在 macOS 上可能由 npx 派生 node 子进程，按端口杀
  if port_in_use "${FRONTEND_PORT}"; then
    if command -v lsof >/dev/null 2>&1; then
      lsof -nP -iTCP:"${FRONTEND_PORT}" -sTCP:LISTEN -t 2>/dev/null | xargs -r kill -9 2>/dev/null || true
    fi
  fi
}

# ---------- 命令实现 ----------
cmd_up() {
  local do_build=1 do_frontend=1
  for a in "$@"; do
    case "$a" in
      --no-build) do_build=0 ;;
      --no-frontend) do_frontend=0 ;;
      *) err "未知参数：$a"; return 1 ;;
    esac
  done

  check_prereqs
  mw_up
  if (( do_build )); then java_build || return 1; fi

  for svc in "${JAVA_SERVICES[@]}"; do
    java_start_one "${svc}" || { err "服务 ${svc} 启动失败，已停在此处"; return 1; }
  done

  if (( do_frontend )); then
    frontend_start || warn "前端启动失败（不影响后端联调）"
  fi

  cmd_status
  echo
  ok "全部就绪。前端：http://localhost:${FRONTEND_PORT}  网关：http://localhost:30260"
  ok "停止：./dev.sh down"
}

cmd_down() {
  check_prereqs
  frontend_stop
  # 反向停 Java：gateway → file-server → account
  for ((i=${#JAVA_SERVICES[@]}-1; i>=0; i--)); do
    java_stop_one "${JAVA_SERVICES[$i]}"
  done
  mw_down
  ok "全部停止"
}

cmd_status() {
  COMPOSE="$(detect_compose)" || true
  mw_status
  section "Java 服务"
  printf '  %-13s %-8s %-7s %-12s %s\n' "service" "port" "pid" "status" "log"
  for svc in "${JAVA_SERVICES[@]}"; do
    local port; port="$(service_port "${svc}")"
    local pid; pid="$(read_pid "${svc}")"
    local status_text="DOWN"
    local status_color="${C_RED}"
    if pid_alive "${pid}" && port_in_use "${port}"; then
      status_text="UP"; status_color="${C_GREEN}"
    elif pid_alive "${pid}"; then
      status_text="STARTING"; status_color="${C_YELLOW}"
    fi
    printf '  %-13s %-8s %-7s %b%-10s%b %s\n' \
      "${svc}" "${port}" "${pid:-—}" "${status_color}" "${status_text}" "${C_RST}" ".dev/logs/${svc}.out"
  done
  section "前端"
  local fpid; fpid="$(read_pid frontend)"
  if pid_alive "${fpid}" && port_in_use "${FRONTEND_PORT}"; then
    printf '  vite     %-8s %-7s %bUP%b\n' "${FRONTEND_PORT}" "${fpid}" "${C_GREEN}" "${C_RST}"
  else
    printf '  vite     %-8s %-7s %bDOWN%b\n' "${FRONTEND_PORT}" "${fpid:-—}" "${C_RED}" "${C_RST}"
  fi
}

cmd_restart() {
  local target="${1:-all}"
  check_prereqs
  case "${target}" in
    mw)
      mw_down; mw_up
      ;;
    frontend)
      frontend_stop; frontend_start
      ;;
    account|file-server|gateway)
      java_stop_one "${target}"; java_start_one "${target}"
      ;;
    all)
      cmd_down; cmd_up
      ;;
    *)
      err "未知服务：${target}（可选 mw / frontend / account / file-server / gateway / all）"
      return 1
      ;;
  esac
}

cmd_build() {
  check_prereqs
  java_build "$@"
}

cmd_logs() {
  local svc="${1:?用法：dev.sh logs <account|file-server|gateway|frontend>}"
  local f="${LOG_DIR}/${svc}.out"
  [[ -f "${f}" ]] || { err "日志不存在：${f}"; return 1; }
  tail -f "${f}"
}

cmd_seed_nacos() {
  check_prereqs
  bash "${ROOT_DIR}/scripts/dev/nacos/seed.sh"
}

cmd_seed_sql() {
  check_prereqs
  COMPOSE="$(detect_compose)"
  section "执行 docs/*.sql 到本地 MySQL"
  shopt -s nullglob
  local files=("${ROOT_DIR}"/docs/*.sql)
  shopt -u nullglob
  if (( ${#files[@]} == 0 )); then
    warn "未找到 docs/*.sql"
    return 0
  fi
  for f in "${files[@]}"; do
    log "  apply $(basename "${f}")"
    ${COMPOSE} -f "${COMPOSE_FILE}" exec -T mysql \
      mysql -uroot -proot --default-character-set=utf8mb4 < "${f}" \
      || warn "  $(basename "${f}") 应用失败（可能是已应用过的迁移）"
  done
  ok "迁移完成"
}

cmd_nuke() {
  check_prereqs
  COMPOSE="$(detect_compose)"
  warn "将删除中间件容器、volume、本地 PID/日志，输入 yes 继续："
  read -r ans
  [[ "${ans}" == "yes" ]] || { log "取消"; return 0; }
  cmd_down
  ${COMPOSE} -f "${COMPOSE_FILE}" down -v || true
  rm -rf "${DEV_DIR}"
  ok "已清空"
}

# ---------- 入口 ----------
main() {
  local cmd="${1:-help}"; shift || true
  case "${cmd}" in
    up)         cmd_up "$@" ;;
    down)       cmd_down "$@" ;;
    status)     cmd_status "$@" ;;
    restart)    cmd_restart "$@" ;;
    build)      cmd_build "$@" ;;
    logs)       cmd_logs "$@" ;;
    seed-nacos) cmd_seed_nacos "$@" ;;
    seed-sql)   cmd_seed_sql "$@" ;;
    nuke)       cmd_nuke "$@" ;;
    help|-h|--help|"")
      sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
      ;;
    *)
      err "未知命令：${cmd}"; exit 1
      ;;
  esac
}

main "$@"
