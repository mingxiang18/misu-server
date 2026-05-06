# RSS 自动化数据库变更

P2-2 新增 RSS 条目历史和匹配规则，需要在 `misu_file_server` 库补充两张表。当前仓库还没有统一迁移工具，落库前请在目标环境手动执行或纳入后续迁移体系。

```sql
CREATE TABLE IF NOT EXISTS rss_item (
  item_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '条目id',
  rss_id BIGINT NOT NULL COMMENT 'rss订阅id',
  guid VARCHAR(1000) NULL COMMENT 'RSS条目唯一标识',
  title VARCHAR(1000) NOT NULL COMMENT '标题',
  torrent_url VARCHAR(2000) NULL COMMENT '磁力链接',
  torrent_hash VARCHAR(64) NULL COMMENT 'torrent hash',
  description TEXT NULL COMMENT '描述',
  author VARCHAR(200) NULL COMMENT '发布者',
  publish_time DATETIME NULL COMMENT '发布时间',
  updated_time DATETIME NULL COMMENT '更新时间',
  match_state INT NOT NULL DEFAULT 0 COMMENT '匹配状态，0-未匹配，1-已匹配',
  download_state INT NOT NULL DEFAULT 0 COMMENT '下载状态，0-未下载，1-已下载，2-失败',
  matched_rule_id BIGINT NULL COMMENT '匹配规则id',
  error_message VARCHAR(200) NULL COMMENT '错误信息',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (item_id),
  KEY idx_rss_item_rss_time (rss_id, publish_time, create_time),
  KEY idx_rss_item_hash (rss_id, torrent_hash),
  KEY idx_rss_item_guid (rss_id, guid(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RSS条目历史';

CREATE TABLE IF NOT EXISTS rss_rule (
  rule_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '规则id',
  rss_id BIGINT NOT NULL COMMENT 'rss订阅id',
  rule_name VARCHAR(200) NULL COMMENT '规则名称',
  include_keywords VARCHAR(1000) NULL COMMENT '包含关键词，逗号或换行分隔',
  exclude_keywords VARCHAR(1000) NULL COMMENT '排除关键词，逗号或换行分隔',
  regex VARCHAR(1000) NULL COMMENT '正则表达式',
  download_path VARCHAR(1000) NOT NULL COMMENT '下载目录',
  enabled TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  auto_download TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否自动下载',
  remark VARCHAR(200) NULL COMMENT '备注',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (rule_id),
  KEY idx_rss_rule_rss_enabled (rss_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RSS匹配规则';
```
