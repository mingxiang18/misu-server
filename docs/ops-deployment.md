# 运维中心部署说明

`misu-ops` 是单副本 Deployment，Java 后端只监听 Pod 内的 `127.0.0.1:30264`，Nginx sidecar 监听 `8080`，Service `misu-ops` 只提供 ClusterIP `30264`。两个控制台通过固定 Host 路由：

- `ops-nacos.misu.chat` → `nacos.misu-server.svc.cluster.local:8848`
- `ops-k8s.misu.chat` → `headlamp.kuboard.svc.cluster.local:80`

现有边缘 Nginx/Ingress 需要将上述两个 HTTPS Host 转发到 `misu-ops.misu-server.svc.cluster.local:30264`，并把现有 API 网关的 `/ops/**` 路由到同一 Service。仓库没有边缘入口配置，因此 DNS、证书和边缘路由仍由生产入口维护。

边缘入口必须能访问集群网络中的 Service（例如集群内 Ingress、LoadBalancer/VIP 或现有 NodePort）。公网边缘 Nginx 不能直接把 `*.svc.cluster.local` 当作公网 DNS 解析；应先转发到一个集群可达的入口，再由入口转到 `misu-ops` 的 ClusterIP。转发时保留 Host，否则 sidecar 的默认虚拟主机会返回 421：

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
    server_name ops-nacos.misu.chat ops-k8s.misu.chat;
    access_log off;                         # no ticket/query in edge logs
    error_log /var/log/nginx/misu-ops.error.log crit;

    location / {
        proxy_pass http://misu_ops_service;
        proxy_http_version 1.1;
        proxy_set_header Host $host;                 # sidecar allowlist
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;
        proxy_read_timeout 3600s;
    }
}
```

如果 HTTPS 边缘由 Kubernetes Ingress/Gateway 承载，给两个 Host 建立到 Service `misu-ops:30264` 的 HTTP 路由，并配置 equivalent 的 `PreserveHostHeader`。现有 API 网关也要把 `/ops/api/**` 和 `/ops/ws/ssh/**` 原样转到同一 Service；Spring Cloud Gateway 的路由形状如下，`PreserveHostHeader` 使 sidecar 命中 `api.misu.chat`：

```yaml
- id: misu-ops-api
  uri: http://misu-ops.misu-server.svc.cluster.local:30264
  predicates:
    - Path=/ops/api/**,/ops/ws/ssh/**
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

常用发布命令：

```bash
scripts/deploy/release.sh misu-ops
scripts/deploy/release.sh --config misu-ops
scripts/deploy/release.sh --rollback <UTC备份时间戳>
```

`ops-nacos` 和 `ops-k8s` 与主站继续处于 `.misu.chat` Cookie 信任边界内。sidecar 在转发上游控制台时会清除主站 JWT Cookie 和 Authorization，只保留上游自己的登录 Cookie；不要把这两个 Host 视作主站凭据的完全隔离边界。

sidecar 的 HTTP `auth_request` 由 Java 服务返回 `X-Ops-Upstream-Cookie` 和 `X-Ops-Upstream-Authorization`，Nginx 只把这两个已清洗的值发给 Nacos/Headlamp；控制台 WebSocket 进入 Java `/ops/ws/console/{target}` bridge，并把原始上游 URI 作为内部请求头传递。Nginx 不再用正则猜测或删除 Cookie，因此 Headlamp 自己的 Bearer 会被保留。控制台响应会追加 `Content-Security-Policy: frame-ancestors https://server.misu.chat`，同时保留上游已有的 CSP 指令。

## 生产验收

上线前先确认 Secret、Service、Pod 和 sidecar 探针均就绪：

```bash
kubectl -n misu-server get secret misu-ops-ssh misu-ops-config misu-account-signing
kubectl -n misu-server get service misu-ops
kubectl -n misu-server rollout status deployment/misu-ops --timeout=5m
kubectl -n misu-server run ops-probe --rm -i --restart=Never --image=curlimages/curl:8.10.1 -- \
  curl -fsS -H 'Host: ops-nacos.misu.chat' http://misu-ops:30264/_ops/healthz
```

从真实 HTTPS 入口分别验证 `ops-nacos.misu.chat` 和 `ops-k8s.misu.chat`：未登录控制台页面应被 `auth_request` 拒绝，管理员登录后 Nacos/Headlamp 页面及其 API 请求应能通过；浏览器开发者工具应显示 WebSocket Upgrade 成功。再用未知 Host 请求 Service，应返回 `421`；请求 `/ops/internal/*` 不应从公网入口暴露。最后验证主站退出、管理员会话撤销和票据过期会关闭对应控制台连接，并记录真实状态码与日志时间。

配置代理变更使用 `scripts/deploy/release.sh --config misu-ops`，它生成带小写 `cfg-<git-sha>-<utc时间>` 的 ConfigMap，并从 live Deployment 导出实际镜像、环境变量和探针；主节点需要 `jq` 来清理 server fields，缺少时脚本会拒绝发布。回滚该时间戳时必须同时恢复 ConfigMap 和 Deployment 引用。正常 Java 发布使用提交短 SHA 作为 `OPS_CONFIG_TAG`，会先 apply 版本化 ConfigMap，再 apply Deployment。
