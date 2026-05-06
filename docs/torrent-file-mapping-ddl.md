# Torrent File Mapping DDL

Torrent 完成同步后不再在用户目录创建软链接，而是在 `torrent_file_mapping` 中记录用户可见路径到真实下载文件的映射。

```sql
CREATE TABLE IF NOT EXISTS misu_file_server.torrent_file_mapping (
    id BIGINT NOT NULL AUTO_INCREMENT,
    open_type INT NOT NULL DEFAULT 0 COMMENT '公开类型，0-私人，1-公共',
    user_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '用户ID，公共目录固定为 public',
    virtual_path VARCHAR(1200) NOT NULL COMMENT '用户文件系统中的相对路径',
    target_path VARCHAR(2000) NOT NULL COMMENT '真实文件或目录绝对路径',
    torrent_hash VARCHAR(64) NULL COMMENT 'torrent hash，预留',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已删除',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_torrent_file_mapping_visible_path (open_type, user_id, virtual_path(512), deleted),
    KEY idx_torrent_file_mapping_user (open_type, user_id, deleted),
    KEY idx_torrent_file_mapping_hash (torrent_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='torrent 文件虚拟路径映射';
```
