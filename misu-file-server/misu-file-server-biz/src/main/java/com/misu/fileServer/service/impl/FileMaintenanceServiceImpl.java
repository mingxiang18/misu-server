package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileMaintenanceService;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.support.FileMappingManager;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.PhysicalFileOps;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 文件服务的「后台维护」Service 实现。
 *
 * <p>从 {@code FileServiceImpl} 行为等价搬出：两个 {@code @Scheduled} 清理任务
 * （过期临时文件回收 / 逻辑删除映射的物理 GC）+ file_mapping 物理回填执行体。
 * 仍是 Spring bean，使 {@code @Scheduled} 生效；cron 的 {@code @Value} 默认值原样保留。</p>
 *
 * @author misu
 */
@Slf4j
@Service
public class FileMaintenanceServiceImpl implements FileMaintenanceService {

    private final static long TMP_FILE_EXPIRE_MILLIS = 24 * 60 * 60 * 1000L;

    @Value("${file-server.path}")
    private String fileServerPath;

    @Value("${file.mapping.gc.retentionDays:7}")
    private long fileMappingGcRetentionDays;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private ThreadPoolTaskExecutor fileExecutor;

    @Resource
    private FileVersionService fileVersionService;

    @Resource
    private FilePathResolver filePathResolver;

    @Resource
    private PhysicalFileOps physicalFileOps;

    @Resource
    private FileMappingManager fileMappingManager;

    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);
    private volatile LocalDateTime backfillStartTime;
    private volatile LocalDateTime backfillEndTime;
    private volatile String backfillLastError;
    private final AtomicLong backfillProcessedCount = new AtomicLong(0L);
    private final AtomicLong backfillCreatedCount = new AtomicLong(0L);
    private final AtomicLong backfillUpdatedCount = new AtomicLong(0L);

    /**
     * 清理过期上传分片和兜底残留的临时ZIP。
     */
    @Scheduled(cron = "${file.tmpClean:0 30 3 * * ?}")
    public void cleanExpiredTmpFiles() {
        File tmpDirectory = new File(fileServerPath + FilePathResolver.TMP_DIRECTORY);
        if (!tmpDirectory.exists() || !tmpDirectory.isDirectory()) {
            return;
        }
        long expireBefore = Instant.now().toEpochMilli() - TMP_FILE_EXPIRE_MILLIS;
        // 兜底残留的临时 ZIP（平铺文件）
        File[] zipFiles = tmpDirectory.listFiles((dir, name) -> name.endsWith(".zip"));
        if (zipFiles != null) {
            for (File tmpFile : zipFiles) {
                if (tmpFile.isFile() && tmpFile.lastModified() < expireBefore && !tmpFile.delete()) {
                    log.warn("过期临时文件删除失败：{}", tmpFile.getAbsolutePath());
                }
            }
        }
        // 过期的分片上传目录（<tmp>/<fileMD5>/part*），整目录回收
        File[] chunkDirs = tmpDirectory.listFiles(File::isDirectory);
        if (chunkDirs != null) {
            for (File chunkDir : chunkDirs) {
                if (chunkDir.lastModified() < expireBefore && !physicalFileOps.deletePhysicalRecursively(chunkDir)) {
                    log.warn("过期分片目录删除失败：{}", chunkDir.getAbsolutePath());
                }
            }
        }
    }

    /**
     * 清理已逻辑删除且无有效映射引用的物理文件。
     *
     * <p>Q1：原实现 findAll() 一次性把全表载入堆，规模上来后 GC 抖动 / OOM 风险高。
     * 改为：(a) 仅取所有 active mapping 的不重复 targetPath（按引用集合）；
     * (b) 用 streamDeletedBefore 流式扫描"已删 + 过保留期"候选，逐条处理。
     * 仅遍历真正需要 GC 的子集，并保留原本的"被 active 引用就跳过"语义。</p>
     */
    @Scheduled(cron = "${file.mapping.gc.cron:0 0 4 * * ?}")
    @Transactional("fileServerTransactionManager")
    public void cleanDeletedFileMappings() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(Math.max(0, fileMappingGcRetentionDays));
        Set<String> activeTargetPaths = new HashSet<>(fileMappingRepository.findDistinctActiveTargetPaths());
        try (Stream<FileMapping> deletedStream = fileMappingRepository.streamDeletedBefore(threshold)) {
            deletedStream.forEach(mapping -> {
                String targetPath = mapping.getTargetPath();
                if (StringUtils.isBlank(targetPath) || activeTargetPaths.contains(targetPath)) {
                    return;
                }
                File target = Path.of(targetPath).toFile();
                if (target.exists() && !physicalFileOps.deletePhysicalRecursively(target)) {
                    log.warn("物理文件清理失败，id={}, targetPath={}", mapping.getId(), targetPath);
                    return;
                }
                // M18：GC 物理清除时级联清版本快照
                fileVersionService.purgeAllVersionsForMapping(mapping.getId());
                fileMappingRepository.delete(mapping);
            });
        }
    }

    @Override
    public void runBackfillAsync() {
        if (!backfillRunning.compareAndSet(false, true)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "回填任务正在执行中，请稍后再试");
        }
        backfillStartTime = LocalDateTime.now();
        backfillEndTime = null;
        backfillLastError = null;
        backfillProcessedCount.set(0L);
        backfillCreatedCount.set(0L);
        backfillUpdatedCount.set(0L);
        fileExecutor.execute(() -> {
            try {
                doBackfillFileMapping();
            } catch (Exception e) {
                backfillLastError = e.getMessage();
                log.error("file_mapping 回填任务执行失败", e);
            } finally {
                backfillEndTime = LocalDateTime.now();
                backfillRunning.set(false);
            }
        });
    }

    @Override
    public Map<String, Object> getBackfillStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", backfillRunning.get());
        status.put("startTime", backfillStartTime);
        status.put("endTime", backfillEndTime);
        status.put("lastError", backfillLastError);
        status.put("processedCount", backfillProcessedCount.get());
        status.put("createdCount", backfillCreatedCount.get());
        status.put("updatedCount", backfillUpdatedCount.get());
        return status;
    }

    private void doBackfillFileMapping() {
        File publicRoot = Path.of(fileServerPath, "public").toFile();
        if (publicRoot.exists() && publicRoot.isDirectory()) {
            upsertTree(1, "public", publicRoot);
        }

        File privateRoot = Path.of(fileServerPath, "private").toFile();
        if (privateRoot.exists() && privateRoot.isDirectory()) {
            File[] userDirectories = privateRoot.listFiles(File::isDirectory);
            if (userDirectories != null) {
                for (File userDirectory : userDirectories) {
                    String userId = userDirectory.getName();
                    if (StringUtils.isNotBlank(userId)) {
                        upsertTree(0, userId, userDirectory);
                    }
                }
            }
        }
    }

    private void upsertTree(Integer openType, String userId, File rootDirectory) {
        List<FileMapping> existing = fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId);
        Map<String, FileMapping> existingMap = new HashMap<>();
        for (FileMapping mapping : existing) {
            existingMap.put(mapping.getVirtualPath(), mapping);
        }
        walkAndUpsert(openType, userId, rootDirectory, rootDirectory, existingMap);
    }

    private void walkAndUpsert(Integer openType, String userId, File rootDirectory, File current,
                               Map<String, FileMapping> existingMap) {
        if (!current.exists()) {
            return;
        }

        if (!rootDirectory.equals(current)) {
            String virtualPath = rootDirectory.toPath()
                    .toAbsolutePath()
                    .normalize()
                    .relativize(current.toPath().toAbsolutePath().normalize())
                    .toString()
                    .replace("\\", "/");
            FileMapping oldMapping = existingMap.get(virtualPath);
            fileMappingManager.saveOrUpdateFileMapping(openType, userId, virtualPath, filePathResolver.getParentPath(virtualPath), current.getName(), current);
            if (oldMapping == null) {
                backfillCreatedCount.incrementAndGet();
            } else {
                backfillUpdatedCount.incrementAndGet();
            }
            backfillProcessedCount.incrementAndGet();
        }

        if (!current.isDirectory()) {
            return;
        }
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            walkAndUpsert(openType, userId, rootDirectory, child, existingMap);
        }
    }
}
