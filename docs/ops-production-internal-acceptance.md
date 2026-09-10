# 生产内部接入验收记录

本记录只保留命令摘要、资源名、状态、时间和短标识，不记录密钥、令牌、Cookie、票据或密码。

## 变更时间

- 记录时间：2026-09-10T10:23:13Z（本机）
- 功能分支提交：`0055a132b737a4d6cb3c7ad0b66fe7d6272771b0`
- 生产回滚点：Kubernetes 备份 `/root/backups/20260910T100559Z/k8s`；Nacos 备份 `/root/backups/20260910T100842Z/nacos/misu-gateway-prod.yml`

## 阶段状态

| 阶段 | 状态 | 证据 |
| --- | --- | --- |
| 0 只读预检 | PASS | 集群节点健康；`misu-server` 命名空间和现有 account、gateway、frontend 资源可见；公共域名/DNS 未配置。 |
| 1 密钥与 Secret | PASS | `misu-ops-ssh`、`misu-ops-config`、`misu-account-signing` 已存在；两节点专用公钥已存在；临时文件已清理。 |
| 2 构建与检查 | PASS | 后端 17 tests 通过；前端 production build 通过；deploy harness PASS；敏感路径和值扫描通过。 |
| 3 misu-ops 与网关 | PASS | `misu-ops` Deployment 1/1、Pod 双容器 Ready、Service `10.98.237.214`；应用镜像 `0055a13`，digest 前缀 `a17d946e`；Nacos 唯一 `misu-ops-api` 路由已写入并完成 `misu-gateway` rollout。 |
| 4 内部验收 | PARTIAL | 健康、Host、未登录、上游连通和日志检查通过；真实 ADMIN 票据/控制台会话/SSH 只读命令待可用生产 ADMIN 登录上下文。 |
| 5 前端 | NOT RUN | 按门槛等待阶段4管理员代理与 SSH 验收；未发布 frontend。 |

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
- 网关配置精确核对：`misu-ops-api` 数量 1，URI、Path 谓词和 PreserveHostHeader 与部署计划一致。
- 主节点无残留 `kubectl port-forward`、release、Docker build/push 进程。

## 未完成项

- 需使用真实 ADMIN 登录完成 ticket 单次消费、Host 绑定、Nacos/Headlamp 首屏及静态资源代理、Cookie 隔离、console WS、revoke 关闭，以及 master/worker SSH 只读命令与单次 session 验证。
- DNS/TLS 尚未配置，因此不宣称公共 HTTPS 主站 iframe 可用；未降低 Secure Cookie 或添加长期 NodePort。
