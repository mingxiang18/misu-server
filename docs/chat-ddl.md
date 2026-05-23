# misu_chat 迁移 DDL（聊天模块）

prod 用迁移 DDL 建表（不像本地 `hbm2ddl=update` 自动建）。在 prod MySQL 执行下列脚本。
列类型/索引与实体（`com.misu.chat.domain.entity.*`）对齐。

> ⚠️ 关键：`chat_message.content_json` 与 `chat_bot_profile.avatar` 必须 **LONGTEXT**
> （消息引用虽小，但兼容旧数据 / bb 文本回复也走它；avatar 是 base64 头像，TEXT 64KB 装不下）。
> 复合索引列序按查询左前缀；长 varchar（`content_json`/`at_user_ids`/`net_url`）不进复合索引。

```sql
-- 库
CREATE DATABASE IF NOT EXISTS `misu_chat`
  DEFAULT CHARACTER SET utf8mb4 DEFAULT COLLATE utf8mb4_unicode_ci;

USE `misu_chat`;

-- 会话（1对1 PRIVATE / 群 GROUP）
CREATE TABLE IF NOT EXISTS `chat_conversation` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT,
  `type`            VARCHAR(16)  NOT NULL DEFAULT 'PRIVATE',
  `title`           VARCHAR(128) DEFAULT NULL,
  `owner_user_id`   VARCHAR(64)  NOT NULL,
  `bb_group_id`     VARCHAR(64)  DEFAULT NULL,
  `last_message_at` DATETIME(6)  DEFAULT NULL,
  `create_time`     DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `update_time`     DATETIME(6)  DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_conv_owner_type_lastmsg` (`owner_user_id`, `type`, `last_message_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 会话成员（PRIVATE 只放该用户 1 行；bb 是隐式参与方，不入表）
CREATE TABLE IF NOT EXISTS `chat_conversation_member` (
  `id`              BIGINT      NOT NULL AUTO_INCREMENT,
  `conversation_id` BIGINT      NOT NULL,
  `member_user_id`  VARCHAR(64) NOT NULL,
  `role`            VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
  `joined_at`       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `last_read_at`    DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_member_user_conv` (`member_user_id`, `conversation_id`),
  KEY `idx_member_conv` (`conversation_id`),
  UNIQUE KEY `uk_member_conv_user` (`conversation_id`, `member_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 消息（content_json：List<BbMessageContent> 的 JSON；文件/图片只放引用 fileId，旧数据可能内联 base64）
CREATE TABLE IF NOT EXISTS `chat_message` (
  `id`                BIGINT      NOT NULL AUTO_INCREMENT,
  `conversation_id`   BIGINT      NOT NULL,
  `client_message_id` VARCHAR(64) DEFAULT NULL,
  `sender_type`       VARCHAR(16) NOT NULL DEFAULT 'USER',
  `sender_user_id`    VARCHAR(64) DEFAULT NULL,
  `stream_id`         VARCHAR(64) DEFAULT NULL,
  `content_json`      LONGTEXT    NOT NULL,
  `at_user_ids`       VARCHAR(512) DEFAULT NULL,
  `create_time`       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_msg_conv_ctime_id` (`conversation_id`, `create_time`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 群文件索引（文件落磁盘，store_path 指相对路径；bb 外链文件用 net_url；旧数据走 message_id 兜底）
CREATE TABLE IF NOT EXISTS `chat_file` (
  `id`               BIGINT       NOT NULL AUTO_INCREMENT,
  `conversation_id`  BIGINT       NOT NULL,
  `message_id`       BIGINT       DEFAULT NULL,
  `uploader_user_id` VARCHAR(64)  DEFAULT NULL,
  `sender_type`      VARCHAR(16)  DEFAULT NULL,
  `file_name`        VARCHAR(255) DEFAULT NULL,
  `mime_type`        VARCHAR(128) DEFAULT NULL,
  `size`             BIGINT       DEFAULT NULL,
  `category`         VARCHAR(16)  DEFAULT NULL,
  `store_path`       VARCHAR(512) DEFAULT NULL,
  `net_url`          VARCHAR(1024) DEFAULT NULL,
  `source_type`      VARCHAR(16)  DEFAULT NULL,
  `deleted`          BIT(1)       NOT NULL DEFAULT b'0',
  `create_time`      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `idx_file_conv_deleted_ctime` (`conversation_id`, `deleted`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- bb 全局资料（单行，id 固定 1；avatar 全局头像，ADMIN 设置）
CREATE TABLE IF NOT EXISTS `chat_bot_profile` (
  `id`          BIGINT      NOT NULL,
  `name`        VARCHAR(64) DEFAULT NULL,
  `avatar`      LONGTEXT    DEFAULT NULL,
  `update_time` DATETIME(6) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## 已有库升级（如果之前已用旧版本建过表）
旧版本 `content_json`/`avatar` 是 TEXT（64KB），带图/文件消息会被截断丢失，需改 LONGTEXT；
`chat_file` 早期没有磁盘列，需补：

```sql
ALTER TABLE `misu_chat`.`chat_message`     MODIFY `content_json` LONGTEXT NOT NULL;
ALTER TABLE `misu_chat`.`chat_bot_profile` MODIFY `avatar` LONGTEXT;
ALTER TABLE `misu_chat`.`chat_file`
  ADD COLUMN `category`   VARCHAR(16)   DEFAULT NULL,
  ADD COLUMN `store_path` VARCHAR(512)  DEFAULT NULL,
  ADD COLUMN `net_url`    VARCHAR(1024) DEFAULT NULL;
```

## 配套（仓库外）
- prod Nacos `misu-file-server-prod.yml` 新增 `spring.datasource.chat`（指向本库）、`chat.file.path`（群文件落盘目录，**需持久卷**）、`chat.mockBotReply: false`；`bot.*`（bb 连接 + WS 桥）保持不变。
- 群文件落盘目录要挂 k8s 持久卷（否则容器重启文件丢）。
- 详见 CLAUDE.md「模块」一节。
