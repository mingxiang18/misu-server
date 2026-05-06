package com.misu.fileServer.service.impl;

import com.alibaba.fastjson2.JSON;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.service.VideoTranscodeService;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

@Slf4j
@Service
public class VideoTranscodeServiceImpl implements VideoTranscodeService {

    @Value("${file-server.path}")
    private String fileServerPath;

    @Value("${video.transcode.enabled:true}")
    private boolean enabled;

    @Value("${video.transcode.max-bytes:1610612736}")
    private long maxBytes;

    @Value("${video.transcode.queue-dir:}")
    private String queueDir;

    @Value("${video.transcode.status-dir:}")
    private String statusDir;

    @Value("${video.transcode.transcode-dir:}")
    private String transcodeDir;

    @Value("${video.transcode.preview-dir:}")
    private String previewDir;

    @Value("${video.transcode.max-height:1080}")
    private int maxHeight;

    @Value("${video.transcode.crf:28}")
    private int crf;

    @Value("${video.transcode.preset:medium}")
    private String preset;

    @Value("${video.transcode.audio-bitrate:128k}")
    private String audioBitrate;

    @Value("${video.transcode.profile-version:hevc-aac-mp4-1080p-v1}")
    private String profileVersion;

    @Override
    public VideoTranscodeStatusDto getOrCreateTranscodeStatus(File sourceFile) {
        VideoTranscodeStatusDto status = new VideoTranscodeStatusDto();
        status.setTaskId(getTaskId(sourceFile));
        status.setProgress(0);

        if (!enabled) {
            status.setState(VideoTranscodeState.NONE);
            status.setMessage("视频转码未启用");
            return status;
        }
        if (!sourceFile.exists() || sourceFile.isDirectory()) {
            status.setState(VideoTranscodeState.UNSUPPORTED);
            status.setMessage("视频文件不存在");
            return status;
        }
        if (sourceFile.length() > maxBytes) {
            status.setState(VideoTranscodeState.TOO_LARGE);
            status.setMessage("视频超过在线播放上限，当前最大允许 " + formatBytes(maxBytes));
            return status;
        }

        VideoTranscodeStatusDto diskStatus = readStatus(sourceFile);
        if (diskStatus != null && StringUtils.isNotBlank(diskStatus.getState())) {
            if (!VideoTranscodeState.SUCCESS.equals(diskStatus.getState()) || getTranscodedFile(sourceFile).exists()) {
                fillDefaultPaths(sourceFile, diskStatus);
                return diskStatus;
            }
        }

        File outputFile = getTranscodedFile(sourceFile);
        if (outputFile.exists()) {
            status.setState(VideoTranscodeState.SUCCESS);
            status.setProgress(100);
            status.setMessage("转码完成");
            fillDefaultPaths(sourceFile, status);
            writeStatus(sourceFile, status);
            return status;
        }

        enqueueTask(sourceFile);
        status.setState(VideoTranscodeState.WAITING);
        status.setMessage("等待转码");
        fillDefaultPaths(sourceFile, status);
        writeStatus(sourceFile, status);
        return status;
    }

    @Override
    public File getTranscodedFile(File sourceFile) {
        return resolveConfiguredDirectory(transcodeDir, "transcode").resolve(getTaskId(sourceFile) + ".mp4").toFile();
    }

    @Override
    public File getVideoPreviewFile(File sourceFile) {
        return resolveConfiguredDirectory(previewDir, "preview/video").resolve(getTaskId(sourceFile) + ".jpg").toFile();
    }

    @Override
    public long getMaxBytes() {
        return maxBytes;
    }

    private VideoTranscodeStatusDto readStatus(File sourceFile) {
        File statusFile = getStatusFile(sourceFile);
        if (!statusFile.exists() || !statusFile.isFile()) {
            return null;
        }
        try {
            return JSON.parseObject(Files.readString(statusFile.toPath(), StandardCharsets.UTF_8), VideoTranscodeStatusDto.class);
        } catch (Exception e) {
            log.warn("读取视频转码状态失败：{}", statusFile.getAbsolutePath(), e);
            return null;
        }
    }

    @SneakyThrows
    private void writeStatus(File sourceFile, VideoTranscodeStatusDto status) {
        File statusFile = getStatusFile(sourceFile);
        Path statusPath = statusFile.toPath();
        Files.createDirectories(statusPath.getParent());
        Path tmpPath = statusPath.resolveSibling(statusFile.getName() + ".tmp");
        Files.writeString(tmpPath, JSON.toJSONString(status), StandardCharsets.UTF_8);
        Files.move(tmpPath, statusPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @SneakyThrows
    private void enqueueTask(File sourceFile) {
        File taskFile = getTaskFile(sourceFile);
        if (taskFile.exists()) {
            return;
        }
        File runningTaskFile = getRunningTaskFile(sourceFile);
        if (runningTaskFile.exists()) {
            return;
        }

        Files.createDirectories(taskFile.toPath().getParent());
        Files.createDirectories(getStatusFile(sourceFile).toPath().getParent());
        Files.createDirectories(getTranscodedFile(sourceFile).toPath().getParent());
        Files.createDirectories(getVideoPreviewFile(sourceFile).toPath().getParent());

        Path tmpTask = taskFile.toPath().resolveSibling(taskFile.getName() + ".tmp");
        Files.writeString(tmpTask, buildTaskScript(sourceFile), StandardCharsets.UTF_8);
        Files.move(tmpTask, taskFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private String buildTaskScript(File sourceFile) {
        String taskId = getTaskId(sourceFile);
        File statusFile = getStatusFile(sourceFile);
        File outputFile = getTranscodedFile(sourceFile);
        File previewFile = getVideoPreviewFile(sourceFile);
        File progressFile = statusFile.toPath().resolveSibling(taskId + ".progress").toFile();

        return """
                #!/bin/sh
                set -eu
                TASK_ID=%s
                SOURCE=%s
                OUTPUT=%s
                PREVIEW=%s
                STATUS=%s
                PROGRESS_FILE=%s
                MAX_HEIGHT=%s
                CRF=%s
                PRESET=%s
                AUDIO_BITRATE=%s

                mkdir -p "$(dirname "$OUTPUT")" "$(dirname "$PREVIEW")" "$(dirname "$STATUS")"

                write_status() {
                  state="$1"
                  progress="$2"
                  message="$3"
                  tmp="$STATUS.tmp"
                  printf '{"taskId":"%%s","state":"%%s","progress":%%s,"message":"%%s","previewPath":"%%s","transcodedPath":"%%s"}\\n' \\
                    "$TASK_ID" "$state" "$progress" "$message" "$PREVIEW" "$OUTPUT" > "$tmp"
                  mv "$tmp" "$STATUS"
                }

                duration_us="$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$SOURCE" | awk '{printf "%%d", $1 * 1000000}' || true)"
                if [ -z "$duration_us" ] || [ "$duration_us" -le 0 ]; then
                  duration_us=0
                fi

                write_status PROCESSING 1 正在生成封面
                ffmpeg -y -ss 5 -i "$SOURCE" -frames:v 1 -vf "scale='min(480,iw)':-2" "$PREVIEW" >/dev/null 2>&1 || true

                write_status PROCESSING 2 正在转码
                rm -f "$PROGRESS_FILE"
                ffmpeg -y -i "$SOURCE" -map 0:v:0 -map 0:a? \\
                  -vf "scale='if(gt(ih,$MAX_HEIGHT),-2,iw)':'if(gt(ih,$MAX_HEIGHT),$MAX_HEIGHT,ih)'" \\
                  -c:v libx265 -preset "$PRESET" -crf "$CRF" -pix_fmt yuv420p -tag:v hvc1 \\
                  -c:a aac -b:a "$AUDIO_BITRATE" -movflags +faststart \\
                  -progress "$PROGRESS_FILE" -nostats "$OUTPUT" &
                ffmpeg_pid="$!"

                while kill -0 "$ffmpeg_pid" 2>/dev/null; do
                  if [ "$duration_us" -gt 0 ] && [ -f "$PROGRESS_FILE" ]; then
                    out_time_ms="$(awk -F= '/out_time_ms/ {v=$2} END {print v+0}' "$PROGRESS_FILE")"
                    progress="$(awk -v out="$out_time_ms" -v dur="$duration_us" 'BEGIN {p=int(out*100/dur); if (p < 2) p=2; if (p > 99) p=99; print p}')"
                    write_status PROCESSING "$progress" 正在转码
                  fi
                  sleep 2
                done

                if wait "$ffmpeg_pid"; then
                  write_status SUCCESS 100 转码完成
                else
                  rm -f "$OUTPUT"
                  write_status FAILED 0 转码失败
                  exit 1
                fi
                """.formatted(
                shellQuote(taskId),
                shellQuote(sourceFile.getAbsolutePath()),
                shellQuote(outputFile.getAbsolutePath()),
                shellQuote(previewFile.getAbsolutePath()),
                shellQuote(statusFile.getAbsolutePath()),
                shellQuote(progressFile.getAbsolutePath()),
                shellQuote(String.valueOf(maxHeight)),
                shellQuote(String.valueOf(crf)),
                shellQuote(preset),
                shellQuote(audioBitrate)
        );
    }

    private void fillDefaultPaths(File sourceFile, VideoTranscodeStatusDto status) {
        status.setTaskId(StringUtils.defaultIfBlank(status.getTaskId(), getTaskId(sourceFile)));
        status.setProgress(Objects.requireNonNullElse(status.getProgress(), 0));
        status.setPreviewPath(StringUtils.defaultIfBlank(status.getPreviewPath(), getVideoPreviewFile(sourceFile).getAbsolutePath()));
        status.setTranscodedPath(StringUtils.defaultIfBlank(status.getTranscodedPath(), getTranscodedFile(sourceFile).getAbsolutePath()));
    }

    private File getStatusFile(File sourceFile) {
        return resolveConfiguredDirectory(statusDir, "transcode-status").resolve(getTaskId(sourceFile) + ".json").toFile();
    }

    private File getTaskFile(File sourceFile) {
        return resolveConfiguredDirectory(queueDir, "transcode-queue").resolve(getTaskId(sourceFile) + ".task").toFile();
    }

    private File getRunningTaskFile(File sourceFile) {
        return resolveConfiguredDirectory(queueDir, "transcode-queue").resolve("running").resolve(getTaskId(sourceFile) + ".task").toFile();
    }

    private Path resolveConfiguredDirectory(String configuredDirectory, String defaultRelativeDirectory) {
        if (StringUtils.isNotBlank(configuredDirectory)) {
            return Path.of(configuredDirectory).toAbsolutePath().normalize();
        }
        return Path.of(fileServerPath).resolve(defaultRelativeDirectory).toAbsolutePath().normalize();
    }

    private String getTaskId(File sourceFile) {
        return DigestUtils.md5Hex(getSourceIdentity(sourceFile));
    }

    private String getSourceIdentity(File sourceFile) {
        try {
            Path rootPath = Path.of(fileServerPath).toAbsolutePath().normalize();
            Path sourcePath = sourceFile.toPath().toAbsolutePath().normalize();
            String relativePath = rootPath.relativize(sourcePath).toString().replace("\\", "/");
            return profileVersion + ":" + relativePath + ":" + sourceFile.length() + ":" + sourceFile.lastModified();
        } catch (Exception e) {
            return profileVersion + ":" + sourceFile.getAbsolutePath() + ":" + sourceFile.length() + ":" + sourceFile.lastModified();
        }
    }

    private String formatBytes(long bytes) {
        double gb = bytes / 1024D / 1024D / 1024D;
        return String.format("%.1fGB", gb);
    }

    private String shellQuote(String value) {
        return "'" + StringUtils.defaultString(value).replace("'", "'\"'\"'") + "'";
    }
}
