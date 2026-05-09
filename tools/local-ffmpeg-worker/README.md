# Local FFmpeg Worker

这个目录用于在当前 Mac 上运行一个轻量 worker：

1. 通过 SSH 扫描 Linux `192.168.50.194:/mnt/misu/misu-server/user-file/transcode-queue`。
2. 读取后端生成的 `.task` 文件。
3. 把 task 中的 `/app/user-file/...` 映射成 Linux 真实路径 `/mnt/misu/misu-server/user-file/...`。
4. 下载源视频到本机临时目录。
5. 使用本机 `ffmpeg` 转码，默认走 macOS VideoToolbox：`hevc_videotoolbox`。
6. 上传缩略图、MP4 转码结果、状态 JSON 回 Linux 对应目录。

## 准备

安装本机 FFmpeg：

```bash
brew install ffmpeg
```

确认支持 Mac 硬件 HEVC 编码：

```bash
ffmpeg -hide_banner -encoders | grep hevc_videotoolbox
```

准备配置：

```bash
cd /Users/renyuming/IdeaProjects/misu-server/tools/local-ffmpeg-worker
cp .env.example .env
```

如果不用系统默认 SSH key，在 `.env` 中配置：

```bash
SSH_KEY=/Users/renyuming/.ssh/your_key
```

## 启动

启动 worker：

```bash
cd /Users/renyuming/IdeaProjects/misu-server/tools/local-ffmpeg-worker
./worker.sh
```

只处理一个任务后退出，方便测试：

```bash
./worker.sh --once
```

如果上一次运行中断，任务卡在远端 `transcode-queue/running/`，可以先恢复回队列：

```bash
./worker.sh --recover-running
```

另开一个终端启动本地看板：

```bash
cd /Users/renyuming/IdeaProjects/misu-server/tools/local-ffmpeg-worker
./serve-dashboard.sh
```

浏览器打开：

```text
http://127.0.0.1:18765/dashboard.html
```

看板会读取 `.work/state.json`，展示 worker 状态、当前 task、进度、源文件、输出文件和按 task 聚合的历史记录。

## 注意

- 同一时间只建议运行一个消费 `transcode-queue` 的 worker，避免和服务器上的容器 worker 抢任务。
- 默认输出仍是后端当前约定的 `HEVC + AAC + MP4`，最高分辨率由 task 中的 `MAX_HEIGHT` 控制，通常是 `1080`。
- 如果源文件路径本身、相对 `REMOTE_USER_FILE_ROOT` 的任一子路径组件是软链接，或 `REMOTE_USER_FILE_ROOT` 下存在指向当前源文件/源目录的软链接，worker 会跳过转码，写入 `FAILED` 状态和“软链接文件跳过转码”消息，并把 task 移到 `done/`。
- `hevc_videotoolbox` 是硬件编码，速度快、CPU 压力低，但同体积画质通常不如 `libx265` 慢速软件编码。想进一步压缩可以调低 `.env` 里的 `-q:v 55`，数值越小通常体积越小、画质越低。
- `.work/tasks/` 是本地临时转码目录，上传成功后默认清理源文件、预览图和输出文件；设置 `KEEP_LOCAL_WORK=1` 可保留用于排查。
- `.work/state.json`、`.work/task-status/*.json` 和 `.work/events.jsonl` 是看板状态文件，会保留在本机；页面主要展示 task 状态，`events.jsonl` 只作为终端日志备查。
