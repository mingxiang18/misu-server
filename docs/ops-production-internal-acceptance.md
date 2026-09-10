# 生产内部接入验收记录

本记录只保留命令摘要、资源名、状态、时间和短标识，不记录密钥、令牌、Cookie、票据或密码。

## 变更时间

- 记录时间：2026-09-10T19:18:00+08:00（本机）
- 功能分支提交：`0055a132b737a4d6cb3c7ad0b66fe7d6272771b0`
- 运维授权兼容修复：`54a185b`（测试后仅重发 `misu-ops`）
- 前端权限修复：`18378d6`；验收记录提交会在本次收尾后更新
- Nacos 认证关闭兼容发布：`3a1d74c6cc3e4f62de6a7596867ae6e25236d79a`
- 生产回滚点：Kubernetes 备份 `/root/backups/20260910T100559Z/k8s`；`misu-ops` 修复备份 `/root/backups/20260910T111606Z/k8s`；前端备份 `/root/backups/20260910T111035Z/html`；Nacos 备份 `/root/backups/20260910T100842Z/nacos/misu-gateway-prod.yml`
- 本次 `misu-ops` 发布回滚点：`/root/backups/20260910T121744Z/k8s`（另有发布前资源快照 `/root/backups/20260910T121719Z/k8s-ops-sso`）

## 阶段状态

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| 0 只读预检 | PASS | 集群节点健康；`misu-server` 命名空间和现有 account、gateway、frontend 资源可见；公共域名/DNS 未配置。 |
| 1 密钥与 Secret | PASS | `misu-ops-ssh`、`misu-ops-config`、`misu-account-signing` 已存在；两节点专用公钥已存在；临时文件已清理。 |
| 2 构建与检查 | PASS | 初始后端 17 tests、修复后 misu-ops 20 tests 通过；前端 production build 通过；deploy harness PASS；敏感路径和值扫描通过。 |
| 3 misu-ops 与网关 | PASS | `misu-ops` Deployment 1/1、Pod 双容器 Ready、Service `10.98.237.214`；本次镜像 `3a1d74c`，digest 前缀 `30008ddb`；既有 Nacos 唯一 `misu-ops-api` 路由保持不变并已完成 gateway rollout。 |
| 4 内部验收 | PASS / DNS-TLS BLOCKED | 内部 Service 验证健康、Host 路由、无会话拒绝和 Headlamp 上游；Nacos 认证关闭时匿名 API 可达且不注入 Authorization。真实 ADMIN 浏览器会话的页面/API验收由主线程补充；独立域名仍因 DNS/TLS 未配置而不能公共访问。 |
| 5 前端 | PASS / DNS-TLS BLOCKED | 修复权限后的 production build 已重新发布（tag `18378d6`）；首页与实际引用 JS/CSS 均 HTTP 200，`misu-server-nginx` 1/1；ADMIN 菜单已可见并进入 `/ops`。 |

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
- `misu-ops` Secret 投影文件存在且目标文件 mode `0400`；未读取文件内容。
- 本次发布：镜像 `10.8.0.26:30500/misuaa/misu-ops:3a1d74c`，digest 前缀 `30008ddb`；ConfigMap `misu-ops-nginx-config-3a1d74c`；Deployment 1/1、双容器 Ready、重启次数 0；Pod 内 `nginx -t` 成功。
- 本次 manifest 将 `OPS_NACOS_USERNAME/PASSWORD` 的 `misu-ops-nacos-auth` 引用设为 optional；生产未创建该 Secret。Nacos `NACOS_AUTH_ENABLE` 与 `nacos.core.auth.enabled` 均未配置，匿名 namespace API HTTP 200；state HTTP 200，`login_page_enabled` 为 string `false`。因此 Java 认证服务在无凭据时不注入上游 Authorization，ops session 鉴权仍由代理保留。
- 本次内部 Service 验证：健康 HTTP 200；Nacos state 无会话 HTTP 401；Headlamp 根路径无会话 HTTP 401；未知 Host HTTP 421；`/ops/api/endpoints` 无会话 HTTP 401；Headlamp 上游 HTTP 200。临时 port-forward 已关闭，NodePort `30087` 未改。
- 本次最近 24 小时日志扫描未发现 token、Cookie、ticket 或 session id 的赋值；发现 2 个 `password` 词和 1 个 UUID 形态的 Spring Security 自动生成密码启动日志命中，未读取或记录其值，需后续关闭该未使用默认用户日志后再宣称日志完全无敏感模式。
- 网关配置精确核对：`misu-ops-api` 数量 1，URI、Path 谓词和 PreserveHostHeader 与部署计划一致。
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
- `misu-ops` 启动日志仍有 Spring Security 自动生成密码的模式命中；该值未被读取或写入证据，需单独修复日志来源。
