# 运维中心技术方案批准

用户批准 docs/ops-console-plan.md，并修正所有 SUPER_ADMIN 设计为已有 ADMIN，不新增角色或普通 ADMIN 与超级管理员的权限层级。

独立 Java 运维服务与反向代理、后端 SSH PTY、服务端强制权限校验、凭据不进入前端或仓库。具体接口与代理配置在实现中由主代理审核，遵循简洁清晰易读要求。

## 2026-09-13 扩展批准

用户明确回复“没问题，按这版开发吧”，批准 qBittorrent 服务端 session 代理和 misu-ops 原生数据库 API。数据库 API 使用 `JdbcTemplate` 与独立小连接池，固定 schema/identifier allowlist、参数化值、单列主键写入、受限 DDL 和审计脱敏；精确接口与测试边界见 [`docs/ops-download-database-tech-plan.md`](../../docs/ops-download-database-tech-plan.md)。
