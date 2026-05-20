#!/usr/bin/env bash
# 在装 ARC 之前必须先做的前置 ——
#  1) 给 containerd 配 clash 代理（k8s 拉 ghcr.io 镜像必须走它）
#  2) 给 master k8s API 加足 timeout
#  3) 清理上次半装的残留（如有）
#
# 用法：
#   ssh master 上跑：  bash pre-install.sh master
#   ssh misu-maco 跑： bash pre-install.sh worker
#
# 跑完两边都要 systemctl restart containerd ——
# 会让节点上跑着的 pod 短暂重启（10-30s），但不影响 prod 数据。

set -euo pipefail
ROLE="${1:-}"
[[ "${ROLE}" == "master" || "${ROLE}" == "worker" ]] || { echo "usage: $0 master|worker"; exit 1; }

# ---- 1) containerd 代理 ----
echo "[1/3] 配 containerd HTTP_PROXY..."
mkdir -p /etc/systemd/system/containerd.service.d
cat > /etc/systemd/system/containerd.service.d/http-proxy.conf <<'EOF'
[Service]
Environment="HTTP_PROXY=http://127.0.0.1:7890"
Environment="HTTPS_PROXY=http://127.0.0.1:7890"
Environment="NO_PROXY=localhost,127.0.0.1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,.cluster.local,.svc,10.8.0.26,192.168.50.227"
EOF
systemctl daemon-reload

# ---- 2) (仅 master) 清理上次 ARC 半装残留 ----
if [[ "${ROLE}" == "master" ]]; then
  echo "[2/3] (master only) 清理半装的 arc helm release / CRD..."
  helm uninstall arc -n arc-systems --no-hooks 2>/dev/null || true
  kubectl get crd 2>/dev/null | awk '/actions.github.com/{print $1}' | xargs -r kubectl delete crd --grace-period=0 --force 2>/dev/null || true
  # 等 etcd 把 finalizer 处理掉
  sleep 5
fi

# ---- 3) restart containerd（关键）----
echo "[3/3] restart containerd（节点上 pod 会重启一次，约 20-30s）..."
systemctl restart containerd

echo
echo "完成。验证："
echo "  systemctl status containerd --no-pager | head -10"
echo "  systemctl show containerd --property=Environment"
