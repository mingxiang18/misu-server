# misu-file-server UX MVP — DB 迁移说明（prod）

本轮新增了若干 UX 功能（搜索、回收站、批量、ZIP 下载、配额、哈希秒传），需要在 `file_mapping` 表新增一列与若干索引。

本地 dev 环境由 Nacos 配置 `spring.jpa.properties.hibernate.hbm2ddl.auto=update` 自动建列建索引，**无需手工执行**。

prod 环境请按下面顺序执行，**先在低峰期分两批跑**（DDL 锁表风险）。

## 1. 新增列

```sql
ALTER TABLE misu_file_server.file_mapping
    ADD COLUMN file_md5 VARCHAR(32) NULL AFTER target_path;
```

> `file_md5` 用于哈希秒传与去重；老数据保持 NULL，由"上传时回填"逐步生效。

## 2. 新增索引

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

## 3. 可选：父路径前缀索引（生产规模建议）

`virtual_path` / `parent_path` 是 `VARCHAR(1200)`，单列做前缀索引能进一步压低 `getFileList` / `findActiveSubtree` 的开销，但 utf8mb4 下需限定前缀长度（InnoDB DYNAMIC 行格式上限 3072 bytes）：

```sql
-- 仅在生产规模 / 大数据集场景下补：
ALTER TABLE misu_file_server.file_mapping
    ADD INDEX idx_fm_owner_parent_deleted_191 (open_type, user_id, parent_path(191), deleted);

ALTER TABLE misu_file_server.file_mapping
    ADD INDEX idx_fm_owner_vpath_deleted_191 (open_type, user_id, virtual_path(191), deleted);
```

实体注解 `@Index` 不支持 column-level prefix length，所以这两个索引**只在 DDL 文档里**提供，不进 hbm2ddl 自动迁移。

## 4. 验证

```sql
SHOW INDEX FROM misu_file_server.file_mapping;

EXPLAIN SELECT * FROM misu_file_server.file_mapping
WHERE open_type = 0 AND user_id = '1' AND deleted = 0
ORDER BY file_type DESC, file_name ASC LIMIT 50;
-- 应命中 idx_fm_owner_deleted_type_name；type 列显示 ref，Extra 不应含 Using filesort

EXPLAIN SELECT * FROM misu_file_server.file_mapping
WHERE open_type = 0 AND user_id = '1' AND deleted = 1
ORDER BY update_time DESC LIMIT 50;
-- 应命中 idx_fm_owner_deleted_update
```

## 5. 回滚

```sql
ALTER TABLE misu_file_server.file_mapping DROP INDEX idx_fm_owner_deleted_type_name;
ALTER TABLE misu_file_server.file_mapping DROP INDEX idx_fm_owner_deleted_update;
ALTER TABLE misu_file_server.file_mapping DROP INDEX idx_fm_md5;
ALTER TABLE misu_file_server.file_mapping DROP COLUMN file_md5;
```
