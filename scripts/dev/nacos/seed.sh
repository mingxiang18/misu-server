#!/usr/bin/env bash
# 把 scripts/dev/nacos/*.yml 推送到本机 Nacos
# 由 dev.sh 自动调用；也可单独执行：./scripts/dev/nacos/seed.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
export ROOT_DIR
# shellcheck source=../lib/common.sh
source "${ROOT_DIR}/scripts/dev/lib/common.sh"

NACOS_DIR="${ROOT_DIR}/scripts/dev/nacos"

section "Nacos: 等待就绪"
wait_for "Nacos" "curl -fsS http://${NACOS_HOST}:${NACOS_PORT}/nacos/v1/console/health/readiness" 90

section "Nacos: 确保命名空间 ${NACOS_NS} 存在"
nacos_namespace_ensure "${NACOS_NS}"
ok "namespace=${NACOS_NS}"

section "Nacos: 推送配置"
for f in "${NACOS_DIR}"/*.yml; do
  dataId="$(basename "${f}")"
  log "  publish ${dataId}"
  if nacos_publish "${dataId}" "${f}"; then
    ok "  ${dataId}"
  else
    err "  ${dataId} publish failed"
    exit 1
  fi
done

section "Nacos: seed 完成"
echo "  控制台：http://${NACOS_HOST}:${NACOS_PORT}/nacos （${NACOS_USER} / ${NACOS_PASS}）"
echo "  命名空间：${NACOS_NS} | 分组：${NACOS_GROUP}"
