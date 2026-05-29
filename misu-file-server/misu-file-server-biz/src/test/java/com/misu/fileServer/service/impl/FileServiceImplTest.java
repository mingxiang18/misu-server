package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.dto.AddFileInkRequest;
import com.misu.fileServer.domain.dto.BatchFileRequestDto;
import com.misu.fileServer.domain.dto.BatchOperationResultDto;
import com.misu.fileServer.domain.dto.FileRenameRequestDto;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.FileResponseDto;
import com.misu.fileServer.domain.dto.FileUploadRequest;
import com.misu.fileServer.domain.dto.FileUploadResponse;
import com.misu.fileServer.domain.dto.HashUploadCheckRequestDto;
import com.misu.fileServer.domain.dto.HashUploadCheckResponseDto;
import com.misu.fileServer.domain.dto.PageResponseDto;
import com.misu.fileServer.domain.dto.SearchFileRequestDto;
import com.misu.fileServer.domain.dto.SharePrivateFileToPublicRequestDto;
import com.misu.fileServer.domain.dto.StorageUsageResponseDto;
import com.misu.fileServer.domain.dto.UploadStatusResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.UploadConcurrencyGuard;
import com.misu.fileServer.service.support.ChunkUploadSupport;
import com.misu.fileServer.service.support.FileAuthorityChecker;
import com.misu.fileServer.service.support.FileMappingManager;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.FileResponseAssembler;
import com.misu.fileServer.service.support.PhysicalFileOps;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileServiceImpl} 收尾后的编排层边界单测（纯 JUnit5 + Mockito + {@link TempDir}）。
 *
 * <p>FileServiceImpl 现在只剩「用户写操作 + 查询 + 上传」并委托给已抽出的协作组件
 * （{@link FilePathResolver} / {@link FileMappingManager} / {@link FileResponseAssembler} /
 * {@link ChunkUploadSupport} / {@link PhysicalFileOps} / {@link FileAuthorityChecker} 等）。
 * 这里 {@code @Mock} 全部协作组件，用 {@link ReflectionTestUtils} 注入；登录态用
 * {@link SecurityContextHolder} 塞 {@link LoginUser}（{@code LoginMessageUtil} 静态读它）。</p>
 *
 * <p>权限 403：{@code FileAuthorityChecker.checkPublicWriteAuthority} 是被 mock 的，直接 stub 它抛
 * {@link HttpStatus#FORBIDDEN} 来模拟非管理员写公共域，无需碰真实 SecurityContext 权限列表。</p>
 *
 * <p>覆盖全部 13 个 public 方法（与 {@code FileService} 接口一致）：getFileList / uploadFile /
 * addFileInk / createDirectory / moveFile / sharePrivateFileToPublic / deleteFile / searchFiles /
 * batchDelete / batchMove / getStorageUsage / getUploadStatus / checkUploadByHash。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    private static final Long ME = 42L;
    private static final String ME_STR = ME.toString();

    @Mock
    FileMappingRepository fileMappingRepository;
    @Mock
    com.misu.fileServer.util.UploadExtensionGuard uploadExtensionGuard;
    @Mock
    FileVersionService fileVersionService;
    @Mock
    FilePathResolver filePathResolver;
    @Mock
    FileAuthorityChecker fileAuthorityChecker;
    @Mock
    PhysicalFileOps physicalFileOps;
    @Mock
    FileMappingManager fileMappingManager;
    @Mock
    FileResponseAssembler fileResponseAssembler;
    @Mock
    ChunkUploadSupport chunkUploadSupport;

    // 真实并发限流器：acquire() 返回真正的 Releaser（其构造器 private，不易 mock），逻辑无副作用。
    UploadConcurrencyGuard uploadConcurrencyGuard = new UploadConcurrencyGuard(8, 0);

    FileServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileServiceImpl();
        inject("fileMappingRepository", fileMappingRepository);
        inject("uploadExtensionGuard", uploadExtensionGuard);
        inject("uploadConcurrencyGuard", uploadConcurrencyGuard);
        inject("fileVersionService", fileVersionService);
        inject("filePathResolver", filePathResolver);
        inject("fileAuthorityChecker", fileAuthorityChecker);
        inject("physicalFileOps", physicalFileOps);
        inject("fileMappingManager", fileMappingManager);
        inject("fileResponseAssembler", fileResponseAssembler);
        inject("chunkUploadSupport", chunkUploadSupport);
        inject("privateQuotaBytesPerUser", -1L);
    }

    private void inject(String field, Object value) {
        ReflectionTestUtils.setField(service, field, value);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        LoginUser loginUser = new LoginUser(userId, "u" + userId, List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private void logout() {
        SecurityContextHolder.clearContext();
    }

    /** 模拟 FileAuthorityChecker 对公共域非管理员写操作抛 403。 */
    private void stubPublicWriteForbidden(int openType) {
        doThrow(new ServiceException(HttpStatus.FORBIDDEN, "当前用户无权修改公共文件"))
                .when(fileAuthorityChecker).checkPublicWriteAuthority(openType);
    }

    private FileMapping mapping(Integer openType, String userId, String virtualPath, String fileType) {
        FileMapping m = new FileMapping();
        m.setOpenType(openType);
        m.setUserId(userId);
        m.setVirtualPath(virtualPath);
        m.setFileType(fileType);
        return m;
    }

    // ===================================================================
    // getFileList
    // ===================================================================

    @Test
    void getFileList_private_assemblesLinksAndStripsFile() {
        loginAs(ME);
        FileRequestDto req = new FileRequestDto("/dir/", 0);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        FileResponseDto dto = new FileResponseDto();
        dto.setFileName("a.txt");
        dto.setFile(new File("/tmp/whatever"));
        when(fileResponseAssembler.getFileListFromDirectory(req, ME_STR)).thenReturn(new ArrayList<>(List.of(dto)));
        when(fileResponseAssembler.createUserFileAccessLink(eq("/download"), anyString(), eq(0))).thenReturn("DL");
        when(fileResponseAssembler.createUserFileAccessLink(eq("/stream"), anyString(), eq(0))).thenReturn("ST");

        List<FileResponseDto> result = service.getFileList(req);

        assertEquals(1, result.size());
        FileResponseDto out = result.get(0);
        assertEquals("DL", out.getDownloadLink());
        assertEquals("ST", out.getStreamLink());
        assertNull(out.getFile(), "file 句柄应被清空，不下发给前端");
        // 委托到组装器封装预览 / 转码信息
        verify(fileResponseAssembler).packagePreviewLink(0, out);
        verify(fileResponseAssembler).packageVideoTranscodeInfo(0, ME_STR, out);
        // 下载 / 流式链接拼接用的是 filePath + fileName
        verify(fileResponseAssembler).createUserFileAccessLink("/download", "/dir/a.txt", 0);
        verify(fileResponseAssembler).createUserFileAccessLink("/stream", "/dir/a.txt", 0);
    }

    @Test
    void getFileList_public_usesPublicMappingUserId() {
        loginAs(ME);
        FileRequestDto req = new FileRequestDto("/", 1);
        when(filePathResolver.getMappingUserId(1, ME_STR)).thenReturn("public");
        when(fileResponseAssembler.getFileListFromDirectory(req, "public")).thenReturn(new ArrayList<>());

        List<FileResponseDto> result = service.getFileList(req);

        assertTrue(result.isEmpty());
        verify(fileResponseAssembler).getFileListFromDirectory(req, "public");
    }

    @Test
    void getFileList_notLoggedIn_unauthorized() {
        logout();
        FileRequestDto req = new FileRequestDto("/", 0);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getFileList(req));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getCode());
        verify(fileResponseAssembler, never()).getFileListFromDirectory(any(), anyString());
    }

    // ===================================================================
    // searchFiles
    // ===================================================================

    @Test
    void searchFiles_byKeyword_pagedAndAssembled() {
        loginAs(ME);
        SearchFileRequestDto req = new SearchFileRequestDto();
        req.setOpenType(0);
        req.setKeyword("  report  ");
        req.setPageNumber(2);
        req.setPageSize(10);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);

        FileMapping m = mapping(0, ME_STR, "dir/report.txt", FileType.TEXT_FILE);
        Page<FileMapping> page = new PageImpl<>(List.of(m), PageRequest.of(1, 10), 21);
        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndDeletedFalseAndFileNameContainingIgnoreCaseOrderByFileTypeDescFileNameAsc(
                        eq(0), eq(ME_STR), eq("report"), any(Pageable.class)))
                .thenReturn(page);

        FileResponseDto dto = new FileResponseDto();
        dto.setFileName("report.txt");
        dto.setFilePath("/dir/");
        dto.setFile(new File("/tmp"));
        // toFileResponseDto 后 file 必须存在才保留 —— 用真实存在的文件
        when(fileResponseAssembler.toFileResponseDto(m)).thenAnswer(inv -> {
            FileResponseDto d = new FileResponseDto();
            d.setFileName("report.txt");
            d.setFilePath("/dir/");
            d.setFile(existingTmpFile());
            return d;
        });
        when(fileResponseAssembler.createUserFileAccessLink(anyString(), anyString(), eq(0))).thenReturn("L");

        PageResponseDto<FileResponseDto> result = service.searchFiles(req);

        assertEquals(21L, result.getTotal());
        assertEquals(1, result.getItems().size());
        assertEquals(2, result.getPageNumber());
        assertEquals(10, result.getPageSize());
        // 关键词被 trim
        verify(fileMappingRepository)
                .findByOpenTypeAndUserIdAndDeletedFalseAndFileNameContainingIgnoreCaseOrderByFileTypeDescFileNameAsc(
                        eq(0), eq(ME_STR), eq("report"), any(Pageable.class));
        // 分页索引 = pageNumber - 1
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(fileMappingRepository)
                .findByOpenTypeAndUserIdAndDeletedFalseAndFileNameContainingIgnoreCaseOrderByFileTypeDescFileNameAsc(
                        eq(0), eq(ME_STR), eq("report"), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(10, captor.getValue().getPageSize());
        // file 句柄被清空
        assertNull(result.getItems().get(0).getFile());
    }

    @Test
    void searchFiles_withFileType_usesTypedQuery() {
        loginAs(ME);
        SearchFileRequestDto req = new SearchFileRequestDto();
        req.setOpenType(0);
        req.setKeyword("vid");
        req.setFileType(FileType.VIDEO_FILE);
        req.setPageNumber(1);
        req.setPageSize(50);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndDeletedFalseAndFileTypeAndFileNameContainingIgnoreCaseOrderByFileNameAsc(
                        eq(0), eq(ME_STR), eq(FileType.VIDEO_FILE), eq("vid"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0));

        PageResponseDto<FileResponseDto> result = service.searchFiles(req);

        assertTrue(result.getItems().isEmpty());
        verify(fileMappingRepository)
                .findByOpenTypeAndUserIdAndDeletedFalseAndFileTypeAndFileNameContainingIgnoreCaseOrderByFileNameAsc(
                        eq(0), eq(ME_STR), eq(FileType.VIDEO_FILE), eq("vid"), any(Pageable.class));
    }

    @Test
    void searchFiles_filtersOutMissingPhysicalFiles() {
        loginAs(ME);
        SearchFileRequestDto req = new SearchFileRequestDto();
        req.setOpenType(0);
        req.setKeyword("x");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        FileMapping m = mapping(0, ME_STR, "dir/gone.txt", FileType.TEXT_FILE);
        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndDeletedFalseAndFileNameContainingIgnoreCaseOrderByFileTypeDescFileNameAsc(
                        eq(0), eq(ME_STR), eq("x"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(m), PageRequest.of(0, 50), 1));
        FileResponseDto dto = new FileResponseDto();
        dto.setFile(new File("/definitely/not/here.txt")); // 物理不存在 -> 被过滤
        when(fileResponseAssembler.toFileResponseDto(m)).thenReturn(dto);

        PageResponseDto<FileResponseDto> result = service.searchFiles(req);

        // total 来自仓库（1），但 items 被物理存在性过滤为空
        assertEquals(1L, result.getTotal());
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    void searchFiles_notLoggedIn_unauthorized() {
        logout();
        SearchFileRequestDto req = new SearchFileRequestDto();
        req.setOpenType(0);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.searchFiles(req));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getCode());
    }

    // ===================================================================
    // uploadFile
    // ===================================================================

    private FileUploadRequest uploadReq(int openType, String fileName, String filePath,
                                        int totalChunks, int chunkIndex, boolean cover) {
        FileUploadRequest r = new FileUploadRequest();
        r.setOpenType(openType);
        r.setFileName(fileName);
        r.setFilePath(filePath);
        r.setTotalChunks(totalChunks);
        r.setChunkIndex(chunkIndex);
        r.setCoverFlag(cover);
        return r;
    }

    @Test
    void uploadFile_publicNonAdmin_forbiddenBeforeAnyWork() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        FileUploadRequest req = uploadReq(1, "a.txt", "/", 1, 0, false);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.uploadFile(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(chunkUploadSupport, never()).storeChunk(anyString(), any());
    }

    @Test
    void uploadFile_existingAndNoCover_returnsAlreadyExists(@TempDir Path tmp) {
        loginAs(ME);
        FileUploadRequest req = uploadReq(0, "a.txt", "dir", 1, 0, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.of(mapping(0, ME_STR, "dir/a.txt", FileType.TEXT_FILE)));

        FileUploadResponse resp = service.uploadFile(req);

        assertEquals(2, resp.getUploadState());
        // 不覆盖且已存在 -> 不落分片
        verify(chunkUploadSupport, never()).storeChunk(anyString(), any());
        verify(filePathResolver, never()).buildUploadStorageFile(anyInt(), anyString(), anyString());
    }

    @Test
    void uploadFile_chunkNotAllUploaded_storesChunkButNoMerge(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        FileUploadRequest req = uploadReq(0, "a.txt", "dir", 3, 0, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.empty());
        File storage = tmp.resolve("storage").resolve("blob.bin").toFile();
        when(filePathResolver.buildUploadStorageFile(0, ME_STR, "a.txt")).thenReturn(storage);
        when(chunkUploadSupport.allChunksUploaded(anyString(), eq(3))).thenReturn(false);

        FileUploadResponse resp = service.uploadFile(req);

        assertEquals(1, resp.getUploadState());
        verify(chunkUploadSupport).storeChunk(anyString(), eq(req));
        // 未到齐：不合并、不落 mapping
        verify(chunkUploadSupport, never()).mergeChunks(any(), anyString(), anyInt());
        verify(fileMappingManager, never()).saveOrUpdateFileMapping(anyInt(), anyString(), anyString(),
                anyString(), anyString(), any(File.class), anyString());
        // 扩展名安全校验被调用
        verify(uploadExtensionGuard).requireSafeForUpload("a.txt");
    }

    @Test
    void uploadFile_allChunksUploaded_mergesAndSavesMapping(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        FileUploadRequest req = uploadReq(0, "a.txt", "dir", 2, 1, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.empty());
        File storage = tmp.resolve("storage").resolve("blob.bin").toFile();
        when(filePathResolver.buildUploadStorageFile(0, ME_STR, "a.txt")).thenReturn(storage);
        when(chunkUploadSupport.allChunksUploaded(anyString(), eq(2))).thenReturn(true);
        when(chunkUploadSupport.mergeChunks(eq(storage), anyString(), eq(2))).thenReturn("contentmd5");

        FileUploadResponse resp = service.uploadFile(req);

        assertEquals(1, resp.getUploadState());
        verify(chunkUploadSupport).storeChunk(anyString(), eq(req));
        verify(chunkUploadSupport).mergeChunks(eq(storage), anyString(), eq(2));
        verify(chunkUploadSupport).fileAddAfter(storage);
        // 落 mapping，带 contentMd5
        verify(fileMappingManager).saveOrUpdateFileMapping(0, ME_STR, "dir/a.txt", "dir", "a.txt", storage, "contentmd5");
        // 无旧 mapping -> 不打快照
        verify(fileVersionService, never()).snapshotIfEligible(any(), anyString());
    }

    @Test
    void uploadFile_overwriteExisting_snapshotsOldThenSaves(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        FileUploadRequest req = uploadReq(0, "a.txt", "dir", 1, 0, true); // coverFlag = true
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        FileMapping old = mapping(0, ME_STR, "dir/a.txt", FileType.TEXT_FILE);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.of(old));
        File storage = tmp.resolve("storage").resolve("blob.bin").toFile();
        when(filePathResolver.buildUploadStorageFile(0, ME_STR, "a.txt")).thenReturn(storage);
        when(chunkUploadSupport.allChunksUploaded(anyString(), eq(1))).thenReturn(true);
        when(chunkUploadSupport.mergeChunks(eq(storage), anyString(), eq(1))).thenReturn("md5x");

        FileUploadResponse resp = service.uploadFile(req);

        assertEquals(1, resp.getUploadState());
        // 覆盖场景：先给旧 mapping 打 OVERWRITE 快照
        verify(fileVersionService).snapshotIfEligible(old, "OVERWRITE");
        verify(fileMappingManager).saveOrUpdateFileMapping(0, ME_STR, "dir/a.txt", "dir", "a.txt", storage, "md5x");
    }

    @Test
    void uploadFile_mergeThrows_cleansUpAndWrapsError(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        FileUploadRequest req = uploadReq(0, "a.txt", "dir", 1, 0, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.empty());
        File storage = tmp.resolve("storage").resolve("blob.bin").toFile();
        when(filePathResolver.buildUploadStorageFile(0, ME_STR, "a.txt")).thenReturn(storage);
        when(chunkUploadSupport.allChunksUploaded(anyString(), eq(1))).thenReturn(true);
        when(chunkUploadSupport.mergeChunks(any(), anyString(), anyInt())).thenThrow(new RuntimeException("boom"));
        File chunkDir = tmp.resolve("chunkdir").toFile();
        chunkDir.mkdirs();
        when(chunkUploadSupport.chunkDir(anyString())).thenReturn(chunkDir.toPath());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.uploadFile(req));
        assertEquals(HttpStatus.ERROR, ex.getCode());
        // 非 ServiceException 异常被包装为 ERROR 并清理分片目录
        verify(physicalFileOps).deletePhysicalRecursively(chunkDir);
        verify(fileMappingManager, never()).saveOrUpdateFileMapping(anyInt(), anyString(), anyString(),
                anyString(), anyString(), any(File.class), anyString());
    }

    @Test
    void uploadFile_serviceExceptionDuringStore_propagatedNotWrapped(@TempDir Path tmp) {
        loginAs(ME);
        FileUploadRequest req = uploadReq(0, "a.txt", "dir", 1, 0, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.empty());
        File storage = tmp.resolve("storage").resolve("blob.bin").toFile();
        when(filePathResolver.buildUploadStorageFile(0, ME_STR, "a.txt")).thenReturn(storage);
        doThrow(new ServiceException(HttpStatus.BAD_REQUEST, "bad chunk"))
                .when(chunkUploadSupport).storeChunk(anyString(), eq(req));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.uploadFile(req));
        // ServiceException 直接透传，保留原始 code
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    // ===================================================================
    // addFileInk
    // ===================================================================

    @Test
    void addFileInk_privateMissingUserId_badRequest() {
        AddFileInkRequest req = new AddFileInkRequest();
        req.setOpenType(0);
        req.setUserId(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.addFileInk(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void addFileInk_sourceMissing_badRequest(@TempDir Path tmp) {
        AddFileInkRequest req = new AddFileInkRequest();
        req.setOpenType(0);
        req.setUserId(ME_STR);
        req.setFilePath("dir");
        req.setFileName("a.txt");
        req.setInkFilePath(tmp.resolve("does-not-exist.bin").toString());
        when(filePathResolver.getUserRootDirectory(0, ME_STR)).thenReturn(tmp.toString() + "/");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "dir/a.txt")).thenReturn(false);
        when(fileMappingManager.hasMappingParentConflict(0, ME_STR, "dir/a.txt")).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.addFileInk(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).mapPhysicalTreeToVirtualPaths(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void addFileInk_nameConflict_badRequest(@TempDir Path tmp) {
        AddFileInkRequest req = new AddFileInkRequest();
        req.setOpenType(0);
        req.setUserId(ME_STR);
        req.setFilePath("dir");
        req.setFileName("a.txt");
        req.setInkFilePath(tmp.resolve("src.bin").toString());
        when(filePathResolver.getUserRootDirectory(0, ME_STR)).thenReturn(tmp.toString() + "/");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "dir/a.txt")).thenReturn(true);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.addFileInk(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).mapPhysicalTreeToVirtualPaths(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void addFileInk_happyPath_mapsTree(@TempDir Path tmp) throws Exception {
        AddFileInkRequest req = new AddFileInkRequest();
        req.setOpenType(0);
        req.setUserId(ME_STR);
        req.setFilePath("dir");
        req.setFileName("a.txt");
        File ink = tmp.resolve("src.bin").toFile();
        Files.write(ink.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        req.setInkFilePath(ink.getAbsolutePath());
        when(filePathResolver.getUserRootDirectory(0, ME_STR)).thenReturn(tmp.toString() + "/");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "dir/a.txt")).thenReturn(false);
        when(fileMappingManager.hasMappingParentConflict(0, ME_STR, "dir/a.txt")).thenReturn(false);

        service.addFileInk(req);

        verify(fileMappingManager).mapPhysicalTreeToVirtualPaths(eq(0), eq(ME_STR), eq("dir/a.txt"), eq(ink));
    }

    // ===================================================================
    // createDirectory
    // ===================================================================

    @Test
    void createDirectory_publicNonAdmin_forbidden() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        FileRequestDto req = new FileRequestDto("/newdir", 1);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.createDirectory(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMappingManager, never()).saveOrUpdateFileMapping(anyInt(), anyString(), anyString(),
                anyString(), anyString(), any(File.class));
    }

    @Test
    void createDirectory_conflict_badRequest(@TempDir Path tmp) {
        loginAs(ME);
        FileRequestDto req = new FileRequestDto("/newdir", 0);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        File dirStorage = tmp.resolve("vdir").toFile();
        when(filePathResolver.buildVirtualDirectoryStorage(eq(0), eq(ME_STR), eq("newdir"))).thenReturn(dirStorage);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "newdir"))
                .thenReturn(Optional.of(mapping(0, ME_STR, "newdir", FileType.DIRECTORY_FILE)));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.createDirectory(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).saveOrUpdateFileMapping(anyInt(), anyString(), anyString(),
                anyString(), anyString(), any(File.class));
    }

    @Test
    void createDirectory_happyPath_createsAndSaves(@TempDir Path tmp) {
        loginAs(ME);
        FileRequestDto req = new FileRequestDto("/parent/newdir", 0);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(filePathResolver.getParentPath("parent/newdir")).thenReturn("parent");
        File dirStorage = tmp.resolve("vdir-xyz").toFile();
        when(filePathResolver.buildVirtualDirectoryStorage(eq(0), eq(ME_STR), eq("newdir"))).thenReturn(dirStorage);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "parent/newdir"))
                .thenReturn(Optional.empty());

        Boolean ok = service.createDirectory(req);

        assertTrue(ok);
        assertTrue(dirStorage.exists() && dirStorage.isDirectory());
        verify(fileMappingManager).saveOrUpdateFileMapping(0, ME_STR, "parent/newdir", "parent", "newdir", dirStorage);
    }

    // ===================================================================
    // moveFile
    // ===================================================================

    @Test
    void moveFile_publicNonAdmin_forbidden() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        FileRenameRequestDto req = new FileRenameRequestDto();
        req.setOpenType(1);
        req.setOriginFilePath("/a.txt");
        req.setNewFilePath("/b.txt");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.moveFile(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMappingManager, never()).moveFileMappingTree(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void moveFile_samePath_noOp() {
        loginAs(ME);
        FileRenameRequestDto req = new FileRenameRequestDto();
        req.setOpenType(0);
        req.setOriginFilePath("/dir/a.txt");
        req.setNewFilePath("/dir/a.txt");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);

        service.moveFile(req);

        verify(fileMappingManager, never()).moveFileMappingTree(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void moveFile_intoOwnSubdir_badRequest() {
        loginAs(ME);
        FileRenameRequestDto req = new FileRenameRequestDto();
        req.setOpenType(0);
        req.setOriginFilePath("/dir");
        req.setNewFilePath("/dir/sub");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.moveFile(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).moveFileMappingTree(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void moveFile_sourceMissing_badRequest() {
        loginAs(ME);
        FileRenameRequestDto req = new FileRenameRequestDto();
        req.setOpenType(0);
        req.setOriginFilePath("/a.txt");
        req.setNewFilePath("/b.txt");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "a.txt")).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.moveFile(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void moveFile_targetExists_badRequest() {
        loginAs(ME);
        FileRenameRequestDto req = new FileRenameRequestDto();
        req.setOpenType(0);
        req.setOriginFilePath("/a.txt");
        req.setNewFilePath("/b.txt");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "a.txt")).thenReturn(true);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "b.txt"))
                .thenReturn(Optional.of(mapping(0, ME_STR, "b.txt", FileType.TEXT_FILE)));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.moveFile(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).moveFileMappingTree(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void moveFile_destinationConflict_badRequest() {
        loginAs(ME);
        FileRenameRequestDto req = new FileRenameRequestDto();
        req.setOpenType(0);
        req.setOriginFilePath("/a.txt");
        req.setNewFilePath("/b.txt");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "a.txt")).thenReturn(true);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "b.txt"))
                .thenReturn(Optional.empty());
        when(fileMappingManager.hasMoveDestinationConflict(0, ME_STR, "a.txt", "b.txt")).thenReturn(true);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.moveFile(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).moveFileMappingTree(anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void moveFile_happyPath_movesTree() {
        loginAs(ME);
        FileRenameRequestDto req = new FileRenameRequestDto();
        req.setOpenType(0);
        req.setOriginFilePath("/a.txt");
        req.setNewFilePath("/dir/b.txt");
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "a.txt")).thenReturn(true);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/b.txt"))
                .thenReturn(Optional.empty());
        when(fileMappingManager.hasMoveDestinationConflict(0, ME_STR, "a.txt", "dir/b.txt")).thenReturn(false);

        service.moveFile(req);

        verify(fileMappingManager).moveFileMappingTree(0, ME_STR, "a.txt", "dir/b.txt");
    }

    // ===================================================================
    // deleteFile
    // ===================================================================

    @Test
    void deleteFile_publicNonAdmin_forbidden() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        FileRequestDto req = new FileRequestDto("/a.txt", 1);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.deleteFile(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMappingManager, never()).markDeletedByPrefix(anyInt(), anyString(), anyString());
    }

    @Test
    void deleteFile_present_softDeletesAndReturnsTrue() {
        loginAs(ME);
        FileRequestDto req = new FileRequestDto("/dir/a.txt", 0);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "dir/a.txt")).thenReturn(true);

        Boolean ok = service.deleteFile(req);

        assertTrue(ok);
        verify(fileMappingManager).markDeletedByPrefix(0, ME_STR, "dir/a.txt");
    }

    @Test
    void deleteFile_absent_returnsFalse() {
        loginAs(ME);
        FileRequestDto req = new FileRequestDto("/dir/a.txt", 0);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "dir/a.txt")).thenReturn(false);

        Boolean ok = service.deleteFile(req);

        assertFalse(ok);
        verify(fileMappingManager, never()).markDeletedByPrefix(anyInt(), anyString(), anyString());
    }

    // ===================================================================
    // batchDelete
    // ===================================================================

    @Test
    void batchDelete_publicNonAdmin_forbidden() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        BatchFileRequestDto req = new BatchFileRequestDto();
        req.setOpenType(1);
        req.setFilePaths(List.of("/a.txt"));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.batchDelete(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void batchDelete_partialFailureDoesNotBlockOthers() {
        loginAs(ME);
        BatchFileRequestDto req = new BatchFileRequestDto();
        req.setOpenType(0);
        req.setFilePaths(Arrays.asList("/ok.txt", "/missing.txt", "/boom.txt"));
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "ok.txt")).thenReturn(true);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "missing.txt")).thenReturn(false);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "boom.txt")).thenReturn(true);
        // ok.txt 软删正常 no-op；boom.txt 软删抛运行时异常。
        // 注意：strict-stubs 下，若只对 boom 一组实参做 stub，service 用 ok 实参调同一方法时
        // 实参不匹配会抛 PotentialStubbingProblem，被 service 的 catch(Exception) 吞成「处理异常」、
        // 导致 ok 也被计为失败。故两组实参都显式 stub。
        doNothing().when(fileMappingManager).markDeletedByPrefix(0, ME_STR, "ok.txt");
        doThrow(new RuntimeException("io")).when(fileMappingManager).markDeletedByPrefix(0, ME_STR, "boom.txt");

        BatchOperationResultDto result = service.batchDelete(req);

        assertEquals(1, result.getSuccessCount());
        assertEquals(2, result.getFailureCount());
        // ok.txt 真的被删了
        verify(fileMappingManager).markDeletedByPrefix(0, ME_STR, "ok.txt");
        // 失败明细包含 missing 与 boom
        List<String> failedPaths = result.getFailures().stream()
                .map(BatchOperationResultDto.BatchItemError::getFilePath).toList();
        assertTrue(failedPaths.contains("/missing.txt"));
        assertTrue(failedPaths.contains("/boom.txt"));
    }

    @Test
    void batchDelete_serviceExceptionPerItem_recordedWithMessage() {
        loginAs(ME);
        BatchFileRequestDto req = new BatchFileRequestDto();
        req.setOpenType(0);
        req.setFilePaths(List.of("/x.txt"));
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "x.txt")).thenReturn(true);
        doThrow(new ServiceException(HttpStatus.BAD_REQUEST, "denied"))
                .when(fileMappingManager).markDeletedByPrefix(0, ME_STR, "x.txt");

        BatchOperationResultDto result = service.batchDelete(req);

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals("denied", result.getFailures().get(0).getMessage());
    }

    // ===================================================================
    // batchMove
    // ===================================================================

    @Test
    void batchMove_publicNonAdmin_forbidden() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        BatchFileRequestDto req = new BatchFileRequestDto();
        req.setOpenType(1);
        req.setFilePaths(List.of("/a.txt"));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.batchMove(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void batchMove_targetParentMissing_badRequest() {
        loginAs(ME);
        BatchFileRequestDto req = new BatchFileRequestDto();
        req.setOpenType(0);
        req.setTargetParentPath("nope");
        req.setFilePaths(List.of("/a.txt"));
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "nope"))
                .thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.batchMove(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void batchMove_mixedItems_perItemOutcomes() {
        loginAs(ME);
        BatchFileRequestDto req = new BatchFileRequestDto();
        req.setOpenType(0);
        req.setTargetParentPath("target");
        // a.txt -> ok; b.txt -> source missing; c.txt -> target conflict
        req.setFilePaths(Arrays.asList("/a.txt", "/b.txt", "/c.txt"));
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "target"))
                .thenReturn(Optional.of(mapping(0, ME_STR, "target", FileType.DIRECTORY_FILE)));

        // a.txt -> target/a.txt
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "a.txt")).thenReturn(true);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "target/a.txt"))
                .thenReturn(Optional.empty());
        when(fileMappingManager.hasMoveDestinationConflict(0, ME_STR, "a.txt", "target/a.txt")).thenReturn(false);
        // b.txt -> source missing
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "b.txt")).thenReturn(false);
        // c.txt -> target exists
        when(fileMappingManager.hasMappingUnderPath(0, ME_STR, "c.txt")).thenReturn(true);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "target/c.txt"))
                .thenReturn(Optional.of(mapping(0, ME_STR, "target/c.txt", FileType.TEXT_FILE)));

        BatchOperationResultDto result = service.batchMove(req);

        assertEquals(1, result.getSuccessCount());
        assertEquals(2, result.getFailureCount());
        verify(fileMappingManager).moveFileMappingTree(0, ME_STR, "a.txt", "target/a.txt");
        verify(fileMappingManager, never()).moveFileMappingTree(0, ME_STR, "b.txt", "target/b.txt");
        verify(fileMappingManager, never()).moveFileMappingTree(0, ME_STR, "c.txt", "target/c.txt");
    }

    @Test
    void batchMove_sameTargetAsOrigin_countedSuccessNoMove() {
        loginAs(ME);
        BatchFileRequestDto req = new BatchFileRequestDto();
        req.setOpenType(0);
        req.setTargetParentPath(""); // root, so a.txt -> a.txt
        req.setFilePaths(List.of("/a.txt"));
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);

        BatchOperationResultDto result = service.batchMove(req);

        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailureCount());
        verify(fileMappingManager, never()).moveFileMappingTree(anyInt(), anyString(), anyString(), anyString());
    }

    // ===================================================================
    // sharePrivateFileToPublic
    // ===================================================================

    @Test
    void sharePrivateFileToPublic_nonAdmin_forbidden() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        SharePrivateFileToPublicRequestDto req = new SharePrivateFileToPublicRequestDto();
        req.setSourceFilePath("/a.txt");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.sharePrivateFileToPublic(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMappingManager, never()).savePublicFileMapping(anyString(), any(), anyString(), anyString());
    }

    @Test
    void sharePrivateFileToPublic_sourceMissing_badRequest(@TempDir Path tmp) {
        loginAs(ME);
        SharePrivateFileToPublicRequestDto req = new SharePrivateFileToPublicRequestDto();
        req.setSourceFilePath("/a.txt");
        req.setTargetDirectoryPath("");
        Path missing = tmp.resolve("gone.bin");
        when(filePathResolver.resolveUserRequestFile(0, ME_STR, "a.txt")).thenReturn(missing);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.sharePrivateFileToPublic(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void sharePrivateFileToPublic_targetDirMissing_badRequest(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        SharePrivateFileToPublicRequestDto req = new SharePrivateFileToPublicRequestDto();
        req.setSourceFilePath("/a.txt");
        req.setTargetDirectoryPath("pubdir");
        File src = tmp.resolve("a.txt").toFile();
        Files.write(src.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        when(filePathResolver.resolveUserRequestFile(0, ME_STR, "a.txt")).thenReturn(src.toPath());
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", "pubdir"))
                .thenReturn(Optional.empty());

        ServiceException ex = assertThrows(ServiceException.class, () -> service.sharePrivateFileToPublic(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void sharePrivateFileToPublic_targetExists_badRequest(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        SharePrivateFileToPublicRequestDto req = new SharePrivateFileToPublicRequestDto();
        req.setSourceFilePath("/a.txt");
        req.setTargetDirectoryPath("");
        File src = tmp.resolve("a.txt").toFile();
        Files.write(src.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        when(filePathResolver.resolveUserRequestFile(0, ME_STR, "a.txt")).thenReturn(src.toPath());
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", "a.txt"))
                .thenReturn(Optional.of(mapping(1, "public", "a.txt", FileType.TEXT_FILE)));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.sharePrivateFileToPublic(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).savePublicFileMapping(anyString(), any(), anyString(), anyString());
    }

    @Test
    void sharePrivateFileToPublic_happyPath_savesPublicMapping(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        SharePrivateFileToPublicRequestDto req = new SharePrivateFileToPublicRequestDto();
        req.setSourceFilePath("/sub/a.txt");
        req.setTargetDirectoryPath("pubdir");
        File src = tmp.resolve("a.txt").toFile();
        Files.write(src.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        when(filePathResolver.resolveUserRequestFile(0, ME_STR, "sub/a.txt")).thenReturn(src.toPath());
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", "pubdir"))
                .thenReturn(Optional.of(mapping(1, "public", "pubdir", FileType.DIRECTORY_FILE)));
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", "pubdir/a.txt"))
                .thenReturn(Optional.empty());

        service.sharePrivateFileToPublic(req);

        verify(fileMappingManager).savePublicFileMapping(eq("pubdir/a.txt"), eq(src.toPath()), eq(ME_STR), eq("sub/a.txt"));
    }

    // ===================================================================
    // getStorageUsage
    // ===================================================================

    @Test
    void getStorageUsage_nullOpenType_badRequest() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getStorageUsage(null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void getStorageUsage_notLoggedIn_unauthorized() {
        logout();
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getStorageUsage(0));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getCode());
    }

    @Test
    void getStorageUsage_privateWithQuota_includesQuota() {
        loginAs(ME);
        inject("privateQuotaBytesPerUser", 1000L);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.sumUsedBytes(0, ME_STR)).thenReturn(123L);
        when(fileMappingRepository.countUsedFiles(0, ME_STR)).thenReturn(7L);

        StorageUsageResponseDto dto = service.getStorageUsage(0);

        assertEquals(0, dto.getOpenType());
        assertEquals(123L, dto.getUsedBytes());
        assertEquals(7L, dto.getFileCount());
        assertEquals(1000L, dto.getQuotaBytes());
    }

    @Test
    void getStorageUsage_public_noQuota() {
        loginAs(ME);
        inject("privateQuotaBytesPerUser", 1000L);
        when(filePathResolver.getMappingUserId(1, ME_STR)).thenReturn("public");
        when(fileMappingRepository.sumUsedBytes(1, "public")).thenReturn(500L);
        when(fileMappingRepository.countUsedFiles(1, "public")).thenReturn(3L);

        StorageUsageResponseDto dto = service.getStorageUsage(1);

        assertEquals(500L, dto.getUsedBytes());
        assertEquals(3L, dto.getFileCount());
        // 公共空间不显示配额
        assertNull(dto.getQuotaBytes());
    }

    @Test
    void getStorageUsage_privateNoQuotaConfigured_noQuota() {
        loginAs(ME);
        // privateQuotaBytesPerUser stays at -1 (default)
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.sumUsedBytes(0, ME_STR)).thenReturn(10L);
        when(fileMappingRepository.countUsedFiles(0, ME_STR)).thenReturn(1L);

        StorageUsageResponseDto dto = service.getStorageUsage(0);

        assertNull(dto.getQuotaBytes());
    }

    // ===================================================================
    // getUploadStatus
    // ===================================================================

    @Test
    void getUploadStatus_nullOpenType_badRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.getUploadStatus(null, "a.txt", "dir", 2));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void getUploadStatus_noChunkDir_emptyNotAllUploaded() {
        loginAs(ME);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(chunkUploadSupport.chunkDir(anyString())).thenReturn(Path.of("/definitely/not/here"));

        UploadStatusResponseDto dto = service.getUploadStatus(0, "a.txt", "dir", 3);

        assertTrue(dto.getUploadedChunks().isEmpty());
        assertFalse(dto.getAllUploaded());
        assertEquals("dir/a.txt", dto.getVirtualPath());
    }

    @Test
    void getUploadStatus_partialChunks_sortedNotAllUploaded(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        File chunkDir = tmp.resolve("md5dir").toFile();
        chunkDir.mkdirs();
        Files.write(chunkDir.toPath().resolve("part2"), new byte[]{1});
        Files.write(chunkDir.toPath().resolve("part0"), new byte[]{1});
        Files.write(chunkDir.toPath().resolve("notapart"), new byte[]{1}); // ignored
        when(chunkUploadSupport.chunkDir(anyString())).thenReturn(chunkDir.toPath());

        UploadStatusResponseDto dto = service.getUploadStatus(0, "a.txt", "dir", 3);

        assertEquals(List.of(0, 2), dto.getUploadedChunks()); // 排序后
        assertFalse(dto.getAllUploaded()); // 缺 part1
    }

    @Test
    void getUploadStatus_allChunksPresent_allUploadedTrue(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        File chunkDir = tmp.resolve("md5dir").toFile();
        chunkDir.mkdirs();
        for (int i = 0; i < 3; i++) {
            Files.write(chunkDir.toPath().resolve("part" + i), new byte[]{1});
        }
        when(chunkUploadSupport.chunkDir(anyString())).thenReturn(chunkDir.toPath());

        UploadStatusResponseDto dto = service.getUploadStatus(0, "a.txt", "dir", 3);

        assertEquals(List.of(0, 1, 2), dto.getUploadedChunks());
        assertTrue(dto.getAllUploaded());
    }

    // ===================================================================
    // checkUploadByHash
    // ===================================================================

    private HashUploadCheckRequestDto hashReq(int openType, String fileName, String filePath,
                                              String md5, long size, Boolean cover) {
        HashUploadCheckRequestDto r = new HashUploadCheckRequestDto();
        r.setOpenType(openType);
        r.setFileName(fileName);
        r.setFilePath(filePath);
        r.setFileMd5(md5);
        r.setFileSize(size);
        r.setCoverFlag(cover);
        return r;
    }

    @Test
    void checkUploadByHash_publicNonAdmin_forbidden() {
        loginAs(ME);
        stubPublicWriteForbidden(1);
        HashUploadCheckRequestDto req = hashReq(1, "a.txt", "/", "ABC", 10L, false);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.checkUploadByHash(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void checkUploadByHash_existingNoCover_alreadyExists() {
        loginAs(ME);
        HashUploadCheckRequestDto req = hashReq(0, "a.txt", "dir", "ABCDEF", 10L, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.of(mapping(0, ME_STR, "dir/a.txt", FileType.TEXT_FILE)));

        HashUploadCheckResponseDto resp = service.checkUploadByHash(req);

        assertEquals(2, resp.getState());
        verify(fileMappingRepository, never()).save(any());
    }

    @Test
    void checkUploadByHash_noHit_requireFullUpload() {
        loginAs(ME);
        HashUploadCheckRequestDto req = hashReq(0, "a.txt", "dir", "ABCDEF", 10L, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.empty());
        // md5 被 lower-case
        when(fileMappingRepository.findFirst5ByFileMd5AndFileSizeAndDeletedFalse("abcdef", 10L))
                .thenReturn(Collections.emptyList());

        HashUploadCheckResponseDto resp = service.checkUploadByHash(req);

        assertEquals(0, resp.getState());
        verify(fileMappingRepository, never()).save(any());
        verify(fileMappingRepository).findFirst5ByFileMd5AndFileSizeAndDeletedFalse("abcdef", 10L);
    }

    @Test
    void checkUploadByHash_hitButPhysicalGone_requireFullUpload() {
        loginAs(ME);
        HashUploadCheckRequestDto req = hashReq(0, "a.txt", "dir", "ABCDEF", 10L, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.empty());
        FileMapping hit = mapping(0, "someone", "other/a.txt", FileType.TEXT_FILE);
        hit.setTargetPath("/no/such/physical/file.bin");
        when(fileMappingRepository.findFirst5ByFileMd5AndFileSizeAndDeletedFalse("abcdef", 10L))
                .thenReturn(List.of(hit));

        HashUploadCheckResponseDto resp = service.checkUploadByHash(req);

        assertEquals(0, resp.getState());
        verify(fileMappingRepository, never()).save(any());
    }

    @Test
    void checkUploadByHash_hit_reusesPhysicalAndSavesMapping(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        HashUploadCheckRequestDto req = hashReq(0, "a.txt", "dir", "ABCDEF", 4L, false);
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.empty());
        File physical = tmp.resolve("blob.bin").toFile();
        Files.write(physical.toPath(), "data".getBytes(StandardCharsets.UTF_8));
        FileMapping hit = mapping(0, "someone", "other/a.txt", FileType.DOCUMENT_FILE);
        hit.setTargetPath(physical.getAbsolutePath());
        hit.setFileSize(4L);
        when(fileMappingRepository.findFirst5ByFileMd5AndFileSizeAndDeletedFalse("abcdef", 4L))
                .thenReturn(List.of(hit));
        when(filePathResolver.getParentPath("dir/a.txt")).thenReturn("dir");

        HashUploadCheckResponseDto resp = service.checkUploadByHash(req);

        assertEquals(1, resp.getState());
        ArgumentCaptor<FileMapping> captor = ArgumentCaptor.forClass(FileMapping.class);
        verify(fileMappingRepository).save(captor.capture());
        FileMapping saved = captor.getValue();
        assertEquals(0, saved.getOpenType());
        assertEquals(ME_STR, saved.getUserId());
        assertEquals("dir/a.txt", saved.getVirtualPath());
        assertEquals("dir", saved.getParentPath());
        assertEquals("a.txt", saved.getFileName());
        assertEquals(FileType.DOCUMENT_FILE, saved.getFileType());
        assertEquals(4L, saved.getFileSize());
        assertEquals(physical.getAbsolutePath(), saved.getTargetPath());
        assertEquals("abcdef", saved.getFileMd5());
        assertFalse(saved.getDeleted());
        assertNotNull(saved.getCreateTime());
        assertNotNull(saved.getUpdateTime());
        // 秒传命中触发幂等后置
        verify(chunkUploadSupport).fileAddAfter(physical);
        // 无同位置旧 mapping -> 不打快照
        verify(fileVersionService, never()).snapshotIfEligible(any(), anyString());
    }

    @Test
    void checkUploadByHash_overwriteHit_snapshotsOldThenReuses(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        HashUploadCheckRequestDto req = hashReq(0, "a.txt", "dir", "ABCDEF", 4L, true); // coverFlag = true
        when(filePathResolver.getMappingUserId(0, ME_STR)).thenReturn(ME_STR);
        FileMapping old = mapping(0, ME_STR, "dir/a.txt", FileType.TEXT_FILE);
        old.setCreateTime(java.time.LocalDateTime.now().minusDays(1));
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME_STR, "dir/a.txt"))
                .thenReturn(Optional.of(old));
        File physical = tmp.resolve("blob.bin").toFile();
        Files.write(physical.toPath(), "data".getBytes(StandardCharsets.UTF_8));
        FileMapping hit = mapping(0, "someone", "other/a.txt", FileType.DOCUMENT_FILE);
        hit.setTargetPath(physical.getAbsolutePath());
        hit.setFileSize(4L);
        when(fileMappingRepository.findFirst5ByFileMd5AndFileSizeAndDeletedFalse("abcdef", 4L))
                .thenReturn(List.of(hit));
        when(filePathResolver.getParentPath("dir/a.txt")).thenReturn("dir");

        HashUploadCheckResponseDto resp = service.checkUploadByHash(req);

        assertEquals(1, resp.getState());
        // 覆盖场景：先给旧 mapping 打 HASH_DEDUP 快照，再复用旧实体保存
        verify(fileVersionService).snapshotIfEligible(old, "HASH_DEDUP");
        verify(fileMappingRepository).save(old);
    }

    // ----- helper -----

    /** 返回一个确实存在于磁盘的临时文件（searchFiles 物理存在性过滤用）。 */
    private File existingTmpFile() {
        try {
            File f = File.createTempFile("fsit", ".tmp");
            f.deleteOnExit();
            return f;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
