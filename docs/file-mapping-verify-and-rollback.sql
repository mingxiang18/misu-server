-- 校验：检查 file_mapping 回填结果

-- 1) 总量对比（旧映射 vs 新映射）
SELECT 'old_torrent_file_mapping' AS source_name, COUNT(*) AS total_count
FROM misu_file_server.torrent_file_mapping
UNION ALL
SELECT 'new_file_mapping', COUNT(*) AS total_count
FROM misu_file_server.file_mapping;

-- 2) 按 open_type / user_id 统计
SELECT open_type, user_id, COUNT(*) AS total_count
FROM misu_file_server.file_mapping
WHERE deleted = 0
GROUP BY open_type, user_id
ORDER BY open_type, user_id;

-- 3) 查重复（不应有结果）
SELECT open_type, user_id, virtual_path, deleted, COUNT(*) AS dup_count
FROM misu_file_server.file_mapping
GROUP BY open_type, user_id, virtual_path, deleted
HAVING COUNT(*) > 1;

-- 4) 查无效 target_path（需要应用侧二次核对磁盘）
SELECT id, open_type, user_id, virtual_path, target_path
FROM misu_file_server.file_mapping
WHERE target_path IS NULL OR target_path = '';


-- 回滚方案（仅在新版本未切流或需紧急回退时执行）
-- A. 保留现场并清空新表
-- CREATE TABLE IF NOT EXISTS misu_file_server.file_mapping_rollback_bak_20260507 AS
-- SELECT * FROM misu_file_server.file_mapping;
-- TRUNCATE TABLE misu_file_server.file_mapping;

-- B. 如果已把旧表 rename 过，恢复旧表名
-- RENAME TABLE
--   misu_file_server.torrent_file_mapping_bak_20260507 TO misu_file_server.torrent_file_mapping;
