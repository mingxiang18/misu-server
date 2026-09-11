# 生产内部接入验收记录

本记录只保留命令摘要、资源名、状态、时间和短标识，不记录密钥、令牌、Cookie、票据或密码。

## 变更时间

- 记录时间：2026-09-10T19:18:00+08:00（本机）
- 功能分支提交：`0055a132b737a4d6cb3c7ad0b66fe7d6272771b0`
- 运维授权兼容修复：`54a185b`（测试后仅重发 `misu-ops`）
- 前端权限修复：`18378d6`；验收记录提交会在本次收尾后更新
- Nacos 认证关闭兼容发布：`3a1d74c6cc3e4f62de6a7596867ae6e25236d79a`
- Spring Security 默认密码日志修复发布：`fb625512d99c12ed7f4e4b06f620ca332fe7511c`
- 交换入口修复发布：`50e1b2a0bfb7f5d0992854727d17415c774db166`（sidecar 信任头、Host 和单次目标票据负责交换授权）
- Gateway 转发头修复发布：`d0c09109e2948a8d376420823e2d6ae0944ce0e1`（包含 `50e1b2a`、`1730861`）
- 运维会话校验与撤销修复发布：`37e77d013663e002dbc56c7d95732cd774e3499b`
- 控制台 iframe 响应头修复：`28797c846fead2b3fde10a6e09eac12616becfca`（config-only 发布，保留 d0c0910 Java 镜像）
- SameSite 修复发布尝试：`5d927c1335b854d558f4eedb31bbbdbb1892f4ab`（rollout 超时后进入自动回滚）
- 生产回滚点：Kubernetes 备份 `/root/backups/20260910T100559Z/k8s`；`misu-ops` 修复备份 `/root/backups/20260910T111606Z/k8s`；前端备份 `/root/backups/20260910T111035Z/html`；Nacos 备份 `/root/backups/20260910T100842Z/nacos/misu-gateway-prod.yml`
- 本次 `misu-ops` 发布回滚点：`/root/backups/20260910T121744Z/k8s`（另有发布前资源快照 `/root/backups/20260910T121719Z/k8s-ops-sso`）
- 本次日志修复发布回滚点：`/root/backups/20260910T165319Z/k8s`（发布前资源快照 `/root/backups/20260910T165308Z/k8s-ops-logfix`）
- 5430c92 发布前资源备份：`/root/backups/20260911T034419Z/ops-release-5430c92`；本次 misu-ops 发布备份：`/root/backups/20260911T041457Z/k8s`；Gateway ConfigMap 发布备份：`/root/backups/20260911T042120Z/k8s`
- 37e77d0 发布前完整资源备份：`/root/backups/20260911T055033Z/ops-release-37e77d0`；release Kubernetes 回滚点：`/root/backups/20260911T055115Z/k8s`
- MySQL 单次恢复重启前备份：`/root/backups/20260911T055904Z/mysql-recovery-pre-restart`

## 阶段状态

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| 0 只读预检 | PASS | 集群节点健康；`misu-server` 命名空间和现有 account、gateway、frontend 资源可见；公共域名/DNS 未配置。 |
| 1 密钥与 Secret | PASS | `misu-ops-ssh`、`misu-ops-config`、`misu-account-signing` 已存在；两节点专用公钥已存在；临时文件已清理。 |
| 2 构建与检查 | PASS | 初始后端 17 tests、修复后 misu-ops 20 tests 通过；前端 production build 通过；deploy harness PASS；敏感路径和值扫描通过。 |
| 3 misu-ops 与网关 | PASS | `misu-ops` Deployment 1/1、Pod 双容器 Ready、Service `10.98.237.214`；最终镜像 `fb62551`，digest 前缀 `1d3c2c28`；既有 Nacos 唯一 `misu-ops-api` 路由保持不变并已完成 gateway rollout。 |
| 4 内部验收 | PASS / DNS-TLS BLOCKED | 内部 Service 验证健康、Host 路由、无会话拒绝和 Headlamp 上游；Nacos 认证关闭时匿名 API 可达且不注入 Authorization。真实 ADMIN 浏览器会话的页面/API验收由主线程补充；独立域名仍因 DNS/TLS 未配置而不能公共访问。 |
| 5 前端 | PASS / DNS-TLS BLOCKED | 修复权限后的 production build 已重新发布（tag `18378d6`）；首页与实际引用 JS/CSS 均 HTTP 200，`misu-server-nginx` 1/1；ADMIN 菜单已可见并进入 `/ops`。 |
| 3b 5430c92 运维代理 | PASS | 镜像已推送并发布；sidecar Nginx 配置通过 `nginx -t`，Deployment 1/1，双容器 Ready，重启 0。 |
| 3c Headlamp base URL | PASS | `headlamp` rollout 1/1；保留 `-in-cluster`，设置 `/headlamp/plugins` 和 `/ops/headlamp`；探针 4466、Service NodePort 30087、`headlamp-admin:cluster-admin` 保持。 |
| 3d Gateway 路由 | PASS | Nacos prod 路由 console HTTP/WS 各 1 条、`misu-ops-api` 1 条；仓库实际挂载 ConfigMap 同步 console HTTP/WS 路由；Gateway rollout 1/1。 |
| 3e d0c0910 交换修复 | PASS | 仅重发 misu-ops；Gateway/Headlamp 保持现状。公网两端 exchange 对无效票据均 HTTP 401，Pod 内 `nginx -t` 成功；Deployment 1/1、双容器 Ready、重启 0。 |
| 3f 28797c8 iframe 响应头 | PASS | config-only 备份 `/root/backups/20260911T051051Z/k8s`；生效 ConfigMap `misu-ops-nginx-config-cfg-28797c8-20260911t051051z`；Java 镜像保持 d0c0910；Deployment 1/1、双容器 Ready、重启 0。 |
| 3g 37e77d0 会话校验与撤销修复 | PASS | 镜像 manifest digest 前缀 `73b6bbfa2bbd`；生效 ConfigMap `misu-ops-nginx-config-37e77d0`；Deployment 1/1、双容器 Ready、重启 0；Pod 内 `nginx -t` 成功。 |

## 已执行的内部检查

- `ops-nacos.misu.chat` 与 `ops-k8s.misu.chat` 的 `/_ops/healthz`：HTTP 200。
- 未知 Host：HTTP 421。
- 无会话访问 `/ops/api/endpoints`：HTTP 401。
- 无会话访问两个控制台根路径：Nacos 与 Headlamp 均 HTTP 401。
- 经 `misu-gateway` 访问 `/ops/api/endpoints`：HTTP 401，说明网关路由已到达 ops 后端。
- 未认证 SSH WebSocket 路径：HTTP 400，说明路径已到达后端握手层。
- 无效交换票据和未认证 session revoke：均被拒绝（HTTP 401）。
- Nacos 与 Headlamp 上游 Service：HTTP 200。
- misu-ops 与 gateway 最近 30 分钟日志中的 Bearer、长票据、Cookie、运维会话标识模式：0 命中。
- `misu-ops` Service endpoints：Pod `10.244.1.131:8080`；Pod 两容器 Ready，重启次数 0。
- 37e77d0 发布后 Service 仍为 ClusterIP `10.98.237.214:30264`；当前 Pod `10.244.1.102`，两个容器 Ready，重启次数 0；实际 Java 镜像为 `10.8.0.26:30500/misuaa/misu-ops:37e77d0`，Nginx 镜像保持 `nginx:1.27-alpine`。
- `misu-ops` Secret 投影文件存在且目标文件 mode `0400`；未读取文件内容。
- 本次发布：镜像 `10.8.0.26:30500/misuaa/misu-ops:3a1d74c`，digest 前缀 `30008ddb`；ConfigMap `misu-ops-nginx-config-3a1d74c`；Deployment 1/1、双容器 Ready、重启次数 0；Pod 内 `nginx -t` 成功。
- 最终日志修复发布：镜像 `10.8.0.26:30500/misuaa/misu-ops:fb62551`，digest 前缀 `1d3c2c28`；ConfigMap `misu-ops-nginx-config-fb62551`；Deployment 1/1、双容器 Ready、重启次数 0；Pod 内 `nginx -t` 成功。
- 本次 manifest 将 `OPS_NACOS_USERNAME/PASSWORD` 的 `misu-ops-nacos-auth` 引用设为 optional；生产未创建该 Secret。Nacos `NACOS_AUTH_ENABLE` 与 `nacos.core.auth.enabled` 均未配置，匿名 namespace API HTTP 200；state HTTP 200，`login_page_enabled` 为 string `false`。因此 Java 认证服务在无凭据时不注入上游 Authorization，ops session 鉴权仍由代理保留。
- 本次内部 Service 验证：健康 HTTP 200；Nacos state 无会话 HTTP 401；Headlamp 根路径无会话 HTTP 401；未知 Host HTTP 421；`/ops/api/endpoints` 无会话 HTTP 401；Headlamp 上游 HTTP 200。临时 port-forward 已关闭，NodePort `30087` 未改。
- 3a1d74c 发布后曾发现 Spring Security 自动生成密码启动日志模式；fb62551 发布后最近 15 分钟扫描中 `Using generated security password` 为 0，token/Cookie/ticket/session id/密码赋值模式均为 0，未读取或记录任何敏感值。
- 最终发布后的临时 port-forward、release 和 Docker build/push 进程计数均为 0。
- 网关配置精确核对：`misu-ops-api` 数量 1，URI、Path 谓词和 PreserveHostHeader 与部署计划一致。
- 5430c92 后网关精确核对：Nacos `misu-gateway-prod.yml` 中 console HTTP/WS 路由各唯一 1 条，既有 `misu-ops-api` 唯一 1 条，总路由 6；实际挂载 `misu-gateway-config` 中 console HTTP/WS 路由各 1 条；均保留 `PreserveHostHeader`。
- 5430c92 misu-ops 发布：镜像 tag `5430c92`，manifest digest 前缀 `3a6aba2cade1`；ConfigMap `misu-ops-nginx-config-5430c92`；Deployment 1/1，双容器 Ready，重启 0；Pod 内 `nginx -t` 成功。
- Headlamp base URL 发布：Deployment 1/1，参数保留 `-in-cluster` 并设置 `/headlamp/plugins`、`/ops/headlamp`；探针 `/ops/headlamp/` 端口 4466；Service 仍为 NodePort 30087；`headlamp-admin` 仍绑定 `cluster-admin`。
- 5430c92 内部 Gateway NodePort 回归：`/nacos/`、`/ops/headlamp/` 未认证均 HTTP 401；未知路径 HTTP 404；未知 Host HTTP 421；`/ops/api/endpoints` 和 SSH API 未认证均 HTTP 401。
- 5430c92 最近 30 分钟 misu-ops/nginx 日志敏感模式计数均为 0，`Using generated security password` 均为 0；SSH Secret 投影目标文件 mode 0400，未读取文件内容。
- 5430c92 发布后无残留 port-forward、release 或 Docker build/push 进程；临时 Headlamp patch 文件已清理。
- `50e1b2a` misu-ops 重发：镜像 manifest digest 前缀 `ad97a25d9127`；备份 `/root/backups/20260911T044712Z/k8s`；ConfigMap `misu-ops-nginx-config-50e1b2a`；Deployment 1/1，双容器 Ready，重启 0；Pod 内 `nginx -t` 成功。
- `50e1b2a` 发布后 Gateway 回归：`/nacos/`、`/ops/headlamp/`、`/ops/api/endpoints` 和 SSH API 未认证均 HTTP 401；未知路径 HTTP 404；未知 Host HTTP 421。近 10 分钟 Nginx/ops 日志未命中 token、Cookie、ticket、JWT、password 或 secret 敏感模式。
- 修复前诊断中票据签发接口曾返回 HTTP 200，交换 POST 到达 Nginx 但返回 HTTP 403；修复后真实 ADMIN 的 303、目标 Path Cookie、页面资源/API、重放和错目标验收仍待主线程浏览器会话补充；未记录任何票据或 Cookie。
- `50e1b2a` 公网交换验收失败：Nacos 与 Headlamp 交换 POST 均 HTTP 403；同一新 Pod 本地 Nginx 对无效占位票据返回 HTTP 401，故按门槛仅回滚 misu-ops。回滚至镜像 `5430c92`、ConfigMap `misu-ops-nginx-config-5430c92`，Deployment 1/1、双容器 Ready、重启 0；Gateway、Headlamp 和其他资源未回滚。
- `d0c0910` 最终重发：镜像 manifest digest 前缀 `24b487676f5d`；备份 `/root/backups/20260911T050033Z/k8s`；ConfigMap `misu-ops-nginx-config-d0c0910`；Deployment 1/1、双容器 Ready、重启 0；Pod 内 `nginx -t` 成功。公网 Nacos/Headlamp exchange 对无效票据均 HTTP 401（无 Origin 与允许 Origin 均相同），内部健康 HTTP 200、未知 Host HTTP 421、未知路径 HTTP 404。
- `d0c0910` 发布后匿名回归：`/nacos/`、`/ops/headlamp/`、`/ops/api/endpoints` 和 SSH API 均 HTTP 401；公网未知路径 HTTP 404；近 10 分钟 Nginx/ops 日志未命中 authorization、cookie、ticket、password、secret 或 generated security password 模式。
- `28797c8` 发布后：两端无效 exchange 均 HTTP 401，响应无 `X-Frame-Options`，CSP 含 `frame-ancestors https://server.misu.chat`；匿名 Nacos/Headlamp/API/SSH 均 HTTP 401，公网未知路径 HTTP 404，sidecar 内未知 Host HTTP 421；内部健康 HTTP 200；近 10 分钟 Nginx/ops 日志敏感模式计数均为 0。
- 37e77d0 发布后：Pod 内健康 HTTP 200、未知 Host HTTP 421；公网 `/nacos/`、`/ops/headlamp/`、`/ops/api/endpoints`、`/ops/api/ssh/sessions` 均 HTTP 401，未知路径 HTTP 404；两端无效 exchange 均 HTTP 401，响应无 `X-Frame-Options`，CSP 仅报告 `frame-ancestors https://server.misu.chat`。近 15 分钟日志敏感模式仅命中 3 次通用 `Authorization` 请求字段，未输出其值；未命中 Cookie、ticket、password、secret 或 generated security password 模式。
- 37e77d0 发布期间无新的 release、Docker build/push 或 port-forward 残留；近期事件仅记录本次 Pod 正常创建/拉取/启动，另有 Nacos readiness 警告，与本次 Ops 发布无关。
- 独立依赖状态：`misu-account` Deployment 1/1 且 Service endpoint 存在；MySQL Deployment 0/1、Pod Running 但未 Ready，MySQL Service endpoints 为空，PVC 仍 Bound。未重启或修改 MySQL/account；该依赖故障单独记录，不归因于本次 Ops 发布。
- MySQL 恢复尝试：在确认 PVC/PV 均 Bound 后仅执行一次 `kubectl -n mysql rollout restart deployment/mysql`。旧 Pod 已终止，新 Pod 运行但仍 `0/1`，Service endpoints 仍为空；容器 3306 未监听、主进程状态为 `D`，启动日志未出现 `ready for connections`。承载节点近 15 分钟无 OOM、I/O、文件系统或挂载异常计数；MySQL PV 实际为 NFS `10.8.0.1:/mysql`，容器挂载 `/var/lib/mysql`，与下载任务使用的 `10.8.0.1:/bb-bot/static` NFS 挂载独立。未重启节点、kubelet、containerd，未触碰下载 Pod/目录/进程、PVC/PV 或数据目录；已停止继续重试。
- `5d927c1` 发布尝试：镜像 manifest digest 前缀 `510d03749a68`；备份 `/root/backups/20260911T052240Z/k8s`；rollout 等待 180 秒超时，release 脚本进入 misu-ops 自动回滚。回滚收尾时主节点 SSH banner timeout，最终资源状态待 SSH 恢复后确认；未进行真实 ADMIN ticket 验收。
- 主节点无残留 `kubectl port-forward`、release、Docker build/push 进程。
- 前端备份目录 `/root/backups/20260910T110417Z/html` 存在（73 个文件）；live `index.html` 已更新，ops 页面标记可见。
- 回滚后集群内 `server.misu.chat` 首页 HTTP 200，静态 JS 资源 HTTP 200；回滚未触碰 `misu-ops` 或 gateway。
- 403 原因：发布命令使用 `umask 077`，Vite `dist` 多数文件为 mode 0600，`rsync -a` 保留该权限，nginx 无法读取；备份和回滚后的静态文件为 mode 0644。
- 修复后在同样的严格 umask 下重新发布：备份 `/root/backups/20260910T111035Z/html`，live `index.html` mode 0644；首页、`/assets/index-ChAEgX-x.js`、`/assets/index-sXMHlQSs.css` 均 HTTP 200，nginx rollout `ready=1/1`。
- 生产只读诊断：account 内部用户查询返回 `status=null`、`delFlag=null`、权限包含 `ADMIN`；账号服务既有语义只拒绝明确 `status=1` 或 `delFlag=2`。`AccountCurrentUserClient` 已改为相同规则，新增 null/0 active、1 disabled、2 deleted 测试；20 个 misu-ops 测试通过。
- 修复镜像 `54a185b` 已发布，Deployment 1/1，Pod 双容器 Ready，重启 0；备份 `/root/backups/20260910T111606Z/k8s`。
- ADMIN UI 验收：Nacos 与 Kubernetes 均成功签发入口票据并加载独立域名；控制台连接因 DNS/TLS 未配置终止，未将其宣称为公共可用。
- SSH UI 验收：master 返回 `MISU_OPS_OK`、uid 0、预期主节点 hostname；worker 返回 `MISU_OPS_OK`、uid 0、预期工作节点 hostname；两条连接均显式断开成功。
- 普通账号负向验收未完成：生产 account 对仓库提供的 `verifybot` 登录返回 code 400，内部查询无 authorities，未取得 token，因此没有伪造身份或宣称 403。

## 未完成项

- 需补充一个确实存在且非 ADMIN 的生产账号后，才能完成普通用户 403 负向验收；当前 `verifybot` 不存在/登录失败。
- DNS/TLS 尚未配置，因此 Nacos/Headlamp 独立域名连接终止，不能宣称公共 HTTPS 主站 iframe 或控制台可用；未降低 Secure Cookie、添加浏览器证书例外或长期 NodePort。
- 本次真实 ADMIN 浏览器 session 的 Nacos state/API 与页面静态资源验收需由持有浏览器会话的主线程补充；本代理未获取或记录任何浏览器凭据。
- `d0c0910` 修复后的真实 ADMIN 303、目标 Path Cookie、HTML/CSS/JS/state/API、redirect、错目标/重放和 HTTP/WS 页面验收仍需由持有登录浏览器会话的主线程补充；当前本机无浏览器 tab，未伪造会话或令牌。
- `5d927c1` 的 SameSite=None 真实 Cookie 验收未执行；需先确认自动回滚最终状态，再由主线程浏览器重试。
- 37e77d0 的真实 ADMIN session 撤销/重放行为仍需由持有登录浏览器会话的主线程完成；本代理未获取或记录任何浏览器凭据。
- MySQL 单次恢复后仍未 Ready，`misu-account` 虽保持 1/1 且 endpoint 存在，近 10 分钟日志仍有数据库连接失败；ADMIN 依赖验收需待数据库恢复后再进行。
- 前端未重新发布；现网前端继续使用已验收版本。DNS/TLS 仍未配置，不宣称新 console URL 可从公共 HTTPS 主站使用。
