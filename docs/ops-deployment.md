# 运维中心部署说明

`misu-ops` 是单副本 Deployment，Java 后端只监听 Pod 内的 `127.0.0.1:30264`，Nginx sidecar 监听 `8080`，Service `misu-ops` 只提供 ClusterIP `30264`。主站 Nginx 把控制台路径和 SSH WebSocket 转给该 Service并保留 `Host=server.misu.chat`；现有 Gateway 继续以 `Host=api.misu.chat` 提供 `/ops/api/**` 和 `/ops/ws/**`。sidecar 仅接受这两个 Host，并按路径固定选择 Nacos 或 Headlamp 上游：

- `https://server.misu.chat/nacos/` → `nacos.misu-server.svc.cluster.local:8848`
- `https://server.misu.chat/ops/headlamp/` → `headlamp.kuboard.svc.cluster.local:80`

两个控制台共用主站 Host，但会话 Cookie 名称相同、SameSite=None、Secure、HttpOnly，Path 分别为 `/nacos/` 和 `/ops/headlamp/`；主站统一 revoke 流程会撤销运维会话并按两个目标 Path 删除 Cookie。仓库没有外部边缘入口配置，因此 DNS、证书和到 Gateway 的边缘路由仍由生产入口维护。

Headlamp 的 `kuboard/headlamp` Service 通过 `headlamp-service-clusterip-patch.json` 收回为 `ClusterIP`，并原子移除旧的 `nodePort: 30087`；patch 不改 Service 名称、selector、port 或 targetPort。`apply-headlamp-base-url.sh` 先应用 Service patch，再应用 Headlamp Deployment patch。旧的 `10.8.0.26:30087` 入口在应用该 patch 后关闭，浏览器唯一入口是经过 ADMIN `auth_request` 的 `https://server.misu.chat/ops/headlamp/`。

exchange 响应同时过期旧版 `Path=/` 的 `MISU_OPS_SESSION`，避免升级前遗留的根路径 Cookie 与目标路径 Cookie 同时发送并触发重复凭据拒绝。它不影响主站登录 Cookie。

交换入口是 `/nacos/_ops/exchange` 与 `/ops/headlamp/_ops/exchange`。sidecar 将固定的 `X-Ops-Target` 和 `X-Ops-Proxy-Key` 仅从 loopback 转给 Java；Java 先校验 sidecar、Host 与目标 URL，再消费 30 秒一次性目标票据。交换不依赖浏览器 `Origin`，而发票接口仍要求主站 Origin 与 ADMIN JWT。

边缘入口必须能访问集群网络中的 Service（例如集群内 Ingress 或 LoadBalancer/VIP）。公网边缘 Nginx 不能直接把 `*.svc.cluster.local` 当作公网 DNS 解析；应先转发到一个集群可达的入口，再由入口转到 `misu-ops` 的 ClusterIP。转发时保留 Host，否则 sidecar 的默认虚拟主机会返回 421：

```nginx
upstream misu_ops_service {
    # 仅示例：这里填生产入口实际能访问的集群地址。
    server misu-ops.misu-server.svc.cluster.local:30264;
}

map $http_upgrade $connection_upgrade {
    default upgrade;
    '' close;
}

server {
    listen 443 ssl;
    server_name api.misu.chat;
    access_log off;                         # no ticket/query in edge logs
    error_log /var/log/nginx/misu-ops.error.log crit;

    location / {
        proxy_pass http://misu_ops_service;
        proxy_http_version 1.1;
        proxy_set_header Host api.misu.chat;          # sidecar fixed host
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
    }
}
```

如果 HTTPS 边缘由 Kubernetes Ingress/Gateway 承载，给 `api.misu.chat` 建立到现有 Gateway 的 HTTPS 路由。Gateway 内的路由形状如下，`PreserveHostHeader` 使 sidecar 按路径选择上游：

```yaml
- id: misu-ops-console
  uri: http://misu-ops:30264
  predicates:
    - Path=/nacos/**,/ops/headlamp/**,/ops/api/**
  filters:
    - PreserveHostHeader
```

上面是入口的部署示例，不是仓库中的生产配置。边缘必须同时转发 WebSocket Upgrade/Connection 头；不要把票据或 JWT 拼到查询字符串。边缘入口也必须对这两个 Host 关闭原始 request/request_uri 访问日志，或使用只记录方法、路径（不含 query）、状态和耗时的格式，并将 error log 级别设为 `crit`；否则上游连接错误可能把票据或 SSH 路径写入边缘日志。sidecar 已采用同样的访问日志字段和 `crit` error log。

生产首次部署前，在 `misu-server` 命名空间创建 SSH Secret；命令只读取开发机上的专用生产密钥，不把密钥或 `known_hosts` 写入仓库：

```bash
kubectl -n misu-server create secret generic misu-ops-ssh \
  --from-file=id_ed25519=/secure/path/misu-ops-prod_ed25519 \
  --from-file=known_hosts=/secure/path/misu-ops-known_hosts \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n misu-server create secret generic misu-ops-config \
  --from-literal=proxy-shared-secret="$(openssl rand -hex 32)" \
  --dry-run=client -o yaml | kubectl apply -f -

# token-secret 必须与 misu-account 生产环境用于签发 User-Token 的 token.secret 完全相同。
# 从受控文件读取，避免把签名值写进仓库或命令历史。
kubectl -n misu-server create secret generic misu-account-signing \
  --from-file=token-secret=/secure/path/misu-account-token-secret \
  --dry-run=client -o yaml | kubectl apply -f -
```

`misu-ops-config`（`proxy-shared-secret`）和 `misu-account-signing`（`token-secret`）都是必需 Secret；缺失或 key 名错误时 Pod 不会拿到相应环境变量，控制台鉴权/代理不会成功。发布前应先确认三个 Secret（另有 `misu-ops-ssh`）存在且权限为只读。生产密钥应使用专用运维账号；若继续使用 root，页面必须明确显示目标节点和账号。

Nacos 免二次登录还需要单独的 `misu-ops-nacos-auth` Secret，key 为 `username` 和 `password`，值是专用的 Nacos 2.5.0 运维账号。该 Secret 只以环境变量挂载到 Java 容器，Nginx 不读取，仓库不保存真实值：

```bash
kubectl -n misu-server create secret generic misu-ops-nacos-auth \
  --from-literal=username='受控文件中的 Nacos 运维用户名' \
  --from-literal=password='受控文件中的 Nacos 运维密码' \
  --dry-run=client -o yaml | kubectl apply -f -
```

Nacos 2.5.0 在当前生产配置中已关闭认证，sidecar 保持上游原生 `/nacos/` 响应，不做 HTML/JSON/JS 内容替换；未来配置服务端认证时，Java 仍可按 ops session 使用只读 Secret 获取短时 token，并只通过内部 `Authorization` 注入。浏览器不会得到 Nacos 用户名、密码或 token。Headlamp 使用 `-in-cluster` 加官方 `-proxy-auth=true`，由 sidecar 将 Java 已校验的 ADMIN 用户名写入 `X-Forwarded-User`，Headlamp 使用自身 ServiceAccount 的 RBAC 访问 Kubernetes API，因此管理员无需再次输入 Kubernetes token。官方 v0.43.0 release 首次加入 proxy-auth；仓库中的 `headlamp-base-url-patch.yaml` 已将镜像固定为官方 `ghcr.io/headlamp-k8s/headlamp:v0.45.0@sha256:db3f0e0fc58d358d41daa3fe7fc852437552c7ee873c3645470f7b86a8e0db49`，并固定 base URL 为 `/ops/headlamp`、更新探针路径。应用 patch 后须确认该 Deployment 的 ServiceAccount/RBAC 与现网一致。

启用 Headlamp 的 proxy-auth 后，先应用 Service patch 收回旧的 NodePort；sidecar 通过 kuboard Service 的集群内地址访问。v0.45.0 包含 proxy-auth 之后的常规功能和安全修复，升级会重启 Headlamp Pod，需按现有 RBAC、探针和页面/WS 验收流程灰度确认。应用 patch 后确认旧 `10.8.0.26:30087` 无法访问、`server.misu.chat/ops/headlamp/` 仍经 ADMIN `auth_request` 正常进入，并记录 Deployment 镜像摘要与 ServiceAccount/RBAC。

常用发布命令：

```bash
scripts/deploy/release.sh misu-ops
scripts/deploy/release.sh --config misu-ops
scripts/deploy/release.sh --rollback <UTC备份时间戳>
```

sidecar 在转发上游控制台时会清除主站 JWT Cookie 和 Authorization，只保留经过 Java 鉴权后返回的上游 Cookie/Authorization。两个控制台使用同名但不同 Path 的 `MISU_OPS_SESSION`，可在同一 Host 并存。

sidecar 的 HTTP `auth_request` 由 Java 服务返回 `X-Ops-Upstream-Cookie` 和 `X-Ops-Upstream-Authorization`，Nginx 只把这两个已清洗的值发给 Nacos/Headlamp；控制台 WebSocket 进入 Java `/ops/ws/console/{target}` bridge，并把原始上游 URI 作为内部请求头传递。Nginx 不再用正则猜测或删除 Cookie，因此 Headlamp 自己的 Bearer 会被保留。控制台响应会追加 `Content-Security-Policy: frame-ancestors https://server.misu.chat`，并隐藏上游的 `X-Frame-Options` 和冲突 CSP，确保主站 iframe 可加载。

## 生产验收

上线前先确认 Secret、Service、Pod 和 sidecar 探针均就绪：

```bash
kubectl -n misu-server get secret misu-ops-ssh misu-ops-config misu-account-signing
kubectl -n misu-server get service misu-ops
kubectl -n misu-server rollout status deployment/misu-ops --timeout=5m
kubectl -n misu-server run ops-probe --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  curl -fsS -H 'Host: api.misu.chat' http://misu-ops:30264/_ops/healthz
```

从真实 HTTPS 入口验证 `https://server.misu.chat/nacos/` 和 `https://server.misu.chat/ops/headlamp/`：未登录控制台页面应被 `auth_request` 拒绝，管理员登录后页面、资源/API 请求和 WebSocket Upgrade 应能通过。再用未知 Host 请求 Service，应返回 `421`；请求 `/ops/internal/*` 和目标路径下未知 `/_ops/*` 不应从公网入口暴露。最后验证主站退出、管理员会话撤销和票据过期会关闭对应控制台连接，并记录真实状态码与日志时间。

配置代理变更使用 `scripts/deploy/release.sh --config misu-ops`，它生成带小写 `cfg-<git-sha>-<utc时间>` 的 ConfigMap，并从 live Deployment 导出实际镜像、环境变量和探针；主节点需要 `jq` 来清理 server fields，缺少时脚本会拒绝发布。回滚该时间戳时必须同时恢复 ConfigMap 和 Deployment 引用。正常 Java 发布使用提交短 SHA 作为 `OPS_CONFIG_TAG`，会先 apply 版本化 ConfigMap，再 apply Deployment。
