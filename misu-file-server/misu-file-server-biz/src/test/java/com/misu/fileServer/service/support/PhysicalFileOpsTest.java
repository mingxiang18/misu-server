package com.misu.fileServer.service.support;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.service.PreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link PhysicalFileOps} 边界单测：纯 JUnit5 + Mockito + {@link TempDir}（真实磁盘）。
 *
 * <p>两个限额阈值 {@code directoryDownloadMaxBytes}/{@code directoryDownloadMaxFiles}（原 {@code @Value}）
 * 经 {@link ReflectionTestUtils#setField} 注入；{@link PreviewService} 用 Mockito mock，
 * 仅 {@link PhysicalFileOps#fileDeleteAfter} 与 {@link PhysicalFileOps#deleteFile} 会触达它。</p>
 */
class PhysicalFileOpsTest {

    private PhysicalFileOps ops;
    private PreviewService previewService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ops = new PhysicalFileOps();
        previewService = mock(PreviewService.class);
        ReflectionTestUtils.setField(ops, "previewService", previewService);
        // 默认宽松阈值，限额相关用例各自覆盖
        ReflectionTestUtils.setField(ops, "directoryDownloadMaxBytes", 209715200L);
        ReflectionTestUtils.setField(ops, "directoryDownloadMaxFiles", 1000L);
    }

    // ---------- 测试辅助 ----------

    private File writeFile(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    // ===================== deletePhysicalRecursively =====================

    @Test
    void deletePhysicalRecursively_deletesNonEmptyDirectoryTree() throws IOException {
        // root/
        //   a.txt
        //   sub/
        //     b.txt
        //     deep/
        //       c.txt
        Path root = tempDir.resolve("root");
        Path sub = root.resolve("sub");
        Path deep = sub.resolve("deep");
        Files.createDirectories(deep);
        writeFile(root, "a.txt", "a");
        writeFile(sub, "b.txt", "bb");
        writeFile(deep, "c.txt", "ccc");

        boolean result = ops.deletePhysicalRecursively(root.toFile());

        assertTrue(result);
        assertFalse(Files.exists(root));
    }

    @Test
    void deletePhysicalRecursively_nonExistentTarget_returnsTrue() {
        File ghost = tempDir.resolve("does-not-exist").toFile();
        assertFalse(ghost.exists());
        assertTrue(ops.deletePhysicalRecursively(ghost));
    }

    @Test
    void deletePhysicalRecursively_singleFile_deletedAndTrue() throws IOException {
        File f = writeFile(tempDir, "single.txt", "hi");
        assertTrue(f.exists());

        boolean result = ops.deletePhysicalRecursively(f);

        assertTrue(result);
        assertFalse(f.exists());
    }

    @Test
    void deletePhysicalRecursively_doesNotInvokePreviewService() throws IOException {
        File f = writeFile(tempDir, "x.txt", "x");
        ops.deletePhysicalRecursively(f);
        // 与 deleteFile 不同：递归物理删不触发 fileDeleteAfter
        verifyNoInteractions(previewService);
    }

    // ===================== deleteFile(File) =====================

    @Test
    void deleteFile_singleFileSuccess_returnsTrueAndCallsAfter() throws IOException {
        File f = writeFile(tempDir, "del.txt", "data");

        Boolean result = ops.deleteFile(f);

        assertTrue(result);
        assertFalse(f.exists());
        // 删成功后回调 fileDeleteAfter → previewService.deletePreviewFile
        verify(previewService, times(1)).deletePreviewFile(f);
    }

    @Test
    void deleteFile_nonExistentFile_returnsFalseAndSkipsAfter() {
        File ghost = tempDir.resolve("nope.txt").toFile();
        assertFalse(ghost.exists());

        Boolean result = ops.deleteFile(ghost);

        // File.delete() 对不存在文件返回 false
        assertFalse(result);
        verifyNoInteractions(previewService);
    }

    @Test
    void deleteFile_directoryTree_deletesAllAndCallsAfterPerNode() throws IOException {
        Path root = tempDir.resolve("tree");
        Path sub = root.resolve("sub");
        Files.createDirectories(sub);
        writeFile(root, "a.txt", "a");
        writeFile(sub, "b.txt", "b");

        Boolean result = ops.deleteFile(root.toFile());

        assertTrue(result);
        assertFalse(Files.exists(root));
        // 2 个文件 + sub 目录 + root 目录 = 4 个节点全部删成功 → 4 次 after 回调
        verify(previewService, times(4)).deletePreviewFile(org.mockito.ArgumentMatchers.any(File.class));
    }

    // ===================== ensureDirectoryExists =====================

    @Test
    void ensureDirectoryExists_createsWhenMissing() {
        Path dir = tempDir.resolve("new/nested/dir");
        assertFalse(Files.exists(dir));

        ops.ensureDirectoryExists(dir);

        assertTrue(Files.isDirectory(dir));
    }

    @Test
    void ensureDirectoryExists_idempotentWhenExists() throws IOException {
        Path dir = tempDir.resolve("exists");
        Files.createDirectories(dir);

        assertDoesNotThrow(() -> ops.ensureDirectoryExists(dir));
        assertTrue(Files.isDirectory(dir));
    }

    // ===================== getDirectoryDownloadStat =====================

    @Test
    void getDirectoryDownloadStat_sumsBytesAndFileCount() throws IOException {
        Path root = tempDir.resolve("stat");
        Path sub = root.resolve("sub");
        Files.createDirectories(sub);
        writeFile(root, "f1.txt", "12345");   // 5 bytes
        writeFile(root, "f2.txt", "678");     // 3 bytes
        writeFile(sub, "f3.txt", "90");       // 2 bytes

        PhysicalFileOps.DirectoryDownloadStat stat = ops.getDirectoryDownloadStat(root);

        assertEquals(3, stat.fileCount);
        assertEquals(10, stat.totalBytes);
    }

    @Test
    void getDirectoryDownloadStat_emptyDirectory_zeroes() throws IOException {
        Path root = tempDir.resolve("empty");
        Files.createDirectories(root);

        PhysicalFileOps.DirectoryDownloadStat stat = ops.getDirectoryDownloadStat(root);

        assertEquals(0, stat.fileCount);
        assertEquals(0, stat.totalBytes);
    }

    @Test
    void getDirectoryDownloadStat_nonExistentDirectory_throwsServiceException() {
        Path ghost = tempDir.resolve("ghost-dir");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> ops.getDirectoryDownloadStat(ghost));
        assertEquals(HttpStatus.ERROR, ex.getCode());
    }

    // ===================== checkDirectoryDownloadLimit =====================

    @Test
    void checkDirectoryDownloadLimit_underLimit_passes() throws IOException {
        Path root = tempDir.resolve("ok");
        Files.createDirectories(root);
        writeFile(root, "a.txt", "small");

        assertDoesNotThrow(() -> ops.checkDirectoryDownloadLimit(root.toFile()));
    }

    @Test
    void checkDirectoryDownloadLimit_exceedsMaxFiles_throws() throws IOException {
        ReflectionTestUtils.setField(ops, "directoryDownloadMaxFiles", 2L);
        Path root = tempDir.resolve("manyfiles");
        Files.createDirectories(root);
        writeFile(root, "a.txt", "a");
        writeFile(root, "b.txt", "b");
        writeFile(root, "c.txt", "c"); // 3 > 2

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ops.checkDirectoryDownloadLimit(root.toFile()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("数量"));
    }

    @Test
    void checkDirectoryDownloadLimit_exceedsMaxBytes_throws() throws IOException {
        ReflectionTestUtils.setField(ops, "directoryDownloadMaxBytes", 4L);
        Path root = tempDir.resolve("bigbytes");
        Files.createDirectories(root);
        writeFile(root, "a.txt", "abcdefghij"); // 10 bytes > 4

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ops.checkDirectoryDownloadLimit(root.toFile()));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("体积"));
    }

    @Test
    void checkDirectoryDownloadLimit_fileCountCheckedBeforeBytes() throws IOException {
        // 文件数与字节都超：按原逻辑先判文件数，抛"数量过多"
        ReflectionTestUtils.setField(ops, "directoryDownloadMaxFiles", 1L);
        ReflectionTestUtils.setField(ops, "directoryDownloadMaxBytes", 1L);
        Path root = tempDir.resolve("both");
        Files.createDirectories(root);
        writeFile(root, "a.txt", "abc");
        writeFile(root, "b.txt", "def");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> ops.checkDirectoryDownloadLimit(root.toFile()));
        assertTrue(ex.getMessage().contains("数量"));
    }

    // ===================== fileDeleteAfter =====================

    @Test
    void fileDeleteAfter_delegatesToPreviewService() throws IOException {
        File f = writeFile(tempDir, "after.txt", "x");

        assertDoesNotThrow(() -> ops.fileDeleteAfter(f));

        verify(previewService, times(1)).deletePreviewFile(f);
    }
}
