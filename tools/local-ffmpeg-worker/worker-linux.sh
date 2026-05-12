#!/usr/bin/env bash
# Linux 容器版 ffmpeg worker。
# 与原 worker.sh 的区别：
#   - 不再走 SSH，pod 通过 hostPath 直接读写 /data/user-file。
#   - 编码器由 detect-encoder.sh 在启动时自动选择 (VAAPI → NVENC → libx265)。
#   - 状态文件写到 /work/state (hostPath)，pod 重启后看板状态仍可读。
#   - 源/输出文件零拷贝：直接通过 hostPath 路径访问，不再 ssh cat 上下传。
#
# 与原 worker.sh 的相同点（即 misu-file-server 端无需改动）：
#   - 仍然消费 ${USER_FILE_ROOT}/transcode-queue/*.task 文件，
#     按 mv 锁定到 running/、处理后 mv 到 done/ 或 failed/。
#   - 任务文件中的 /app/user-file/... 容器路径会被 map 到 ${USER_FILE_ROOT}。
#   - 仍生成 .json 状态写入任务文件指定的 STATUS 路径，供后端轮询。
#   - 软链接拒绝转码的安全控制保留。

set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 自动探测编码器，写入 VIDEO_ENCODER_ARGS。若环境变量已显式设置则跳过。
# shellcheck disable=SC1091
source "$SCRIPT_DIR/detect-encoder.sh"

# 容器侧约定路径，与 k8s deployment.yaml 的 volumeMounts 保持一致
USER_FILE_ROOT="${USER_FILE_ROOT:-/data/user-file}"
# 后端 misu-file-server 容器内部看到的根，task 文件里的 SOURCE/OUTPUT/... 用的是这个前缀
CONTAINER_USER_FILE_ROOT="${CONTAINER_USER_FILE_ROOT:-/app/user-file}"
WORK_DIR="${WORK_DIR:-/work}"
STATE_DIR="${STATE_DIR:-/work/state}"

POLL_INTERVAL="${POLL_INTERVAL:-5}"
KEEP_LOCAL_WORK="${KEEP_LOCAL_WORK:-0}"
AUDIO_BITRATE="${AUDIO_BITRATE:-128k}"
MAX_TASK_RETRIES="${MAX_TASK_RETRIES:-1}"

QUEUE_DIR="$USER_FILE_ROOT/transcode-queue"
RUNNING_DIR="$QUEUE_DIR/running"
DONE_DIR="$QUEUE_DIR/done"
FAILED_DIR="$QUEUE_DIR/failed"

STATE_FILE="$STATE_DIR/state.json"
EVENT_FILE="$STATE_DIR/events.jsonl"
TASK_STATE_DIR="$STATE_DIR/task-status"
ENCODER_INFO_FILE="$STATE_DIR/encoder.txt"

ONCE=0
RECOVER_RUNNING=0

for arg in "$@"; do
  case "$arg" in
    --once) ONCE=1 ;;
    --recover-running) RECOVER_RUNNING=1 ;;
    *) printf 'Unknown option: %s\n' "$arg" >&2; exit 64 ;;
  esac
done

mkdir -p "$WORK_DIR" "$STATE_DIR" "$TASK_STATE_DIR" \
  "$QUEUE_DIR" "$RUNNING_DIR" "$DONE_DIR" "$FAILED_DIR"

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

log() {
  local now
  now="$(date '+%Y-%m-%d %H:%M:%S')"
  printf '[%s] %s\n' "$now" "$*"
  printf '{"time":"%s","message":"%s"}\n' \
    "$(json_escape "$now")" \
    "$(json_escape "$*")" >> "$EVENT_FILE"
}

map_task_path() {
  local path="$1"
  if [[ "$path" == "$CONTAINER_USER_FILE_ROOT"* ]]; then
    printf '%s%s' "$USER_FILE_ROOT" "${path#"$CONTAINER_USER_FILE_ROOT"}"
  else
    printf '%s' "$path"
  fi
}

# 检查路径自身或任一父级是否是 symlink (相对 USER_FILE_ROOT)
path_contains_symlink() {
  local path="$1"
  local root="$USER_FILE_ROOT"
  local cur rel part old_ifs
  case "$path" in
    "$root"/*) rel="${path#"$root"/}"; cur="$root" ;;
    *) rel="${path#/}"; cur="" ;;
  esac
  old_ifs=$IFS
  IFS=/
  for part in $rel; do
    [[ -z "$part" ]] && continue
    if [[ -z "$cur" ]]; then
      cur="/$part"
    else
      cur="$cur/$part"
    fi
    if [[ -L "$cur" ]]; then
      IFS=$old_ifs
      return 0
    fi
  done
  IFS=$old_ifs
  return 1
}

# 检查 USER_FILE_ROOT 树下是否存在指向 source 的 symlink
source_has_symlink_reference() {
  local source_path="$1"
  local source_real
  source_real="$(readlink -f "$source_path" 2>/dev/null || true)"
  [[ -n "$source_real" ]] || return 1
  local hit
  hit="$(find "$USER_FILE_ROOT" -type l 2>/dev/null | while IFS= read -r link; do
    local target_real
    target_real="$(readlink -f "$link" 2>/dev/null || true)"
    [[ -n "$target_real" ]] || continue
    case "$source_real" in
      "$target_real"|"$target_real"/*)
        printf '%s\n' "$link"
        break
        ;;
    esac
  done)"
  if [[ -n "$hit" ]]; then
    printf '%s\n' "$hit"
    return 0
  fi
  return 1
}

# ---- 原子写文件 -----------------------------------------------------------
atomic_write() {
  local dest="$1"
  local content="$2"
  local tmp="$dest.tmp.$$"
  mkdir -p "$(dirname "$dest")"
  printf '%s' "$content" > "$tmp"
  mv -f "$tmp" "$dest"
}

# 写源/输出文件本身（用于状态 JSON 这种大小可控的）
atomic_copy() {
  local src="$1"
  local dest="$2"
  local tmp="$dest.tmp.$$"
  mkdir -p "$(dirname "$dest")"
  cp "$src" "$tmp"
  mv -f "$tmp" "$dest"
}

# ---- Dashboard state JSON --------------------------------------------------
events_json() {
  if [[ ! -s "$EVENT_FILE" ]]; then
    printf '[]'
    return
  fi
  awk 'BEGIN { printf "[" } { if (NR > 1) printf ","; printf "%s", $0 } END { printf "]" }' < <(tail -n 40 "$EVENT_FILE")
}

tasks_json() {
  mkdir -p "$TASK_STATE_DIR"
  local files=()
  while IFS= read -r f; do
    files+=("$f")
  done < <(ls -t "$TASK_STATE_DIR"/*.json 2>/dev/null | head -n 80)

  if [[ "${#files[@]}" -eq 0 ]]; then
    printf '[]'
    return
  fi
  local first=1
  printf '['
  for f in "${files[@]}"; do
    [[ -s "$f" ]] || continue
    if [[ "$first" -eq 0 ]]; then printf ','; fi
    first=0
    tr -d '\n' < "$f"
  done
  printf ']'
}

queue_counts_json() {
  local q r d f
  q="$(find "$QUEUE_DIR" -maxdepth 1 -type f -name '*.task' 2>/dev/null | wc -l | tr -d ' ')"
  r="$(find "$RUNNING_DIR" -maxdepth 1 -type f -name '*.task' 2>/dev/null | wc -l | tr -d ' ')"
  d="$(find "$DONE_DIR" -maxdepth 1 -type f -name '*.task' 2>/dev/null | wc -l | tr -d ' ')"
  f="$(find "$FAILED_DIR" -maxdepth 1 -type f -name '*.task' 2>/dev/null | wc -l | tr -d ' ')"
  printf '{"queue":%s,"running":%s,"done":%s,"failed":%s}' "${q:-0}" "${r:-0}" "${d:-0}" "${f:-0}"
}

write_task_record() {
  local task_state="$1"
  local progress="$2"
  local message="$3"
  local result="${4:-RUNNING}"
  local reason="${5:-}"
  local now task_file tmp_file

  [[ -n "${TASK_ID:-}" ]] || return 0
  now="$(date '+%Y-%m-%d %H:%M:%S')"
  task_file="$TASK_STATE_DIR/$TASK_ID.json"
  tmp_file="$task_file.tmp"

  {
    printf '{'
    printf '"taskId":"%s",' "$(json_escape "$TASK_ID")"
    printf '"state":"%s",' "$(json_escape "$task_state")"
    printf '"result":"%s",' "$(json_escape "$result")"
    printf '"progress":%s,' "$progress"
    printf '"message":"%s",' "$(json_escape "$message")"
    printf '"reason":"%s",' "$(json_escape "$reason")"
    printf '"updatedAt":"%s",' "$(json_escape "$now")"
    if [[ "$result" == "SUCCESS" || "$result" == "FAILED" || "$result" == "SKIPPED" ]]; then
      printf '"finishedAt":"%s",' "$(json_escape "$now")"
    else
      printf '"finishedAt":"",'
    fi
    printf '"encoder":"%s",' "$(json_escape "${ENCODER_NAME:-unknown}")"
    printf '"source":"%s",' "$(json_escape "${SOURCE:-}")"
    printf '"localSource":"%s",' "$(json_escape "${LOCAL_SOURCE:-}")"
    printf '"output":"%s",' "$(json_escape "${OUTPUT:-}")"
    printf '"localOutput":"%s",' "$(json_escape "${LOCAL_OUTPUT:-}")"
    printf '"preview":"%s",' "$(json_escape "${PREVIEW:-}")"
    printf '"status":"%s"' "$(json_escape "${STATUS:-}")"
    printf '}\n'
  } > "$tmp_file"
  mv -f "$tmp_file" "$task_file"
}

write_dashboard_state() {
  local worker_state="$1"
  local task_state="$2"
  local progress="$3"
  local message="$4"
  local now tmp_file
  now="$(date '+%Y-%m-%d %H:%M:%S')"
  tmp_file="$STATE_FILE.tmp"

  {
    printf '{'
    printf '"updatedAt":"%s",' "$(json_escape "$now")"
    printf '"workerState":"%s",' "$(json_escape "$worker_state")"
    printf '"encoder":"%s",' "$(json_escape "${ENCODER_NAME:-unknown}")"
    printf '"mode":"in-cluster",'
    printf '"userFileRoot":"%s",' "$(json_escape "$USER_FILE_ROOT")"
    printf '"queueDir":"%s",' "$(json_escape "$QUEUE_DIR")"
    printf '"queueCounts":%s,' "$(queue_counts_json)"
    printf '"message":"%s",' "$(json_escape "$message")"
    if [[ -n "${TASK_ID:-}" ]]; then
      printf '"currentTask":{'
      printf '"taskId":"%s",' "$(json_escape "$TASK_ID")"
      printf '"state":"%s",' "$(json_escape "$task_state")"
      printf '"progress":%s,' "$progress"
      printf '"source":"%s",' "$(json_escape "${SOURCE:-}")"
      printf '"output":"%s",' "$(json_escape "${OUTPUT:-}")"
      printf '"preview":"%s",' "$(json_escape "${PREVIEW:-}")"
      printf '"status":"%s"' "$(json_escape "${STATUS:-}")"
      printf '},'
    else
      printf '"currentTask":null,'
    fi
    printf '"tasks":%s' "$(tasks_json)"
    printf '}\n'
  } > "$tmp_file"
  mv -f "$tmp_file" "$STATE_FILE"
}

write_status_local() {
  local file="$1" state="$2" progress="$3" message="$4" preview_path="$5" transcoded_path="$6"
  mkdir -p "$(dirname "$file")"
  printf '{"taskId":"%s","state":"%s","progress":%s,"message":"%s","previewPath":"%s","transcodedPath":"%s"}\n' \
    "$(json_escape "${TASK_ID:-}")" \
    "$(json_escape "$state")" \
    "$progress" \
    "$(json_escape "$message")" \
    "$(json_escape "$preview_path")" \
    "$(json_escape "$transcoded_path")" > "$file"
}

publish_status() {
  local state="$1" progress="$2" message="$3"
  local task_state="${4:-$state}"
  local result="${5:-RUNNING}"
  local reason="${6:-}"
  write_task_record "$task_state" "$progress" "$message" "$result" "$reason"
  write_dashboard_state "RUNNING" "$state" "$progress" "$message"
  # 写本地 status，再原子复制到任务约定的 STATUS 路径（hostPath 上）。
  # JSON 里的 previewPath/transcodedPath 必须用任务原始路径 ($PREVIEW/$OUTPUT)
  # —— 这些是 misu-file-server 容器内的 /app/user-file/... 视角，misu-file-server
  # 读回时直接拼到自己的 mount 上；如果写成 worker 视角的 /data/user-file/...
  # 后端解析会指向不存在的路径。
  write_status_local "$LOCAL_STATUS" "$state" "$progress" "$message" "$PREVIEW" "$OUTPUT"
  atomic_copy "$LOCAL_STATUS" "$MAPPED_STATUS"
}

# ---- Task file parsing ----------------------------------------------------
extract_task_assignments() {
  local task_file="$1"
  awk '
    /^[A-Z_][A-Z0-9_]*=/ { print; next }
    /^$/ { exit }
    /^[[:space:]]*$/ { exit }
    /^mkdir / { exit }
    /^write_status/ { exit }
  ' "$task_file"
}

load_task() {
  local task_file="$1"
  TASK_ID=""; SOURCE=""; OUTPUT=""; PREVIEW=""; STATUS=""; PROGRESS_FILE=""
  MAX_HEIGHT="1080"; CRF="28"; PRESET="medium"

  # 只 eval task 文件开头的简单赋值；后续脚本 body 完全不执行。
  eval "$(extract_task_assignments "$task_file")"

  if [[ -z "$TASK_ID" || -z "$SOURCE" || -z "$OUTPUT" || -z "$PREVIEW" || -z "$STATUS" ]]; then
    log "Invalid task file: $task_file"
    return 1
  fi

  MAPPED_SOURCE="$(map_task_path "$SOURCE")"
  MAPPED_OUTPUT="$(map_task_path "$OUTPUT")"
  MAPPED_PREVIEW="$(map_task_path "$PREVIEW")"
  MAPPED_STATUS="$(map_task_path "$STATUS")"
  write_task_record "CLAIMED" "0" "已领取任务"
  write_dashboard_state "RUNNING" "CLAIMED" "0" "已领取任务"
}

# ---- Queue ops (本地文件系统) ---------------------------------------------
claim_task() {
  local task
  task="$(find "$QUEUE_DIR" -maxdepth 1 -type f -name '*.task' 2>/dev/null | sort | head -n 1)"
  if [[ -z "$task" ]]; then
    return 2
  fi
  local base dest
  base="$(basename "$task")"
  dest="$RUNNING_DIR/$base"
  if mv "$task" "$dest"; then
    printf '%s\n' "$dest"
    return 0
  fi
  return 3
}

move_claimed_task() {
  local task="$1"
  local target_dir="$2"
  local base
  base="$(basename "$task")"
  mkdir -p "$target_dir"
  mv -f "$task" "$target_dir/$base" || true
}

recover_running_tasks() {
  find "$RUNNING_DIR" -maxdepth 1 -type f -name '*.task' 2>/dev/null | while IFS= read -r task; do
    local base
    base="$(basename "$task")"
    mv -f "$task" "$QUEUE_DIR/$base"
    printf '%s\n' "$base"
  done
}

# ---- ffmpeg primitives ----------------------------------------------------
duration_us() {
  ffprobe -v error -show_entries format=duration -of default=nw=1:nk=1 "$1" \
    | awk '{ printf "%.0f", $1 * 1000000 }'
}

progress_percent() {
  local duration="$1"
  local progress_file="$2"
  if [[ ! -s "$progress_file" || "$duration" == "0" ]]; then
    printf '5'
    return
  fi
  local out_time
  out_time="$(awk -F= '$1=="out_time_us" { value=$2 } END { print value + 0 }' "$progress_file")"
  awk -v t="$out_time" -v d="$duration" 'BEGIN {
    p = int(t * 90 / d) + 5
    if (p < 5) p = 5
    if (p > 95) p = 95
    print p
  }'
}

generate_preview() {
  local source_file="$1"
  local preview_file="$2"
  local seek_seconds
  rm -f "$preview_file"
  for seek_seconds in 5 0; do
    if ffmpeg -y -ss "$seek_seconds" -i "$source_file" \
      -frames:v 1 \
      -vf "scale='min(480,iw)':-2,format=yuvj420p" \
      -q:v 3 \
      -update 1 \
      "$preview_file" < /dev/null && [[ -s "$preview_file" ]]; then
      return 0
    fi
    rm -f "$preview_file"
  done
  return 1
}

# ---- 主流程 ---------------------------------------------------------------
run_ffmpeg_task() {
  local local_task_file="$1"
  load_task "$local_task_file" || return 1

  local task_dir="$WORK_DIR/tasks/$TASK_ID"
  LOCAL_SOURCE="$MAPPED_SOURCE"                         # 直接读 hostPath 上的源文件
  LOCAL_OUTPUT="$task_dir/output/$(basename "$MAPPED_OUTPUT")"
  LOCAL_PREVIEW="$task_dir/output/$(basename "$MAPPED_PREVIEW")"
  LOCAL_STATUS="$task_dir/status/status.json"
  LOCAL_PROGRESS="$task_dir/status/progress.txt"
  local symlink_ref=""

  rm -rf "$task_dir"
  mkdir -p "$(dirname "$LOCAL_OUTPUT")" "$(dirname "$LOCAL_STATUS")"

  if path_contains_symlink "$MAPPED_SOURCE" || symlink_ref="$(source_has_symlink_reference "$MAPPED_SOURCE")"; then
    log "Task $TASK_ID: source uses symlink, skip transcoding: ${symlink_ref:-$MAPPED_SOURCE}"
    publish_status "FAILED" "0" "软链接文件跳过转码" "SKIPPED" "SKIPPED" "${symlink_ref:-$MAPPED_SOURCE}" || return 1
    rm -rf "$task_dir"
    return 0
  fi

  if [[ ! -s "$LOCAL_SOURCE" ]]; then
    log "Task $TASK_ID: source missing: $LOCAL_SOURCE"
    publish_status "FAILED" "0" "源视频不存在" "FAILED" "FAILED" "missing source" || return 1
    return 1
  fi

  log "Task $TASK_ID: generating preview"
  if generate_preview "$LOCAL_SOURCE" "$LOCAL_PREVIEW"; then
    atomic_copy "$LOCAL_PREVIEW" "$MAPPED_PREVIEW"
    publish_status "PROCESSING" "3" "已生成视频预览图" "PREVIEWING" || return 1
  else
    log "Task $TASK_ID: preview generation failed, continue transcoding"
    publish_status "PROCESSING" "3" "预览图生成失败，继续转码" "PREVIEW_FAILED" || return 1
  fi

  local total_us
  total_us="$(duration_us "$LOCAL_SOURCE")" || return 1
  if [[ -z "$total_us" || "$total_us" == "0" ]]; then
    total_us=1
  fi

  # shellcheck disable=SC2206
  local video_args=($VIDEO_ENCODER_ARGS)

  log "Task $TASK_ID: transcoding with $ENCODER_NAME (${video_args[*]})"
  publish_status "PROCESSING" "5" "容器内转码中 ($ENCODER_NAME)" "TRANSCODING" || return 1

  ffmpeg -y -i "$LOCAL_SOURCE" \
    -map 0:v:0 -map 0:a? \
    -vf "scale='if(gt(ih,$MAX_HEIGHT),-2,iw)':'if(gt(ih,$MAX_HEIGHT),$MAX_HEIGHT,ih)'" \
    "${video_args[@]}" \
    -c:a aac -b:a "$AUDIO_BITRATE" \
    -movflags +faststart \
    -progress "$LOCAL_PROGRESS" -nostats "$LOCAL_OUTPUT" < /dev/null &

  local ffmpeg_pid=$!
  while kill -0 "$ffmpeg_pid" 2>/dev/null; do
    local percent
    percent="$(progress_percent "$total_us" "$LOCAL_PROGRESS")"
    if ! publish_status "PROCESSING" "$percent" "容器内转码中 ($ENCODER_NAME)" "TRANSCODING"; then
      log "Task $TASK_ID: failed to write progress status, keep transcoding"
    fi
    sleep 2
  done

  wait "$ffmpeg_pid" || return 1
  [[ -s "$LOCAL_OUTPUT" ]] || return 1

  log "Task $TASK_ID: publishing output"
  publish_status "PROCESSING" "97" "写出转码结果" "UPLOADING" || return 1
  atomic_copy "$LOCAL_OUTPUT" "$MAPPED_OUTPUT" || return 1
  publish_status "SUCCESS" "100" "转码完成" "SUCCESS" "SUCCESS" || return 1

  if [[ "$KEEP_LOCAL_WORK" != "1" ]]; then
    rm -rf "$task_dir"
    log "Task $TASK_ID: local scratch cleaned"
  fi
}

process_once() {
  local claimed_task
  if ! claimed_task="$(claim_task)"; then
    return 2
  fi

  local attempt=1
  while true; do
    if run_ffmpeg_task "$claimed_task"; then
      move_claimed_task "$claimed_task" "$DONE_DIR"
      return 0
    fi

    if [[ "$attempt" -ge "$MAX_TASK_RETRIES" ]]; then
      if [[ -n "${MAPPED_STATUS:-}" && -n "${LOCAL_STATUS:-}" ]]; then
        publish_status "FAILED" "0" "容器内转码失败" "FAILED" "FAILED" "ffmpeg 或写出流程失败"
      fi
      write_task_record "FAILED" "0" "容器内转码失败" "FAILED" "ffmpeg 或写出流程失败"
      write_dashboard_state "RUNNING" "FAILED" "0" "容器内转码失败"
      move_claimed_task "$claimed_task" "$FAILED_DIR"
      return 1
    fi
    attempt=$((attempt + 1))
    log "Retry task after failure: $attempt/$MAX_TASK_RETRIES"
  done
}

main() {
  command -v ffmpeg >/dev/null || { log "FATAL: ffmpeg not on PATH"; exit 127; }
  command -v ffprobe >/dev/null || { log "FATAL: ffprobe not on PATH"; exit 127; }

  printf '%s\n' "$ENCODER_NAME" > "$ENCODER_INFO_FILE"

  log "In-cluster ffmpeg worker started, encoder=$ENCODER_NAME, root=$USER_FILE_ROOT"
  log "Task path map: $CONTAINER_USER_FILE_ROOT -> $USER_FILE_ROOT"
  log "Dashboard state: $STATE_FILE"
  log "ffmpeg args: $VIDEO_ENCODER_ARGS"

  if [[ "$RECOVER_RUNNING" == "1" ]]; then
    log "Recover running tasks back to queue"
    recover_running_tasks
    write_dashboard_state "IDLE" "" "0" "已恢复 running 任务到队列"
    exit 0
  fi

  write_dashboard_state "IDLE" "" "0" "等待任务"

  while true; do
    if process_once; then
      :
    else
      code=$?
      if [[ "$code" == "2" ]]; then
        TASK_ID=""
        write_dashboard_state "IDLE" "" "0" "暂无任务"
      else
        log "Task failed, continue polling"
      fi
    fi

    if [[ -n "${TASK_ID:-}" ]]; then
      TASK_ID=""
      write_dashboard_state "IDLE" "" "0" "等待任务"
    fi

    if [[ "$ONCE" == "1" ]]; then
      break
    fi
    sleep "$POLL_INTERVAL"
  done
}

# 容器优雅退出：SIGTERM 时尝试把 running 任务退回队列再退出
trap 'log "SIGTERM received, recovering running tasks before exit"; recover_running_tasks; exit 0' TERM INT

main "$@"
