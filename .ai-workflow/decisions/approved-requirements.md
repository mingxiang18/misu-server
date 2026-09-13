# 运维中心需求批准

用户于本任务明确表示“方案审核没问题”，批准 docs/ops-console-plan.md，并修正：ADMIN 就是总的超级管理员，不新增 SUPER_ADMIN。

范围：独立运维模块、网站内 Nacos/Headlamp、两节点 SSH；仅 ADMIN 可操作。实现与部署保持简洁清晰易读。主要实现委派 Luna xhigh，主代理审核关键步骤。

## 2026-09-13 扩展批准

用户明确回复“没问题，按这版开发吧”，批准在上述范围内增加 qBittorrent 下载管理和 misu-ops 原生 MySQL 数据库 API/UI；数据库能力受 schema、metadata、主键和 DDL allowlist 约束，不引入第三方数据库管理 UI。保留 ADMIN 超级管理员、服务端 Secret 和现有安全边界。
