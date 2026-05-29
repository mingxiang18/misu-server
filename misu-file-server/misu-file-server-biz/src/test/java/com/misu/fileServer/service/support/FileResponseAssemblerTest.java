package com.misu.fileServer.service.support;

import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.FileResponseDto;
import com.misu.fileServer.domain.dto.TrashFileResponseDto;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.VideoTranscodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FileResponseAssembler} 纯单元测试（JUnit5 + Mockito）。
 *
 * <p>策略：mock PreviewService / VideoTranscodeService / FileMappingRepository；
 * 路径计算用真实 {@link FilePathResolver} + {@link TempDir} 注入 fileServerPath，
 * 这样 toFileResponseDto 的 resolveMappedFile / packagePreviewLink 的 getPreviewFile 等
 * 路径逻辑都走真实实现，只把外部副作用（缩略图生成 / 转码状态查询 / DB 查询）打桩。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileResponseAssemblerTest {

    @TempDir
    Path tempDir;

    @Mock
    PreviewService previewService;

    @Mock
    VideoTranscodeService videoTranscodeService;

    @Mock
    FileMappingRepository fileMappingRepository;

    private FilePathResolver filePathResolver;
    private FileResponseAssembler assembler;

    private String rootPath;

    @BeforeEach
    void setUp() {
        rootPath = tempDir.toString() + File.separator;

        filePathResolver = new FilePathResolver();
        ReflectionTestUtils.setField(filePathResolver, "fileServerPath", rootPath);
        ReflectionTestUtils.setField(filePathResolver, "fileMappingRepository", fileMappingRepository);

        assembler = new FileResponseAssembler();
        ReflectionTestUtils.setField(assembler, "previewService", previewService);
        ReflectionTestUtils.setField(assembler, "videoTranscodeService", videoTranscodeService);
        ReflectionTestUtils.setField(assembler, "fileMappingRepository", fileMappingRepository);
        ReflectionTestUtils.setField(assembler, "filePathResolver", filePathResolver);
    }

    /** 在 storage 下落一个真实物理文件，返回它。 */
    private File createPhysicalFile(String name, String content) throws Exception {
        Path storage = tempDir.resolve("storage");
        Files.createDirectories(storage);
        Path file = storage.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file.toFile();
    }

    private FileMapping mapping(String fileName, String parentPath, String fileType,
                               Long fileSize, String targetPath) {
        FileMapping m = new FileMapping();
        m.setFileName(fileName);
        m.setParentPath(parentPath);
        m.setFileType(fileType);
        m.setFileSize(fileSize);
        m.setTargetPath(targetPath);
        return m;
    }

    // ===================== toFileResponseDto =====================

    @Test
    void toFileResponseDto_mapsAllFields() throws Exception {
        File physical = createPhysicalFile("a.txt", "hello");
        FileMapping m = mapping("a.txt", "dir/sub", FileType.TEXT_FILE, 5L, physical.getAbsolutePath());

        FileResponseDto dto = assembler.toFileResponseDto(m);

        assertEquals("a.txt", dto.getFileName());
        assertEquals(5L, dto.getFileSize());
        assertEquals(FileType.TEXT_FILE, dto.getFileType());
        // file 解析为 targetPath 的归一化绝对路径
        assertEquals(physical.toPath().toAbsolutePath().normalize().toFile(), dto.getFile());
        // 有 parentPath -> /dir/sub/
        assertEquals("/dir/sub/", dto.getFilePath());
        // fileId 总是有值（UUID）
        assertNotNull(dto.getFileId());
    }

    @Test
    void toFileResponseDto_blankParentPath_filePathIsRootSlash() throws Exception {
        File physical = createPhysicalFile("root.txt", "x");
        FileMapping m = mapping("root.txt", "", FileType.OTHER_FILE, 1L, physical.getAbsolutePath());

        FileResponseDto dto = assembler.toFileResponseDto(m);

        assertEquals("/", dto.getFilePath());
    }

    @Test
    void toFileResponseDto_nullParentPath_filePathIsRootSlash() throws Exception {
        File physical = createPhysicalFile("root2.txt", "x");
        FileMapping m = mapping("root2.txt", null, FileType.OTHER_FILE, 1L, physical.getAbsolutePath());

        FileResponseDto dto = assembler.toFileResponseDto(m);

        assertEquals("/", dto.getFilePath());
    }

    // ===================== packagePreviewLink =====================

    @Test
    void packagePreviewLink_imageWithExistingPreview_setsPreviewLink() throws Exception {
        // 源图片
        File origin = createPhysicalFile("pic.png", "imgbytes");
        // 预览文件需真实存在：由 FilePathResolver.getPreviewFile 派生路径后落盘
        File previewFile = filePathResolver.getPreviewFile(origin);
        Files.createDirectories(previewFile.getParentFile().toPath());
        Files.write(previewFile.toPath(), "preview".getBytes(StandardCharsets.UTF_8));

        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.IMAGE_FILE);
        dto.setFile(origin);
        dto.setFilePath("/album/");
        dto.setFileName("pic.png");

        assembler.packagePreviewLink(1, dto);

        assertNotNull(dto.getPreviewLink());
        assertTrue(dto.getPreviewLink().startsWith("fileServer/file/preview?openType=1"));
        assertTrue(dto.getPreviewLink().contains("filePath="));
        // 预览已存在 -> 不应触发生成
        verifyNoInteractions(previewService);
    }

    @Test
    void packagePreviewLink_imageWithoutPreview_enqueuesGenerationAndNoLink() throws Exception {
        File origin = createPhysicalFile("nopreview.jpg", "imgbytes");
        // 不创建 preview 文件 -> 走生成分支

        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.IMAGE_FILE);
        dto.setFile(origin);
        dto.setFilePath("/");
        dto.setFileName("nopreview.jpg");

        assembler.packagePreviewLink(0, dto);

        assertNull(dto.getPreviewLink());
        verify(previewService, times(1)).generatePreviewFile(origin);
    }

    @Test
    void packagePreviewLink_nonImage_doesNothing() throws Exception {
        File origin = createPhysicalFile("doc.txt", "x");
        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.TEXT_FILE);
        dto.setFile(origin);
        dto.setFilePath("/");
        dto.setFileName("doc.txt");

        assembler.packagePreviewLink(0, dto);

        assertNull(dto.getPreviewLink());
        verifyNoInteractions(previewService);
    }

    @Test
    void packagePreviewLink_openTypeReflectedInLink() throws Exception {
        File origin = createPhysicalFile("p2.png", "imgbytes");
        File previewFile = filePathResolver.getPreviewFile(origin);
        Files.createDirectories(previewFile.getParentFile().toPath());
        Files.write(previewFile.toPath(), "preview".getBytes(StandardCharsets.UTF_8));

        FileResponseDto dtoPrivate = new FileResponseDto();
        dtoPrivate.setFileType(FileType.IMAGE_FILE);
        dtoPrivate.setFile(origin);
        dtoPrivate.setFilePath("/");
        dtoPrivate.setFileName("p2.png");

        assembler.packagePreviewLink(0, dtoPrivate);
        assertTrue(dtoPrivate.getPreviewLink().contains("openType=0"));
    }

    // ===================== packageVideoTranscodeInfo =====================

    private VideoTranscodeStatusDto status(String state, Integer progress, String message) {
        VideoTranscodeStatusDto s = new VideoTranscodeStatusDto();
        s.setState(state);
        s.setProgress(progress);
        s.setMessage(message);
        return s;
    }

    @Test
    void packageVideoTranscodeInfo_nonVideo_doesNothing() throws Exception {
        File origin = createPhysicalFile("notvideo.txt", "x");
        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.TEXT_FILE);
        dto.setFile(origin);
        dto.setFilePath("/");
        dto.setFileName("notvideo.txt");

        assembler.packageVideoTranscodeInfo(0, "42", dto);

        assertNull(dto.getTranscodeState());
        verifyNoInteractions(videoTranscodeService);
    }

    @Test
    void packageVideoTranscodeInfo_processing_setsStatusNoTranscodedLink() throws Exception {
        File origin = createPhysicalFile("v.mp4", "video");
        when(videoTranscodeService.getOrCreateTranscodeStatus(eq(origin), eq(0), eq("42"), anyString()))
                .thenReturn(status(VideoTranscodeState.PROCESSING, 30, "转码中"));
        when(videoTranscodeService.getMaxBytes()).thenReturn(123L);
        // 预览封面文件不存在
        File missingPreview = tempDir.resolve("video-preview-missing.jpg").toFile();
        when(videoTranscodeService.getVideoPreviewFile(origin)).thenReturn(missingPreview);

        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.VIDEO_FILE);
        dto.setFile(origin);
        dto.setFilePath("/movies/");
        dto.setFileName("v.mp4");

        assembler.packageVideoTranscodeInfo(0, "42", dto);

        assertEquals(VideoTranscodeState.PROCESSING, dto.getTranscodeState());
        assertEquals(30, dto.getTranscodeProgress());
        assertEquals("转码中", dto.getTranscodeMessage());
        assertEquals(123L, dto.getTranscodeMaxBytes());
        assertNull(dto.getVideoPreviewLink());
        assertNull(dto.getTranscodedStreamLink());
    }

    @Test
    void packageVideoTranscodeInfo_successWithPreview_setsAllLinks() throws Exception {
        File origin = createPhysicalFile("ok.mp4", "video");
        when(videoTranscodeService.getOrCreateTranscodeStatus(eq(origin), eq(1), eq("public"), anyString()))
                .thenReturn(status(VideoTranscodeState.SUCCESS, 100, null));
        when(videoTranscodeService.getMaxBytes()).thenReturn(999L);
        // 预览封面存在
        File preview = createPhysicalFile("cover.jpg", "cover");
        when(videoTranscodeService.getVideoPreviewFile(origin)).thenReturn(preview);

        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.VIDEO_FILE);
        dto.setFile(origin);
        dto.setFilePath("/");
        dto.setFileName("ok.mp4");

        assembler.packageVideoTranscodeInfo(1, "public", dto);

        assertEquals(VideoTranscodeState.SUCCESS, dto.getTranscodeState());
        assertNotNull(dto.getVideoPreviewLink());
        assertTrue(dto.getVideoPreviewLink().startsWith("fileServer/file/videoPreview?openType=1"));
        assertNotNull(dto.getTranscodedStreamLink());
        assertTrue(dto.getTranscodedStreamLink().startsWith("fileServer/file/transcodedVideo?openType=1"));
    }

    @Test
    void packageVideoTranscodeInfo_passthrough_setsTranscodedLink() throws Exception {
        File origin = createPhysicalFile("pass.mp4", "video");
        when(videoTranscodeService.getOrCreateTranscodeStatus(eq(origin), eq(0), eq("7"), anyString()))
                .thenReturn(status(VideoTranscodeState.PASSTHROUGH, 100, null));
        when(videoTranscodeService.getMaxBytes()).thenReturn(1L);
        File missingPreview = tempDir.resolve("nocover.jpg").toFile();
        when(videoTranscodeService.getVideoPreviewFile(origin)).thenReturn(missingPreview);

        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.VIDEO_FILE);
        dto.setFile(origin);
        dto.setFilePath("/");
        dto.setFileName("pass.mp4");

        assembler.packageVideoTranscodeInfo(0, "7", dto);

        assertEquals(VideoTranscodeState.PASSTHROUGH, dto.getTranscodeState());
        assertNull(dto.getVideoPreviewLink());
        assertNotNull(dto.getTranscodedStreamLink());
    }

    @Test
    void packageVideoTranscodeInfo_failed_noTranscodedLink() throws Exception {
        File origin = createPhysicalFile("fail.mp4", "video");
        when(videoTranscodeService.getOrCreateTranscodeStatus(eq(origin), eq(0), eq("7"), anyString()))
                .thenReturn(status(VideoTranscodeState.FAILED, 0, "失败"));
        when(videoTranscodeService.getMaxBytes()).thenReturn(1L);
        File missingPreview = tempDir.resolve("nocover2.jpg").toFile();
        when(videoTranscodeService.getVideoPreviewFile(origin)).thenReturn(missingPreview);

        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.VIDEO_FILE);
        dto.setFile(origin);
        dto.setFilePath("/");
        dto.setFileName("fail.mp4");

        assembler.packageVideoTranscodeInfo(0, "7", dto);

        assertEquals(VideoTranscodeState.FAILED, dto.getTranscodeState());
        assertNull(dto.getTranscodedStreamLink());
    }

    @Test
    void packageVideoTranscodeInfo_virtualPathStripsLeadingSlash() throws Exception {
        File origin = createPhysicalFile("vp.mp4", "video");
        when(videoTranscodeService.getOrCreateTranscodeStatus(eq(origin), eq(0), eq("7"), eq("dir/vp.mp4")))
                .thenReturn(status(VideoTranscodeState.WAITING, 0, null));
        when(videoTranscodeService.getMaxBytes()).thenReturn(1L);
        File missingPreview = tempDir.resolve("nocover3.jpg").toFile();
        when(videoTranscodeService.getVideoPreviewFile(origin)).thenReturn(missingPreview);

        FileResponseDto dto = new FileResponseDto();
        dto.setFileType(FileType.VIDEO_FILE);
        dto.setFile(origin);
        dto.setFilePath("/dir/");
        dto.setFileName("vp.mp4");

        assembler.packageVideoTranscodeInfo(0, "7", dto);

        // 由 eq("dir/vp.mp4") 的 stub 命中即验证了去前导斜杠逻辑
        assertEquals(VideoTranscodeState.WAITING, dto.getTranscodeState());
    }

    // ===================== getFileListFromDirectory =====================

    @Test
    void getFileListFromDirectory_mapsExistingFilesToDtoList() throws Exception {
        File f1 = createPhysicalFile("one.txt", "a");
        File f2 = createPhysicalFile("two.txt", "bb");
        FileMapping m1 = mapping("one.txt", "dir", FileType.TEXT_FILE, 1L, f1.getAbsolutePath());
        FileMapping m2 = mapping("two.txt", "dir", FileType.TEXT_FILE, 2L, f2.getAbsolutePath());

        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(
                        eq(0), eq("42"), eq("dir")))
                .thenReturn(Arrays.asList(m1, m2));

        FileRequestDto req = new FileRequestDto("/dir", 0);
        List<FileResponseDto> list = assembler.getFileListFromDirectory(req, "42");

        assertEquals(2, list.size());
        // 顺序保持 repository 返回顺序
        assertEquals("one.txt", list.get(0).getFileName());
        assertEquals("two.txt", list.get(1).getFileName());
    }

    @Test
    void getFileListFromDirectory_filtersOutMissingPhysicalFiles() throws Exception {
        File present = createPhysicalFile("present.txt", "a");
        FileMapping mPresent = mapping("present.txt", "", FileType.TEXT_FILE, 1L, present.getAbsolutePath());
        FileMapping mMissing = mapping("ghost.txt", "", FileType.TEXT_FILE, 1L,
                tempDir.resolve("storage").resolve("ghost.txt").toString());

        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(
                        anyInt(), anyString(), anyString()))
                .thenReturn(Arrays.asList(mPresent, mMissing));

        FileRequestDto req = new FileRequestDto("/", 0);
        List<FileResponseDto> list = assembler.getFileListFromDirectory(req, "42");

        assertEquals(1, list.size());
        assertEquals("present.txt", list.get(0).getFileName());
    }

    @Test
    void getFileListFromDirectory_empty_returnsEmptyList() {
        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(
                        anyInt(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        FileRequestDto req = new FileRequestDto("/", 0);
        List<FileResponseDto> list = assembler.getFileListFromDirectory(req, "42");

        assertTrue(list.isEmpty());
    }

    // ===================== toTrashResponseDto =====================

    @Test
    void toTrashResponseDto_mapsFieldsAndUsesUpdateTimeAsDeleteTime() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 10, 0);
        LocalDateTime updated = LocalDateTime.of(2026, 2, 2, 12, 0);
        FileMapping m = mapping("del.txt", "trash/dir", FileType.OTHER_FILE, 9L, "/x/y");
        m.setId(77L);
        m.setVirtualPath("trash/dir/del.txt");
        m.setCreateTime(created);
        m.setUpdateTime(updated);

        TrashFileResponseDto dto = assembler.toTrashResponseDto(m);

        assertEquals(77L, dto.getId());
        assertEquals("del.txt", dto.getFileName());
        assertEquals(FileType.OTHER_FILE, dto.getFileType());
        assertEquals(9L, dto.getFileSize());
        assertEquals("trash/dir/del.txt", dto.getOriginalPath());
        assertEquals("trash/dir", dto.getOriginalParentPath());
        // updateTime 非空 -> deleteTime = updateTime
        assertEquals(updated, dto.getDeleteTime());
    }

    @Test
    void toTrashResponseDto_nullUpdateTime_fallsBackToCreateTime() {
        LocalDateTime created = LocalDateTime.of(2026, 3, 3, 8, 0);
        FileMapping m = mapping("d2.txt", "", FileType.OTHER_FILE, 1L, "/x");
        m.setId(1L);
        m.setVirtualPath("d2.txt");
        m.setCreateTime(created);
        m.setUpdateTime(null);

        TrashFileResponseDto dto = assembler.toTrashResponseDto(m);

        assertEquals(created, dto.getDeleteTime());
    }

    // ===================== createUserFileAccessLink =====================

    @Test
    void createUserFileAccessLink_buildsEncodedQueryString() {
        String link = assembler.createUserFileAccessLink("/download", "/dir/带空格 文件.txt", 0);

        assertTrue(link.startsWith("fileServer/file/download?openType=0&filePath="));
        // 空格被 URL 编码（URLEncoder 编码为 +）
        assertTrue(link.contains("+") || link.contains("%20"));
        // 中文被编码
        assertTrue(link.contains("%"));
    }

    @Test
    void createUserFileAccessLink_neverTouchesPreviewOrTranscodeServices() {
        assembler.createUserFileAccessLink("/stream", "a.txt", 1);
        verify(previewService, never()).generatePreviewFile(any());
        verify(videoTranscodeService, never()).getMaxBytes();
    }
}
