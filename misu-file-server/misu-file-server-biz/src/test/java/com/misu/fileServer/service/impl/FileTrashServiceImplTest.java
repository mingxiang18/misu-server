package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.PageResponseDto;
import com.misu.fileServer.domain.dto.TrashFileResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.support.FileAuthorityChecker;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileTrashServiceImpl} 边界单测（纯 JUnit5 + Mockito + @TempDir）。
 *
 * <p>登录态用 {@link SecurityContextHolder} 塞 {@link LoginUser}（{@code FileAuthorityChecker} /
 * {@code LoginMessageUtil} 静态读它）。{@link FileResponseAssembler#toTrashResponseDto} 已在 B5 抽出，
 * 此处 mock 它直接回 dto，只验证分页 / 倒序透传。purge 重点验证 GC 联动：物理删除 + 版本快照级联清理。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileTrashServiceImplTest {

    private static final Long ME = 42L;
    private static final Long OTHER = 99L;

    @Mock
    FileMappingRepository fileMappingRepository;

    @Mock
    FileVersionService fileVersionService;

    @Mock
    PhysicalFileOps physicalFileOps;

    @Mock
    FileResponseAssembler fileResponseAssembler;

    // 真实组件：归属/权限判定 + 路径(mappingUserId)解析，逻辑同 god class
    FileAuthorityChecker fileAuthorityChecker = new FileAuthorityChecker();
    FilePathResolver filePathResolver = new FilePathResolver();

    FileTrashServiceImpl service;

    @BeforeEach
    void setUp() {
        // service 用 @InjectMocks 注 mock；真实组件手工塞
        service = new FileTrashServiceImpl();
        inject("fileMappingRepository", fileMappingRepository);
        inject("fileVersionService", fileVersionService);
        inject("physicalFileOps", physicalFileOps);
        inject("fileResponseAssembler", fileResponseAssembler);
        inject("fileAuthorityChecker", fileAuthorityChecker);
        inject("filePathResolver", filePathResolver);
    }

    private void inject(String field, Object value) {
        org.springframework.test.util.ReflectionTestUtils.setField(service, field, value);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId, String... roles) {
        LoginUser loginUser = new LoginUser(userId, "u" + userId, List.of(roles));
        List<GrantedAuthority> authorities = Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities));
    }

    private FileMapping mapping(Long id, Integer openType, String userId, boolean deleted, String targetPath) {
        FileMapping m = new FileMapping();
        m.setId(id);
        m.setOpenType(openType);
        m.setUserId(userId);
        m.setVirtualPath("dir/file.txt");
        m.setDeleted(deleted);
        m.setTargetPath(targetPath);
        return m;
    }

    // ===================== listTrash =====================

    @Test
    void listTrash_private_pagedAndDescByDeleteTime() {
        loginAs(ME);
        FileMapping m1 = mapping(1L, 0, ME.toString(), true, null);
        FileMapping m2 = mapping(2L, 0, ME.toString(), true, null);
        Pageable expectedPageable = PageRequest.of(1, 10); // pageNumber=2 -> index 1
        Page<FileMapping> page = new PageImpl<>(Arrays.asList(m1, m2), expectedPageable, 12);
        when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(eq(0), eq(ME.toString()), any(Pageable.class)))
                .thenReturn(page);
        when(fileResponseAssembler.toTrashResponseDto(any(FileMapping.class)))
                .thenReturn(new TrashFileResponseDto());

        PageResponseDto<TrashFileResponseDto> result = service.listTrash(0, 2, 10);

        assertEquals(12L, result.getTotal());
        assertEquals(2, result.getItems().size());
        assertEquals(2, result.getPageNumber());
        assertEquals(10, result.getPageSize());
        // 仓库用的是 *OrderByUpdateTimeDesc 方法（删除时间倒序由 SQL 保证），分页索引为 pageNumber-1
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(fileMappingRepository).findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(eq(0), eq(ME.toString()), captor.capture());
        assertEquals(1, captor.getValue().getPageNumber());
        assertEquals(10, captor.getValue().getPageSize());
    }

    @Test
    void listTrash_public_usesPublicMappingUserId() {
        loginAs(ME, UserRole.ADMIN); // 公共域写操作需管理员
        Page<FileMapping> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0);
        when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(eq(1), eq("public"), any(Pageable.class)))
                .thenReturn(page);

        PageResponseDto<TrashFileResponseDto> result = service.listTrash(1, null, null);

        assertTrue(result.getItems().isEmpty());
        // openType=1 时 mappingUserId 应为 "public"
        verify(fileMappingRepository).findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(eq(1), eq("public"), any(Pageable.class));
    }

    @Test
    void listTrash_emptyResult_defaultsPaging() {
        loginAs(ME);
        Page<FileMapping> page = new PageImpl<>(Collections.emptyList(), PageRequest.of(0, 50), 0);
        when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(eq(0), eq(ME.toString()), any(Pageable.class)))
                .thenReturn(page);

        PageResponseDto<TrashFileResponseDto> result = service.listTrash(0, null, null);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getItems().isEmpty());
        assertEquals(1, result.getPageNumber());
        assertEquals(50, result.getPageSize());
    }

    @Test
    void listTrash_nullOpenType_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.listTrash(null, 1, 10));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void listTrash_publicNonAdmin_forbidden() {
        loginAs(ME); // 非管理员
        ServiceException ex = assertThrows(ServiceException.class, () -> service.listTrash(1, 1, 10));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    // ===================== restoreFromTrash =====================

    @Test
    void restoreFromTrash_setsDeletedFalseAndSaves(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        File physical = tmp.resolve("blob.bin").toFile();
        Files.write(physical.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        FileMapping m = mapping(5L, 0, ME.toString(), true, physical.getAbsolutePath());
        when(fileMappingRepository.findById(5L)).thenReturn(Optional.of(m));
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME.toString(), "dir/file.txt"))
                .thenReturn(Optional.empty());

        service.restoreFromTrash(5L);

        assertFalse(m.getDeleted());
        verify(fileMappingRepository).save(m);
    }

    @Test
    void restoreFromTrash_otherUserPrivate_forbidden() {
        loginAs(ME);
        FileMapping m = mapping(5L, 0, OTHER.toString(), true, null);
        when(fileMappingRepository.findById(5L)).thenReturn(Optional.of(m));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.restoreFromTrash(5L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMappingRepository, never()).save(any());
    }

    @Test
    void restoreFromTrash_notFound_throws() {
        loginAs(ME);
        when(fileMappingRepository.findById(404L)).thenReturn(Optional.empty());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.restoreFromTrash(404L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void restoreFromTrash_nullId_throws() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.restoreFromTrash(null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void restoreFromTrash_notInTrash_throws() {
        loginAs(ME);
        FileMapping m = mapping(5L, 0, ME.toString(), false, null);
        when(fileMappingRepository.findById(5L)).thenReturn(Optional.of(m));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.restoreFromTrash(5L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void restoreFromTrash_activeSamePath_throws() {
        loginAs(ME);
        FileMapping m = mapping(5L, 0, ME.toString(), true, null);
        when(fileMappingRepository.findById(5L)).thenReturn(Optional.of(m));
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME.toString(), "dir/file.txt"))
                .thenReturn(Optional.of(mapping(6L, 0, ME.toString(), false, null)));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.restoreFromTrash(5L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingRepository, never()).save(any());
    }

    @Test
    void restoreFromTrash_physicalGone_throws() {
        loginAs(ME);
        FileMapping m = mapping(5L, 0, ME.toString(), true, "/nonexistent/path/gone.bin");
        when(fileMappingRepository.findById(5L)).thenReturn(Optional.of(m));
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME.toString(), "dir/file.txt"))
                .thenReturn(Optional.empty());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.restoreFromTrash(5L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingRepository, never()).save(any());
    }

    // ===================== purgeFromTrash（GC 联动重点） =====================

    @Test
    void purgeFromTrash_noActiveRef_deletesPhysicalAndVersionsAndRow(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        File physical = tmp.resolve("blob.bin").toFile();
        Files.write(physical.toPath(), "data".getBytes(StandardCharsets.UTF_8));
        String targetPath = physical.getAbsolutePath();
        FileMapping m = mapping(7L, 0, ME.toString(), true, targetPath);
        when(fileMappingRepository.findById(7L)).thenReturn(Optional.of(m));
        // 没有其它 active mapping 引用该物理文件
        when(fileMappingRepository.findDistinctActiveTargetPaths()).thenReturn(Collections.emptyList());
        when(physicalFileOps.deletePhysicalRecursively(any(File.class))).thenReturn(true);

        service.purgeFromTrash(7L);

        // GC 联动 1：物理文件删除
        ArgumentCaptor<File> fileCaptor = ArgumentCaptor.forClass(File.class);
        verify(physicalFileOps).deletePhysicalRecursively(fileCaptor.capture());
        assertEquals(targetPath, fileCaptor.getValue().getPath());
        // GC 联动 2：级联清版本快照
        verify(fileVersionService).purgeAllVersionsForMapping(7L);
        // GC 联动 3：repository 删除 mapping
        verify(fileMappingRepository).delete(m);
    }

    @Test
    void purgeFromTrash_stillReferencedByActive_keepsPhysical_butStillPurgesVersionsAndRow(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        File physical = tmp.resolve("shared.bin").toFile();
        Files.write(physical.toPath(), "shared".getBytes(StandardCharsets.UTF_8));
        String targetPath = physical.getAbsolutePath();
        FileMapping m = mapping(8L, 0, ME.toString(), true, targetPath);
        when(fileMappingRepository.findById(8L)).thenReturn(Optional.of(m));
        // 仍被另一 active mapping 引用 -> 不删物理
        when(fileMappingRepository.findDistinctActiveTargetPaths()).thenReturn(List.of(targetPath));

        service.purgeFromTrash(8L);

        // 物理文件不删（仍被引用）
        verify(physicalFileOps, never()).deletePhysicalRecursively(any());
        assertTrue(physical.exists());
        // 但版本快照与行记录仍要清
        verify(fileVersionService).purgeAllVersionsForMapping(8L);
        verify(fileMappingRepository).delete(m);
    }

    @Test
    void purgeFromTrash_otherUserPrivate_forbidden() {
        loginAs(ME);
        FileMapping m = mapping(9L, 0, OTHER.toString(), true, null);
        when(fileMappingRepository.findById(9L)).thenReturn(Optional.of(m));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.purgeFromTrash(9L));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileVersionService, never()).purgeAllVersionsForMapping(anyLong());
        verify(fileMappingRepository, never()).delete(any());
    }

    @Test
    void purgeFromTrash_nullId_throws() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.purgeFromTrash(null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void purgeFromTrash_notFound_throws() {
        loginAs(ME);
        when(fileMappingRepository.findById(404L)).thenReturn(Optional.empty());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.purgeFromTrash(404L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void purgeFromTrash_notInTrash_throws() {
        loginAs(ME);
        FileMapping m = mapping(9L, 0, ME.toString(), false, null);
        when(fileMappingRepository.findById(9L)).thenReturn(Optional.of(m));
        ServiceException ex = assertThrows(ServiceException.class, () -> service.purgeFromTrash(9L));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileVersionService, never()).purgeAllVersionsForMapping(anyLong());
    }

    @Test
    void purgeFromTrash_physicalDeleteFails_throwsErrorAndDoesNotPurgeVersions(@TempDir Path tmp) throws Exception {
        loginAs(ME);
        File physical = tmp.resolve("stuck.bin").toFile();
        Files.write(physical.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        FileMapping m = mapping(10L, 0, ME.toString(), true, physical.getAbsolutePath());
        when(fileMappingRepository.findById(10L)).thenReturn(Optional.of(m));
        when(fileMappingRepository.findDistinctActiveTargetPaths()).thenReturn(Collections.emptyList());
        when(physicalFileOps.deletePhysicalRecursively(any(File.class))).thenReturn(false);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.purgeFromTrash(10L));
        assertEquals(HttpStatus.ERROR, ex.getCode());
        verify(fileVersionService, never()).purgeAllVersionsForMapping(anyLong());
        verify(fileMappingRepository, never()).delete(any());
    }

    @Test
    void purgeFromTrash_blankTargetPath_skipsPhysicalButPurgesVersionsAndRow() {
        loginAs(ME);
        FileMapping m = mapping(11L, 0, ME.toString(), true, null);
        when(fileMappingRepository.findById(11L)).thenReturn(Optional.of(m));

        service.purgeFromTrash(11L);

        verify(fileMappingRepository, never()).findDistinctActiveTargetPaths();
        verify(physicalFileOps, never()).deletePhysicalRecursively(any());
        verify(fileVersionService).purgeAllVersionsForMapping(11L);
        verify(fileMappingRepository).delete(m);
    }
}
