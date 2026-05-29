package com.misu.fileServer.service.support;

import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.FileUploadRequest;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.framework.upload.ChunkedUploadAssembler;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
 * {@link ChunkUploadSupport} 边界单测：纯 JUnit5 + Mockito + {@link TempDir}（真实磁盘）。
 *
 * <p>底层合并跑真实 {@code new ChunkedUploadAssembler()}（真实 per-key 锁 + 顺序合并）；
 * {@link PreviewService}/{@link VideoTranscodeService} 用 Mockito mock，仅
 * {@link ChunkUploadSupport#fileAddAfter} 会按文件类型触达它们。{@code file-server.path}
 * 经 {@link ReflectionTestUtils#setField} 注入到临时目录。</p>
 */
class ChunkUploadSupportTest {

    private ChunkUploadSupport support;
    private PreviewService previewService;
    private VideoTranscodeService videoTranscodeService;

    @TempDir
    Path tempDir;

    /** fileServerPath 必须以分隔符结尾（原 god class 用字符串拼接，TMP_DIRECTORY 形如 "tmp/"）。 */
    private String fileServerPath;

    @BeforeEach
    void setUp() {
        support = new ChunkUploadSupport();
        previewService = mock(PreviewService.class);
        videoTranscodeService = mock(VideoTranscodeService.class);
        fileServerPath = tempDir.toString() + File.separator;
        ReflectionTestUtils.setField(support, "fileServerPath", fileServerPath);
        ReflectionTestUtils.setField(support, "chunkedUploadAssembler", new ChunkedUploadAssembler());
        ReflectionTestUtils.setField(support, "previewService", previewService);
        ReflectionTestUtils.setField(support, "videoTranscodeService", videoTranscodeService);
    }

    private FileUploadRequest request(int totalChunks, int chunkIndex, byte[] data) {
        FileUploadRequest req = new FileUploadRequest();
        req.setTotalChunks(totalChunks);
        req.setChunkIndex(chunkIndex);
        req.setFile(new MockMultipartFile("file", "chunk", "application/octet-stream", data));
        return req;
    }

    // ===================== checkUploadChunk =====================

    @Test
    void checkUploadChunk_legalParams_pass() {
        assertDoesNotThrow(() -> support.checkUploadChunk(request(3, 0, new byte[0])));
        assertDoesNotThrow(() -> support.checkUploadChunk(request(3, 2, new byte[0])));
    }

    @Test
    void checkUploadChunk_totalChunksZeroOrNegative_throws() {
        ServiceException e0 = assertThrows(ServiceException.class,
                () -> support.checkUploadChunk(request(0, 0, new byte[0])));
        assertTrue(e0.getMessage().contains("总块数"));
        assertThrows(ServiceException.class,
                () -> support.checkUploadChunk(request(-1, 0, new byte[0])));
    }

    @Test
    void checkUploadChunk_chunkIndexOutOfRange_throws() {
        // 负索引
        ServiceException eNeg = assertThrows(ServiceException.class,
                () -> support.checkUploadChunk(request(3, -1, new byte[0])));
        assertTrue(eNeg.getMessage().contains("块索引"));
        // 索引 == totalChunks（越界上界）
        assertThrows(ServiceException.class,
                () -> support.checkUploadChunk(request(3, 3, new byte[0])));
        // 索引 > totalChunks
        assertThrows(ServiceException.class,
                () -> support.checkUploadChunk(request(3, 5, new byte[0])));
    }

    // ===================== allChunksUploaded =====================

    @Test
    void allChunksUploaded_missingChunk_false() {
        String md5 = "md5missing";
        support.storeChunk(md5, request(3, 0, "a".getBytes(StandardCharsets.UTF_8)));
        support.storeChunk(md5, request(3, 2, "c".getBytes(StandardCharsets.UTF_8)));
        assertFalse(support.allChunksUploaded(md5, 3));
    }

    @Test
    void allChunksUploaded_allPresent_true() {
        String md5 = "md5complete";
        support.storeChunk(md5, request(3, 0, "a".getBytes(StandardCharsets.UTF_8)));
        support.storeChunk(md5, request(3, 1, "b".getBytes(StandardCharsets.UTF_8)));
        support.storeChunk(md5, request(3, 2, "c".getBytes(StandardCharsets.UTF_8)));
        assertTrue(support.allChunksUploaded(md5, 3));
    }

    // ===================== storeChunk 落盘布局 =====================

    @Test
    void storeChunk_writesUnderFileMd5SubDir() {
        String md5 = "md5layout";
        support.storeChunk(md5, request(2, 1, "x".getBytes(StandardCharsets.UTF_8)));
        File part = support.chunkDir(md5).resolve("part1").toFile();
        assertTrue(part.exists(), "分片应落在 <tmp>/<fileMD5>/part<index>");
    }

    // ===================== mergeChunks + MD5 =====================

    @Test
    void mergeChunks_outOfOrderChunks_mergesInIndexOrderWithCorrectContentAndMd5() throws Exception {
        String md5 = "md5merge";
        byte[] p0 = "Hello, ".getBytes(StandardCharsets.UTF_8);
        byte[] p1 = "chunked ".getBytes(StandardCharsets.UTF_8);
        byte[] p2 = "upload!".getBytes(StandardCharsets.UTF_8);
        // 乱序落盘：2 -> 0 -> 1
        support.storeChunk(md5, request(3, 2, p2));
        support.storeChunk(md5, request(3, 0, p0));
        support.storeChunk(md5, request(3, 1, p1));
        assertTrue(support.allChunksUploaded(md5, 3));

        File target = tempDir.resolve("merged.bin").toFile();
        String contentMd5 = support.mergeChunks(target, md5, 3);

        byte[] expected = new byte[p0.length + p1.length + p2.length];
        System.arraycopy(p0, 0, expected, 0, p0.length);
        System.arraycopy(p1, 0, expected, p0.length, p1.length);
        System.arraycopy(p2, 0, expected, p0.length + p1.length, p2.length);

        assertArrayEquals(expected, Files.readAllBytes(target.toPath()), "合并须按 index 顺序");
        // MD5 校验：内容 MD5 与独立计算一致（与原 god class bytesToHex 等价）
        assertEquals(DigestUtils.md5Hex(expected), contentMd5);
        // 合并后分片目录被清理
        assertFalse(support.chunkDir(md5).toFile().exists(), "合并完成后 chunkDir 应被清理");
    }

    @Test
    void mergeChunks_contentMd5MatchesDeclared_whenContentUnchanged() throws Exception {
        String md5 = "md5verify";
        byte[] full = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        // 单片
        support.storeChunk(md5, request(1, 0, full));
        File target = tempDir.resolve("verify.bin").toFile();
        String contentMd5 = support.mergeChunks(target, md5, 1);
        // 声明 MD5 == 内容 MD5 -> 一致（行为：上层据此存 mapping，不报错）
        String declared = DigestUtils.md5Hex(full);
        assertEquals(declared, contentMd5);
    }

    @Test
    void mergeChunks_tamperedChunk_yieldsDifferentMd5() throws Exception {
        // 模拟内容被篡改：实际落盘内容与“声明”不符时，重算 MD5 会与声明不同（上层据此可拒绝）
        String md5 = "md5tamper";
        byte[] declaredFull = "original-content".getBytes(StandardCharsets.UTF_8);
        String declared = DigestUtils.md5Hex(declaredFull);
        support.storeChunk(md5, request(1, 0, "tampered-content".getBytes(StandardCharsets.UTF_8)));
        File target = tempDir.resolve("tamper.bin").toFile();
        String contentMd5 = support.mergeChunks(target, md5, 1);
        org.junit.jupiter.api.Assertions.assertNotEquals(declared, contentMd5,
                "内容被篡改时重算 MD5 应与声明不一致");
    }

    @Test
    void mergeChunks_notAllPresent_throws() {
        String md5 = "md5incomplete";
        support.storeChunk(md5, request(3, 0, "a".getBytes(StandardCharsets.UTF_8)));
        // 只有 1/3 片
        File target = tempDir.resolve("incomplete.bin").toFile();
        assertThrows(ServiceException.class, () -> support.mergeChunks(target, md5, 3));
    }

    // ===================== bytesToHex =====================

    @Test
    void bytesToHex_knownVectors() {
        assertEquals("00", ChunkUploadSupport.bytesToHex(new byte[]{0x00}));
        assertEquals("ff", ChunkUploadSupport.bytesToHex(new byte[]{(byte) 0xFF}));
        assertEquals("0f10", ChunkUploadSupport.bytesToHex(new byte[]{0x0F, 0x10}));
        assertEquals("deadbeef",
                ChunkUploadSupport.bytesToHex(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}));
        // 与 commons-codec 实现一致（小写）
        byte[] digest = DigestUtils.md5("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(DigestUtils.md5Hex("abc"), ChunkUploadSupport.bytesToHex(digest));
    }

    // ===================== 续传探测（落盘分片集合）=====================

    @Test
    void resumeProbe_listsLandedChunkIndices() {
        String md5 = "md5resume";
        support.storeChunk(md5, request(4, 0, "0".getBytes(StandardCharsets.UTF_8)));
        support.storeChunk(md5, request(4, 2, "2".getBytes(StandardCharsets.UTF_8)));
        // 与 getUploadStatus 等价的扫描：chunkDir 下 part* 文件
        File chunkDir = support.chunkDir(md5).toFile();
        File[] parts = chunkDir.listFiles((dir, name) -> name.startsWith("part"));
        List<Integer> uploaded = new ArrayList<>();
        if (parts != null) {
            for (File p : parts) {
                uploaded.add(Integer.parseInt(p.getName().substring("part".length())));
            }
        }
        uploaded.sort(Integer::compareTo);
        assertEquals(List.of(0, 2), uploaded);
        assertFalse(support.allChunksUploaded(md5, 4));
    }

    // ===================== fileAddAfter 回调 =====================

    @Test
    void fileAddAfter_videoFile_enqueuesTranscodeNoPreview() throws Exception {
        File video = tempDir.resolve("clip.mp4").toFile();
        Files.write(video.toPath(), new byte[]{0x00, 0x00, 0x00, 0x18});
        support.fileAddAfter(video);
        verify(videoTranscodeService, times(1)).getOrCreateTranscodeStatus(video);
        verifyNoInteractions(previewService);
    }

    @Test
    void fileAddAfter_imageFile_generatesPreviewNoTranscode() throws Exception {
        // PNG 签名字节 -> probeContentType 多数平台识别为 image/png
        File image = tempDir.resolve("pic.png").toFile();
        byte[] pngSig = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        Files.write(image.toPath(), pngSig);
        // 仅当平台把它识别为 image 时才断言（避免环境差异导致脆弱）
        if ("image".equals(com.misu.fileServer.util.FileTypeUtils.getFileType(image))) {
            support.fileAddAfter(image);
            verify(previewService, times(1)).generatePreviewFile(image);
            verifyNoInteractions(videoTranscodeService);
        }
    }

    @Test
    void fileAddAfter_nonMediaFile_noCallbacks() throws Exception {
        File other = tempDir.resolve("data.bin").toFile();
        Files.write(other.toPath(), "just bytes".getBytes(StandardCharsets.UTF_8));
        support.fileAddAfter(other);
        verifyNoInteractions(previewService);
        verifyNoInteractions(videoTranscodeService);
    }
}
