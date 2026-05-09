-- 目标：将 torrent_file_mapping 迁移到新的 file_mapping（文件管理解耦 torrent/rss）
-- 执行前请先备份：misu_file_server.torrent_file_mapping

START TRANSACTION;

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

INSERT INTO misu_file_server.file_mapping
(
    open_type,
    user_id,
    virtual_path,
    parent_path,
    file_name,
    file_type,
    file_size,
    target_path,
    deleted,
    create_time,
    update_time
)
SELECT
    tfm.open_type,
    tfm.user_id,
    tfm.virtual_path,
    CASE
        WHEN LOCATE('/', tfm.virtual_path) = 0 THEN ''
        ELSE SUBSTRING(tfm.virtual_path, 1, LENGTH(tfm.virtual_path) - LENGTH(SUBSTRING_INDEX(tfm.virtual_path, '/', -1)) - 1)
    END AS parent_path,
    SUBSTRING_INDEX(tfm.virtual_path, '/', -1) AS file_name,
    'other' AS file_type,
    0 AS file_size,
    tfm.target_path,
    tfm.deleted,
    tfm.create_time,
    tfm.update_time
FROM misu_file_server.torrent_file_mapping tfm
WHERE NOT EXISTS (
    SELECT 1 FROM misu_file_server.file_mapping fm
    WHERE fm.open_type = tfm.open_type
      AND fm.user_id = tfm.user_id
      AND fm.virtual_path = tfm.virtual_path
      AND fm.deleted = tfm.deleted
);

COMMIT;

-- 可选：验证后再执行
-- RENAME TABLE misu_file_server.torrent_file_mapping TO misu_file_server.torrent_file_mapping_bak_20260507;

-- 本地磁盘文件回填说明（管理员后台触发）：
-- 1) 调用 POST /fileServer/fileAdmin/startFileMappingBackfill 启动异步回填
-- 2) 调用 GET /fileServer/fileAdmin/getFileMappingBackfillStatus 查询进度和结果
-- 3) 回填任务会扫描 ${file-server.path}/public 和 ${file-server.path}/private/*
