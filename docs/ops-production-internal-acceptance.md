# 生产内部接入验收记录

本记录只保留提交、资源、状态、时间和短标识，不记录密钥、令牌、Cookie、票据、密码或其值。

## 最终发布

- 核对时间：2026-09-11T12:04:50Z。
- 本次相关发布提交：`d6a5b57`（sidecar 配置修复）、`0df3c4e`（misu-ops 镜像）、`c7e7339`（Headlamp v0.45 SSO patch）、`ebcf64e`（Headlamp Service 收回 ClusterIP）、`4acea56`（启用 service-account-token 代理模式）。
- misu-ops 当前镜像：`10.8.0.26:30500/misuaa/misu-ops:0df3c4e`，实际 image digest `sha256:47dc036a6e1b043a0066280c52e06bb81b3e71f9043704693ea5398967a467ef`。
- Headlamp 当前镜像：v0.45.0，digest `sha256:db3f0e0fc58d358d41daa3fe7fc852437552c7ee873c3645470f7b86a8e0db49`。
- Headlamp 参数保留 `-in-cluster`、`-proxy-auth=true`、`-plugins-dir=/headlamp/plugins`、`-base-url /ops/headlamp`，并启用 `-unsafe-use-service-account-token`。
- Headlamp Service 为 ClusterIP `10.101.196.45:80 -> 4466`，无 nodePort；Endpoint 为 `10.244.1.233:4466`。

## 当前健康状态

| 资源 | 实际状态 | 只读证据 |
| --- | --- | --- |
| `misu-server/misu-ops` | 1/1，双容器 Ready，restart 0 | Pod `misu-ops-6d8cfb6958-qqp46`，Service endpoint `10.244.1.104:8080` |
| `kuboard/headlamp` | 1/1，Ready，restart 0 | Pod `headlamp-565c6b6b89-ggfvv`，Service ClusterIP-only，Endpoint ready |
| `mysql/mysql` | 1/1，Ready，restart 0 | Pod `mysql-689ffd464b-z8dx2`，Service endpoint `10.244.1.176:3306` |
| `misu-server/q-bit-torrent-pi` | Running，Ready，restart 0 | Pod `q-bit-torrent-pi-854fcf5f6d-sghg6` |

同一时间窗口在 `misu-server`、`kuboard`、`mysql` 查询不到 Warning events。未访问 NFS、`/bb-bot/static`、下载目录或下载进程，未修改 MySQL、qBittorrent、节点服务或其他数据资源。

## 浏览器与内部验收

- 主线程持有的真实 ADMIN 会话已打开 Nacos 配置管理页面。
- Headlamp 页面已加载，Overview 显示 `3/3`。
- SSH 主节点与工作节点均通过 Ops 页面建立连接并执行只读命令：输出 `MISU_OPS_OK`、uid 0；主节点 hostname 为 `iZ7xv6ttskek29itscng14Z`，工作节点 hostname 为 `misu-MACO`；两条连接均显式断开成功。
- 可信 sidecar 对 Headlamp Kubernetes API 的只读验证：匿名请求 HTTP 401，带代理用户身份请求 HTTP 200。
- Headlamp 使用现有 `headlamp-admin` ServiceAccount；该账号保留现有 `cluster-admin` RBAC。安全边界是：只有主站 ADMIN 鉴权后通过受信 sidecar 的用户，才能进入该共享权限路径；普通用户仍受 Ops 鉴权拒绝。该权限扩大已由用户明确确认。
- Headlamp 日志无 flag/fatal/panic；Nginx 与 Headlamp 日志的凭据值模式扫描为 0。唯一 `error` 字段为 info 级 kubeadm 默认 clusterName 提示。
- 当前浏览器入口使用同源 `/ops/headlamp/`。独立 `ops-nacos`/`ops-k8s` 域名仍未配置 DNS/TLS，因此不宣称其可从公共 HTTPS 直接访问；未降低 HTTPS、Secure Cookie 或证书校验策略。

## 备份与回滚点

- Headlamp Deployment 发布前备份：`/root/backups/20260911T103752Z/headlamp-v045-sso-4acea56`。
- Headlamp 收回 NodePort、切换 ClusterIP 的备份：`/root/backups/20260911T094752Z/ops-headlamp-clusterip-sso-ebcf64e`。
- 更早的 Headlamp v0.45 发布前备份：`/root/backups/20260911T093149Z/ops-headlamp-sso-0df3c4e`。
- misu-ops 与 Gateway 的历史发布备份保留在此前的运维回滚目录；本次 4acea56 只修改 Headlamp Deployment，未触碰 misu-ops、Gateway 或 MySQL。

## 未完成项与限制

- 普通非 ADMIN 账号的 403 负向验收仍未完成：仓库提供的 `verifybot` 在生产登录失败，未伪造身份或令牌。
- 独立 Nacos/Headlamp 域名的 DNS/TLS 尚未配置；同源 Ops 页面验收不代表这些独立域名可用。
- 本记录不包含任何浏览器 Cookie、票据、JWT、ServiceAccount token 或凭据值。
