package com.misu.fileServer.service.impl;

import com.alibaba.fastjson2.JSON;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.VideoTranscodeJobDto;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.dto.VideoTranscodeTaskAdminDto;
import com.misu.fileServer.domain.dto.VideoTranscodeTaskAdminSummaryDto;
import com.misu.fileServer.domain.entity.VideoTranscodeJob;
import com.misu.fileServer.repository.VideoTranscodeJobRepository;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Service
public class VideoTranscodeServiceImpl implements VideoTranscodeService {

    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[0-9a-fA-F]{32}");

    /**
     * 最优先任务文件名前缀。worker 按 .task 文件名 ASCII 升序 (sort | head -n 1) 领取，'!' 0x21 排在
     * '0' 0x30 与字母之前，因此带此前缀的任务永远优先被领取。
     */
    private static final String PRIORITY_FILENAME_PREFIX = "!priority-";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /** Q8：转码队列总长上限（waiting + running）。新任务超限时返回 OVERLOADED 提示，不再 enqueue。 */
    @Value("${video.transcode.maxQueueLength:50}")
    private int maxQueueLength;

    /** 直通探测开关。开启后 worker 领到任务先 ffprobe 一遍，源文件满足直通条件就跳过 libx265 转码。 */
    @Value("${video.transcode.passthrough.enabled:true}")
    private boolean passthroughEnabled;

    /** 直通码率上限（bps）；源文件超过此值即使其他条件满足也强制转码。默认 5 Mbps。 */
    @Value("${video.transcode.passthrough.max-bitrate:5000000}")
    private long passthroughMaxBitrate;

    @Resource
    private VideoTranscodeJobRepository videoTranscodeJobRepository;

    @Override
    public VideoTranscodeStatusDto getOrCreateTranscodeStatus(File sourceFile) {
        return getOrCreateTranscodeStatus(sourceFile, null, null, null);
    }

    @Override
    public VideoTranscodeStatusDto getOrCreateTranscodeStatus(File sourceFile,
                                                              Integer sourceOpenType,
                                                              String sourceUserId,
                                                              String sourceVirtualPath) {
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
            String diskState = diskStatus.getState();
            // 终态判断：
            //  - SUCCESS 还要再验产物文件存在（防止外部清理产物后状态文件残留误导）；
            //  - PASSTHROUGH 不产生 transcoded mp4，源文件存在即视为终态；
            //  - 其它非 SUCCESS 状态（WAITING / PROCESSING / FAILED / …）直接透传。
            boolean isTerminalSuccess = VideoTranscodeState.SUCCESS.equals(diskState) && getTranscodedFile(sourceFile).exists();
            boolean isPassthrough = VideoTranscodeState.PASSTHROUGH.equals(diskState);
            boolean isNonSuccess = !VideoTranscodeState.SUCCESS.equals(diskState) && !VideoTranscodeState.PASSTHROUGH.equals(diskState);
            if (isTerminalSuccess || isPassthrough || isNonSuccess) {
                fillDefaultPaths(sourceFile, diskStatus);
                persistJobFromStatus(sourceFile, diskStatus, sourceOpenType, sourceUserId, sourceVirtualPath, null);
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
            persistJobFromStatus(sourceFile, status, sourceOpenType, sourceUserId, sourceVirtualPath, "DONE");
            return status;
        }

        // Q8：转码队列总长上限保护，超限不再 enqueue，避免被恶意用户灌满磁盘 / 长时间排队
        if (transcodeQueueOverloaded()) {
            status.setState(VideoTranscodeState.NONE);
            status.setMessage("当前转码队列已满，请稍后再试");
            fillDefaultPaths(sourceFile, status);
            return status;
        }

        enqueueTask(sourceFile);
        status.setState(VideoTranscodeState.WAITING);
        status.setMessage("等待转码");
        fillDefaultPaths(sourceFile, status);
        writeStatus(sourceFile, status);
        persistJobFromStatus(sourceFile, status, sourceOpenType, sourceUserId, sourceVirtualPath, "WAITING");
        return status;
    }

    /**
     * 判断 queue-dir（顶层 .task） + queue-dir/running 中文件总数是否触达上限。
     */
    private boolean transcodeQueueOverloaded() {
        if (maxQueueLength <= 0) {
            return false;
        }
        Path queueDirectory = getQueueDirectory();
        long pending = countTaskFiles(queueDirectory);
        long running = countTaskFiles(queueDirectory.resolve("running"));
        return (pending + running) >= maxQueueLength;
    }

    private long countTaskFiles(Path directory) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return 0L;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".task")).count();
        } catch (IOException e) {
            log.warn("统计转码队列长度失败，directory={}", directory, e);
            return 0L;
        }
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

    @Override
    public VideoTranscodeTaskAdminSummaryDto getAdminTaskSummary() {
        checkAdmin();

        VideoTranscodeTaskAdminSummaryDto summaryDto = new VideoTranscodeTaskAdminSummaryDto();
        Map<String, VideoTranscodeTaskAdminDto> taskMap = new HashMap<>();

        collectTaskFiles(taskMap, getQueueDirectory(), "WAITING");
        collectTaskFiles(taskMap, getQueueDirectory().resolve("running"), "RUNNING");
        collectTaskFiles(taskMap, getQueueDirectory().resolve("failed"), "FAILED");
        collectTaskFiles(taskMap, getQueueDirectory().resolve("done"), "DONE");
        collectStatusFiles(taskMap);

        taskMap.values().forEach(task -> {
            task.setRetryable("FAILED".equals(task.getQueueState()));
            if ("WAITING".equals(task.getQueueState())) {
                summaryDto.setWaitingCount(summaryDto.getWaitingCount() + 1);
            } else if ("RUNNING".equals(task.getQueueState())) {
                summaryDto.setRunningCount(summaryDto.getRunningCount() + 1);
            } else if ("FAILED".equals(task.getQueueState())) {
                summaryDto.setFailedCount(summaryDto.getFailedCount() + 1);
            } else if ("DONE".equals(task.getQueueState())) {
                summaryDto.setDoneCount(summaryDto.getDoneCount() + 1);
            }
            if (VideoTranscodeState.FAILED.equals(task.getState())
                    && StringUtils.contains(task.getMessage(), "软链接")) {
                summaryDto.setSkippedCount(summaryDto.getSkippedCount() + 1);
            }
        });

        summaryDto.setTasks(taskMap.values().stream()
                .sorted(Comparator.comparing(VideoTranscodeTaskAdminDto::getUpdateTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList());
        return summaryDto;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public void retryFailedTask(String taskId) {
        checkAdmin();
        if (!TASK_ID_PATTERN.matcher(StringUtils.defaultString(taskId)).matches()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "任务ID不合法");
        }
        Path failedTask = findTaskFile(getQueueDirectory().resolve("failed"), taskId);
        if (failedTask == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "失败任务不存在");
        }
        moveTaskToQueue(failedTask);
        updateJobAfterRetry(taskId);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public int retryAllFailedTasks() {
        checkAdmin();
        List<String> movedTaskIds = new ArrayList<>();
        int count = moveDirectoryTasksToQueue(getQueueDirectory().resolve("failed"), movedTaskIds);
        movedTaskIds.forEach(this::updateJobAfterRetry);
        return count;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public int recoverRunningTasks() {
        checkAdmin();
        List<String> movedTaskIds = new ArrayList<>();
        int count = moveDirectoryTasksToQueue(getQueueDirectory().resolve("running"), movedTaskIds);
        movedTaskIds.forEach(this::updateJobAfterRetry);
        return count;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public Page<VideoTranscodeJobDto> queryJobs(String state, String queueState, String keyword, Pageable pageable) {
        checkAdmin();
        reconcileFromDisk();

        String stateFilter = StringUtils.isBlank(state) ? null : state.trim();
        String queueStateFilter = StringUtils.isBlank(queueState) ? null : queueState.trim();
        String keywordFilter = StringUtils.isBlank(keyword)
                ? null
                : "%" + keyword.trim().toLowerCase() + "%";

        return videoTranscodeJobRepository.searchJobs(stateFilter, queueStateFilter, keywordFilter, pageable)
                .map(this::toJobDto);
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public int retryJobsBatch(List<String> taskIds) {
        checkAdmin();
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        int success = 0;
        Path failedDir = getQueueDirectory().resolve("failed");
        for (String taskId : taskIds) {
            if (!TASK_ID_PATTERN.matcher(StringUtils.defaultString(taskId)).matches()) {
                continue;
            }
            Path failedTask = findTaskFile(failedDir, taskId);
            if (failedTask == null) {
                continue;
            }
            try {
                moveTaskToQueue(failedTask);
                updateJobAfterRetry(taskId);
                success++;
            } catch (Exception e) {
                log.warn("批量重试失败任务异常 taskId={}", taskId, e);
            }
        }
        return success;
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public int prioritizeJobsBatch(List<String> taskIds) {
        checkAdmin();
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        Path queueDir = getQueueDirectory();
        int success = 0;
        for (String taskId : taskIds) {
            if (!TASK_ID_PATTERN.matcher(StringUtils.defaultString(taskId)).matches()) {
                continue;
            }
            try {
                if (prioritizeOne(queueDir, taskId)) {
                    success++;
                }
            } catch (Exception e) {
                log.warn("提升任务优先级异常 taskId={}", taskId, e);
            }
        }
        return success;
    }

    /**
     * 把单个 taskId 的 .task 文件改名为 priority 前缀，使其在 worker 的 sort | head 中排到最前。
     * 若 task 当前还在 RUNNING（已被 worker 抢走），返回 false 表示无法抢占；
     * 若处于 FAILED / DONE 子目录，先 move 回顶层 queue 再 prioritize。
     */
    private boolean prioritizeOne(Path queueDir, String taskId) throws IOException {
        Path running = findTaskFile(queueDir.resolve("running"), taskId);
        if (running != null) {
            return false;
        }
        Path pending = findTaskFile(queueDir, taskId);
        if (pending == null) {
            Path failed = findTaskFile(queueDir.resolve("failed"), taskId);
            Path done = findTaskFile(queueDir.resolve("done"), taskId);
            Path moveSource = failed != null ? failed : done;
            if (moveSource == null) {
                return false;
            }
            moveTaskToQueue(moveSource);
            pending = findTaskFile(queueDir, taskId);
            if (pending == null) {
                return false;
            }
        }
        if (pending.getFileName().toString().startsWith(PRIORITY_FILENAME_PREFIX)) {
            // 已经是 priority 文件，仍刷新 DB 标志 + updateTime
            updateJobAfterPrioritize(taskId);
            return true;
        }
        Path target = queueDir.resolve(PRIORITY_FILENAME_PREFIX + taskId + ".task");
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        updateJobAfterPrioritize(taskId);
        return true;
    }

    private void updateJobAfterPrioritize(String taskId) {
        videoTranscodeJobRepository.findByTaskId(taskId).ifPresent(job -> {
            LocalDateTime now = LocalDateTime.now();
            job.setPriority(Boolean.TRUE);
            job.setQueueState("WAITING");
            if (StringUtils.isBlank(job.getState())
                    || VideoTranscodeState.SUCCESS.equals(job.getState())
                    || VideoTranscodeState.FAILED.equals(job.getState())) {
                job.setState(VideoTranscodeState.WAITING);
            }
            job.setMessage("已置最优先");
            job.setLastEnqueuedAt(now);
            job.setUpdateTime(now);
            videoTranscodeJobRepository.save(job);
        });
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public int reTranscodeJobsBatch(List<String> taskIds) {
        checkAdmin();
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        int success = 0;
        for (String taskId : taskIds) {
            if (!TASK_ID_PATTERN.matcher(StringUtils.defaultString(taskId)).matches()) {
                continue;
            }
            Optional<VideoTranscodeJob> opt = videoTranscodeJobRepository.findByTaskId(taskId);
            if (opt.isEmpty()) {
                continue;
            }
            VideoTranscodeJob job = opt.get();
            File sourceFile = new File(job.getSourcePath());
            if (!sourceFile.exists() || sourceFile.isDirectory()) {
                continue;
            }
            try {
                cleanupTranscodeArtifacts(taskId, sourceFile);
                enqueueTask(sourceFile);

                LocalDateTime now = LocalDateTime.now();
                job.setState(VideoTranscodeState.WAITING);
                job.setQueueState("WAITING");
                job.setProgress(0);
                job.setMessage("等待重转");
                job.setEnqueueCount(Objects.requireNonNullElse(job.getEnqueueCount(), 0) + 1);
                job.setLastEnqueuedAt(now);
                job.setUpdateTime(now);
                // 重转生成的是无前缀的新 task 文件；显式清掉旧的 priority 标志，避免 UI 误显示为最优先
                job.setPriority(Boolean.FALSE);
                videoTranscodeJobRepository.save(job);
                success++;
            } catch (Exception e) {
                log.warn("重新转码任务异常 taskId={}", taskId, e);
            }
        }
        return success;
    }

    private void cleanupTranscodeArtifacts(String taskId, File sourceFile) throws IOException {
        Files.deleteIfExists(getTranscodedFile(sourceFile).toPath());
        Files.deleteIfExists(getVideoPreviewFile(sourceFile).toPath());
        Files.deleteIfExists(getStatusFile(sourceFile).toPath());
        Path queueDirectory = getQueueDirectory();
        for (String sub : new String[]{".", "running", "failed", "done"}) {
            Path subDir = sub.equals(".") ? queueDirectory : queueDirectory.resolve(sub);
            Files.deleteIfExists(subDir.resolve(taskId + ".task"));
            Files.deleteIfExists(subDir.resolve(PRIORITY_FILENAME_PREFIX + taskId + ".task"));
        }
        // 取消标记文件不与上面的 cleanup 共用，因为 re-enqueue 后新任务也得清掉旧 marker
        Files.deleteIfExists(getCancelMarkerFile(taskId));
    }

    private Path getCancelMarkerFile(String taskId) {
        return getQueueDirectory().resolve(taskId + ".cancel");
    }

    @Override
    @Transactional("fileServerTransactionManager")
    public int cancelJobsBatch(List<String> taskIds) {
        checkAdmin();
        if (taskIds == null || taskIds.isEmpty()) {
            return 0;
        }
        int success = 0;
        Path queueDir = getQueueDirectory();
        for (String taskId : taskIds) {
            if (!TASK_ID_PATTERN.matcher(StringUtils.defaultString(taskId)).matches()) {
                continue;
            }
            try {
                // 1. 先写 cancel marker —— 如果有 running 脚本，它会在 progress 循环里看到，主动 kill ffmpeg。
                Path marker = getCancelMarkerFile(taskId);
                Files.createDirectories(marker.getParent());
                Files.write(marker, new byte[]{(byte) 'x'});

                // 2. 删除所有可能的 .task 文件副本（root + running / failed / done 子目录 + !priority- 前缀）。
                for (String sub : new String[]{".", "running", "failed", "done"}) {
                    Path subDir = sub.equals(".") ? queueDir : queueDir.resolve(sub);
                    Files.deleteIfExists(subDir.resolve(taskId + ".task"));
                    Files.deleteIfExists(subDir.resolve(PRIORITY_FILENAME_PREFIX + taskId + ".task"));
                }

                // 3. 清掉对应的产物 / 封面 / status JSON（基于 DB 行的 sourcePath 来定位）。
                Optional<VideoTranscodeJob> opt = videoTranscodeJobRepository.findByTaskId(taskId);
                if (opt.isPresent()) {
                    String srcPath = opt.get().getSourcePath();
                    if (StringUtils.isNotBlank(srcPath)) {
                        File sourceFile = new File(srcPath);
                        Files.deleteIfExists(getTranscodedFile(sourceFile).toPath());
                        Files.deleteIfExists(getVideoPreviewFile(sourceFile).toPath());
                        Files.deleteIfExists(getStatusFile(sourceFile).toPath());
                    }
                    // 兜底：直接按 taskId 删 status JSON（防止 sourcePath 改名后老 status 残留）
                    Files.deleteIfExists(getStatusDirectory().resolve(taskId + ".json"));
                    // 4. DB 行删除 —— 任务从管理界面消失
                    videoTranscodeJobRepository.deleteById(taskId);
                } else {
                    // 没有 DB 行也按 taskId 兜底清 status
                    Files.deleteIfExists(getStatusDirectory().resolve(taskId + ".json"));
                }

                success++;
            } catch (Exception e) {
                log.warn("移除任务异常 taskId={}", taskId, e);
            }
        }
        return success;
    }

    /**
     * 将磁盘上 task / status 最新态同步回 DB。新发现的 taskId 会插入新行；已有行则更新 state / queueState / progress / message / updateTime。
     */
    void reconcileFromDisk() {
        Map<String, VideoTranscodeTaskAdminDto> taskMap = new HashMap<>();
        collectTaskFiles(taskMap, getQueueDirectory(), "WAITING");
        collectTaskFiles(taskMap, getQueueDirectory().resolve("running"), "RUNNING");
        collectTaskFiles(taskMap, getQueueDirectory().resolve("failed"), "FAILED");
        collectTaskFiles(taskMap, getQueueDirectory().resolve("done"), "DONE");
        collectStatusFiles(taskMap);

        if (taskMap.isEmpty()) {
            return;
        }

        Map<String, VideoTranscodeJob> existing = new HashMap<>();
        videoTranscodeJobRepository.findByTaskIdIn(new ArrayList<>(taskMap.keySet()))
                .forEach(job -> existing.put(job.getTaskId(), job));

        List<VideoTranscodeJob> toSave = new ArrayList<>(taskMap.size());
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, VideoTranscodeTaskAdminDto> entry : taskMap.entrySet()) {
            String taskId = entry.getKey();
            VideoTranscodeTaskAdminDto dto = entry.getValue();
            VideoTranscodeJob job = existing.get(taskId);
            if (job == null) {
                job = new VideoTranscodeJob();
                job.setTaskId(taskId);
                job.setSourcePath(StringUtils.defaultString(dto.getSourcePath()));
                job.setProfileVersion(profileVersion);
                job.setCreateTime(now);
                job.setEnqueueCount(1);
            }
            job.setQueueState(StringUtils.defaultIfBlank(dto.getQueueState(), "UNKNOWN"));
            if (StringUtils.isNotBlank(dto.getState())) {
                job.setState(dto.getState());
            } else if (StringUtils.isBlank(job.getState())) {
                job.setState(VideoTranscodeState.NONE);
            }
            if (dto.getProgress() != null) {
                job.setProgress(dto.getProgress());
            }
            if (StringUtils.isNotBlank(dto.getMessage())) {
                job.setMessage(dto.getMessage());
            }
            if (StringUtils.isNotBlank(dto.getOutputPath())) {
                job.setOutputPath(dto.getOutputPath());
            }
            if (StringUtils.isNotBlank(dto.getPreviewPath())) {
                job.setPreviewPath(dto.getPreviewPath());
            }
            if (StringUtils.isNotBlank(dto.getSourcePath()) && StringUtils.isBlank(job.getSourcePath())) {
                job.setSourcePath(dto.getSourcePath());
            }
            if (dto.getPriority() != null) {
                job.setPriority(dto.getPriority());
            }
            job.setUpdateTime(now);
            toSave.add(job);
        }
        videoTranscodeJobRepository.saveAll(toSave);
    }

    private VideoTranscodeJobDto toJobDto(VideoTranscodeJob job) {
        VideoTranscodeJobDto dto = new VideoTranscodeJobDto();
        dto.setTaskId(job.getTaskId());
        dto.setState(job.getState());
        dto.setQueueState(job.getQueueState());
        dto.setProgress(job.getProgress());
        dto.setMessage(job.getMessage());
        dto.setSourcePath(job.getSourcePath());
        dto.setSourceOpenType(job.getSourceOpenType());
        dto.setSourceUserId(job.getSourceUserId());
        dto.setSourceVirtualPath(job.getSourceVirtualPath());
        dto.setOutputPath(job.getOutputPath());
        dto.setPreviewPath(job.getPreviewPath());
        dto.setProfileVersion(job.getProfileVersion());
        dto.setRetryCount(job.getRetryCount());
        dto.setEnqueueCount(job.getEnqueueCount());
        dto.setLastEnqueuedAt(job.getLastEnqueuedAt());
        dto.setCreateTime(job.getCreateTime());
        dto.setUpdateTime(job.getUpdateTime());
        dto.setRetryable("FAILED".equals(job.getQueueState()));
        boolean hasSource = StringUtils.isNotBlank(job.getSourcePath()) && new File(job.getSourcePath()).exists();
        dto.setReTranscodeable(hasSource);
        dto.setPriority(Boolean.TRUE.equals(job.getPriority()));
        return dto;
    }

    private void persistJobFromStatus(File sourceFile,
                                      VideoTranscodeStatusDto status,
                                      Integer sourceOpenType,
                                      String sourceUserId,
                                      String sourceVirtualPath,
                                      String queueStateHint) {
        try {
            String taskId = StringUtils.defaultIfBlank(status.getTaskId(), getTaskId(sourceFile));
            VideoTranscodeJob job = videoTranscodeJobRepository.findByTaskId(taskId)
                    .orElseGet(VideoTranscodeJob::new);
            boolean isNew = job.getTaskId() == null;
            LocalDateTime now = LocalDateTime.now();
            if (isNew) {
                job.setTaskId(taskId);
                job.setCreateTime(now);
                job.setEnqueueCount(1);
                job.setLastEnqueuedAt(now);
            }
            job.setSourcePath(sourceFile.getAbsolutePath());
            if (sourceOpenType != null) {
                job.setSourceOpenType(sourceOpenType);
            }
            if (StringUtils.isNotBlank(sourceUserId)) {
                job.setSourceUserId(sourceUserId);
            }
            if (StringUtils.isNotBlank(sourceVirtualPath)) {
                job.setSourceVirtualPath(sourceVirtualPath);
            }
            if (StringUtils.isNotBlank(status.getTranscodedPath())) {
                job.setOutputPath(status.getTranscodedPath());
            }
            if (StringUtils.isNotBlank(status.getPreviewPath())) {
                job.setPreviewPath(status.getPreviewPath());
            }
            job.setProfileVersion(profileVersion);
            job.setState(StringUtils.defaultIfBlank(status.getState(), VideoTranscodeState.NONE));
            if (StringUtils.isNotBlank(queueStateHint)) {
                job.setQueueState(queueStateHint);
            } else if (StringUtils.isBlank(job.getQueueState())) {
                job.setQueueState("UNKNOWN");
            }
            job.setProgress(Objects.requireNonNullElse(status.getProgress(), 0));
            if (StringUtils.isNotBlank(status.getMessage())) {
                job.setMessage(status.getMessage());
            }
            job.setUpdateTime(now);
            videoTranscodeJobRepository.save(job);
        } catch (Exception e) {
            // DB 镜像失败不应影响转码主流程
            log.warn("写入 video_transcode_job 失败，taskId={}", status.getTaskId(), e);
        }
    }

    private void updateJobAfterRetry(String taskId) {
        videoTranscodeJobRepository.findByTaskId(taskId).ifPresent(job -> {
            LocalDateTime now = LocalDateTime.now();
            job.setQueueState("WAITING");
            job.setState(VideoTranscodeState.WAITING);
            job.setProgress(0);
            job.setMessage("等待重试");
            job.setRetryCount(Objects.requireNonNullElse(job.getRetryCount(), 0) + 1);
            job.setLastEnqueuedAt(now);
            job.setUpdateTime(now);
            videoTranscodeJobRepository.save(job);
        });
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

    private void checkAdmin() {
        if (!AuthorityUtil.hasAuthority(UserRole.ADMIN)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "只有 ADMIN 用户可以管理视频转码任务");
        }
    }

    private void collectTaskFiles(Map<String, VideoTranscodeTaskAdminDto> taskMap, Path directory, String queueState) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".task"))
                    .forEach(taskPath -> mergeTaskFile(taskMap, taskPath, queueState));
        } catch (IOException e) {
            log.warn("读取视频转码任务目录失败：{}", directory, e);
        }
    }

    private void mergeTaskFile(Map<String, VideoTranscodeTaskAdminDto> taskMap, Path taskPath, String queueState) {
        String fileName = taskPath.getFileName().toString();
        boolean priorityFile = fileName.startsWith(PRIORITY_FILENAME_PREFIX);
        String taskId = stripTaskFileName(fileName);
        VideoTranscodeTaskAdminDto taskDto = taskMap.computeIfAbsent(taskId, this::createAdminTaskDto);
        taskDto.setQueueState(queueState);
        taskDto.setTaskPath(taskPath.toAbsolutePath().normalize().toString());
        taskDto.setUpdateTime(formatLastModifiedTime(taskPath));
        if (priorityFile) {
            taskDto.setPriority(Boolean.TRUE);
        } else if (taskDto.getPriority() == null) {
            taskDto.setPriority(Boolean.FALSE);
        }

        Map<String, String> taskVariables = readTaskVariables(taskPath);
        taskDto.setSourcePath(StringUtils.defaultIfBlank(taskDto.getSourcePath(), taskVariables.get("SOURCE")));
        taskDto.setOutputPath(StringUtils.defaultIfBlank(taskDto.getOutputPath(), taskVariables.get("OUTPUT")));
        taskDto.setPreviewPath(StringUtils.defaultIfBlank(taskDto.getPreviewPath(), taskVariables.get("PREVIEW")));
        taskDto.setStatusPath(StringUtils.defaultIfBlank(taskDto.getStatusPath(), taskVariables.get("STATUS")));
    }

    private static String stripTaskFileName(String fileName) {
        String name = StringUtils.removeEnd(fileName, ".task");
        if (name.startsWith(PRIORITY_FILENAME_PREFIX)) {
            name = name.substring(PRIORITY_FILENAME_PREFIX.length());
        }
        return name;
    }

    /**
     * 在指定子目录下查找 taskId 对应的 .task 文件，兼容是否带 priority 前缀；找不到返回 null。
     */
    private Path findTaskFile(Path subDirectory, String taskId) {
        Path plain = subDirectory.resolve(taskId + ".task");
        if (Files.exists(plain) && Files.isRegularFile(plain)) {
            return plain;
        }
        Path prio = subDirectory.resolve(PRIORITY_FILENAME_PREFIX + taskId + ".task");
        if (Files.exists(prio) && Files.isRegularFile(prio)) {
            return prio;
        }
        return null;
    }

    private void collectStatusFiles(Map<String, VideoTranscodeTaskAdminDto> taskMap) {
        Path directory = getStatusDirectory();
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json"))
                    .forEach(statusPath -> mergeStatusFile(taskMap, statusPath));
        } catch (IOException e) {
            log.warn("读取视频转码状态目录失败：{}", directory, e);
        }
    }

    private void mergeStatusFile(Map<String, VideoTranscodeTaskAdminDto> taskMap, Path statusPath) {
        try {
            VideoTranscodeStatusDto statusDto = JSON.parseObject(Files.readString(statusPath, StandardCharsets.UTF_8),
                    VideoTranscodeStatusDto.class);
            if (statusDto == null || StringUtils.isBlank(statusDto.getTaskId())) {
                return;
            }
            VideoTranscodeTaskAdminDto taskDto = taskMap.computeIfAbsent(statusDto.getTaskId(), this::createAdminTaskDto);
            taskDto.setState(statusDto.getState());
            taskDto.setProgress(statusDto.getProgress());
            taskDto.setMessage(statusDto.getMessage());
            taskDto.setPreviewPath(StringUtils.defaultIfBlank(taskDto.getPreviewPath(), statusDto.getPreviewPath()));
            taskDto.setOutputPath(StringUtils.defaultIfBlank(taskDto.getOutputPath(), statusDto.getTranscodedPath()));
            taskDto.setStatusPath(statusPath.toAbsolutePath().normalize().toString());
            taskDto.setUpdateTime(maxTime(taskDto.getUpdateTime(), formatLastModifiedTime(statusPath)));
        } catch (Exception e) {
            log.warn("读取视频转码状态失败：{}", statusPath, e);
        }
    }

    private VideoTranscodeTaskAdminDto createAdminTaskDto(String taskId) {
        VideoTranscodeTaskAdminDto taskDto = new VideoTranscodeTaskAdminDto();
        taskDto.setTaskId(taskId);
        taskDto.setQueueState("UNKNOWN");
        taskDto.setProgress(0);
        taskDto.setRetryable(false);
        return taskDto;
    }

    private Map<String, String> readTaskVariables(Path taskPath) {
        Map<String, String> variableMap = new HashMap<>();
        try {
            for (String line : Files.readAllLines(taskPath, StandardCharsets.UTF_8)) {
                if (StringUtils.isBlank(line)) {
                    break;
                }
                int splitIndex = line.indexOf('=');
                if (splitIndex <= 0) {
                    continue;
                }
                String key = line.substring(0, splitIndex).trim();
                if (!key.matches("[A-Z_][A-Z0-9_]*")) {
                    continue;
                }
                variableMap.put(key, unquoteShellValue(line.substring(splitIndex + 1).trim()));
            }
        } catch (IOException e) {
            log.warn("读取视频转码任务文件失败：{}", taskPath, e);
        }
        return variableMap;
    }

    private String unquoteShellValue(String value) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("'\"'\"'", "'");
        }
        return value;
    }

    private String formatLastModifiedTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(DATE_TIME_FORMATTER);
        } catch (IOException e) {
            return null;
        }
    }

    private String maxTime(String left, String right) {
        if (StringUtils.isBlank(left)) {
            return right;
        }
        if (StringUtils.isBlank(right)) {
            return left;
        }
        return left.compareTo(right) >= 0 ? left : right;
    }

    @SneakyThrows
    private void moveTaskToQueue(Path taskPath) {
        Path queueDirectory = getQueueDirectory();
        Files.createDirectories(queueDirectory);
        Path targetPath = queueDirectory.resolve(taskPath.getFileName()).normalize();
        Files.move(taskPath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private int moveDirectoryTasksToQueue(Path directory, List<String> movedTaskIdsOut) {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".task"))
                    .mapToInt(taskPath -> {
                        String taskId = stripTaskFileName(taskPath.getFileName().toString());
                        moveTaskToQueue(taskPath);
                        if (movedTaskIdsOut != null) {
                            movedTaskIdsOut.add(taskId);
                        }
                        return 1;
                    })
                    .sum();
        } catch (IOException e) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "读取任务目录失败");
        }
    }

    private Path resolveQueueTaskPath(String queueStateDirectory, String taskId) {
        if (!TASK_ID_PATTERN.matcher(StringUtils.defaultString(taskId)).matches()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "任务ID不合法");
        }
        return getQueueDirectory().resolve(queueStateDirectory).resolve(taskId + ".task").toAbsolutePath().normalize();
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
                CANCEL_MARKER=%s
                MAX_HEIGHT=%s
                CRF=%s
                PRESET=%s
                AUDIO_BITRATE=%s
                PASSTHROUGH_ENABLED=%s
                PASSTHROUGH_MAX_BITRATE=%s

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

                # ---- 直通探测：源文件已是 HEVC+hvc1+AAC+MP4+<=MAX_HEIGHT+<=PASSTHROUGH_MAX_BITRATE → 跳过转码 ----
                probe_passthrough() {
                  if [ "$PASSTHROUGH_ENABLED" != "true" ]; then return 1; fi
                  v_codec="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of default=nw=1:nk=1 "$SOURCE" 2>/dev/null || true)"
                  v_tag="$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_tag_string -of default=nw=1:nk=1 "$SOURCE" 2>/dev/null || true)"
                  v_h_raw="$(ffprobe -v error -select_streams v:0 -show_entries stream=height -of default=nw=1:nk=1 "$SOURCE" 2>/dev/null || true)"
                  a_codec="$(ffprobe -v error -select_streams a:0 -show_entries stream=codec_name -of default=nw=1:nk=1 "$SOURCE" 2>/dev/null || true)"
                  fmt="$(ffprobe -v error -show_entries format=format_name -of default=nw=1:nk=1 "$SOURCE" 2>/dev/null || true)"
                  br_raw="$(ffprobe -v error -show_entries format=bit_rate -of default=nw=1:nk=1 "$SOURCE" 2>/dev/null || true)"
                  v_h=$([ -n "$v_h_raw" ] && echo "$v_h_raw" || echo 0)
                  br=$([ -n "$br_raw" ] && [ "$br_raw" != "N/A" ] && echo "$br_raw" || echo 0)
                  case "$fmt" in *mp4*) ;; *) return 1 ;; esac
                  [ "$v_codec" = "hevc" ] || return 1
                  [ "$v_tag" = "hvc1" ] || return 1
                  [ "$a_codec" = "aac" ] || return 1
                  [ "$v_h" -le "$MAX_HEIGHT" ] || return 1
                  [ "$br" -le "$PASSTHROUGH_MAX_BITRATE" ] || return 1
                  return 0
                }

                if probe_passthrough; then
                  write_status PASSTHROUGH 100 "源文件已满足播放条件，跳过转码"
                  exit 0
                fi

                write_status PROCESSING 2 正在转码
                rm -f "$PROGRESS_FILE"
                ffmpeg -y -i "$SOURCE" -map 0:v:0 -map 0:a? \\
                  -vf "scale='if(gt(ih,$MAX_HEIGHT),-2,iw)':'if(gt(ih,$MAX_HEIGHT),$MAX_HEIGHT,ih)'" \\
                  -c:v libx265 -preset "$PRESET" -crf "$CRF" -pix_fmt yuv420p -tag:v hvc1 \\
                  -c:a aac -b:a "$AUDIO_BITRATE" -movflags +faststart \\
                  -progress "$PROGRESS_FILE" -nostats "$OUTPUT" &
                ffmpeg_pid="$!"

                while kill -0 "$ffmpeg_pid" 2>/dev/null; do
                  if [ -f "$CANCEL_MARKER" ]; then
                    kill -TERM "$ffmpeg_pid" 2>/dev/null || true
                    sleep 1
                    kill -KILL "$ffmpeg_pid" 2>/dev/null || true
                    wait "$ffmpeg_pid" 2>/dev/null || true
                    # 不写 status —— file-server 的 cancelJobsBatch 已经清理 DB / status JSON，
                    # 这里再写会被下次 reconcile 当成 orphan 重新拉回 admin 列表。
                    rm -f "$OUTPUT" "$CANCEL_MARKER" "$STATUS" "$PROGRESS_FILE"
                    exit 1
                  fi
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
                shellQuote(getCancelMarkerFile(taskId).toString()),
                shellQuote(String.valueOf(maxHeight)),
                shellQuote(String.valueOf(crf)),
                shellQuote(preset),
                shellQuote(audioBitrate),
                shellQuote(passthroughEnabled ? "true" : "false"),
                shellQuote(String.valueOf(passthroughMaxBitrate))
        );
    }

    private void fillDefaultPaths(File sourceFile, VideoTranscodeStatusDto status) {
        status.setTaskId(StringUtils.defaultIfBlank(status.getTaskId(), getTaskId(sourceFile)));
        status.setProgress(Objects.requireNonNullElse(status.getProgress(), 0));
        status.setPreviewPath(StringUtils.defaultIfBlank(status.getPreviewPath(), getVideoPreviewFile(sourceFile).getAbsolutePath()));
        status.setTranscodedPath(StringUtils.defaultIfBlank(status.getTranscodedPath(), getTranscodedFile(sourceFile).getAbsolutePath()));
    }

    private File getStatusFile(File sourceFile) {
        return getStatusDirectory().resolve(getTaskId(sourceFile) + ".json").toFile();
    }

    private File getTaskFile(File sourceFile) {
        return getQueueDirectory().resolve(getTaskId(sourceFile) + ".task").toFile();
    }

    private File getRunningTaskFile(File sourceFile) {
        return getQueueDirectory().resolve("running").resolve(getTaskId(sourceFile) + ".task").toFile();
    }

    private Path getQueueDirectory() {
        return resolveConfiguredDirectory(queueDir, "transcode-queue");
    }

    private Path getStatusDirectory() {
        return resolveConfiguredDirectory(statusDir, "transcode-status");
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
