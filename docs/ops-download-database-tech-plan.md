# 运维中心下载与数据库技术方案

状态：2026-09-13 用户已批准，本文用于实现和评审。ADMIN 是唯一运维超级管理员；所有接口仍经过 misu-ops 的 ADMIN 会话校验。

## 1. 固定入口和通用门禁

| 能力 | 浏览器入口 | 服务端目标 |
| --- | --- | --- |
| qBittorrent | `/ops/qbittorrent/` | 配置中的固定 qBittorrent Service |
| 数据库页面 | `/ops/database/` | misu-ops 原生 `/ops/api/database/**` |

主站 Vue 只使用相对 URL，避免把内部 Service、Secret、上游 Cookie 或数据库连接串放入浏览器。Gateway 和 Nginx 只配置上述固定前缀；代理清除客户端 `Authorization`、`Cookie`、`X-Ops-*`、`X-Original-*` 和身份头，再写入服务端生成的必要字段。目标和 Host 不从请求参数选择。

每个入口使用目标专属的 `MISU_OPS_SESSION` Cookie Path（qBittorrent `/ops/qbittorrent/`，数据库 `/ops/database/`），设置 `Secure; HttpOnly; SameSite=None`。交换票据 30 秒、一次性、目标绑定；会话过期、logout、ADMIN 撤权和进程清理都撤销对应上游 session 或连接。

## 2. 数据库 API

以下路径均位于 misu-ops 的 `/ops/api` 上下文，响应使用统一 JSON 外壳：

```json
{ "code": 0, "message": "", "data": {} }
```

服务端拒绝未知字段，分页和写入都受请求体大小限制。`database`、`table`、`column` 只接受 allowlist 中的元数据名称，不能用 URL 编码绕过校验。

### 2.1 只读 API

```text
GET /ops/api/database/catalogs
GET /ops/api/database/{database}/tables
GET /ops/api/database/{database}/tables/{table}/metadata
GET /ops/api/database/{database}/tables/{table}/rows
```

响应 DTO：

```text
DatabaseCatalogDto { name, displayName }
DatabaseTableDto   { name, comment, rowCountEstimate, primaryKeyMode, tableType }
TableMetadataDto   { database, table, columns[], indexes[], writable, primaryKey, tableType }
ColumnDto          { name, jdbcType, typeName, objectType, size, scale, nullable, defaultValuePresent, autoIncrement, generated, readOnly }
IndexDto           { name, unique, columns[] }
PageDto<T>         { items[], page, pageSize, total, hasNext }
RowDto             { values: Map<String, JsonValue>, rowVersion: String? }
```

`primaryKeyMode` 为 `SINGLE`、`COMPOSITE` 或 `NONE`；元数据同时返回 `tableType`，VIEW 始终只读，即使驱动暴露了主键。元数据 API 不返回数据库账号、连接 URL、密码或隐藏 schema。

`rows` 查询参数只有：

```text
page        1..1000，默认 1；超过上限拒绝，避免大 OFFSET 扫描
pageSize    1..100，默认 50
sort        一个已知字段，默认主键或 metadata 顺序第一列
order       asc|desc，默认 asc
filter      最多 10 个字段条件
```

每个筛选条件为 `{column, operator, value}`，operator 只允许 `eq`、`ne`、`like`、`prefix`、`gt`、`gte`、`lt`、`lte`、`isNull`；单个 value 最长 512 字节，`like` 的通配符由服务端转义后再按约定添加。单次查询最多返回 100 行、扫描上限和超时由连接池/数据库驱动共同限制。总数无法低成本取得时返回 `total: null` 并使用 `hasNext`，不执行无界 count。

### 2.2 数据写入 API

```text
POST   /ops/api/database/{database}/tables/{table}/rows
PATCH  /ops/api/database/{database}/tables/{table}/rows/{primaryKey}
DELETE /ops/api/database/{database}/tables/{table}/rows/{primaryKey}
```

请求 DTO：

```text
CreateRowRequest { values: Map<String, JsonValue> }
UpdateRowRequest { values: Map<String, JsonValue>, expectedRowVersion: String? }
DeleteRowRequest { expectedRowVersion: String? }
```

`primaryKey` 先按 metadata 转成主键 JDBC 类型；路径值不能拼入 SQL。只允许 `SINGLE` 主键且主键列、可写列均通过当前 metadata 校验的表写入。`NONE` 和 `COMPOSITE` 表的 POST/PATCH/DELETE 统一返回 `OPS_DB_TABLE_READ_ONLY`。禁止修改自增主键；服务端忽略或拒绝未知列、生成列、只读列和超长值。

更新/删除在事务内按主键执行，影响行数必须为 1；为 0 返回 `OPS_DB_CONFLICT`，大于 1 返回 `OPS_DB_METADATA_CHANGED` 并回滚。若表有受支持的单调版本列（优先 `updated_at` 或明确配置的版本列），`expectedRowVersion` 作为 WHERE 条件进行乐观并发校验；没有版本列时仍使用影响行数检查，并在 DTO 中不伪造版本值。DELETE 的 `expectedRowVersion` 放在 JSON 请求体中。

### 2.3 受限 DDL API

```text
POST /ops/api/database/{database}/tables
POST /ops/api/database/{database}/tables/{table}/columns
```

请求 DTO：

```text
CreateTableRequest {
  name, comment?, columns: [{name, type, nullable, defaultValue?, primaryKey?, autoIncrement?}]
}
AddColumnRequest {
  name, type, nullable, defaultValue?, position?
}
```

只允许新建表和添加字段。类型 allowlist 为 `TINYINT`、`INT`、`BIGINT`、`DECIMAL(p,s)`（p/s 有界）、`VARCHAR(n)`（n 有界）、`TEXT`、`DATE`、`DATETIME`、`TIMESTAMP`、`JSON`；长度、精度、默认表达式和 comment 长度均有限制。新表最多 64 列，最多一个主键且必须单列；添加字段不能创建第二个主键、外键、索引、触发器或权限语句。默认值只允许安全字面量或明确的 `CURRENT_TIMESTAMP`，不接受表达式、分号、注释或多语句。

表名和字段名先与配置 schema allowlist、`DatabaseMetaData` 当前结果和 ASCII 标识符规则逐项比较，再以 MySQL 标识符引用函数生成 SQL；不能把用户字符串直接当作 SQL 片段。MySQL 的 CREATE/ALTER DDL 会隐式提交，不能依赖事务回滚；每次 DDL 必须是单条原子语句，执行前做完整 metadata preflight，执行后重新读取并精确校验 metadata。执行失败时再次读取 metadata 以确认实际状态，再返回稳定错误码；不得声称 DDL 已回滚。

## 3. JDBC 实现和 SQL 安全

misu-ops 使用 `JdbcTemplate` 配合独立的小型 Hikari 数据源，不复用业务 JPA 数据源。每个配置目标一个连接池，初始建议 `maximumPoolSize=4`、`minimumIdle=0`、连接获取超时 2 秒、空闲超时 60 秒、单请求查询超时 5 秒；总连接和并发请求还受 Ops 全局 semaphore 限制。连接 URL、用户名和密码只从服务端 Secret 绑定。

数据库名、表名、列名、排序列和 DDL 类型只能来自以下顺序的校验：

1. 配置的 database/schema allowlist；
2. 当前连接的 `DatabaseMetaData`；
3. 固定 ASCII 标识符和保留字检查；
4. MySQL identifier quote（反引号）生成。

值全部使用 `?` 参数和 `PreparedStatement`。筛选、主键、默认字面量、更新值不得通过字符串拼接；LIKE 通配符、NULL 和空字符串分别按 DTO 语义绑定。每次 DML 执行使用显式写事务，查询使用受限连接，禁止多语句和任意 SQL 文本。数据库 API 的写请求体在 HTTP 入口由 bounded wrapper 限制为 64 KiB；Content-Length 和 chunked 请求都受限。

类型转换由集中校验和 `PreparedStatement` 绑定处理：整数按 JDBC 类型范围检查，小数按 metadata 的 precision/scale 检查，字符串按 metadata 的 size 检查，日期时间使用 ISO-8601 到 JDBC 时间类型，JSON 必须先解析，布尔只接受 JSON boolean/规定的 0/1。binary/blob/SQLXML/数组/结构体等对象类型明确拒绝并返回 400。结果统一转换为 JSON 安全值；驱动异常只映射为稳定错误码。

审计记录 actor、ADMIN user id、动作、database/table/column 名、请求 ID、结果码和影响行数，不记录任何值、筛选内容、SQL 文本、连接 URL、Cookie、票据、密码或上游响应。日志中的路径只保留固定模板和脱敏 query。

## 4. 错误码

| HTTP | code | 含义 |
| --- | --- | --- |
| 401 | `OPS_AUTH_REQUIRED` | 未登录或会话无效 |
| 403 | `OPS_ADMIN_REQUIRED` | 不是 ADMIN 或 Origin/内部头不可信 |
| 400 | `OPS_DB_INVALID_REQUEST` | DTO、分页、类型或标识符无效 |
| 404 | `OPS_DB_NOT_ALLOWED` | database/table/column 不在 allowlist |
| 409 | `OPS_DB_CONFLICT` | 版本不匹配或影响行数不是 1 |
| 409 | `OPS_DB_METADATA_CHANGED` | metadata 与执行时不一致 |
| 403 | `OPS_DB_TABLE_READ_ONLY` | 无主键或复合主键表写入 |
| 429 | `OPS_DB_LIMIT` | 并发、连接、扫描或请求大小超过上限 |
| 502 | `OPS_DB_UPSTREAM` | MySQL 不可用或驱动错误已安全映射 |
| 500 | `OPS_DB_DDL_REJECTED` | DDL 未通过 allowlist 或执行失败 |

响应 message 只给用户可行动的短说明；不能透传 MySQL 错误、SQL、值或连接信息。

## 5. qBittorrent 接入

qBittorrent 继续使用现有 Service，浏览器唯一入口是 `/ops/qbittorrent/`。misu-ops 为每个 ops session 服务端执行上游登录并缓存短期 Cookie/CSRF 状态；凭据来自 Secret，浏览器只看到固定路径代理响应。登录失败、logout、session 过期或 ADMIN 撤权时清除对应缓存。

Nginx 只允许固定 upstream，保留必要的 redirect、长轮询和 WebSocket Upgrade；清除浏览器 Authorization、主站 Cookie、客户端 X-Ops/X-Original/身份头，并按 qBittorrent 要求重写安全的 Origin/Referer。不得把 qBittorrent 的 Set-Cookie、CSRF token、用户名或密码转发给浏览器。保留原 NodePort `30120`，是否最终关闭另立发布决策。

## 6. 配置 Secret

只提交 Secret 名称、key 和模板，不提交真实值。建议字段：

```text
misu-ops-database: username, password, allowed-schemas
misu-ops-qbittorrent: url, username, password
```

Secret 通过 Pod `secretKeyRef` 注入，缺失时对应能力安全失败；不写入 ConfigMap、前端构建产物、异常、审计或 access log。数据库连接使用应用内固定的 `jdbc:mysql://mysql-inner.mysql.svc.cluster.local:3316/` 目标，Secret 不提供 URL 覆盖；本地测试如需 loopback URL 只能在明确的 `test` profile 下使用。启用远端认证时使用 TLS；qBittorrent URL 也必须通过固定目标配置，不接受环境外覆盖。

## 7. 测试和发布变更

后端单测覆盖 DTO 边界、ADMIN/会话、schema/table/column allowlist、保留字、参数绑定、类型转换、单列 PK 写入、无 PK/复合 PK 只读、影响行数冲突、版本冲突、DDL allowlist、DDL 执行后的 metadata 校验、连接/分页/筛选上限和审计脱敏。使用隔离 MySQL schema 做 JdbcTemplate 集成测试，断言日志没有值和 Secret；由于 MySQL DDL 可能隐式提交，测试验证失败后重新读取 metadata，而不声称事务回滚。

前端测试覆盖数据库/表切换、分页排序筛选、字段类型显示、只读提示、编辑/删除确认、新建表/添加字段错误反馈、429/409 映射；qBittorrent harness 覆盖固定路径、登录 Cookie 不泄露、CSRF、redirect、长轮询、Upgrade、logout 和撤权。

Nginx/Gateway harness 覆盖未登录 401、非 ADMIN 403、未知 Host/路径拒绝、伪造身份头清洗、两个目标 Cookie Path、浏览器 Authorization 不到上游、数据库 API 固定路由和 qBittorrent 代理。发布前执行 `git diff --check`、后端测试、前端 build、YAML/NGINX 语法和离线 release harness。

发布顺序为：先应用代码和 ConfigMap，再创建最小权限 Secret，确认连接和审计指标，最后由 ADMIN 在网站中验收专用 qBittorrent 任务和独立 MySQL 测试 schema。不得在验收中使用现有下载任务、NFS、生产表值或生产 DDL；DDL 失败时重新读取 metadata 判断实际状态，再按备份恢复配置。
