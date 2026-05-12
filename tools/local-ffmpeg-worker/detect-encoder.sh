#!/usr/bin/env bash
# 启动时探测可用的 ffmpeg HEVC 编码器：
#   VAAPI (Intel/AMD iGPU + /dev/dri)
#   → NVENC (NVIDIA + nvidia-smi)
#   → libx265 (软件兜底，永远可用)
#
# 每个分支会跑 1 秒 testsrc dry-encode，捕获 "encoder 列在 -encoders 但
# 实际打不开" 的情形（典型：容器有 hevc_vaapi 但 /dev/dri 未挂载、
# 或挂载了但权限不对）。失败则级联到下一档。
#
# 用法：从 worker-linux.sh 顶部 `source detect-encoder.sh`
# 副作用：导出 VIDEO_ENCODER_ARGS、ENCODER_NAME 两个环境变量。
# 跳过：若调用前 VIDEO_ENCODER_ARGS 已是非空，detect 直接返回。

set -u

probe_encoder() {
  # $1 = label (vaapi|nvenc|libx265)
  # $2 = extra ffmpeg pre-input args (e.g. -vaapi_device)
  # $3 = encoder args
  local label="$1"
  local pre="$2"
  local enc="$3"

  # shellcheck disable=SC2086
  if ffmpeg -hide_banner -loglevel error \
      $pre \
      -f lavfi -i "testsrc=size=320x240:rate=15:duration=1" \
      -frames:v 15 \
      ${pre:+-vf "format=nv12,hwupload"} \
      $enc \
      -f null - </dev/null 2>/dev/null; then
    return 0
  fi
  return 1
}

detect_encoder() {
  if [[ -n "${VIDEO_ENCODER_ARGS:-}" ]]; then
    ENCODER_NAME="${ENCODER_NAME:-override}"
    echo "[detect-encoder] VIDEO_ENCODER_ARGS already set, skip detection (${VIDEO_ENCODER_ARGS})"
    export VIDEO_ENCODER_ARGS ENCODER_NAME
    return 0
  fi

  local encoders
  encoders="$(ffmpeg -hide_banner -encoders 2>/dev/null || true)"

  # ---- 1. VAAPI (Intel/AMD) -------------------------------------------------
  if echo "$encoders" | grep -q "hevc_vaapi" && [[ -e /dev/dri/renderD128 ]]; then
    if probe_encoder "vaapi" \
        "-vaapi_device /dev/dri/renderD128" \
        "-c:v hevc_vaapi -qp 23"; then
      VIDEO_ENCODER_ARGS="-vaapi_device /dev/dri/renderD128 -vf format=nv12,hwupload -c:v hevc_vaapi -qp 23"
      ENCODER_NAME="hevc_vaapi"
      echo "[detect-encoder] picked hevc_vaapi (Intel/AMD iGPU via /dev/dri/renderD128)"
      export VIDEO_ENCODER_ARGS ENCODER_NAME
      return 0
    fi
    echo "[detect-encoder] hevc_vaapi listed but dry-encode failed, cascading"
  fi

  # ---- 2. NVENC (NVIDIA) ----------------------------------------------------
  if echo "$encoders" | grep -q "hevc_nvenc" && command -v nvidia-smi >/dev/null 2>&1 \
      && nvidia-smi >/dev/null 2>&1; then
    if probe_encoder "nvenc" "" "-c:v hevc_nvenc -preset p5 -rc vbr -cq 28"; then
      VIDEO_ENCODER_ARGS="-c:v hevc_nvenc -preset p5 -rc vbr -cq 28 -tag:v hvc1 -pix_fmt yuv420p"
      ENCODER_NAME="hevc_nvenc"
      echo "[detect-encoder] picked hevc_nvenc (NVIDIA GPU)"
      export VIDEO_ENCODER_ARGS ENCODER_NAME
      return 0
    fi
    echo "[detect-encoder] hevc_nvenc listed but dry-encode failed, cascading"
  fi

  # ---- 3. libx265 (软件兜底) ------------------------------------------------
  if echo "$encoders" | grep -q "libx265"; then
    VIDEO_ENCODER_ARGS="-c:v libx265 -preset medium -crf 28 -tag:v hvc1 -pix_fmt yuv420p"
    ENCODER_NAME="libx265"
    echo "[detect-encoder] picked libx265 (software fallback)"
    export VIDEO_ENCODER_ARGS ENCODER_NAME
    return 0
  fi

  echo "[detect-encoder] FATAL: no usable HEVC encoder found (looked for hevc_vaapi / hevc_nvenc / libx265)" >&2
  return 1
}

detect_encoder
