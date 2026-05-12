FROM alpine:3.20

# 与 misu-file-server-biz/Dockerfile 同一套约定：阿里云源、TZ 上海。
RUN sed -i 's|https://dl-cdn.alpinelinux.org|https://mirrors.aliyun.com|g' /etc/apk/repositories \
    && apk --no-cache add \
        bash \
        coreutils \
        findutils \
        tzdata \
        ffmpeg \
        python3 \
        tini \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

WORKDIR /app

COPY detect-encoder.sh worker-linux.sh entrypoint.sh /app/
RUN chmod +x /app/detect-encoder.sh /app/worker-linux.sh /app/entrypoint.sh

ENV USER_FILE_ROOT=/data/user-file \
    CONTAINER_USER_FILE_ROOT=/app/user-file \
    WORK_DIR=/work \
    STATE_DIR=/work/state \
    POLL_INTERVAL=5 \
    KEEP_LOCAL_WORK=0 \
    AUDIO_BITRATE=128k \
    MAX_TASK_RETRIES=1

# tini 作为 PID 1，确保 SIGTERM 能干净传到 worker-linux.sh（含 ffmpeg）
ENTRYPOINT ["/sbin/tini", "--", "/app/entrypoint.sh"]
