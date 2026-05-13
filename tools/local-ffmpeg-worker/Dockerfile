FROM alpine:3.20

# 与 misu-file-server-biz/Dockerfile 同一套约定：阿里云源、TZ 上海。
#
# VAAPI 支持（Intel iGPU / AMD Radeon 硬件 HEVC 编码）：
#   - ffmpeg：alpine 包已编进 libva 支持，但不带 GPU 厂商的用户态驱动
#   - mesa-va-gallium：Mesa Gallium 的 VAAPI 驱动，包含
#       /usr/lib/dri/radeonsi_drv_video.so  → AMD GCN/RDNA 全系
#       /usr/lib/dri/r600_drv_video.so      → 老 AMD (Northern Islands 之前)
#       /usr/lib/dri/nouveau_drv_video.so   → 开源 NVIDIA (兜底用)
#       /usr/lib/dri/iris_drv_video.so      → Intel Gen8+ iGPU
#       (~30MB 多一点。节点没 GPU 时也无副作用，detect-encoder.sh 会回退到 libx265)
#   - libva-utils：vainfo 工具，排障时进 pod 跑一下能列出可用 entrypoint
RUN sed -i 's|https://dl-cdn.alpinelinux.org|https://mirrors.aliyun.com|g' /etc/apk/repositories \
    && apk --no-cache add \
        bash \
        coreutils \
        findutils \
        tzdata \
        ffmpeg \
        mesa-va-gallium \
        libva-utils \
        python3 \
        tini \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

WORKDIR /app

COPY detect-encoder.sh worker-linux.sh worker-api.py entrypoint.sh /app/
RUN chmod +x /app/detect-encoder.sh /app/worker-linux.sh /app/worker-api.py /app/entrypoint.sh

EXPOSE 18765

ENV USER_FILE_ROOT=/data/user-file \
    CONTAINER_USER_FILE_ROOT=/app/user-file \
    WORK_DIR=/work \
    STATE_DIR=/work/state \
    POLL_INTERVAL=5 \
    KEEP_LOCAL_WORK=0 \
    AUDIO_BITRATE=128k \
    MAX_TASK_RETRIES=1 \
    WORKER_API_PORT=18765 \
    # AMD GPU 默认走 radeonsi 驱动。Intel iGPU 时可在 ConfigMap 覆盖为 iHD（Gen11+）
    # 或 i965（老 Intel）。不挂 /dev/dri 时这个变量无副作用。
    LIBVA_DRIVER_NAME=radeonsi

# tini 作为 PID 1，确保 SIGTERM 能干净传到 worker-linux.sh（含 ffmpeg）
ENTRYPOINT ["/sbin/tini", "--", "/app/entrypoint.sh"]
