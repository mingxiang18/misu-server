# 运维中心扩展方案：下载管理与数据库管理

状态：2026-09-13 用户已批准扩展，待开发与发布验收。本方案只定义范围和边界；精确 API、DTO、SQL 安全规则见 [`ops-download-database-tech-plan.md`](./ops-download-database-tech-plan.md)。

## 目标

在现有运维中心增加下载管理和数据库管理。两者沿用网站 ADMIN（超级管理员）、一次性票据和服务端运维会话；用户登录网站后从现有 Vue 运维页进入，无需再次输入上游凭据。数据库管理使用 misu-ops 原生 API 和现有 Vue UI，不引入第三方数据库管理 UI 或任意 SQL 代理。

## 已核查的基础

- `http://10.8.0.26:30120` 是现有 qBittorrent Web UI，对应 `misu-server/q-bit-torrent-pi` NodePort Service；复用现有服务，不修改下载配置和数据目录。
- MySQL 调研时为 `mysql:8.0.40`，内网 Service 为 `mysql-inner.mysql.svc.cluster.local:3316`。接入时仍需核对实际 schema、账号权限和连接上限。
- 当前运维页是 Vue 的 `OpsConsole.vue`，已有 Nacos、Headlamp、SSH 标签；下载和数据库沿用同一 ADMIN 门禁、固定目标映射和现有页面壳层。
- 以上调研是配置快照。实施前核对实际配置，不读取或输出数据库密码、上游会话令牌、SSH 私钥或下载数据。

## 入口与认证

| 入口 | 固定浏览器路径 | 上游/实现 | 认证边界 |
| --- | --- | --- | --- |
| 下载管理 | `/ops/qbittorrent/` | 现有 qBittorrent 集群内 Service | ADMIN 运维会话；服务端登录并缓存短期上游会话 |
| 数据库管理 | `/ops/database/` | misu-ops 原生数据库 API + 现有 Vue UI | ADMIN 运维会话；服务端持有 MySQL 连接凭据 |

两个入口都只允许固定目标和固定前缀。浏览器不得获得 qBittorrent/MySQL 凭据、数据库连接串或长期上游 Cookie。Cookie 按目标路径隔离；logout、会话过期、ADMIN 撤权和服务端回收都必须撤销对应上游状态。

## 下载管理

- 验证 qBittorrent 子路径、资源加载、重定向、Origin/Referer、登录 Cookie、长轮询和 WebSocket（如版本使用）兼容性。
- 凭据从 Kubernetes Secret 注入 misu-ops；上游会话按运维会话隔离，过滤浏览器 Authorization、Cookie 和身份头，保留 qBittorrent 自身 CSRF 防护。
- 只代理固定 qBittorrent Service，不接受请求传入的 URL、Host 或目标参数。子路径不兼容时记录具体路径和替代方案，不关闭全局安全检查。
- 保留原 `30120` NodePort 和现有下载服务配置；第一阶段不重启下载服务，不触碰 NFS、下载目录或既有任务。
- 用专用测试任务验收添加、暂停、继续、删除和异常恢复；测试任务与现有任务、NFS 挂载、`/bb-bot/static` 等资源隔离。

## 数据库管理

数据库页面只调用 misu-ops 原生 API。API 使用独立小连接池和 `JdbcTemplate`，固定配置允许的数据库/schema；浏览器只接收经过 DTO 约束的元数据和分页结果。

首期能力包括：

- 数据库和表列表、字段/索引元数据、表数据分页、排序和筛选。
- 有且只有单列主键的表支持新增、编辑和按主键删除；无主键或复合主键表始终只读。
- 受限的新建表和添加字段操作。字段名、类型、默认值、可空性和主键规则均由服务端 allowlist 校验，不开放任意 DDL 或账号授权 SQL。
- 事务、影响行数检查和可用版本字段的乐观并发校验；每次变更返回明确结果，不把数据库异常原文或值写入日志。

现有 Vue 原型已经确定数据库侧栏、表数据/表结构、搜索、分页、单行编辑、单行删除、新建表和添加字段的信息架构。实现时把模拟数据替换为上述 API；无主键只读、操作确认、上限提示和错误反馈保持一致。原型仍是静态演示，不代表已经连接生产数据库。

## 实施顺序

1. 核对 qBittorrent 版本与路径行为，完成固定代理和服务端 session 适配。
2. 按技术方案实现数据库 API、独立连接池、schema/identifier allowlist 和 Vue 数据接入。
3. 完成未登录/非 ADMIN、跨目标、伪造头、凭据泄露、重放、并发刷新、撤权和资源上限测试。
4. 在隔离 qBittorrent 测试任务和独立 MySQL 测试 schema 上验收读写、DDL、事务、并发和回滚。
5. 审核配置 Secret、固定镜像/Service、备份和回滚步骤后再发布；发布后由网站 ADMIN 完成浏览器验收。

## 验收边界

- 网站 ADMIN 可从同一 Vue 运维页进入 qBittorrent 和数据库页面；普通用户、未登录用户和伪造内部头均不能访问 API 或上游。
- 不提供任意 SQL 编辑器、批量危险操作、整库备份恢复或账号授权入口；需要这些能力时另立需求和权限评审。
- 数据库写入仅适用于单列主键表；无主键、复合主键、未通过 metadata 校验或超过分页/连接/请求上限的操作拒绝。
- 不改变现有 Nacos、Headlamp、SSH、qBittorrent NodePort、NFS 或 MySQL 部署配置；本扩展的生产 Secret 只通过受控部署配置提供，不提交真实值。
