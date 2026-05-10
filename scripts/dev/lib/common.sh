#!/usr/bin/env bash
# 共用工具函数（被 dev.sh 及子脚本 source）
# 不要直接执行本文件

# ---------- 颜色 ----------
if [[ -t 1 ]]; then
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'
  C_BLUE=$'\033[34m'; C_CYAN=$'\033[36m'; C_GRAY=$'\033[90m'
  C_BOLD=$'\033[1m'; C_RST=$'\033[0m'
else
  C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""; C_CYAN=""; C_GRAY=""; C_BOLD=""; C_RST=""
fi

log()    { printf '%s[dev]%s %s\n' "${C_CYAN}" "${C_RST}" "$*"; }
ok()     { printf '%s✓%s %s\n' "${C_GREEN}" "${C_RST}" "$*"; }
warn()   { printf '%s!%s %s\n' "${C_YELLOW}" "${C_RST}" "$*"; }
err()    { printf '%s✗%s %s\n' "${C_RED}" "${C_RST}" "$*" >&2; }
section(){ printf '\n%s== %s ==%s\n' "${C_BOLD}" "$*" "${C_RST}"; }

# ---------- 路径常量 ----------
# 由调用方提前 export ROOT_DIR
: "${ROOT_DIR:?ROOT_DIR must be set by caller}"

DEV_DIR="${ROOT_DIR}/.dev"
PID_DIR="${DEV_DIR}/pids"
LOG_DIR="${DEV_DIR}/logs"
mkdir -p "${PID_DIR}" "${LOG_DIR}"

# ---------- 配置 ----------
NACOS_HOST="${NACOS_HOST:-127.0.0.1}"
NACOS_PORT="${NACOS_PORT:-8848}"
NACOS_USER="${NACOS_USER:-nacos}"
NACOS_PASS="${NACOS_PASS:-nacos}"
NACOS_NS="${NACOS_NS:-local}"
NACOS_GROUP="${NACOS_GROUP:-DEFAULT_GROUP}"

MYSQL_PORT="${MYSQL_PORT:-3316}"

# 选择 docker compose 命令（兼容老版 docker-compose）
detect_compose() {
  if docker compose version >/dev/null 2>&1; then
    echo "docker compose"
  elif command -v docker-compose >/dev/null 2>&1; then
    echo "docker-compose"
  else
    err "未找到 docker compose / docker-compose，请先安装 Docker Desktop"
    return 1
  fi
}

# ---------- 工具函数 ----------

# wait_for "<name>" "<test_cmd>" <max_seconds>
# 例：wait_for "MySQL" "mysqladmin ping -h 127.0.0.1 -P 3316 -uroot -proot --silent" 60
wait_for() {
  local name="$1"; shift
  local cmd="$1"; shift
  local max="${1:-60}"
  local i=0
  printf '  waiting for %s' "${name}"
  while (( i < max )); do
    if eval "${cmd}" >/dev/null 2>&1; then
      printf '\r'; ok "${name} ready"
      return 0
    fi
    printf '.'
    sleep 1
    i=$((i+1))
  done
  printf '\n'
  err "${name} not ready after ${max}s"
  return 1
}

# port_in_use <port>  →  0=in_use, 1=free
port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"${port}" -sTCP:LISTEN >/dev/null 2>&1
  elif command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -q ":${port}$"
  else
    return 1   # 无法检测时按"空闲"处理
  fi
}

# pid_alive <pid>
pid_alive() {
  [[ -n "$1" ]] && kill -0 "$1" 2>/dev/null
}

# read_pid <service-name>  →  echo PID 或空
read_pid() {
  local f="${PID_DIR}/$1.pid"
  [[ -f "$f" ]] && cat "$f" || true
}

# write_pid <service-name> <pid>
write_pid() {
  echo "$2" > "${PID_DIR}/$1.pid"
}

# clear_pid <service-name>
clear_pid() {
  rm -f "${PID_DIR}/$1.pid"
}

# ---------- Nacos 鉴权 token ----------
# 输出 accessToken 到 stdout；失败则返回非 0
nacos_login() {
  local resp
  resp=$(curl -fsS -X POST \
    "http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/auth/login" \
    --data-urlencode "username=${NACOS_USER}" \
    --data-urlencode "password=${NACOS_PASS}" 2>/dev/null) || return 1
  # 极简解析 accessToken（避免依赖 jq）
  echo "${resp}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

# nacos_namespace_ensure <namespaceId>
# 不存在则创建（v2.3 用 v1 API）
nacos_namespace_ensure() {
  local ns="$1"
  local token
  token=$(nacos_login) || { err "Nacos login failed"; return 1; }
  # 列表查询
  local list
  list=$(curl -fsS "http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/console/namespaces?accessToken=${token}" 2>/dev/null || true)
  if echo "${list}" | grep -q "\"namespace\":\"${ns}\""; then
    return 0
  fi
  # 不存在则创建
  curl -fsS -X POST "http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/console/namespaces?accessToken=${token}" \
    --data-urlencode "customNamespaceId=${ns}" \
    --data-urlencode "namespaceName=${ns}" \
    --data-urlencode "namespaceDesc=local dev namespace" >/dev/null
}

# nacos_publish <dataId> <yaml-file>
nacos_publish() {
  local dataId="$1"
  local file="$2"
  local token
  token=$(nacos_login) || { err "Nacos login failed"; return 1; }
  local content
  content=$(cat "${file}")
  curl -fsS -X POST "http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/cs/configs?accessToken=${token}" \
    --data-urlencode "dataId=${dataId}" \
    --data-urlencode "group=${NACOS_GROUP}" \
    --data-urlencode "tenant=${NACOS_NS}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=${content}" >/dev/null
}

# 健康检查
http_ok() {
  local url="$1"
  curl -fsS -o /dev/null -m 3 "${url}"
}

# Java 服务健康：actuator 没引入，用 root path 即认为活
service_alive() {
  local port="$1"
  curl -sS -o /dev/null -m 3 "http://127.0.0.1:${port}/" >/dev/null 2>&1 \
    || curl -sS -o /dev/null -m 3 -w '%{http_code}' "http://127.0.0.1:${port}/" 2>/dev/null | grep -qE '^[2-5][0-9][0-9]$'
}
