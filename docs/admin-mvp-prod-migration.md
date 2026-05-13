# 生产部署清单 —— 转码管理 / 用户目录浏览 / 预置目录

适用于 M1–M9 这批改动一次性上线。按顺序执行即可。

## 1. DB 迁移（必做）

生产 Nacos 关闭了 `hbm2ddl=update`（CLAUDE.md §4 提到 prod 必须用 migration DDL），所以新表必须显式执行：

```sql
-- 文件：docs/video-transcode-job-migration.sql
START TRANSACTION;

CREATE TABLE IF NOT EXISTS misu_file_server.video_transcode_job (
    task_id              VARCHAR(64)  NOT NULL COMMENT 'MD5 hex，与 .task 文件名对应',
    source_path          VARCHAR(1200) NOT NULL COMMENT '源视频物理路径',
    source_open_type     INT          NULL COMMENT '0 私人 / 1 公共',
    source_user_id       VARCHAR(64)  NULL COMMENT '源所属 userId（公共=public）',
    source_virtual_path  VARCHAR(1200) NULL COMMENT '源虚拟路径',
    output_path          VARCHAR(1200) NULL,
    preview_path         VARCHAR(1200) NULL,
    profile_version      VARCHAR(64)  NULL,
    state                VARCHAR(32)  NOT NULL DEFAULT 'NONE'    COMMENT 'WAITING/PROCESSING/SUCCESS/FAILED/TOO_LARGE/UNSUPPORTED/NONE',
    queue_state          VARCHAR(32)  NOT NULL DEFAULT 'UNKNOWN' COMMENT 'WAITING/RUNNING/FAILED/DONE/UNKNOWN',
    progress             INT          NOT NULL DEFAULT 0,
    message              TEXT         NULL,
    retry_count          INT          NOT NULL DEFAULT 0,
    enqueue_count        INT          NOT NULL DEFAULT 1,
    priority             TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否被置为最优先；.task 文件加 !priority- 前缀',
    last_enqueued_at     DATETIME(6)  NULL,
    create_time          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time          DATETIME(6)  NULL,
    PRIMARY KEY (task_id),
    KEY idx_vtj_state_update    (state, update_time),
    KEY idx_vtj_queue_update    (queue_state, update_time),
    KEY idx_vtj_source_user     (source_open_type, source_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='视频转码任务 DB 镜像';

COMMIT;
```

执行：

```bash
mysql -h <prod-host> -P 3306 -u<user> -p<password> < docs/video-transcode-job-migration.sql
```

回滚（仅在确认下线本功能时执行）：

```sql
DROP TABLE IF EXISTS misu_file_server.video_transcode_job;
```

## 2. Nacos 配置（必做）

在生产 nacos 的 `misu-file-server.yml`（或 `misu-file-server-prod.yml`）里新增 staging 路径配置：

```yaml
# 超级管理员维护的"预置目录"。文件可通过 SCP / 挂载 / docker volume 投递进来，
# 再通过 /admin/staging UI 共享到公共目录或某用户的私人目录。
file:
  staging:
    # 留空会回退到 ${file-server.path}/staging，建议显式指定 + 单独挂载
    path: /data/misu/staging/
```

**注意事项**：

- 路径必须是 file-server 进程能读写的目录（owner 与 file-server 容器 uid 一致；k8s 里就是和 file-server pod 的 securityContext 一致）。
- 如果 file-server 是容器跑的，建议把 staging dir 作为单独 volume / hostPath mount，便于运维用 scp / 挂载 nfs 等方式投递文件。
- staging 物理 byte 是被"挂虚拟、不动 byte"语义共享的（参考 sharePrivateFileToPublic），所以 staging dir 删文件会让对应 file_mapping 变 stale —— GC 由 `cleanDeletedFileMappings` 兜底，不会爆掉，但运维删之前最好先在 UI 上撤销 share。

## 3. 文件系统准备（必做）

在 file-server 进程的宿主上（容器场景就是 file-server pod 的 PV 上）：

```bash
mkdir -p /data/misu/staging
chown <file-server-uid>:<file-server-gid> /data/misu/staging
chmod 750 /data/misu/staging
```

如果转码 worker 也跑容器，staging dir **不需要** 挂到 worker 容器（worker 只读 transcode-queue / .task，不直接看 staging）。

## 4. Worker 兼容性（无须升级）

`tools/local-ffmpeg-worker/worker-linux.sh:335` 的拾取逻辑是

```sh
task="$(find "$QUEUE_DIR" -maxdepth 1 -type f -name '*.task' | sort | head -n 1)"
```

`!priority-` 前缀（ASCII `0x21`）在排序里排在数字 + 字母之前，所以**不改 worker 一行代码**，已部署的 worker 也能自动优先消费 priority 任务。无须重新发布 worker 镜像。

## 5. 前端构建（必做）

无新依赖，无新环境变量。常规：

```bash
cd misu-file-server-ui
npm install        # 如果 lockfile 没变其实不需要
npm run build      # 输出 dist/
# 把 dist/* 推到你的 CDN / nginx /var/www/html 等
```

## 6. 后端构建（必做）

无新依赖。常规：

```bash
mvn -pl misu-file-server/misu-file-server-biz -am package -DskipTests
# 把 misu-file-server-biz/target/misu-file-server-biz-*.jar 替换到生产 pod / 服务器
```

如果走 k8s + docker，build-push 流程不变，自动会用新代码。

## 7. 鉴权角色（无须改动）

新端点都用了既有的 `UserRole.ADMIN` / `UserRole.FILE_ADMIN`：

- 转码管理（`/videoTranscodeAdmin/*`）：仅 ADMIN
- 用户目录浏览（`/fileAdmin/listUserFiles` / `getUserStorageUsage`）：ADMIN 或 FILE_ADMIN
- 预置目录（`/fileAdmin/listStaging` / `shareStagingTo*`）：ADMIN 或 FILE_ADMIN

prod 上已经有 admin 用户的话不需要新建角色 / 表。

## 8. 上线后冒烟（建议）

按下面顺序点一遍，确认 prod 落地正常：

1. 用 admin 账号登录，移动端 + 桌面端都打开"文件管理"下拉，确认新增的 5 个入口（回收站 / 我的分享 / 审计日志 / 用户目录浏览 / 预置目录）都能进。
2. 上传一个视频 → 进"转码管理" → 应看到对应 DB 行 + 队列状态 + 可置顶 / 重试 / 重转。
3. 进"用户目录浏览" → 选另一个用户 → 私人目录应能看到容量 + 文件列表（只读）。
4. 进"预置目录" → 应看到 staging 物理目录里的内容。SCP 一个测试文件进去 → 刷新 → 右键共享到公共 → 公共目录页面应能看到。

## 9. 已知遗留 / v1.1 候选

- `video_transcode_job` 表无清理策略。如果一年后行数破百万，加一个按 `update_time` 的 GC（参考 `cleanDeletedFileMappings` 的模式）。
- `reconcileFromDisk` 是惰性的（每次 `/jobs` 调用时跑）。极低频访问下 DB 状态可能有几十秒漂移；如需硬实时，让 worker 在状态变更时 push 一个 webhook。
- 管理员浏览用户目录目前是只读。如果要 admin 直接下载某用户的某文件，把现有的 `accessUserFileAsUser` 包一层 admin 鉴权暴露到 `/fileAdmin/downloadUserFile`。
- staging 共享后没有"撤销共享"按钮（要在 file_mapping 视图里手动删）；如果用得多了再补一个 admin 撤销端点。
