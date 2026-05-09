# File Mapping DDL

文件管理统一使用 `file_mapping`，记录用户可见路径到真实文件路径的映射。该表不再包含 torrent 专用字段，文件服务与 torrent/rss 逻辑解耦。

```sql
CREATE TABLE IF NOT EXISTS misu_file_server.file_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    open_type INT NOT NULL DEFAULT 0 COMMENT '公开类型，0-私人，1-公共',
    user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户ID，公共目录固定为 public',
    virtual_path VARCHAR(1200) NOT NULL COMMENT '用户文件系统中的相对路径',
    parent_path VARCHAR(1200) NOT NULL DEFAULT '' COMMENT '父级相对路径，根目录为空字符串',
    file_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '文件或目录名称',
    file_type VARCHAR(32) NOT NULL DEFAULT 'other' COMMENT 'directory/image/video/other',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，目录可为0',
    target_path VARCHAR(2000) NOT NULL COMMENT '真实文件或目录绝对路径',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_file_mapping_visible_path (open_type, user_id, virtual_path(512), deleted),
    KEY idx_file_mapping_parent (open_type, user_id, parent_path(512), deleted),
    KEY idx_file_mapping_user (open_type, user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文件虚拟路径映射';
```
