# ARC (actions-runner-controller) on misu-server

把"push master → 自动构建镜像 → 推私有 registry → 滚动重启 prod"做成 k8s 集群内的自动化。
GitHub 端用 long-poll，**不需要把任何端口暴露公网**。

## 0. 一图看懂

```
GitHub (push master)
   │ long-poll
   ▼
┌──── k8s ────────────────────────────────────────────────┐
│ arc-systems/ controller pod (常驻, 调度 CRD)            │
│ arc-runners/ listener pod  (常驻, 监听 GitHub job 队列) │
│              ↓ 有 job 时拉起                            │
│              runner pod (临时, 跑完销毁)                │
│              │ 调度到 misu-maco 节点                    │
│              │ 挂 hostPath: maven-repo / html-out       │
│              │                                          │
│              │ workflow:                                │
│              │  mvn package                             │
│              │  Kaniko build & push 3 个镜像            │
│              │  vite build → rsync 到 nginx html dir    │
│              │  kubectl set image / rollout restart     │
│              │  rollout status；失败 undo               │
│              ↓                                          │
│ misu-server/ deploy: gateway / account / file-server /  │
│              misu-server-nginx 滚动更新                 │
└─────────────────────────────────────────────────────────┘
```

## 1. 前置：containerd 代理 + 准备目录

⚠️ **必做**：k8s 节点的 containerd **不认 clash 代理**（clash 只在用户态 7890 端口），
如果不先把 containerd 接上代理，装 ARC 时 controller 拉 `ghcr.io/actions/...` 会
反复 reset 重试，CPU 打满拖垮 master。

### 1a. 给两个节点的 containerd 配代理（一次性）

把 `pre-install.sh` 拷过去跑：

```bash
# master
scp scripts/deploy/arc/pre-install.sh root@10.8.0.1:/tmp/
ssh root@10.8.0.1 'bash /tmp/pre-install.sh master'

# misu-maco
scp scripts/deploy/arc/pre-install.sh root@10.8.0.26:/tmp/
ssh root@10.8.0.26 'bash /tmp/pre-install.sh worker'
```

`pre-install.sh` 做了三件事：
1. 在 `/etc/systemd/system/containerd.service.d/http-proxy.conf` 写代理配置
2. `daemon-reload`
3. `systemctl restart containerd`（节点上 pod 会重启一次，约 20-30s）

NO_PROXY 已包含集群 CIDR + private-registry（10.8.0.26 + 192.168.50.227），
拉私有 registry 不会绕路。

### 1b. 在 misu-maco 上准备目录

```bash
ssh root@10.8.0.26 '
  mkdir -p /mnt/misu/ci/maven-repo /mnt/misu/ci/html-backups
  chown -R 1001:1001 /mnt/misu/ci
  chown -R 1001:1001 /mnt/misu/misu-server/html
'
```

> nginx pod 以 root 读 html，chown 1001 不影响它读。

## 2. 在 master 装 helm（一次性，走 clash 代理）

master 和 misu-maco 上的 clash 都监听 `*:7890`（HTTP 代理），下面的命令通过本机 7890 走代理：

```bash
ssh root@10.8.0.1 'export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 \
  && curl -fsSL https://get.helm.sh/helm-v3.16.2-linux-amd64.tar.gz | tar -xz -C /tmp \
  && mv /tmp/linux-amd64/helm /usr/local/bin/helm && helm version'
```

## 3. 创建 GitHub App

GitHub → Settings → Developer settings → **GitHub Apps** → New GitHub App
- **App name**: `misu-server-ci`（自取）
- **Homepage URL**: 仓库地址即可
- **Webhook**: ☐ Active **不勾**（long-poll 不需要 webhook）
- **Repository permissions**:
  - Actions: **Read and write**
  - Administration: **Read and write**（ARC 需要管理 self-hosted runner）
  - Metadata: Read（默认）
- **Subscribe to events**: 不需要勾任何
- **Where can this GitHub App be installed?**: Only on this account
- 创建后：
  1. 记下顶部的 **App ID**
  2. "Private keys" → Generate a private key → 浏览器下载一个 `.pem` 文件
  3. 左侧 "Install App" → 装到 `mingxiang18/misu-server` → 安装完看浏览器地址栏 `https://github.com/settings/installations/<这串数字>` 就是 **Installation ID**

## 4. 构建自建 runner 镜像（一次性，走 Mac 的 clash）

```bash
cd scripts/deploy/arc/runner-image
docker buildx build --platform linux/amd64 \
  --build-arg http_proxy=http://host.docker.internal:7897 \
  --build-arg https_proxy=http://host.docker.internal:7897 \
  -t 192.168.50.227:30500/misuaa/misu-ci-runner:latest \
  --push .
```

> `host.docker.internal` 是 docker 自动注入的 host 别名，在 Mac 上指向你本地 clash。
> 7897 是 CLAUDE.md 里记录的"可靠"端口（7890 经常 offline）。
> 详见 [runner-image/README.md](runner-image/README.md)。

## 5. 装 ARC + 创建 runner scale set

把所有 yaml 拷到 master（或者直接 `kubectl --kubeconfig=... apply` 远程操作）：

```bash
# 拷过去
scp scripts/deploy/arc/*.yaml root@10.8.0.1:/root/arc/

# 在 master 上跑
ssh root@10.8.0.1 'bash -se' <<'EOF'
set -euo pipefail
cd /root/arc

# 创建 namespaces
kubectl create namespace arc-systems  --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace arc-runners  --dry-run=client -o yaml | kubectl apply -f -

# 存储 + RBAC
kubectl apply -f 00-storage.yaml
kubectl apply -f 01-rbac.yaml

# Secret —— 你需要先把 02-secret.example.yaml 复制为 02-secret.yaml 并填好三个值
test -f 02-secret.yaml || { echo "请先创建并填好 /root/arc/02-secret.yaml"; exit 1; }
kubectl apply -f 02-secret.yaml

# Helm: 装 controller（OCI chart 从 ghcr.io 拉，需要走 clash；chart 走 helm 进程的代理）
export HTTPS_PROXY=http://127.0.0.1:7890 HTTP_PROXY=http://127.0.0.1:7890
# kube-apiserver 走集群内，必须 NO_PROXY，不然 helm 调 apiserver 也走 clash
export NO_PROXY=localhost,127.0.0.1,10.8.0.1,10.0.0.0/8,.cluster.local,.svc

# 镜像 pull 已经由 containerd 代理处理（pre-install.sh）。--timeout 长一点，
# 给 master 上的 etcd / apiserver 慢慢消化 5 个 CRD 的时间。
helm upgrade --install arc \
  -n arc-systems \
  -f 04-values-controller.yaml \
  /tmp/gha-runner-scale-set-controller-0.9.3.tgz \
  --timeout 15m --wait=false

# 等 controller 启动了再装 scale set，避免一次性给控制面压力太大
sleep 30
kubectl -n arc-systems rollout status deploy/arc-gha-rs-controller --timeout=10m

# Helm: 装 scale set
helm upgrade --install misu-runners \
  -n arc-runners \
  -f 03-values-runner.yaml \
  /tmp/gha-runner-scale-set-0.9.3.tgz \
  --timeout 10m --wait=false

unset HTTPS_PROXY HTTP_PROXY NO_PROXY
EOF
```

## 6. 验证

```bash
# A. 控制面 + 监听器 pod 应该都 Running
ssh root@10.8.0.1 'kubectl -n arc-systems get pods; kubectl -n arc-runners get pods'

# B. GitHub 端：仓库 Settings → Actions → Runners 应该能看到 misu-runners (Idle)

# C. 触发一次 workflow：
#    - 推一个空 commit 到 master：
git commit --allow-empty -m "ci: smoke test ARC" && git push origin master
#    - 或者去 GitHub Actions 页面 workflow_dispatch 选 services=gateway 试单服务
```

## 7. 日常使用

| 想做的事 | 做法 |
|---|---|
| 全量发布 | 推 master 自动跑；或 GitHub Actions UI → workflow_dispatch → services=all |
| 只发某个服务 | workflow_dispatch → services=gateway / account / file_server / frontend |
| 看日志 | GitHub Actions 页面，或 `kubectl -n arc-runners logs -l app.kubernetes.io/component=runner -f` |
| 紧急停掉 build | `kubectl -n arc-runners delete pod -l app.kubernetes.io/component=runner` |
| 手动回滚 | `kubectl -n misu-server rollout undo deploy/<name>`（workflow 失败时已自动 undo） |
| 旧的 release.sh | 保留，本地手动发布兜底（紧急情况/调试用） |

## 8. 排错

| 现象 | 原因 / 修复 |
|---|---|
| runner pod CrashLoop, log `permission denied` 写 `.m2` 或 `_html-out` | misu-maco 上 `/mnt/misu/...` 没 chown 1001 → 重做 §1 |
| Kaniko 失败 `http: server gave HTTP response to HTTPS client` | workflow 参数 `--insecure --skip-tls-verify` 应该已覆盖；如果还出，检查 Kaniko 版本是否被错地缓存 |
| `mvn` 慢得离谱（每次都重下） | maven-repo PVC 没绑上 → `kubectl -n arc-runners describe pod` 看 volume mount |
| GitHub Actions 页面看不到 misu-runners | App 没装到 repo，或 Secret 里 installation_id 错；`kubectl -n arc-runners logs deploy/misu-runners-listener` 看认证错 |
| set image 后 pod 不重建 | 镜像 tag 没变（registry 缓存）—— 用 `imagePullPolicy: Always` 或推唯一 SHA tag（workflow 已是 SHA） |
| `helm install` 报 `unable to retrieve OCI chart` | 网络问题，可手动 `helm pull oci://...` 缓存到本地后 `helm install <local.tgz>` |

## 9. Clash 代理一图

```
┌─ Mac 本机 ─────────────────────────────────────┐
│ docker buildx build (用 host.docker.internal:7897) │
└────────────────────────────────────────────────┘

┌─ k8s 集群 ─────────────────────────────────────────────────────┐
│  master (10.8.0.1)                                            │
│    clash *:7890  ← helm install 走 127.0.0.1:7890             │
│                                                                │
│  misu-maco (10.8.0.26)                                        │
│    clash *:7890                                                │
│    └─ runner pod                                              │
│        HTTP_PROXY=http://$NODE_IP:7890 (downward API)         │
│        NO_PROXY=10.8.0.* / 192.168.50.* / .svc / .cluster.local │
│        ↑ mvn / npm / kaniko base pull / git checkout 全走它   │
│        ↑ 集群内 kube-api / private-registry / set image 绕开  │
└────────────────────────────────────────────────────────────────┘
```

代理走 7890（master、misu-maco 上验证过的 HTTP 入口）；Mac 上 build runner 镜像
走 7897（CLAUDE.md 标注的可靠端口）。pod 里的 NO_PROXY 同时列了 CIDR 和具体 IP，
兼顾 curl/wget（认 CIDR）和 Java/Go（部分版本只认明文）。

## 10. 不在范围

- **ffmpeg-worker**：不进 workflow，继续用 `scripts/deploy/release.sh ffmpeg-worker` 手动发
- **Nacos / Sentinel / 中间件**：不动
- **ConfigMap 下发**：原 release.sh 的 `--config` 流程保留；想接入 CI 的话，workflow 加一个 `path-filter` 判断 `scripts/deploy/k8s/misu-server/*-config.yaml` 是否变更，单独 `kubectl apply -f` 即可（按需再加）

## 11. 文件清单

```
scripts/deploy/arc/
├── README.md                    ← 本文件
├── 00-storage.yaml              ← Maven cache PV/PVC
├── 01-rbac.yaml                 ← misu-deployer SA + Role (misu-server ns)
├── 02-secret.example.yaml       ← GitHub App 凭据模板（cp 为 02-secret.yaml 填值）
├── 03-values-runner.yaml        ← scale-set Helm values
└── runner-image/
    ├── Dockerfile               ← jdk17 + maven + node20 + kubectl + kaniko
    └── README.md                ← 如何 build & push runner 镜像
```
