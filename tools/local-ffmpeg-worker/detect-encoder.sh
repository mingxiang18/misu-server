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
# 副作用：导出以下环境变量
#   - ENCODER_NAME          : hevc_vaapi | hevc_nvenc | libx265 | override
#   - VIDEO_ENCODER_ARGS    : 编码相关 ffmpeg 参数 (放 -i 后面)
#   - HWACCEL_INPUT_ARGS    : 硬解相关 ffmpeg 参数 (放 -i 前面)；软编为空
#   - SCALE_FILTER_TYPE     : vaapi | cuda | cpu —— 给 worker-linux.sh 决定
#                             用什么 scale 滤镜（GPU 帧 vs CPU 帧）
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

# scale_vaapi / scale_cuda 可用性不再单独探测：
#  - 一旦 probe_encoder VAAPI/NVENC 通过，对应的硬件 scale filter 与 encoder
#    打的是同一包 mesa-va-gallium / nvidia 驱动，必然同存同亡
#  - alpine mesa-va-gallium 24.x 全部都自带 scale_vaapi（来自 libplacebo + libavfilter
#    编译进去的，不依赖运行时）
#  - 老版 mesa（< 22.x）才可能缺；我们镜像里 Alpine 3.20 锁的是 mesa 24.0.9，
#    锁死之前不会触发这个边缘情况
# 真出问题（极小概率），ffmpeg 实际跑会报"No such filter: 'scale_vaapi'"，
# 走 worker 的 retry/failed 流程，并能从日志一眼看到。

detect_encoder() {
  HWACCEL_INPUT_ARGS=""
  SCALE_FILTER_TYPE="cpu"

  if [[ -n "${VIDEO_ENCODER_ARGS:-}" ]]; then
    ENCODER_NAME="${ENCODER_NAME:-override}"
    echo "[detect-encoder] VIDEO_ENCODER_ARGS already set, skip detection (${VIDEO_ENCODER_ARGS})"
    export VIDEO_ENCODER_ARGS ENCODER_NAME HWACCEL_INPUT_ARGS SCALE_FILTER_TYPE
    return 0
  fi

  local encoders
  encoders="$(ffmpeg -hide_banner -encoders 2>/dev/null || true)"

  # ---- 1. VAAPI (Intel/AMD) -------------------------------------------------
  if echo "$encoders" | grep -q "hevc_vaapi" && [[ -e /dev/dri/renderD128 ]]; then
    if probe_encoder "vaapi" \
        "-vaapi_device /dev/dri/renderD128" \
        "-c:v hevc_vaapi -qp 23"; then
      # 全 GPU pipeline：hwaccel vaapi 硬解 → scale_vaapi GPU 缩放 → hevc_vaapi 硬编
      VIDEO_ENCODER_ARGS="-c:v hevc_vaapi -qp 23"
      HWACCEL_INPUT_ARGS="-hwaccel vaapi -hwaccel_output_format vaapi -hwaccel_device /dev/dri/renderD128 -extra_hw_frames 8"
      SCALE_FILTER_TYPE="vaapi"
      ENCODER_NAME="hevc_vaapi"
      echo "[detect-encoder] picked hevc_vaapi + 全 GPU pipeline (hwaccel vaapi → scale_vaapi → hevc_vaapi)"
      export VIDEO_ENCODER_ARGS ENCODER_NAME HWACCEL_INPUT_ARGS SCALE_FILTER_TYPE
      return 0
    fi
    echo "[detect-encoder] hevc_vaapi listed but dry-encode failed, cascading"
  fi

  # ---- 2. NVENC (NVIDIA) ----------------------------------------------------
  if echo "$encoders" | grep -q "hevc_nvenc" && command -v nvidia-smi >/dev/null 2>&1 \
      && nvidia-smi >/dev/null 2>&1; then
    if probe_encoder "nvenc" "" "-c:v hevc_nvenc -preset p5 -rc vbr -cq 28"; then
      # NVIDIA 走 CUDA hwaccel + scale_cuda
      VIDEO_ENCODER_ARGS="-c:v hevc_nvenc -preset p5 -rc vbr -cq 28 -tag:v hvc1"
      HWACCEL_INPUT_ARGS="-hwaccel cuda -hwaccel_output_format cuda -extra_hw_frames 8"
      SCALE_FILTER_TYPE="cuda"
      ENCODER_NAME="hevc_nvenc"
      echo "[detect-encoder] picked hevc_nvenc + 全 GPU pipeline (hwaccel cuda → scale_cuda → hevc_nvenc)"
      export VIDEO_ENCODER_ARGS ENCODER_NAME HWACCEL_INPUT_ARGS SCALE_FILTER_TYPE
      return 0
    fi
    echo "[detect-encoder] hevc_nvenc listed but dry-encode failed, cascading"
  fi

  # ---- 3. libx265 (软件兜底) ------------------------------------------------
  if echo "$encoders" | grep -q "libx265"; then
    VIDEO_ENCODER_ARGS="-c:v libx265 -preset medium -crf 28 -tag:v hvc1 -pix_fmt yuv420p"
    HWACCEL_INPUT_ARGS=""
    SCALE_FILTER_TYPE="cpu"
    ENCODER_NAME="libx265"
    echo "[detect-encoder] picked libx265 (软件兜底，无硬解硬编)"
    export VIDEO_ENCODER_ARGS ENCODER_NAME HWACCEL_INPUT_ARGS SCALE_FILTER_TYPE
    return 0
  fi

  echo "[detect-encoder] FATAL: no usable HEVC encoder found (looked for hevc_vaapi / hevc_nvenc / libx265)" >&2
  return 1
}

detect_encoder
