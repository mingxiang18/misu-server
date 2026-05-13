-- 目标：创建 video_transcode_job 表（admin MVP 转码管理 DB 镜像）
-- 适用版本：M1 + M7 之后的代码（含 priority 列）
-- 执行前请确认 misu_file_server schema 已存在

START TRANSACTION;

CREATE TABLE IF NOT EXISTS misu_file_server.video_transcode_job (
    task_id              VARCHAR(64)   NOT NULL COMMENT 'MD5 hex，与 .task 文件名对应',
    source_path          VARCHAR(1200) NOT NULL COMMENT '源视频物理路径',
    source_open_type     INT           NULL COMMENT '0 私人 / 1 公共',
    source_user_id       VARCHAR(64)   NULL COMMENT '源所属 userId（公共=public）',
    source_virtual_path  VARCHAR(1200) NULL COMMENT '源虚拟路径',
    output_path          VARCHAR(1200) NULL COMMENT '转码产物 mp4 路径',
    preview_path         VARCHAR(1200) NULL COMMENT '封面 jpg 路径',
    profile_version      VARCHAR(64)   NULL COMMENT 'transcode profile 版本（用于无效化）',
    state                VARCHAR(32)   NOT NULL DEFAULT 'NONE'    COMMENT 'WAITING/PROCESSING/SUCCESS/FAILED/TOO_LARGE/UNSUPPORTED/NONE',
    queue_state          VARCHAR(32)   NOT NULL DEFAULT 'UNKNOWN' COMMENT 'WAITING/RUNNING/FAILED/DONE/UNKNOWN',
    progress             INT           NOT NULL DEFAULT 0,
    message              TEXT          NULL,
    retry_count          INT           NOT NULL DEFAULT 0,
    enqueue_count        INT           NOT NULL DEFAULT 1,
    priority             TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否被置为最优先；.task 文件加 !priority- 前缀',
    last_enqueued_at     DATETIME(6)   NULL,
    create_time          DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time          DATETIME(6)   NULL,
    PRIMARY KEY (task_id),
    KEY idx_vtj_state_update (state, update_time),
    KEY idx_vtj_queue_update (queue_state, update_time),
    KEY idx_vtj_source_user  (source_open_type, source_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频转码任务 DB 镜像';

COMMIT;

-- 回滚（仅在确认下线本功能时执行）：
-- DROP TABLE IF EXISTS misu_file_server.video_transcode_job;
