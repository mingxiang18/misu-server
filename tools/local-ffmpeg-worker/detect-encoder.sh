#!/usr/bin/env bash
# 启动时确定要用哪个 ffmpeg HEVC 编码器。
#
# 当前默认：libx265 软编（永远 Safari 兼容）
#
# 历史尝试：曾试过 hevc_vaapi 全 GPU pipeline（hwaccel vaapi → scale_vaapi
# → hevc_vaapi），CPU 从 ~2000m 降到 ~575m 效果很好。但 AMD VAAPI 出品
# 的 HEVC 流 SPS 内部 sps_temporal_mvp_enabled_flag=0、
# strong_intra_smoothing_enabled_flag=0 跟 libx265=1 不同，Safari 拒绝
# 渲染（黑屏+有声），bsf 改不了这种 bitstream-level 的 flag，只能再编。
# 加上 -color_primaries bt709 -color_trc bt709 -colorspace bt709
# -color_range tv 让 encoder 自己写 VUI 也没用 —— SPS 关键 flag 才是
# 致命点。
#
# 用法：从 worker-linux.sh 顶部 `source detect-encoder.sh`
# 副作用：导出以下环境变量
#   - ENCODER_NAME          : libx265 | hevc_vaapi | hevc_nvenc | override
#   - VIDEO_ENCODER_ARGS    : 编码相关 ffmpeg 参数 (放 -i 后面)
#   - HWACCEL_INPUT_ARGS    : 硬解相关 ffmpeg 参数 (放 -i 前面)；软编为空
#   - SCALE_FILTER_TYPE     : vaapi | cuda | cpu —— 给 worker-linux.sh 决定
#                             用什么 scale 滤镜（GPU 帧 vs CPU 帧）
# 强制指定 encoder：在 ConfigMap 设 VIDEO_ENCODER_ARGS，会跳过自动选择。
#   想强开 hevc_vaapi 硬编（接受 Safari 不能播）：
#     VIDEO_ENCODER_ARGS="-c:v hevc_vaapi -qp 23 -tag:v hvc1"
#     HWACCEL_INPUT_ARGS="-hwaccel vaapi -hwaccel_output_format vaapi -hwaccel_device /dev/dri/renderD128 -extra_hw_frames 8"
#     SCALE_FILTER_TYPE=vaapi
#   都要在 ConfigMap 设全。

set -u

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

  if echo "$encoders" | grep -q "libx265"; then
    # -preset fast: 速度 vs 体积折中 (~2-3x realtime on 2 cores @ 1080p)
    # 显式 color metadata 让 Safari/QuickTime 还原色彩
    VIDEO_ENCODER_ARGS="-c:v libx265 -preset fast -crf 28 -tag:v hvc1 -pix_fmt yuv420p -x265-params colorprim=bt709:transfer=bt709:colormatrix=bt709:range=limited"
    HWACCEL_INPUT_ARGS=""
    SCALE_FILTER_TYPE="cpu"
    ENCODER_NAME="libx265"
    echo "[detect-encoder] picked libx265 (软编, Safari/QuickTime 兼容)"
    export VIDEO_ENCODER_ARGS ENCODER_NAME HWACCEL_INPUT_ARGS SCALE_FILTER_TYPE
    return 0
  fi

  echo "[detect-encoder] FATAL: libx265 not built into ffmpeg" >&2
  return 1
}

detect_encoder
