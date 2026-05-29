package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.SaveTextRequestDto;
import com.misu.fileServer.domain.dto.TextContentResponseDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.FileVersionService;
import com.misu.fileServer.service.support.FileAuthorityChecker;
import com.misu.fileServer.service.support.FilePathResolver;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileTextServiceImpl} 边界单测（纯 JUnit5 + Mockito + @TempDir）。
 *
 * <p>路径解析用真实 {@link FilePathResolver}（注入 @TempDir 作 fileServerPath），它经
 * {@code findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse} 找 mapping，再用
 * {@code resolveMappedFile} 把 mapping.targetPath 还原为物理文件。故每个用例先 stub repository
 * 回一个 targetPath 指向 @TempDir 真实文件的 mapping，让读写逻辑跑真实磁盘。登录态 + 权限经
 * SecurityContext。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileTextServiceImplTest {

    private static final Long ME = 42L;

    @Mock
    FileMappingRepository fileMappingRepository;

    @Mock
    FileVersionService fileVersionService;

    FileAuthorityChecker fileAuthorityChecker = new FileAuthorityChecker();
    FilePathResolver filePathResolver = new FilePathResolver();

    FileTextServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(filePathResolver, "fileServerPath", tempDir.toString() + "/");
        ReflectionTestUtils.setField(filePathResolver, "fileMappingRepository", fileMappingRepository);

        service = new FileTextServiceImpl();
        ReflectionTestUtils.setField(service, "fileMappingRepository", fileMappingRepository);
        ReflectionTestUtils.setField(service, "fileVersionService", fileVersionService);
        ReflectionTestUtils.setField(service, "filePathResolver", filePathResolver);
        ReflectionTestUtils.setField(service, "fileAuthorityChecker", fileAuthorityChecker);
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

    /** 造一个 active mapping，targetPath 指向 physical，并 stub repository 让路径解析命中它。 */
    private FileMapping stubMapping(Integer openType, String mappingUserId, String virtualPath, File physical) {
        FileMapping m = new FileMapping();
        m.setId(1L);
        m.setOpenType(openType);
        m.setUserId(mappingUserId);
        m.setVirtualPath(virtualPath);
        m.setTargetPath(physical.getAbsolutePath());
        m.setDeleted(false);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(openType, mappingUserId, virtualPath))
                .thenReturn(Optional.of(m));
        return m;
    }

    // ===================== getTextContent =====================

    @Test
    void getTextContent_readsUtf8() throws Exception {
        loginAs(ME);
        File physical = tempDir.resolve("a.txt").toFile();
        Files.write(physical.toPath(), "你好 hello".getBytes(StandardCharsets.UTF_8));
        stubMapping(0, ME.toString(), "a.txt", physical);

        TextContentResponseDto dto = service.getTextContent(0, "a.txt");

        assertEquals("你好 hello", dto.getContent());
        assertFalse(dto.getBinaryLikely());
        assertNull(dto.getEncodingHint());
        assertEquals(physical.length(), dto.getSizeBytes());
    }

    @Test
    void getTextContent_detectsUtf8Bom() throws Exception {
        loginAs(ME);
        File physical = tempDir.resolve("bom.txt").toFile();
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "abc".getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        Files.write(physical.toPath(), all);
        stubMapping(0, ME.toString(), "bom.txt", physical);

        TextContentResponseDto dto = service.getTextContent(0, "bom.txt");

        assertEquals("utf-8-bom", dto.getEncodingHint());
        assertEquals("abc", dto.getContent());
    }

    @Test
    void getTextContent_binaryLikely_whenNulByte() throws Exception {
        loginAs(ME);
        File physical = tempDir.resolve("bin.dat").toFile();
        Files.write(physical.toPath(), new byte[]{1, 2, 0, 3});
        stubMapping(0, ME.toString(), "bin.dat", physical);

        TextContentResponseDto dto = service.getTextContent(0, "bin.dat");

        assertTrue(dto.getBinaryLikely());
    }

    @Test
    void getTextContent_tooLarge_rejected() throws Exception {
        loginAs(ME);
        File physical = tempDir.resolve("big.txt").toFile();
        byte[] big = new byte[1024 * 1024 + 1]; // > 1 MB
        Files.write(physical.toPath(), big);
        stubMapping(0, ME.toString(), "big.txt", physical);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.getTextContent(0, "big.txt"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("文件过大"));
    }

    @Test
    void getTextContent_nullOpenType_throws() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getTextContent(null, "a.txt"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void getTextContent_noMapping_throws() {
        loginAs(ME);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME.toString(), "missing.txt"))
                .thenReturn(Optional.empty());
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getTextContent(0, "missing.txt"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    @Test
    void getTextContent_directory_throws() throws Exception {
        loginAs(ME);
        File dir = tempDir.resolve("subdir").toFile();
        assertTrue(dir.mkdirs());
        stubMapping(0, ME.toString(), "subdir", dir);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.getTextContent(0, "subdir"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }

    // ===================== saveTextContent =====================

    @Test
    void saveTextContent_overwritesUtf8_andUpdatesMapping() throws Exception {
        loginAs(ME);
        File physical = tempDir.resolve("edit.txt").toFile();
        Files.write(physical.toPath(), "old".getBytes(StandardCharsets.UTF_8));
        FileMapping m = stubMapping(0, ME.toString(), "edit.txt", physical);
        lenient().when(fileVersionService.snapshotIfEligible(any(), eq("TEXT_EDIT"))).thenReturn(Optional.empty());

        SaveTextRequestDto req = new SaveTextRequestDto();
        req.setOpenType(0);
        req.setFilePath("edit.txt");
        req.setContent("新内容 new");
        service.saveTextContent(req);

        byte[] expected = "新内容 new".getBytes(StandardCharsets.UTF_8);
        assertEquals("新内容 new", new String(Files.readAllBytes(physical.toPath()), StandardCharsets.UTF_8));
        // 写入前快照（M18 GC 联动）
        verify(fileVersionService).snapshotIfEligible(m, "TEXT_EDIT");
        // 写入后同步 mapping
        assertEquals((long) expected.length, m.getFileSize());
        verify(fileMappingRepository).save(m);
    }

    @Test
    void saveTextContent_publicNonAdmin_forbidden() {
        loginAs(ME); // 非管理员
        SaveTextRequestDto req = new SaveTextRequestDto();
        req.setOpenType(1);
        req.setFilePath("pub.txt");
        req.setContent("x");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.saveTextContent(req));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        verify(fileMappingRepository, never()).save(any());
    }

    @Test
    void saveTextContent_contentTooLarge_rejected() throws Exception {
        loginAs(ME);
        File physical = tempDir.resolve("c.txt").toFile();
        Files.write(physical.toPath(), "seed".getBytes(StandardCharsets.UTF_8));
        stubMapping(0, ME.toString(), "c.txt", physical);

        SaveTextRequestDto req = new SaveTextRequestDto();
        req.setOpenType(0);
        req.setFilePath("c.txt");
        req.setContent(new String(new char[1024 * 1024 + 1]).replace('\0', 'a')); // > 1 MB
        ServiceException ex = assertThrows(ServiceException.class, () -> service.saveTextContent(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        assertTrue(ex.getMessage().contains("内容过大"));
        verify(fileMappingRepository, never()).save(any());
    }

    @Test
    void saveTextContent_noMapping_throwsAndNoWrite() {
        loginAs(ME);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, ME.toString(), "ghost.txt"))
                .thenReturn(Optional.empty());
        SaveTextRequestDto req = new SaveTextRequestDto();
        req.setOpenType(0);
        req.setFilePath("ghost.txt");
        req.setContent("x");
        // 路径解析不到 mapping -> resolveUserRequestFile 抛 BAD_REQUEST
        ServiceException ex = assertThrows(ServiceException.class, () -> service.saveTextContent(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
        verify(fileMappingRepository, never()).save(any());
    }

    @Test
    void saveTextContent_directory_throws() throws Exception {
        loginAs(ME);
        File dir = tempDir.resolve("d").toFile();
        assertTrue(dir.mkdirs());
        stubMapping(0, ME.toString(), "d", dir);
        SaveTextRequestDto req = new SaveTextRequestDto();
        req.setOpenType(0);
        req.setFilePath("d");
        req.setContent("x");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.saveTextContent(req));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getCode());
    }
}
