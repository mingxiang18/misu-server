package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.dto.FileResponseDto;
import com.misu.fileServer.domain.dto.ShareStagingRequestDto;
import com.misu.fileServer.domain.dto.StagingEntryDto;
import com.misu.fileServer.domain.dto.StorageUsageResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileMaintenanceService;
import com.misu.fileServer.service.support.FileAuthorityChecker;
import com.misu.fileServer.service.support.FileMappingManager;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.FileResponseAssembler;
import com.misu.fileServer.service.support.PhysicalFileOps;
import com.misu.security.constant.UserRole;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link FileAdminServiceImpl} 纯单元测试（JUnit5 + Mockito + @TempDir）。
 *
 * <p>策略：repository / fileResponseAssembler / fileMappingManager / physicalFileOps /
 * fileMaintenanceService / fileAuthorityChecker 全 mock；{@link FilePathResolver} 用真实实例
 * （仅用其 getMappingUserId / getParentPath，无状态）。staging 物理目录用 @TempDir 造真实文件。</p>
 *
 * <p>权限语义两套：
 * <ul>
 *   <li>listFilesAsAdmin / getStorageUsageAsAdmin / getStagingRoot / listStaging → 走
 *       {@code fileAuthorityChecker.checkAdminViewAuthority()}（mock 抛 403 模拟非管理员）；</li>
 *   <li>share* / backfill → 走 {@code AuthorityUtil.hasAuthority}，读 {@link SecurityContextHolder}，
 *       故造 / 清登录态来切换角色。</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
class FileAdminServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    FileMappingRepository fileMappingRepository;

    @Mock
    FileAuthorityChecker fileAuthorityChecker;

    @Mock
    PhysicalFileOps physicalFileOps;

    @Mock
    FileMappingManager fileMappingManager;

    @Mock
    FileResponseAssembler fileResponseAssembler;

    @Mock
    FileMaintenanceService fileMaintenanceService;

    private FilePathResolver filePathResolver;

    private FileAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        filePathResolver = new FilePathResolver();
        // FilePathResolver.getMappingUserId / getParentPath 无需 fileServerPath，但 staging 默认根需要它
        ReflectionTestUtils.setField(filePathResolver, "fileServerPath", tempDir.toString() + "/");

        service = new FileAdminServiceImpl();
        ReflectionTestUtils.setField(service, "fileServerPath", tempDir.toString() + "/");
        ReflectionTestUtils.setField(service, "privateQuotaBytesPerUser", 1000L);
        // staging 根直接指向 @TempDir，便于造真实物理文件
        ReflectionTestUtils.setField(service, "stagingPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "fileMappingRepository", fileMappingRepository);
        ReflectionTestUtils.setField(service, "filePathResolver", filePathResolver);
        ReflectionTestUtils.setField(service, "fileAuthorityChecker", fileAuthorityChecker);
        ReflectionTestUtils.setField(service, "physicalFileOps", physicalFileOps);
        ReflectionTestUtils.setField(service, "fileMappingManager", fileMappingManager);
        ReflectionTestUtils.setField(service, "fileResponseAssembler", fileResponseAssembler);
        ReflectionTestUtils.setField(service, "fileMaintenanceService", fileMaintenanceService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----- helpers -----

    private void loginAs(Long userId, String... roles) {
        LoginUser loginUser = new LoginUser(userId, "u" + userId, List.of(roles));
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities));
    }

    /** 让 admin 门控通过（管理员）。 */
    private void allowAdminView() {
        // 默认 mock 的 void 方法什么都不做 = 放行，这里仅显式表达意图，无需额外 stub
    }

    /** 让 admin 门控抛 403（非管理员）。 */
    private void denyAdminView() {
        doThrow(new ServiceException(HttpStatus.FORBIDDEN, "无权限"))
                .when(fileAuthorityChecker).checkAdminViewAuthority();
    }

    private FileMapping mapping(Integer openType, String userId, String fileType, String fileName, String parentPath) {
        FileMapping m = new FileMapping();
        m.setOpenType(openType);
        m.setUserId(userId);
        m.setFileType(fileType);
        m.setFileName(fileName);
        m.setParentPath(parentPath);
        return m;
    }

    // ===================== listFilesAsAdmin =====================

    @Test
    void listFilesAsAdmin_public_normalizesUserIdToPublic_andMapsDto() throws Exception {
        allowAdminView();
        FileMapping m1 = mapping(1, "public", FileType.DIRECTORY_FILE, "docs", "");
        FileMapping m2 = mapping(1, "public", FileType.OTHER_FILE, "a.txt", "");
        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(eq(1), eq("public"), eq("")))
                .thenReturn(Arrays.asList(m1, m2));

        FileResponseDto d1 = new FileResponseDto();
        d1.setFileName("docs");
        d1.setFile(new File(tempDir.toFile(), "docs-exists"));
        FileResponseDto d2 = new FileResponseDto();
        d2.setFileName("a.txt");
        d2.setFile(new File(tempDir.toFile(), "a-exists"));
        // 物理文件需存在以通过 filter
        Files.write(new File(tempDir.toFile(), "docs-exists").toPath(), new byte[0]);
        Files.write(new File(tempDir.toFile(), "a-exists").toPath(), new byte[1]);
        when(fileResponseAssembler.toFileResponseDto(m1)).thenReturn(d1);
        when(fileResponseAssembler.toFileResponseDto(m2)).thenReturn(d2);

        // openType=1 时 userId 任意值都应被归一为 "public"
        List<FileResponseDto> result = service.listFilesAsAdmin(1, "anything", null);

        assertEquals(2, result.size());
        // peek 把 file 置空
        assertTrue(result.stream().allMatch(d -> d.getFile() == null));
        assertEquals("docs", result.get(0).getFileName());
    }

    @Test
    void listFilesAsAdmin_filtersOutMappingsWithMissingPhysicalFile() throws Exception {
        allowAdminView();
        FileMapping present = mapping(0, "7", FileType.OTHER_FILE, "live.txt", "");
        FileMapping missing = mapping(0, "7", FileType.OTHER_FILE, "ghost.txt", "");
        when(fileMappingRepository
                .findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(eq(0), eq("7"), eq("")))
                .thenReturn(Arrays.asList(present, missing));

        FileResponseDto liveDto = new FileResponseDto();
        liveDto.setFileName("live.txt");
        File liveFile = new File(tempDir.toFile(), "live.txt");
        Files.write(liveFile.toPath(), new byte[3]);
        liveDto.setFile(liveFile);

        FileResponseDto ghostDto = new FileResponseDto();
        ghostDto.setFileName("ghost.txt");
        ghostDto.setFile(new File(tempDir.toFile(), "nope-missing.txt"));

        when(fileResponseAssembler.toFileResponseDto(present)).thenReturn(liveDto);
        when(fileResponseAssembler.toFileResponseDto(missing)).thenReturn(ghostDto);

        List<FileResponseDto> result = service.listFilesAsAdmin(0, "7", "");
        assertEquals(1, result.size());
        assertEquals("live.txt", result.get(0).getFileName());
    }

    @Test
    void listFilesAsAdmin_nullOpenType_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.listFilesAsAdmin(null, "7", ""));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verifyNoInteractions(fileAuthorityChecker);
    }

    @Test
    void listFilesAsAdmin_privateBlankUserId_throwsBadRequest() {
        allowAdminView();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.listFilesAsAdmin(0, "  ", ""));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void listFilesAsAdmin_nonAdmin_throwsForbidden() {
        denyAdminView();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.listFilesAsAdmin(1, "x", ""));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verifyNoInteractions(fileMappingRepository);
    }

    // ===================== getStorageUsageAsAdmin =====================

    @Test
    void getStorageUsageAsAdmin_private_computesUsageWithQuota() {
        allowAdminView();
        when(fileMappingRepository.sumUsedBytes(0, "9")).thenReturn(500L);
        when(fileMappingRepository.countUsedFiles(0, "9")).thenReturn(3L);

        StorageUsageResponseDto dto = service.getStorageUsageAsAdmin(0, "9");

        assertEquals(0, dto.getOpenType());
        assertEquals(500L, dto.getUsedBytes());
        assertEquals(3L, dto.getFileCount());
        assertEquals(1000L, dto.getQuotaBytes());
    }

    @Test
    void getStorageUsageAsAdmin_public_normalizesUserId_noQuota() {
        allowAdminView();
        when(fileMappingRepository.sumUsedBytes(1, "public")).thenReturn(42L);
        when(fileMappingRepository.countUsedFiles(1, "public")).thenReturn(1L);

        StorageUsageResponseDto dto = service.getStorageUsageAsAdmin(1, "ignored");

        assertEquals(1, dto.getOpenType());
        assertEquals(42L, dto.getUsedBytes());
        // 公共空间不返回配额
        assertEquals(null, dto.getQuotaBytes());
    }

    @Test
    void getStorageUsageAsAdmin_nullOpenType_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.getStorageUsageAsAdmin(null, "9"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void getStorageUsageAsAdmin_privateBlankUserId_throwsBadRequest() {
        allowAdminView();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.getStorageUsageAsAdmin(0, ""));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void getStorageUsageAsAdmin_nonAdmin_throwsForbidden() {
        denyAdminView();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.getStorageUsageAsAdmin(0, "9"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    // ===================== getStagingRoot =====================

    @Test
    void getStagingRoot_returnsConfiguredRoot() {
        allowAdminView();
        String root = service.getStagingRoot();
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), root);
    }

    @Test
    void getStagingRoot_nonAdmin_throwsForbidden() {
        denyAdminView();
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getStagingRoot());
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void getStagingRoot_blankConfig_fallsBackToFileServerStagingDir() {
        allowAdminView();
        ReflectionTestUtils.setField(service, "stagingPath", "");
        String root = service.getStagingRoot();
        assertEquals(Path.of(tempDir.toString(), "staging").toAbsolutePath().normalize().toString(), root);
    }

    // ===================== listStaging =====================

    @Test
    void listStaging_directoriesFirstThenNameAsc() throws Exception {
        allowAdminView();
        // 造：zeta.txt（文件）、alpha.txt（文件）、Beta（目录）、aaa（目录）
        Files.write(tempDir.resolve("zeta.txt"), new byte[5]);
        Files.write(tempDir.resolve("alpha.txt"), new byte[2]);
        Files.createDirectory(tempDir.resolve("Beta"));
        Files.createDirectory(tempDir.resolve("aaa"));

        List<StagingEntryDto> entries = service.listStaging(null);

        assertEquals(4, entries.size());
        // 目录优先（directory=true 排前），目录内按名升序：aaa, Beta；再文件：alpha.txt, zeta.txt
        assertEquals("aaa", entries.get(0).getName());
        assertTrue(entries.get(0).getDirectory());
        assertEquals("Beta", entries.get(1).getName());
        assertTrue(entries.get(1).getDirectory());
        assertEquals("alpha.txt", entries.get(2).getName());
        assertFalse(entries.get(2).getDirectory());
        assertEquals("zeta.txt", entries.get(3).getName());
        // 文件 size 被填充
        assertEquals(2L, entries.get(2).getSize());
    }

    @Test
    void listStaging_subPathTraversal_blocked() {
        allowAdminView();
        // ../ 越界路径被 FilePathGuard.normalizeRelativePath 直接判非法 → FORBIDDEN（403），
        // 绝不会读到 staging 根之外。
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.listStaging("../../etc/secret-not-exist"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void listStaging_nonexistentSubPath_throwsBadRequest() {
        allowAdminView();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.listStaging("no-such-dir"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void listStaging_nonAdmin_throwsForbidden() {
        denyAdminView();
        ServiceException ex = assertThrows(ServiceException.class, () -> service.listStaging(null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    // ===================== shareStagingToPublic =====================

    @Test
    void shareStagingToPublic_admin_registersMapping() throws Exception {
        loginAs(1L, UserRole.ADMIN);
        File src = tempDir.resolve("payload.bin").toFile();
        Files.write(src.toPath(), new byte[8]);

        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(1), eq("public"), anyString()))
                .thenReturn(Optional.empty());

        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "sourceStagingPath", "payload.bin");
        ReflectionTestUtils.setField(req, "targetVirtualPath", "");

        service.shareStagingToPublic(req);

        verify(fileMappingManager).mapPhysicalTreeToVirtualPaths(eq(1), eq("public"), eq("payload.bin"), any(File.class));
    }

    @Test
    void shareStagingToPublic_targetExists_throwsBadRequest() throws Exception {
        loginAs(1L, UserRole.FILE_ADMIN);
        File src = tempDir.resolve("dup.bin").toFile();
        Files.write(src.toPath(), new byte[4]);

        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(1), eq("public"), eq("dup.bin")))
                .thenReturn(Optional.of(mapping(1, "public", FileType.OTHER_FILE, "dup.bin", "")));

        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "sourceStagingPath", "dup.bin");
        ReflectionTestUtils.setField(req, "targetVirtualPath", "");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.shareStagingToPublic(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).mapPhysicalTreeToVirtualPaths(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void shareStagingToPublic_sourceMissing_throwsBadRequest() {
        loginAs(1L, UserRole.ADMIN);
        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "sourceStagingPath", "no-such-file.bin");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.shareStagingToPublic(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void shareStagingToPublic_nonAdmin_throwsForbidden() {
        loginAs(1L, UserRole.USER);
        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "sourceStagingPath", "whatever");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.shareStagingToPublic(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verifyNoInteractions(fileMappingManager);
    }

    // ===================== shareStagingToUser =====================

    @Test
    void shareStagingToUser_admin_registersMappingToTargetUser() throws Exception {
        loginAs(1L, UserRole.ADMIN);
        File dir = tempDir.resolve("bundle").toFile();
        Files.createDirectory(dir.toPath());
        Files.write(new File(dir, "x.txt").toPath(), new byte[2]);

        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("55"), anyString()))
                .thenReturn(Optional.empty());

        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "sourceStagingPath", "bundle");
        ReflectionTestUtils.setField(req, "targetUserId", " 55 ");
        ReflectionTestUtils.setField(req, "targetVirtualPath", "");

        service.shareStagingToUser(req);

        // targetUserId 被 trim 成 "55"，目标虚拟路径用源目录名 "bundle"
        verify(fileMappingManager).mapPhysicalTreeToVirtualPaths(eq(0), eq("55"), eq("bundle"), any(File.class));
    }

    @Test
    void shareStagingToUser_blankTargetUserId_throwsBadRequest() {
        loginAs(1L, UserRole.ADMIN);
        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "sourceStagingPath", "bundle");
        ReflectionTestUtils.setField(req, "targetUserId", "  ");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.shareStagingToUser(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void shareStagingToUser_targetExists_throwsBadRequest() throws Exception {
        loginAs(1L, UserRole.ADMIN);
        File src = tempDir.resolve("c.txt").toFile();
        Files.write(src.toPath(), new byte[1]);

        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("7"), eq("c.txt")))
                .thenReturn(Optional.of(mapping(0, "7", FileType.OTHER_FILE, "c.txt", "")));

        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "sourceStagingPath", "c.txt");
        ReflectionTestUtils.setField(req, "targetUserId", "7");
        ReflectionTestUtils.setField(req, "targetVirtualPath", "");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.shareStagingToUser(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingManager, never()).mapPhysicalTreeToVirtualPaths(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void shareStagingToUser_nonAdmin_throwsForbidden() {
        loginAs(1L, UserRole.USER);
        ShareStagingRequestDto req = new ShareStagingRequestDto();
        ReflectionTestUtils.setField(req, "targetUserId", "7");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.shareStagingToUser(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verifyNoInteractions(fileMappingManager);
    }

    // ===================== backfill 触发 / 状态 =====================

    @Test
    void startFileMappingBackfill_admin_delegates() {
        loginAs(1L, UserRole.ADMIN);
        service.startFileMappingBackfill();
        verify(fileMaintenanceService).runBackfillAsync();
    }

    @Test
    void startFileMappingBackfill_fileAdmin_delegates() {
        loginAs(1L, UserRole.FILE_ADMIN);
        service.startFileMappingBackfill();
        verify(fileMaintenanceService).runBackfillAsync();
    }

    @Test
    void startFileMappingBackfill_nonAdmin_throwsForbidden() {
        loginAs(1L, UserRole.USER);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.startFileMappingBackfill());
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMaintenanceService, never()).runBackfillAsync();
    }

    @Test
    void getFileMappingBackfillStatus_admin_delegates() {
        loginAs(1L, UserRole.ADMIN);
        Map<String, Object> stub = Map.of("running", Boolean.TRUE);
        when(fileMaintenanceService.getBackfillStatus()).thenReturn(stub);

        Map<String, Object> result = service.getFileMappingBackfillStatus();

        assertSame(stub, result);
        verify(fileMaintenanceService).getBackfillStatus();
    }

    @Test
    void getFileMappingBackfillStatus_nonAdmin_throwsForbidden() {
        loginAs(1L, UserRole.USER);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getFileMappingBackfillStatus());
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMaintenanceService, never()).getBackfillStatus();
    }
}
