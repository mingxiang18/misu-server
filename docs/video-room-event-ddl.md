# 放映室事件数据库变更

P2-3 将放映室评论和播放同步事件迁移到 `video_room_event` 表。当前仓库还没有统一迁移工具，落库前请在目标环境手动执行或纳入后续迁移体系。

```sql
CREATE TABLE IF NOT EXISTS video_room_event (
  event_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '事件id',
  room_id VARCHAR(64) NOT NULL COMMENT '放映室id',
  event_type VARCHAR(40) NOT NULL COMMENT '事件类型，COMMENT-评论，PLAYBACK-播放状态',
  user_id BIGINT NULL COMMENT '用户id',
  user_name VARCHAR(100) NULL COMMENT '用户名',
  state VARCHAR(20) NULL COMMENT '播放状态，play/pause',
  video_time_seconds BIGINT NULL COMMENT '视频进度秒数',
  client_send_time BIGINT NULL COMMENT '客户端发送时间戳，毫秒',
  server_receive_time BIGINT NULL COMMENT '服务端接收时间戳，毫秒',
  content TEXT NULL COMMENT '评论内容',
  payload TEXT NULL COMMENT '扩展内容',
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (event_id),
  KEY idx_video_room_event_room_type_time (room_id, event_type, create_time, event_id),
  KEY idx_video_room_event_room_time (room_id, create_time, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='放映室事件';
```
