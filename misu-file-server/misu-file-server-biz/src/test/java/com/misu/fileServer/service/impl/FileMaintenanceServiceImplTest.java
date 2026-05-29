package com.misu.fileServer.service.impl;

import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.support.FileMappingManager;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.PhysicalFileOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileMaintenanceServiceImpl} 纯单元测试（JUnit5 + Mockito + @TempDir）。
 *
 * <p>策略：repository / fileVersionService / physicalFileOps / fileMappingManager 全 mock；
 * {@link FilePathResolver} 用真实实例（仅用其 getParentPath，无状态）；fileExecutor 用真实
 * 单线程 {@link ThreadPoolTaskExecutor}，配 latch / busy-wait 保证异步任务跑完后断言。
 * 物理树用 @TempDir 造真实文件/目录。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileMaintenanceServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    FileMappingRepository fileMappingRepository;

    @Mock
    FileVersionService fileVersionService;

    @Mock
    PhysicalFileOps physicalFileOps;

    @Mock
    FileMappingManager fileMappingManager;

    private FilePathResolver filePathResolver;
    private ThreadPoolTaskExecutor fileExecutor;
    private FileMaintenanceServiceImpl service;

    @BeforeEach
    void setUp() {
        filePathResolver = new FilePathResolver();

        fileExecutor = new ThreadPoolTaskExecutor();
        fileExecutor.setCorePoolSize(1);
        fileExecutor.setMaxPoolSize(1);
        fileExecutor.initialize();

        service = new FileMaintenanceServiceImpl();
        ReflectionTestUtils.setField(service, "fileServerPath", tempDir.toString() + File.separator);
        ReflectionTestUtils.setField(service, "fileMappingGcRetentionDays", 7L);
        ReflectionTestUtils.setField(service, "fileMappingRepository", fileMappingRepository);
        ReflectionTestUtils.setField(service, "fileExecutor", fileExecutor);
        ReflectionTestUtils.setField(service, "fileVersionService", fileVersionService);
        ReflectionTestUtils.setField(service, "filePathResolver", filePathResolver);
        ReflectionTestUtils.setField(service, "physicalFileOps", physicalFileOps);
        ReflectionTestUtils.setField(service, "fileMappingManager", fileMappingManager);
    }

    @AfterEach
    void tearDown() {
        fileExecutor.shutdown();
    }

    /** 阻塞等待回填异步任务跑完（running 复位）。 */
    private void awaitBackfillDone() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (Boolean.TRUE.equals(service.getBackfillStatus().get("running"))) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("回填任务超时未完成");
            }
            Thread.sleep(10L);
        }
    }

    // =====================================================================
    // cleanExpiredTmpFiles
    // =====================================================================

    @Test
    void cleanExpiredTmpFiles_tmpDirMissing_noop() {
        // tmp 目录不存在：直接返回，不触碰 physicalFileOps
        service.cleanExpiredTmpFiles();
        verify(physicalFileOps, never()).deletePhysicalRecursively(any());
    }

    @Test
    void cleanExpiredTmpFiles_onlyExpiredRemoved() throws IOException {
        Path tmp = tempDir.resolve("tmp");
        Files.createDirectories(tmp);
        long now = System.currentTimeMillis();
        long expireBefore = now - 24 * 60 * 60 * 1000L;

        // 过期的平铺 zip
        File oldZip = tmp.resolve("old.zip").toFile();
        Files.write(oldZip.toPath(), new byte[]{1});
        oldZip.setLastModified(expireBefore - 60_000L);

        // 未过期的 zip
        File freshZip = tmp.resolve("fresh.zip").toFile();
        Files.write(freshZip.toPath(), new byte[]{1});
        freshZip.setLastModified(now);

        // 过期分片目录
        File oldChunkDir = tmp.resolve("oldmd5").toFile();
        Files.createDirectories(oldChunkDir.toPath());
        oldChunkDir.setLastModified(expireBefore - 60_000L);

        // 未过期分片目录
        File freshChunkDir = tmp.resolve("freshmd5").toFile();
        Files.createDirectories(freshChunkDir.toPath());
        freshChunkDir.setLastModified(now);

        when(physicalFileOps.deletePhysicalRecursively(any())).thenReturn(true);

        service.cleanExpiredTmpFiles();

        // 过期 zip 被物理删除（File.delete），未过期保留
        assertFalse(oldZip.exists(), "过期 zip 应被删除");
        assertTrue(freshZip.exists(), "未过期 zip 应保留");

        // 仅过期分片目录交给 physicalFileOps 回收
        verify(physicalFileOps, times(1)).deletePhysicalRecursively(oldChunkDir);
        verify(physicalFileOps, never()).deletePhysicalRecursively(freshChunkDir);
    }

    // =====================================================================
    // cleanDeletedFileMappings
    // =====================================================================

    @Test
    void cleanDeletedFileMappings_expiredUnreferenced_purgedWithVersionCascade() throws IOException {
        // 物理文件真实存在，过保留期且未被 active 引用 → 物理删 + 版本级联 + repo 删
        File physical = tempDir.resolve("dead.bin").toFile();
        Files.write(physical.toPath(), new byte[]{1, 2, 3});

        FileMapping deleted = new FileMapping();
        deleted.setId(100L);
        deleted.setTargetPath(physical.getAbsolutePath());

        when(fileMappingRepository.findDistinctActiveTargetPaths()).thenReturn(Collections.emptyList());
        when(fileMappingRepository.streamDeletedBefore(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(deleted).stream());
        when(physicalFileOps.deletePhysicalRecursively(physical)).thenReturn(true);

        service.cleanDeletedFileMappings();

        verify(physicalFileOps, times(1)).deletePhysicalRecursively(physical);
        verify(fileVersionService, times(1)).purgeAllVersionsForMapping(100L);
        verify(fileMappingRepository, times(1)).delete(deleted);
    }

    @Test
    void cleanDeletedFileMappings_stillReferencedByActive_skipped() {
        // targetPath 仍被 active mapping 引用 → 跳过：不删物理、不清版本、不删记录
        String shared = tempDir.resolve("shared.bin").toString();
        FileMapping deleted = new FileMapping();
        deleted.setId(101L);
        deleted.setTargetPath(shared);

        when(fileMappingRepository.findDistinctActiveTargetPaths())
                .thenReturn(Collections.singletonList(shared));
        when(fileMappingRepository.streamDeletedBefore(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(deleted).stream());

        service.cleanDeletedFileMappings();

        verify(physicalFileOps, never()).deletePhysicalRecursively(any());
        verify(fileVersionService, never()).purgeAllVersionsForMapping(any());
        verify(fileMappingRepository, never()).delete(any());
    }

    @Test
    void cleanDeletedFileMappings_blankTargetPath_skipped() {
        FileMapping deleted = new FileMapping();
        deleted.setId(102L);
        deleted.setTargetPath("  ");

        when(fileMappingRepository.findDistinctActiveTargetPaths()).thenReturn(Collections.emptyList());
        when(fileMappingRepository.streamDeletedBefore(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(deleted).stream());

        service.cleanDeletedFileMappings();

        verify(physicalFileOps, never()).deletePhysicalRecursively(any());
        verify(fileVersionService, never()).purgeAllVersionsForMapping(any());
        verify(fileMappingRepository, never()).delete(any());
    }

    @Test
    void cleanDeletedFileMappings_physicalDeleteFails_doesNotDeleteRecord() {
        // 物理还存在但删不掉 → 记日志后 return：不清版本、不删记录（保留原语义）
        File physical = tempDir.resolve("locked.bin").toFile();
        try {
            Files.write(physical.toPath(), new byte[]{9});
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        FileMapping deleted = new FileMapping();
        deleted.setId(103L);
        deleted.setTargetPath(physical.getAbsolutePath());

        when(fileMappingRepository.findDistinctActiveTargetPaths()).thenReturn(Collections.emptyList());
        when(fileMappingRepository.streamDeletedBefore(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(deleted).stream());
        when(physicalFileOps.deletePhysicalRecursively(physical)).thenReturn(false);

        service.cleanDeletedFileMappings();

        verify(physicalFileOps, times(1)).deletePhysicalRecursively(physical);
        verify(fileVersionService, never()).purgeAllVersionsForMapping(any());
        verify(fileMappingRepository, never()).delete(any());
    }

    @Test
    void cleanDeletedFileMappings_physicalMissing_stillPurgesVersionAndRecord() {
        // targetPath 指向已不存在的物理文件 → 跳过物理删，但仍清版本 + 删记录
        String missing = tempDir.resolve("ghost.bin").toString();
        FileMapping deleted = new FileMapping();
        deleted.setId(104L);
        deleted.setTargetPath(missing);

        when(fileMappingRepository.findDistinctActiveTargetPaths()).thenReturn(Collections.emptyList());
        when(fileMappingRepository.streamDeletedBefore(any(LocalDateTime.class)))
                .thenReturn(Arrays.asList(deleted).stream());

        service.cleanDeletedFileMappings();

        verify(physicalFileOps, never()).deletePhysicalRecursively(any());
        verify(fileVersionService, times(1)).purgeAllVersionsForMapping(104L);
        verify(fileMappingRepository, times(1)).delete(deleted);
    }

    // =====================================================================
    // backfill
    // =====================================================================

    @Test
    void runBackfillAsync_concurrentGuard_secondCallRejected() {
        // 模拟已有回填在跑（running=true）→ 再次触发应被 compareAndSet 守卫拒绝
        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(service, "backfillRunning");
        running.set(true);

        assertThrows(ServiceException.class, () -> service.runBackfillAsync(),
                "回填进行中再次触发应抛 ServiceException");

        // 守卫拒绝时不应提交异步任务、不触碰回填依赖
        verify(fileMappingRepository, never()).findByOpenTypeAndUserIdAndDeletedFalse(anyInt(), anyString());
        running.set(false);
    }

    @Test
    void runBackfillAsync_walksTreeAndCounts() throws Exception {
        // 造物理树：public/a.txt, public/sub/b.txt（=> 1 个目录 sub + 2 文件 = 3 项）
        Path publicRoot = tempDir.resolve("public");
        Files.createDirectories(publicRoot.resolve("sub"));
        Files.write(publicRoot.resolve("a.txt"), new byte[]{1});
        Files.write(publicRoot.resolve("sub").resolve("b.txt"), new byte[]{2});

        // private 为空目录：不产生条目
        Files.createDirectories(tempDir.resolve("private"));

        // 全部 virtualPath 视为新建（existingMap 为空）
        when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(anyInt(), anyString()))
                .thenReturn(new ArrayList<>());

        service.runBackfillAsync();
        awaitBackfillDone();

        Map<String, Object> status = service.getBackfillStatus();
        assertEquals(false, status.get("running"));
        assertNull(status.get("lastError"));
        assertNotNull(status.get("startTime"));
        assertNotNull(status.get("endTime"));
        // sub 目录 + a.txt + b.txt = 3 项被 processed/created
        assertEquals(3L, status.get("processedCount"));
        assertEquals(3L, status.get("createdCount"));
        assertEquals(0L, status.get("updatedCount"));

        // saveOrUpdateFileMapping 对每个非根条目调用一次（openType=1, userId=public）
        verify(fileMappingManager, times(3))
                .saveOrUpdateFileMapping(eq(1), eq("public"), anyString(), anyString(), anyString(), any(File.class));
    }

    @Test
    void runBackfillAsync_existingMappingCountedAsUpdate() throws Exception {
        Path publicRoot = tempDir.resolve("public");
        Files.createDirectories(publicRoot);
        Files.write(publicRoot.resolve("a.txt"), new byte[]{1});
        Files.createDirectories(tempDir.resolve("private"));

        FileMapping existing = new FileMapping();
        existing.setVirtualPath("a.txt");
        when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(eq(1), eq("public")))
                .thenReturn(Collections.singletonList(existing));
        lenient().when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(eq(0), anyString()))
                .thenReturn(new ArrayList<>());

        service.runBackfillAsync();
        awaitBackfillDone();

        Map<String, Object> status = service.getBackfillStatus();
        assertEquals(1L, status.get("processedCount"));
        assertEquals(0L, status.get("createdCount"));
        assertEquals(1L, status.get("updatedCount"));
    }

    @Test
    void runBackfillAsync_exception_recordsErrorAndResetsRunning() throws Exception {
        Path publicRoot = tempDir.resolve("public");
        Files.createDirectories(publicRoot);
        Files.write(publicRoot.resolve("a.txt"), new byte[]{1});

        when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(anyInt(), anyString()))
                .thenReturn(new ArrayList<>());
        // 让回填体在 saveOrUpdate 时抛异常
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(fileMappingManager)
                .saveOrUpdateFileMapping(anyInt(), anyString(), anyString(), anyString(), anyString(), any(File.class));

        service.runBackfillAsync();
        awaitBackfillDone();

        Map<String, Object> status = service.getBackfillStatus();
        assertEquals(false, status.get("running"), "异常后 running 必须复位");
        assertEquals("boom", status.get("lastError"));
        assertNotNull(status.get("endTime"));
    }

    @Test
    void getBackfillStatus_initialState() {
        Map<String, Object> status = service.getBackfillStatus();
        assertEquals(false, status.get("running"));
        assertNull(status.get("startTime"));
        assertNull(status.get("endTime"));
        assertNull(status.get("lastError"));
        assertEquals(0L, status.get("processedCount"));
        assertEquals(0L, status.get("createdCount"));
        assertEquals(0L, status.get("updatedCount"));
    }
}
