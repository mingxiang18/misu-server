# misu-file-server UX MVP — DB 迁移说明（prod）

本轮 UX MVP 涉及一次 `file_mapping` 改动 + 三张新表（`file_share`、`file_audit_log`、`file_version`）。

本地 dev 环境由 Nacos 配置 `spring.jpa.properties.hibernate.hbm2ddl.auto=update` 自动建表建索引，**无需手工执行**。

prod 环境请按下面顺序执行，**先在低峰期分批跑**（DDL 锁表风险）。

---

## §1 — `file_mapping` 新增列（M5 起）

```sql
ALTER TABLE misu_file_server.file_mapping
    ADD COLUMN file_md5 VARCHAR(32) NULL AFTER target_path;
```

> `file_md5` 用于哈希秒传与去重；老数据保持 NULL，由"上传时回填"逐步生效。

## §2 — `file_mapping` 新增索引（M5 起）

```sql
-- 列表 / 搜索：复合索引，左前缀覆盖 (open_type, user_id, deleted) 过滤
ALTER TABLE misu_file_server.file_mapping
    ADD INDEX idx_fm_owner_deleted_type_name (open_type, user_id, deleted, file_type, file_name);

-- 回收站列表：(open_type, user_id, deleted, update_time) 倒序
ALTER TABLE misu_file_server.file_mapping
    ADD INDEX idx_fm_owner_deleted_update (open_type, user_id, deleted, update_time);

-- 哈希秒传：在所有用户范围找内容相同的物理文件
ALTER TABLE misu_file_server.file_mapping
    ADD INDEX idx_fm_md5 (file_md5);
```

## §3 — 可选：`file_mapping` 父路径前缀索引（生产规模建议）

`virtual_path` / `parent_path` 是 `VARCHAR(1200)`，单列做前缀索引能进一步压低 `getFileList` / `findActiveSubtree` 的开销，但 utf8mb4 下需限定前缀长度（InnoDB DYNAMIC 行格式上限 3072 bytes）：

```sql
-- 仅在生产规模 / 大数据集场景下补：
ALTER TABLE misu_file_server.file_mapping
    ADD INDEX idx_fm_owner_parent_deleted_191 (open_type, user_id, parent_path(191), deleted);

ALTER TABLE misu_file_server.file_mapping
    ADD INDEX idx_fm_owner_vpath_deleted_191 (open_type, user_id, virtual_path(191), deleted);
```

实体注解 `@Index` 不支持 column-level prefix length，所以这两个索引**只在 DDL 文档里**提供，不进 hbm2ddl 自动迁移。

---

## §4 — `file_share` 新表（M13）

外链分享：一个 share token 对外暴露一个 file_mapping 的只读访问。

```sql
CREATE TABLE IF NOT EXISTS misu_file_server.file_share (
    id                   BIGINT          NOT NULL AUTO_INCREMENT,
    share_token          VARCHAR(64)     NOT NULL,
    owner_user_id        VARCHAR(64)     NOT NULL,
    open_type            INT             NOT NULL DEFAULT 0,
    source_user_id       VARCHAR(64)     NOT NULL,
    source_virtual_path  VARCHAR(1200)   NOT NULL,
    expire_time          DATETIME(6)     NOT NULL,
    password_hash        VARCHAR(128)    NULL,
    max_downloads        INT             NULL,
    download_count       INT             NOT NULL DEFAULT 0,
    revoked              TINYINT(1)      NOT NULL DEFAULT 0,
    create_time          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time          DATETIME(6)     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY idx_share_token (share_token),
    KEY idx_share_owner_revoked_ctime (owner_user_id, revoked, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> `create_time DEFAULT CURRENT_TIMESTAMP(6)` 必须带精度 `(6)`，否则 MySQL 8 STRICT 模式拒绝（`Invalid default value for 'create_time'`）。  
> `password_hash` 是 BCrypt 输出，null 表示无密码。  
> `revoked` 是创建者主动撤销标记，独立于 `expire_time` 过期判断。

## §5 — `file_audit_log` 新表（M15）

关键写操作的审计日志。读操作不进表（量级太大）。

```sql
CREATE TABLE IF NOT EXISTS misu_file_server.file_audit_log (
    id                  BIGINT          NOT NULL AUTO_INCREMENT,
    action_type         VARCHAR(32)     NOT NULL,
    user_id             VARCHAR(64)     NULL,
    user_name           VARCHAR(64)     NULL,
    target_open_type    INT             NULL,
    target_user_id      VARCHAR(64)     NULL,
    target_virtual_path VARCHAR(1200)   NULL,
    ip                  VARCHAR(64)     NULL,
    user_agent          VARCHAR(512)    NULL,
    status_code         INT             NOT NULL DEFAULT 200,
    error_message       VARCHAR(512)    NULL,
    request_id          VARCHAR(64)     NULL,
    extra               VARCHAR(1000)   NULL,
    create_time         DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_audit_user_time      (user_id, create_time),
    KEY idx_audit_action_time    (action_type, create_time),
    KEY idx_audit_target_owner   (target_open_type, target_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> `target_virtual_path` 故意 **不** 入索引：`varchar(1200) * utf8mb4 = 4800 bytes`，超过 InnoDB 单列索引 3072 bytes 上限。若需按路径筛检审计，按 §3 思路在 prod 补一个前缀索引：`KEY idx_audit_target_path_191 (target_open_type, target_user_id, target_virtual_path(191))`。

## §6 — `file_version` 新表（M18）

文件版本快照。在 `file_mapping` 内容被覆盖（uploadFile/coverFlag、hashCheck 命中覆盖、saveText）前打的物理快照。

```sql
CREATE TABLE IF NOT EXISTS misu_file_server.file_version (
    id                   BIGINT         NOT NULL AUTO_INCREMENT,
    mapping_id           BIGINT         NOT NULL,
    version_no           INT            NOT NULL,
    file_size            BIGINT         NOT NULL DEFAULT 0,
    file_md5             VARCHAR(32)    NULL,
    snapshot_target_path VARCHAR(2000)  NOT NULL,
    original_file_name   VARCHAR(255)   NOT NULL,
    snapshot_reason      VARCHAR(32)    NOT NULL,
    snapshot_by_user_id  VARCHAR(64)    NULL,
    create_time          DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_fv_mapping_versionno (mapping_id, version_no),
    KEY idx_fv_mapping_ctime     (mapping_id, create_time),
    KEY idx_fv_md5               (file_md5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> `snapshot_reason` 取值：`OVERWRITE` / `TEXT_EDIT` / `HASH_DEDUP` / `RESTORE_DEMOTE`。  
> 物理快照文件落在 `<file-server.path>/version/<openType>/<userId>/<mappingId>/v<n>-<name>`，DB 只存路径。  
> 大于 `file.version.maxBytesPerSnapshot`（默认 50 MB）的文件不快照，节省存储。  
> 同一 mapping 保留份数封顶 `file.version.maxVersionsPerFile`（默认 5），超出的最旧版本由代码自动剪枝。  
> 不需要 FK 到 `file_mapping(id)`：物理快照随 mapping 永久删除时由 `FileVersionService.purgeAllVersionsForMapping` 主动级联清理，FK 在跨表 GC 任务里反而是束缚。

---

## §7 — 验证

```sql
-- §1/§2 索引验证
SHOW INDEX FROM misu_file_server.file_mapping;

EXPLAIN SELECT * FROM misu_file_server.file_mapping
WHERE open_type = 0 AND user_id = '1' AND deleted = 0
ORDER BY file_type DESC, file_name ASC LIMIT 50;
-- 应命中 idx_fm_owner_deleted_type_name；type 列显示 ref，Extra 不应含 Using filesort

EXPLAIN SELECT * FROM misu_file_server.file_mapping
WHERE open_type = 0 AND user_id = '1' AND deleted = 1
ORDER BY update_time DESC LIMIT 50;
-- 应命中 idx_fm_owner_deleted_update

-- §4 新表存在性
SELECT COUNT(*) FROM information_schema.tables
WHERE table_schema = 'misu_file_server'
  AND table_name IN ('file_share', 'file_audit_log', 'file_version');
-- 应返回 3
```

## §8 — 回滚

```sql
-- §6
DROP TABLE IF EXISTS misu_file_server.file_version;
-- §5
DROP TABLE IF EXISTS misu_file_server.file_audit_log;
-- §4
DROP TABLE IF EXISTS misu_file_server.file_share;
-- §2
ALTER TABLE misu_file_server.file_mapping DROP INDEX idx_fm_owner_deleted_type_name;
ALTER TABLE misu_file_server.file_mapping DROP INDEX idx_fm_owner_deleted_update;
ALTER TABLE misu_file_server.file_mapping DROP INDEX idx_fm_md5;
-- §1
ALTER TABLE misu_file_server.file_mapping DROP COLUMN file_md5;
```

> `file_version.snapshot_target_path` 指向的物理文件需要单独清理（`rm -rf <file-server.path>/version/`），DROP TABLE 不会动磁盘。
