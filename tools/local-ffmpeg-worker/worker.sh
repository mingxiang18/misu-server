#!/usr/bin/env bash
# Legacy Mac-side SSH worker. 生产环境优先用 worker-linux.sh + Dockerfile
# (k8s 部署见 tools/local-ffmpeg-worker/k8s/)，本文件保留作开发机一次性回灌用途。
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -f "$SCRIPT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$SCRIPT_DIR/.env"
  set +a
fi

SSH_USER="${SSH_USER:-root}"
SSH_HOST="${SSH_HOST:-192.168.50.194}"
SSH_PORT="${SSH_PORT:-22}"
SSH_KEY="${SSH_KEY:-}"
CONTAINER_USER_FILE_ROOT="${CONTAINER_USER_FILE_ROOT:-/app/user-file}"
REMOTE_USER_FILE_ROOT="${REMOTE_USER_FILE_ROOT:-/mnt/misu/misu-server/user-file}"
LOCAL_WORK_DIR="${LOCAL_WORK_DIR:-.work}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"
KEEP_LOCAL_WORK="${KEEP_LOCAL_WORK:-0}"
DASHBOARD_PORT="${DASHBOARD_PORT:-18765}"
VIDEO_ENCODER_ARGS="${VIDEO_ENCODER_ARGS:--c:v hevc_videotoolbox -tag:v hvc1 -pix_fmt yuv420p -q:v 55 -allow_sw 1}"
AUDIO_BITRATE="${AUDIO_BITRATE:-128k}"
MAX_TASK_RETRIES="${MAX_TASK_RETRIES:-1}"

QUEUE_DIR="$REMOTE_USER_FILE_ROOT/transcode-queue"
RUNNING_DIR="$QUEUE_DIR/running"
DONE_DIR="$QUEUE_DIR/done"
FAILED_DIR="$QUEUE_DIR/failed"
SSH_TARGET="$SSH_USER@$SSH_HOST"
ONCE=0
RECOVER_RUNNING=0
STATE_FILE=""
EVENT_FILE=""
TASK_STATE_DIR=""

for arg in "$@"; do
  case "$arg" in
    --once)
      ONCE=1
      ;;
    --recover-running)
      RECOVER_RUNNING=1
      ;;
    *)
      printf 'Unknown option: %s\n' "$arg" >&2
      exit 64
      ;;
  esac
done

LOCAL_WORK_DIR_ABS="$LOCAL_WORK_DIR"
if [[ "$LOCAL_WORK_DIR_ABS" != /* ]]; then
  LOCAL_WORK_DIR_ABS="$SCRIPT_DIR/$LOCAL_WORK_DIR_ABS"
fi
STATE_FILE="$LOCAL_WORK_DIR_ABS/state.json"
EVENT_FILE="$LOCAL_WORK_DIR_ABS/events.jsonl"
TASK_STATE_DIR="$LOCAL_WORK_DIR_ABS/task-status"

ssh_cmd=(ssh -p "$SSH_PORT")
if [[ -n "$SSH_KEY" ]]; then
  ssh_cmd+=(-i "$SSH_KEY")
fi

log() {
  local now
  now="$(date '+%Y-%m-%d %H:%M:%S')"
  printf '[%s] %s\n' "$now" "$*"
  mkdir -p "$LOCAL_WORK_DIR_ABS"
  printf '{"time":"%s","message":"%s"}\n' \
    "$(json_escape "$now")" \
    "$(json_escape "$*")" >> "$EVENT_FILE"
}

quote_remote() {
  local value="$1"
  printf "'%s'" "$(printf '%s' "$value" | sed "s/'/'\\\\''/g")"
}

remote_exec() {
  "${ssh_cmd[@]}" "$SSH_TARGET" "$1"
}

remote_mkdir_for() {
  local path="$1"
  local dir
  dir="$(dirname "$path")"
  remote_exec "mkdir -p $(quote_remote "$dir")"
}

download_remote() {
  local remote_path="$1"
  local local_path="$2"
  mkdir -p "$(dirname "$local_path")"
  "${ssh_cmd[@]}" "$SSH_TARGET" "cat $(quote_remote "$remote_path")" > "$local_path"
}

remote_path_contains_symlink() {
  local remote_path="$1"
  remote_exec "set -eu
path=$(quote_remote "$remote_path")
root=$(quote_remote "$REMOTE_USER_FILE_ROOT")
case \"\$path\" in
  \"\$root\"/*)
    rel=\${path#\"\$root\"/}
    cur=\"\$root\"
    ;;
  *)
    rel=\${path#/}
    cur=\"\"
    ;;
esac
old_ifs=\$IFS
IFS=/
for part in \$rel; do
  [ -z \"\$part\" ] && continue
  if [ -z \"\$cur\" ]; then
    cur=\"/\$part\"
  else
    cur=\"\$cur/\$part\"
  fi
  if [ -L \"\$cur\" ]; then
    exit 0
  fi
done
IFS=\$old_ifs
exit 1"
}

remote_source_has_symlink_reference() {
  local remote_path="$1"
  remote_exec "set -eu
source_real=\$(readlink -f $(quote_remote "$remote_path") 2>/dev/null || true)
[ -n \"\$source_real\" ] || exit 1
root=$(quote_remote "$REMOTE_USER_FILE_ROOT")
match=\$(find \"\$root\" -type l -exec sh -c '
source_real=\$1
shift
for link do
  target_real=\$(readlink -f \"\$link\" 2>/dev/null || true)
  [ -n \"\$target_real\" ] || continue
  case \"\$source_real\" in
    \"\$target_real\"|\"\$target_real\"/*)
      printf \"%s\n\" \"\$link\"
      exit 0
      ;;
  esac
done
exit 1
' sh \"\$source_real\" {} + 2>/dev/null || true)
[ -n \"\$match\" ] && printf '%s\n' \"\$match\" && exit 0
exit 1"
}

upload_remote() {
  local local_path="$1"
  local remote_path="$2"
  local tmp_path="$remote_path.uploading.$$"
  remote_mkdir_for "$remote_path"
  if ! "${ssh_cmd[@]}" "$SSH_TARGET" "cat > $(quote_remote "$tmp_path")" < "$local_path"; then
    remote_exec "rm -f $(quote_remote "$tmp_path")" || true
    return 1
  fi
  if ! remote_exec "mv -f $(quote_remote "$tmp_path") $(quote_remote "$remote_path")"; then
    remote_exec "rm -f $(quote_remote "$tmp_path")" || true
    return 1
  fi
}

map_task_path() {
  local path="$1"
  if [[ "$path" == "$CONTAINER_USER_FILE_ROOT"* ]]; then
    printf '%s%s' "$REMOTE_USER_FILE_ROOT" "${path#"$CONTAINER_USER_FILE_ROOT"}"
  else
    printf '%s' "$path"
  fi
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

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
  while IFS= read -r file; do
    files+=("$file")
  done < <(ls -t "$TASK_STATE_DIR"/*.json 2>/dev/null | head -n 80)

  if [[ "${#files[@]}" -eq 0 ]]; then
    printf '[]'
    return
  fi

  local first=1
  printf '['
  for file in "${files[@]}"; do
    [[ -s "$file" ]] || continue
    if [[ "$first" -eq 0 ]]; then
      printf ','
    fi
    first=0
    tr -d '\n' < "$file"
  done
  printf ']'
}

write_task_record() {
  local task_state="$1"
  local progress="$2"
  local message="$3"
  local result="${4:-RUNNING}"
  local reason="${5:-}"
  local now
  local task_file
  local tmp_file

  [[ -n "${TASK_ID:-}" ]] || return 0
  now="$(date '+%Y-%m-%d %H:%M:%S')"
  task_file="$TASK_STATE_DIR/$TASK_ID.json"
  tmp_file="$task_file.tmp"
  mkdir -p "$TASK_STATE_DIR"

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
    printf '"source":"%s",' "$(json_escape "${SOURCE:-}")"
    printf '"remoteSource":"%s",' "$(json_escape "${REMOTE_SOURCE:-}")"
    printf '"output":"%s",' "$(json_escape "${OUTPUT:-}")"
    printf '"remoteOutput":"%s",' "$(json_escape "${REMOTE_OUTPUT:-}")"
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
  local now
  local tmp_file
  now="$(date '+%Y-%m-%d %H:%M:%S')"
  tmp_file="$STATE_FILE.tmp"
  mkdir -p "$LOCAL_WORK_DIR_ABS"

  {
    printf '{'
    printf '"updatedAt":"%s",' "$(json_escape "$now")"
    printf '"workerState":"%s",' "$(json_escape "$worker_state")"
    printf '"remote":"%s",' "$(json_escape "$SSH_TARGET:$REMOTE_USER_FILE_ROOT")"
    printf '"queueDir":"%s",' "$(json_escape "$QUEUE_DIR")"
    printf '"containerRoot":"%s",' "$(json_escape "$CONTAINER_USER_FILE_ROOT")"
    printf '"remoteRoot":"%s",' "$(json_escape "$REMOTE_USER_FILE_ROOT")"
    printf '"message":"%s",' "$(json_escape "$message")"
    if [[ -n "${TASK_ID:-}" ]]; then
      printf '"currentTask":{'
      printf '"taskId":"%s",' "$(json_escape "$TASK_ID")"
      printf '"state":"%s",' "$(json_escape "$task_state")"
      printf '"progress":%s,' "$progress"
      printf '"source":"%s",' "$(json_escape "${SOURCE:-}")"
      printf '"remoteSource":"%s",' "$(json_escape "${REMOTE_SOURCE:-}")"
      printf '"output":"%s",' "$(json_escape "${OUTPUT:-}")"
      printf '"remoteOutput":"%s",' "$(json_escape "${REMOTE_OUTPUT:-}")"
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
  local file="$1"
  local state="$2"
  local progress="$3"
  local message="$4"
  local preview_path="$5"
  local transcoded_path="$6"
  mkdir -p "$(dirname "$file")"
  printf '{"taskId":"%s","state":"%s","progress":%s,"message":"%s","previewPath":"%s","transcodedPath":"%s"}\n' \
    "$(json_escape "${TASK_ID:-}")" \
    "$(json_escape "$state")" \
    "$progress" \
    "$(json_escape "$message")" \
    "$(json_escape "$preview_path")" \
    "$(json_escape "$transcoded_path")" > "$file"
}

upload_status() {
  local state="$1"
  local progress="$2"
  local message="$3"
  local task_state="${4:-$state}"
  local result="${5:-RUNNING}"
  local reason="${6:-}"
  write_task_record "$task_state" "$progress" "$message" "$result" "$reason"
  write_dashboard_state "RUNNING" "$state" "$progress" "$message"
  write_status_local "$LOCAL_STATUS" "$state" "$progress" "$message" "$PREVIEW" "$OUTPUT"
  upload_remote "$LOCAL_STATUS" "$REMOTE_STATUS"
}

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
  TASK_ID=""
  SOURCE=""
  OUTPUT=""
  PREVIEW=""
  STATUS=""
  PROGRESS_FILE=""
  MAX_HEIGHT="1080"
  CRF="28"
  PRESET="medium"

  # The task file is generated by misu-file-server. Only the leading simple
  # shell assignments are evaluated; the script body is never executed.
  eval "$(extract_task_assignments "$task_file")"

  if [[ -z "$TASK_ID" || -z "$SOURCE" || -z "$OUTPUT" || -z "$PREVIEW" || -z "$STATUS" ]]; then
    log "Invalid task file: $task_file"
    return 1
  fi

  REMOTE_SOURCE="$(map_task_path "$SOURCE")"
  REMOTE_OUTPUT="$(map_task_path "$OUTPUT")"
  REMOTE_PREVIEW="$(map_task_path "$PREVIEW")"
  REMOTE_STATUS="$(map_task_path "$STATUS")"
  write_task_record "CLAIMED" "0" "已领取任务"
  write_dashboard_state "RUNNING" "CLAIMED" "0" "已领取任务"
}

claim_task() {
  remote_exec "mkdir -p $(quote_remote "$RUNNING_DIR") $(quote_remote "$DONE_DIR") $(quote_remote "$FAILED_DIR")"

  remote_exec "set -eu
task=\$(find $(quote_remote "$QUEUE_DIR") -maxdepth 1 -type f -name '*.task' | sort | head -n 1)
if [ -z \"\$task\" ]; then
  exit 2
fi
base=\$(basename \"\$task\")
dest=$(quote_remote "$RUNNING_DIR")/\$base
if mv \"\$task\" \"\$dest\"; then
  printf '%s\n' \"\$dest\"
else
  exit 3
fi"
}

move_claimed_task() {
  local remote_task="$1"
  local target_dir="$2"
  local base
  base="$(basename "$remote_task")"
  remote_exec "mkdir -p $(quote_remote "$target_dir") && mv -f $(quote_remote "$remote_task") $(quote_remote "$target_dir/$base")" || true
}

recover_running_tasks() {
  remote_exec "set -eu
mkdir -p $(quote_remote "$QUEUE_DIR") $(quote_remote "$RUNNING_DIR")
find $(quote_remote "$RUNNING_DIR") -maxdepth 1 -type f -name '*.task' | while IFS= read -r task; do
  base=\$(basename \"\$task\")
  mv -f \"\$task\" $(quote_remote "$QUEUE_DIR")/\"\$base\"
  printf '%s\n' \"\$base\"
done"
}

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

run_ffmpeg_task() {
  local local_task_file="$1"
  load_task "$local_task_file" || return 1

  local task_dir="$LOCAL_WORK_DIR_ABS/tasks/$TASK_ID"
  LOCAL_SOURCE="$task_dir/source/$(basename "$REMOTE_SOURCE")"
  LOCAL_OUTPUT="$task_dir/output/$(basename "$REMOTE_OUTPUT")"
  LOCAL_PREVIEW="$task_dir/output/$(basename "$REMOTE_PREVIEW")"
  LOCAL_STATUS="$task_dir/status/status.json"
  LOCAL_PROGRESS="$task_dir/status/progress.txt"
  local symlink_ref=""

  rm -rf "$task_dir"
  mkdir -p "$(dirname "$LOCAL_SOURCE")" "$(dirname "$LOCAL_OUTPUT")" "$(dirname "$LOCAL_STATUS")"

  if remote_path_contains_symlink "$REMOTE_SOURCE" || symlink_ref="$(remote_source_has_symlink_reference "$REMOTE_SOURCE")"; then
    log "Task $TASK_ID: source uses symlink, skip transcoding: ${symlink_ref:-$REMOTE_SOURCE}"
    upload_status "FAILED" "0" "软链接文件跳过转码" "SKIPPED" "SKIPPED" "${symlink_ref:-$REMOTE_SOURCE}" || return 1
    rm -rf "$task_dir"
    return 0
  fi

  log "Task $TASK_ID: downloading source"
  upload_status "PROCESSING" "1" "下载源视频到本机" "DOWNLOADING" || return 1
  download_remote "$REMOTE_SOURCE" "$LOCAL_SOURCE" || return 1

  log "Task $TASK_ID: generating preview"
  if generate_preview "$LOCAL_SOURCE" "$LOCAL_PREVIEW"; then
    upload_remote "$LOCAL_PREVIEW" "$REMOTE_PREVIEW"
    upload_status "PROCESSING" "3" "已生成视频预览图" "PREVIEWING" || return 1
  else
    log "Task $TASK_ID: preview generation failed, continue transcoding"
    upload_status "PROCESSING" "3" "预览图生成失败，继续转码" "PREVIEW_FAILED" || return 1
  fi

  local total_us
  total_us="$(duration_us "$LOCAL_SOURCE")" || return 1
  if [[ -z "$total_us" || "$total_us" == "0" ]]; then
    total_us=1
  fi

  # shellcheck disable=SC2206
  local video_args=($VIDEO_ENCODER_ARGS)

  log "Task $TASK_ID: transcoding with ${video_args[*]}"
  upload_status "PROCESSING" "5" "本机 GPU 转码中" "TRANSCODING" || return 1

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
    if ! upload_status "PROCESSING" "$percent" "本机 GPU 转码中" "TRANSCODING"; then
      log "Task $TASK_ID: failed to upload progress status, keep transcoding"
    fi
    sleep 2
  done

  wait "$ffmpeg_pid" || return 1
  [[ -s "$LOCAL_OUTPUT" ]] || return 1

  log "Task $TASK_ID: uploading output"
  upload_status "PROCESSING" "97" "上传转码结果到服务器" "UPLOADING" || return 1
  upload_remote "$LOCAL_OUTPUT" "$REMOTE_OUTPUT" || return 1
  upload_status "SUCCESS" "100" "转码完成" "SUCCESS" "SUCCESS" || return 1

  if [[ "$KEEP_LOCAL_WORK" != "1" ]]; then
    rm -rf "$task_dir"
    log "Task $TASK_ID: local temp files cleaned"
  fi
}

process_once() {
  local remote_task
  if ! remote_task="$(claim_task)"; then
    return 2
  fi

  local local_task="$LOCAL_WORK_DIR_ABS/claimed/$(basename "$remote_task")"
  mkdir -p "$(dirname "$local_task")"
  if ! download_remote "$remote_task" "$local_task"; then
    log "Failed to download claimed task, move it back to queue: $remote_task"
    move_claimed_task "$remote_task" "$QUEUE_DIR"
    return 1
  fi

  local attempt=1
  while true; do
    if run_ffmpeg_task "$local_task"; then
      move_claimed_task "$remote_task" "$DONE_DIR"
      rm -f "$local_task"
      return 0
    fi

    if [[ "$attempt" -ge "$MAX_TASK_RETRIES" ]]; then
      if [[ -n "${REMOTE_STATUS:-}" && -n "${LOCAL_STATUS:-}" ]]; then
        upload_status "FAILED" "0" "本机转码失败" "FAILED" "FAILED" "ffmpeg 或上传流程失败"
      fi
      write_task_record "FAILED" "0" "本机转码失败" "FAILED" "ffmpeg 或上传流程失败"
      write_dashboard_state "RUNNING" "FAILED" "0" "本机转码失败"
      move_claimed_task "$remote_task" "$FAILED_DIR"
      return 1
    fi
    attempt=$((attempt + 1))
    log "Retry task after failure: $attempt/$MAX_TASK_RETRIES"
  done
}

main() {
  command -v ffmpeg >/dev/null
  command -v ffprobe >/dev/null
  mkdir -p "$LOCAL_WORK_DIR_ABS"
  write_dashboard_state "STARTING" "" "0" "worker 启动中"

  log "Local ffmpeg worker started, remote=$SSH_TARGET:$REMOTE_USER_FILE_ROOT"
  log "Container path mapping: $CONTAINER_USER_FILE_ROOT -> $REMOTE_USER_FILE_ROOT"
  log "Dashboard state: $STATE_FILE"

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
        log "No task, sleep ${POLL_INTERVAL}s"
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

main "$@"
