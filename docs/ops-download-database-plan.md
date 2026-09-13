# 运维中心扩展方案：下载管理与数据库管理

状态：待用户审核；本文件为方案，不代表已开发或部署。

## 目标

在现有运维中心增加“下载管理”和“数据库管理”，沿用网站 ADMIN 权限、一次性票据和服务端会话。用户登录网站后直接操作嵌入页面，无需再次登录上游。数据库管理应覆盖日常完整管理需求，不限定为只读查询。

## 已核查的基础

- `http://10.8.0.26:30120` 是 qBittorrent Web UI；对应 `misu-server/q-bit-torrent-pi` NodePort Service。复用现有服务。
- MySQL 调研时为 `mysql:8.0.40`；内网 Service 为 `mysql-inner.mysql.svc.cluster.local:3316`。实际数据库权限、连接数量仍需接入时验证。
- 当前 `ConsoleTarget` 只有 Nacos、Headlamp；前端还有 SSH 标签。HTTP、WebSocket、主站 Nginx 与网关使用固定目标路由。
- 调研结果是当时快照；实施前核对实际配置，不读取或输出数据库密码、SSH 私钥、会话令牌。

## 推荐接入方式

| 入口 | 固定路径 | 上游 | 认证方案 |
| --- | --- | --- | --- |
| 下载管理 | `/ops/qbittorrent/` | 现有 qBittorrent 的集群内 Service | Ops 校验 ADMIN；服务端登录并保管上游会话 |
| 数据库管理 | `/ops/database/` | 独立 CloudBeaver CE Deployment + ClusterIP | Ops 校验 ADMIN；验证所选版本的反向代理身份认证 |

两个入口均使用固定上游和独立路径 Cookie。新增目标采用显式配置映射，避免现有二选一判断把新目标误路由至 Headlamp。扩展主站 Nginx、Ops sidecar、网关及 WebSocket 目标映射，不开放任意 URL 转发。

## 下载管理

- 先验证 qBittorrent 子路径、资源加载、重定向、Origin/Referer 与登录 Cookie 的兼容性。
- 凭据由 Secret 注入服务端，上游会话按网站用户隔离；拒绝浏览器伪造上游身份。
- 保留 qBittorrent 原有认证和 CSRF 防护。若子路径不兼容，先报告具体限制及替代方案，不直接关闭全局安全检查。
- 第一阶段保留原 `30120` 内网入口，不重启下载服务，不改变下载设置。
- 新建专用测试任务验收添加、暂停、继续和删除测试任务；不操作现有任务、NFS 挂载或 `/bb-bot/static` 等下载数据。

## 数据库管理

推荐 CloudBeaver CE，以独立服务部署，固定镜像版本与 digest，配置持久化使用独立存储，避免依赖现有下载 NFS。

预期功能：

- 数据库、表、字段、索引、视图浏览及数据增删改查。
- SQL 编辑、多语句执行、执行计划与脚本保存。
- 建库建表、表结构变更及账号授权等管理 SQL；实际能力由数据库账号权限决定。
- 验证所选版本的数据与脚本导入导出格式。整库备份恢复另列验收，不将结果集导出等同于完整备份。

使用专用数据库管理账号，按上述完整管理范围授予所需权限；不默认降为只读，也不将 root 密码交给浏览器。连接信息由服务器管理，初期固定 MySQL 目标。最终账号权限清单在实施前列明。

CloudBeaver CE 官方资料列出 Reverse Proxy Header 认证；商业 SSO/OIDC 的版本限制不能与代理认证混为一谈。需用选定版本验证可信身份头、预配置用户/连接、iframe、子路径及 WebSocket 后才能承诺免二次登录。禁止将匿名访问作为未经审核的替代方案。

CloudBeaver 的 HTTP 和 WebSocket 必须经过 Ops 鉴权；不得照搬无鉴权直通的代理示例。数据库凭据留在服务端，清洗客户端身份头，退出、会话过期或 ADMIN 撤权后停止访问。配置连接池、空闲超时与并发限制，避免耗尽 MySQL 连接。

## 实施 TODO

1. 兼容性验证：选定 CloudBeaver CE 版本，验证两套 UI 子路径、iframe、登录、HTTP/WS；输出结论与版本。
2. 配置设计：明确两个固定目标、代理路径、Secret 字段、数据库账号权限、连接数上限及独立存储。
3. 开发：扩展目标配置、票据/会话、上游登录适配、代理规则及前端标签。
4. 自动验证：未登录/普通用户拒绝、票据重放/跨目标拒绝、伪造身份头拒绝、凭据不泄露、退出/撤权、重复刷新和 WS 生命周期。
5. 功能验收：专用下载测试任务；独立测试库验证 DDL、DML、事务、导入导出及权限管理。
6. 审核与发布：审核最终代码和差异，确认镜像、配置备份及回滚方式后发布，再进行网站验收。

## 验收边界与待确认事项

- 网站 ADMIN 可免二次登录进入两个页面；普通用户与未登录用户均不能访问其 HTTP/WS 接口。
- 不承诺 CloudBeaver 覆盖 Navicat 的全部高级功能；数据库设计器、同步迁移、定时备份等需另行评估。
- 现有会话实现为同用户同目标仅最新会话有效；跨浏览器同时使用会相互替换。若需要多设备并用，实施前调整方案。
- 不改变已批准的 Headlamp cluster-admin 与 SSH root 能力。
- 新开发按 GPT-5.6-Luna high 分配明确子任务，禁止嵌套子代理；主代理负责关键设计与最终审核。

## 官方参考

- [CloudBeaver 认证方式](https://github.com/dbeaver/cloudbeaver/wiki/Authentication-methods)
- [商业 SSO 支持范围](https://dbeaver.com/docs/cloudbeaver/Single-Sign-On/)
- [SQL Editor](https://dbeaver.com/docs/cloudbeaver/SQL-Editor/)
- [服务配置与 URI 前缀](https://dbeaver.com/docs/cloudbeaver/Server-configuration/)
- [WebSocket 配置](https://dbeaver.com/docs/cloudbeaver/WebSockets/)
- [代理配置](https://dbeaver.com/docs/cloudbeaver/Proxy-Configuration/)
