package com.misu.fileServer.service.support;

import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * {@link FilePathResolver} 纯单元测试（JUnit5 + Mockito），覆盖路径解析各方法的边界。
 */
@ExtendWith(MockitoExtension.class)
class FilePathResolverTest {

    @TempDir
    Path tempDir;

    @Mock
    FileMappingRepository fileMappingRepository;

    private FilePathResolver resolver;

    /** 以斜杠结尾的 fileServerPath（与生产 file-server.path 约定一致）。 */
    private String rootPath;

    @BeforeEach
    void setUp() {
        resolver = new FilePathResolver();
        rootPath = tempDir.toString() + File.separator;
        ReflectionTestUtils.setField(resolver, "fileServerPath", rootPath);
        ReflectionTestUtils.setField(resolver, "fileMappingRepository", fileMappingRepository);
    }

    // ===================== getMappingUserId =====================

    @Test
    void getMappingUserId_public_normalizesToPublic() {
        assertEquals("public", resolver.getMappingUserId(1, "42"));
    }

    @Test
    void getMappingUserId_private_usesLoginUserId() {
        assertEquals("42", resolver.getMappingUserId(0, "42"));
    }

    @Test
    void getMappingUserId_nullOpenType_usesLoginUserId() {
        assertEquals("42", resolver.getMappingUserId(null, "42"));
    }

    // ===================== getUserRootDirectory =====================

    @Test
    void getUserRootDirectory_public_returnsPublicRoot() {
        assertEquals(rootPath + "public/", resolver.getUserRootDirectory(1, "42"));
    }

    @Test
    void getUserRootDirectory_private_returnsPrivateUserRoot() {
        assertEquals(rootPath + "private/42/", resolver.getUserRootDirectory(0, "42"));
    }

    @Test
    void getUserRootDirectory_nullOpenType_throws() {
        assertThrows(ServiceException.class, () -> resolver.getUserRootDirectory(null, "42"));
    }

    @Test
    void getUserRootDirectory_privateWithoutUserId_throws() {
        assertThrows(ServiceException.class, () -> resolver.getUserRootDirectory(0, "  "));
    }

    @Test
    void getUserRootDirectory_illegalOpenType_throws() {
        assertThrows(ServiceException.class, () -> resolver.getUserRootDirectory(2, "42"));
    }

    // ===================== getParentPath =====================

    @Test
    void getParentPath_multiLevel_returnsParent() {
        assertEquals("a/b", resolver.getParentPath("a/b/c"));
    }

    @Test
    void getParentPath_singleSegment_returnsEmpty() {
        assertEquals("", resolver.getParentPath("a"));
    }

    @Test
    void getParentPath_emptyString_returnsEmpty() {
        assertEquals("", resolver.getParentPath(""));
    }

    @Test
    void getParentPath_leadingSlashSingleSegment_returnsEmpty() {
        // index==0 -> 走 else 分支返回 ""
        assertEquals("", resolver.getParentPath("/a"));
    }

    // ===================== resolveMappedFile =====================

    @Test
    void resolveMappedFile_returnsNormalizedAbsoluteFileFromTargetPath() {
        FileMapping mapping = new FileMapping();
        Path target = tempDir.resolve("storage").resolve("0").resolve("u1").resolve("x.txt");
        mapping.setTargetPath(target.toString());

        File resolved = resolver.resolveMappedFile(mapping);

        assertEquals(target.toAbsolutePath().normalize().toFile(), resolved);
    }

    // ===================== resolveUserRequestFile(Integer,String,String) =====================

    @Test
    void resolveUserRequestFile_byArgs_publicMapped_resolvesToPhysicalFile() {
        Path physical = tempDir.resolve("storage").resolve("1").resolve("public").resolve("a.txt");
        FileMapping mapping = new FileMapping();
        mapping.setTargetPath(physical.toString());
        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(1), eq("public"), eq("dir/a.txt")))
                .thenReturn(Optional.of(mapping));

        Path result = resolver.resolveUserRequestFile(1, "42", "/dir/a.txt");

        assertEquals(physical.toAbsolutePath().normalize(), result);
    }

    @Test
    void resolveUserRequestFile_byArgs_privateUsesLoginUserId() {
        Path physical = tempDir.resolve("storage").resolve("0").resolve("42").resolve("b.txt");
        FileMapping mapping = new FileMapping();
        mapping.setTargetPath(physical.toString());
        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("b.txt")))
                .thenReturn(Optional.of(mapping));

        Path result = resolver.resolveUserRequestFile(0, "42", "b.txt");

        assertEquals(physical.toAbsolutePath().normalize(), result);
    }

    @Test
    void resolveUserRequestFile_byArgs_noMapping_throws() {
        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThrows(ServiceException.class, () -> resolver.resolveUserRequestFile(0, "42", "missing.txt"));
    }

    @Test
    void resolveUserRequestFile_byArgs_traversalRejected() {
        // FilePathGuard.normalizeRelativePath 应拒绝 ../ 越界，且不会触达 repository
        assertThrows(ServiceException.class,
                () -> resolver.resolveUserRequestFile(0, "42", "../../etc/passwd"));
    }

    // ===================== resolveUserRequestFile(FileRequestDto) =====================

    @Test
    void resolveUserRequestFile_byDto_usesLoginUserAndDelegates() {
        Path physical = tempDir.resolve("storage").resolve("0").resolve("7").resolve("c.txt");
        FileMapping mapping = new FileMapping();
        mapping.setTargetPath(physical.toString());
        when(fileMappingRepository
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("7"), eq("c.txt")))
                .thenReturn(Optional.of(mapping));

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(7L);

        try (MockedStatic<LoginMessageUtil> mocked = mockStatic(LoginMessageUtil.class)) {
            mocked.when(LoginMessageUtil::getLoginUser).thenReturn(Optional.of(loginUser));

            Path result = resolver.resolveUserRequestFile(new FileRequestDto("c.txt", 0));

            assertEquals(physical.toAbsolutePath().normalize(), result);
        }
    }

    // ===================== buildUploadStorageFile =====================

    @Test
    void buildUploadStorageFile_withExtension_keepsExtensionAndStorageLayout() {
        File file = resolver.buildUploadStorageFile(0, "42", "photo.JPG");
        Path expectedParent = Paths.get(rootPath, "storage", "0", "42").toAbsolutePath().normalize();

        assertEquals(expectedParent, file.getParentFile().toPath());
        assertTrue(file.getName().endsWith(".JPG"), "应保留原始扩展名: " + file.getName());
        // 文件名 = UUID + .ext，UUID 36 位 + 1 个点 + 3 位扩展名
        assertEquals(36 + 1 + 3, file.getName().length());
    }

    @Test
    void buildUploadStorageFile_withoutExtension_noTrailingDot() {
        File file = resolver.buildUploadStorageFile(1, "public", "README");
        Path expectedParent = Paths.get(rootPath, "storage", "1", "public").toAbsolutePath().normalize();

        assertEquals(expectedParent, file.getParentFile().toPath());
        assertFalse(file.getName().contains("."), "无扩展名时不应有点: " + file.getName());
        assertEquals(36, file.getName().length());
    }

    // ===================== buildVirtualDirectoryStorage =====================

    @Test
    void buildVirtualDirectoryStorage_layoutAndNameSuffix() {
        File file = resolver.buildVirtualDirectoryStorage(0, "42", "myDir");
        Path expectedParent = Paths.get(rootPath, "virtual-directory", "0", "42").toAbsolutePath().normalize();

        assertEquals(expectedParent, file.getParentFile().toPath());
        assertTrue(file.getName().endsWith("-myDir"), "目录名应以 -myDir 结尾: " + file.getName());
        // UUID(36) + "-" + "myDir"(5)
        assertEquals(36 + 1 + 5, file.getName().length());
    }

    // ===================== getPreviewFile =====================

    @Test
    void getPreviewFile_insideRoot_derivesPreviewPath() {
        File origin = tempDir.resolve("public").resolve("img.png").toFile();
        File preview = resolver.getPreviewFile(origin);

        Path expected = tempDir.toAbsolutePath().normalize()
                .resolve("preview").resolve("public").resolve("img.png")
                .normalize();
        assertEquals(expected.toFile(), preview);
    }

    @Test
    void getPreviewFile_nestedOriginUnderRoot_derivesNestedPreviewPath() {
        File origin = tempDir.resolve("private").resolve("42").resolve("sub").resolve("pic.jpeg").toFile();
        File preview = resolver.getPreviewFile(origin);

        Path expected = tempDir.toAbsolutePath().normalize()
                .resolve("preview").resolve("private").resolve("42").resolve("sub").resolve("pic.jpeg")
                .normalize();
        assertEquals(expected.toFile(), preview);
    }

    @Test
    void getPreviewFile_alwaysUnderPreviewDirectory() {
        // 无论源文件在根内还是根外，预览文件都派生在 fileServerPath/preview 之下。
        File origin = tempDir.resolve("public").resolve("p.png").toFile();
        File preview = resolver.getPreviewFile(origin);

        Path previewRoot = tempDir.toAbsolutePath().normalize().resolve("preview").normalize();
        assertTrue(preview.toPath().normalize().startsWith(previewRoot),
                "预览文件应落在 preview 目录下: " + preview);
    }

    // ===================== initFileDirectory =====================

    @Test
    void initFileDirectory_createsPublicAndPrivateBaseDirectories() {
        resolver.initFileDirectory();

        assertTrue(new File(rootPath + "public/").isDirectory(), "public 目录应被创建");
        assertTrue(new File(rootPath + "private/").isDirectory(), "private 目录应被创建");
    }

    // ===================== 常量保持不变 =====================

    @Test
    void directoryConstants_unchanged() {
        assertEquals("public/", FilePathResolver.PUBLIC_DIRECTORY);
        assertEquals("private/", FilePathResolver.PRIVATE_DIRECTORY);
        assertEquals("preview/", FilePathResolver.PREVIEW_DIRECTORY);
        assertEquals("tmp/", FilePathResolver.TMP_DIRECTORY);
        assertEquals("storage/", FilePathResolver.STORAGE_DIRECTORY);
        assertEquals("virtual-directory/", FilePathResolver.VIRTUAL_DIRECTORY_STORAGE);
    }
}
