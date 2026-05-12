# misu ffmpeg worker

后端 `misu-file-server` 把视频转码任务以 `.task` 文件的形式写到
`transcode-queue/`，由本工具消费并产出 HEVC + AAC + MP4 + 预览图。

有两种运行方式：

| 模式 | 何时用 | 文件 |
|---|---|---|
| **k8s 容器** | 生产 / 长期 | `Dockerfile` + `worker-linux.sh` + `worker-api.py` + `k8s/*.yaml` |
| **Mac 本机 SSH** | 临时回灌 / 离线开发 | `worker.sh` + `serve-dashboard.sh` + `dashboard.html` |

---

## 1. 在 k8s 集群内跑（推荐）

### 1.1 它是怎么工作的

- worker pod 跑在与 `misu-file-server` 同一个节点 (`kubernetes.io/hostname=misu-maco`)。
- 共享同一份 `hostPath`：`/mnt/misu/misu-server/user-file`。文件零拷贝，
  任务文件按 `mv` 锁定 `running/` → 转码 → 写出 → `mv` 到 `done/` 或 `failed/`，
  和原来的 SSH worker 是同一份契约，misu-file-server 端无需改动。
- 容器启动时 `detect-encoder.sh` 自动探测：
  1. **VAAPI** (Intel / AMD iGPU + `/dev/dri/renderD128`)
  2. **NVENC** (NVIDIA GPU + `nvidia-smi`)
  3. **libx265** 软件兜底
  每档都跑一次 1 秒 testsrc dry-encode 验证能真编出来，失败级联到下一档。
  环境变量 `VIDEO_ENCODER_ARGS` 显式设置时跳过检测。
- 同时跑两个进程：`worker-linux.sh` (转码) + `worker-api.py` (管理 HTTP API，
  ClusterIP:18765)。`entrypoint.sh` 收到 SIGTERM 时显式 forward 到两个 child，
  让 worker 的 trap 把 `running/` 任务退回 `queue/` 再退出。

### 1.2 镜像构建 & 推送

```bash
# 推到默认仓库 misuaa/misu-ffmpeg-worker
./scripts/build-push-ffmpeg-worker.sh 0.0.1

# 推到本地集群仓库
REGISTRY=10.8.0.26:30500/ ./scripts/build-push-ffmpeg-worker.sh 0.0.1

# 单架构（misu-maco 是 arm64 mac mini 时）
PLATFORMS=linux/arm64 ./scripts/build-push-ffmpeg-worker.sh 0.0.1
```

### 1.3 部署

```bash
cd tools/local-ffmpeg-worker/k8s
# 必要：先确认 deployment.yaml 中的 image 指向正确版本
kubectl apply -f configmap.yaml -f service.yaml -f deployment.yaml
# 可选：开启 NetworkPolicy 限制访问
cp networkpolicy.yaml.example networkpolicy.yaml
kubectl apply -f networkpolicy.yaml
```

文件清单：

| 文件 | 作用 |
|---|---|
| `configmap.yaml` | `POLL_INTERVAL` / `AUDIO_BITRATE` / `MAX_TASK_RETRIES` 等可调参 |
| `deployment.yaml` | 单副本 + `Recreate` + `nodeAffinity: misu-maco` + 60s grace |
| `service.yaml` | ClusterIP:18765 暴露 worker-api（**不开 Ingress / NodePort**） |
| `networkpolicy.yaml.example` | 可选：限定只允许 `app=misufileserver` pod 访问 |

### 1.4 启用硬编

要让 VAAPI / NVENC 真的生效，需要把对应设备挂进容器。**默认 deployment.yaml 已为
`supplementalGroups: 44`（video gid）准备，但 `/dev/dri` mount 被注释掉了，
节点没 iGPU 时挂进去会报错**。打开 VAAPI 的步骤：

```yaml
# deployment.yaml
volumeMounts:
  - { name: dri, mountPath: /dev/dri }
volumes:
  - name: dri
    hostPath:
      path: /dev/dri
      type: Directory
```

NVENC 类似：节点装 `nvidia-container-toolkit`，pod 加 `runtimeClassName: nvidia`，
然后让 `detect-encoder.sh` 探测时能 `nvidia-smi` 成功。

要 **强制** 用某一档编码器（跳过自动探测），在 `configmap.yaml` 里设：

```yaml
VIDEO_ENCODER_ARGS: "-c:v hevc_vaapi -qp 23"          # 强制 VAAPI
VIDEO_ENCODER_ARGS: "-c:v hevc_nvenc -preset p5 -rc vbr -cq 28"  # 强制 NVENC
VIDEO_ENCODER_ARGS: "-c:v libx265 -preset slower -crf 26 -tag:v hvc1 -pix_fmt yuv420p"  # 强制软编
```

### 1.5 管理界面

集成在 misu-file-server-ui 的「视频转码管理」页（admin 可见），通过 file-server
的 `/fileServer/transcodeWorker/**` 透传到本 pod，沿用项目 JWT + ADMIN 鉴权。
没有独立 dashboard URL，也不需要单独管理 token。

worker pod 自身的 HTTP API（仅 ClusterIP 可达）：

```
GET  /api/health
GET  /api/state                 # 当前 encoder / 队列计数 / currentTask 进度
GET  /api/tasks?bucket=queue|running|done|failed&limit=N
POST /api/recover-running       # running/*.task → queue/
POST /api/retry/<task_id>       # failed/<id>.task → queue/
```

### 1.6 排查

```bash
# pod 日志
kubectl -n misu-server logs deploy/misu-ffmpeg-worker -f
# 启动选了哪个 encoder（容器内 /work/state/encoder.txt）
kubectl -n misu-server exec deploy/misu-ffmpeg-worker -- cat /work/state/encoder.txt
# 直接 curl pod 内的 API（开发用）
kubectl -n misu-server port-forward svc/misu-ffmpeg-worker 18765:18765 &
curl http://127.0.0.1:18765/api/state | jq
```

---

## 2. Mac 本机 SSH 模式（开发回灌用）

原始的 `worker.sh`、`serve-dashboard.sh`、`dashboard.html` 保留下来，适合：

- 集群里的 worker 暂停（升级、节点维护）期间，手动跑一台 Mac 上去消费积压队列。
- 想用 Mac 的 `hevc_videotoolbox` 硬编（速度比 libx265 软编快 10-20x）。

### 2.1 准备

```bash
brew install ffmpeg
# 确认 mac 硬编可用
ffmpeg -hide_banner -encoders | grep hevc_videotoolbox

cd tools/local-ffmpeg-worker
cp .env.example .env
# 在 .env 里改 SSH_USER / SSH_HOST / SSH_KEY 指向 misu-maco
```

### 2.2 启动

```bash
./worker.sh                  # 守护进程
./worker.sh --once           # 跑一个任务后退出（测试用）
./worker.sh --recover-running  # 把卡在远端 running/ 的 task 退回 queue/
./serve-dashboard.sh         # http://127.0.0.1:18765/dashboard.html
```

### 2.3 注意

- **同一时间只能有一个 worker 消费** `transcode-queue/`。在集群里的 worker
  还在跑的情况下别同时开 Mac worker —— 否则会两边抢同一个 .task。
  正确做法是先 `kubectl scale --replicas=0 deploy/misu-ffmpeg-worker`。
- 默认输出 `HEVC + AAC + MP4`，最高分辨率由 task 中的 `MAX_HEIGHT` 控制。
- 软链接：如果源文件路径自身或父级是符号链接，或 `REMOTE_USER_FILE_ROOT`
  下有指向源文件的符号链接，worker 会跳过转码、写 `FAILED + SKIPPED`、
  并把 task 移到 `done/`。这是安全控制，容器版也一样保留。
- `hevc_videotoolbox` 同体积画质通常不如 `libx265 -preset slow`；想压更小
  调低 `.env` 里的 `-q:v`。
- `.work/` 是本机临时目录，上传后自动清理；`KEEP_LOCAL_WORK=1` 可保留用于排查。
