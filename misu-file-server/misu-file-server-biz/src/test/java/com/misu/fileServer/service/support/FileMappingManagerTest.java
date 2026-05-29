package com.misu.fileServer.service.support;

import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileMappingManager} 纯单元测试（JUnit5 + Mockito）。
 *
 * <p>策略：repository 全 mock；{@link FilePathResolver} 用真实实例 + {@link ReflectionTestUtils}
 * 注入 fileServerPath=@TempDir，避免脆弱的路径 stub；物理遍历相关用例在 @TempDir 上造真实目录树。
 * save 用 thenAnswer 回填到内存 store，模拟「同 virtualPath 已存在则取回更新」语义。</p>
 */
@ExtendWith(MockitoExtension.class)
class FileMappingManagerTest {

    @TempDir
    Path tempDir;

    @Mock
    FileMappingRepository fileMappingRepository;

    private FilePathResolver filePathResolver;
    private FileMappingManager manager;

    /** 内存 store：模拟 (openType,userId,virtualPath) -> active mapping。 */
    private final Map<String, FileMapping> store = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    private static String key(Integer openType, String userId, String virtualPath) {
        return openType + "|" + userId + "|" + virtualPath;
    }

    @BeforeEach
    void setUp() {
        filePathResolver = new FilePathResolver();
        ReflectionTestUtils.setField(filePathResolver, "fileServerPath", tempDir.toString() + File.separator);
        ReflectionTestUtils.setField(filePathResolver, "fileMappingRepository", fileMappingRepository);

        manager = new FileMappingManager();
        ReflectionTestUtils.setField(manager, "fileMappingRepository", fileMappingRepository);
        ReflectionTestUtils.setField(manager, "filePathResolver", filePathResolver);
    }

    /** 让 repository 由内存 store 驱动 active 查询 + save 回填。 */
    private void wireStore() {
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    FileMapping m = store.get(key(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
                    return (m != null && !Boolean.TRUE.equals(m.getDeleted())) ? Optional.of(m) : Optional.empty();
                });
        when(fileMappingRepository.save(any(FileMapping.class))).thenAnswer(inv -> {
            FileMapping m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(idSeq.getAndIncrement());
            }
            store.put(key(m.getOpenType(), m.getUserId(), m.getVirtualPath()), m);
            return m;
        });
    }

    private void wireFindByOwner(Integer openType, String userId, List<FileMapping> mappings) {
        when(fileMappingRepository.findByOpenTypeAndUserIdAndDeletedFalse(openType, userId))
                .thenReturn(mappings);
    }

    private static FileMapping active(Integer openType, String userId, String virtualPath, String fileType) {
        FileMapping m = new FileMapping();
        m.setOpenType(openType);
        m.setUserId(userId);
        m.setVirtualPath(virtualPath);
        m.setParentPath(virtualPath.contains("/") ? virtualPath.substring(0, virtualPath.lastIndexOf('/')) : "");
        m.setFileName(virtualPath.contains("/") ? virtualPath.substring(virtualPath.lastIndexOf('/') + 1) : virtualPath);
        m.setFileType(fileType);
        m.setFileSize(10L);
        m.setTargetPath("/somewhere/" + virtualPath);
        m.setDeleted(false);
        return m;
    }

    private File writeFile(String name, String content) throws IOException {
        File f = tempDir.resolve(name).toFile();
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), content);
        return f;
    }

    // ===================== saveOrUpdateFileMapping =====================

    @Test
    void saveOrUpdate_notExisting_createsNewMapping() throws IOException {
        wireStore();
        File physical = writeFile("storage/new.txt", "hi");

        manager.saveOrUpdateFileMapping(0, "42", "dir/new.txt", "dir", "new.txt", physical);

        FileMapping saved = store.get(key(0, "42", "dir/new.txt"));
        assertNotNull(saved);
        assertEquals(0, saved.getOpenType());
        assertEquals("42", saved.getUserId());
        assertEquals("dir/new.txt", saved.getVirtualPath());
        assertEquals("dir", saved.getParentPath());
        assertEquals("new.txt", saved.getFileName());
        assertEquals(FileType.TEXT_FILE, saved.getFileType());
        assertEquals(physical.length(), saved.getFileSize());
        assertEquals(physical.toPath().toAbsolutePath().normalize().toString(), saved.getTargetPath());
        assertFalse(saved.getDeleted());
        assertNotNull(saved.getCreateTime());
        assertNotNull(saved.getUpdateTime());
        assertNull(saved.getFileMd5(), "无 md5 入参时不写 md5");
    }

    @Test
    void saveOrUpdate_existing_updatesInPlaceKeepingCreateTime() throws IOException {
        wireStore();
        // 预置一条已存在 active mapping
        FileMapping existing = active(0, "42", "dir/x.txt", FileType.OTHER_FILE);
        existing.setId(99L);
        java.time.LocalDateTime origCreate = java.time.LocalDateTime.now().minusDays(3);
        existing.setCreateTime(origCreate);
        store.put(key(0, "42", "dir/x.txt"), existing);

        File physical = writeFile("storage/x.txt", "updated-content");
        manager.saveOrUpdateFileMapping(0, "42", "dir/x.txt", "dir", "x.txt", physical, "abc123md5");

        FileMapping saved = store.get(key(0, "42", "dir/x.txt"));
        assertEquals(99L, saved.getId(), "应复用既有记录，不新建");
        assertEquals(origCreate, saved.getCreateTime(), "createTime 不被覆盖");
        assertEquals("abc123md5", saved.getFileMd5());
        assertEquals(physical.length(), saved.getFileSize());
        assertEquals(FileType.TEXT_FILE, saved.getFileType());
    }

    @Test
    void saveOrUpdate_directory_typeIsDirectory() throws IOException {
        wireStore();
        File dir = tempDir.resolve("storage/folder").toFile();
        dir.mkdirs();

        manager.saveOrUpdateFileMapping(1, "public", "folder", "", "folder", dir);

        FileMapping saved = store.get(key(1, "public", "folder"));
        assertEquals(FileType.DIRECTORY_FILE, saved.getFileType());
        assertEquals("", saved.getParentPath());
    }

    // ===================== markDeletedByPrefix =====================

    @Test
    void markDeletedByPrefix_marksOnlySubtreeNotSiblings() {
        FileMapping root = active(0, "42", "a", FileType.DIRECTORY_FILE);
        FileMapping child = active(0, "42", "a/b.txt", FileType.TEXT_FILE);
        FileMapping deepChild = active(0, "42", "a/sub/c.txt", FileType.TEXT_FILE);
        FileMapping sibling = active(0, "42", "ab.txt", FileType.TEXT_FILE);   // 前缀像但不在 a/ 下
        FileMapping other = active(0, "42", "z/d.txt", FileType.TEXT_FILE);
        List<FileMapping> all = new ArrayList<>(List.of(root, child, deepChild, sibling, other));
        wireFindByOwner(0, "42", all);
        when(fileMappingRepository.save(any(FileMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.markDeletedByPrefix(0, "42", "a");

        assertTrue(root.getDeleted());
        assertTrue(child.getDeleted());
        assertTrue(deepChild.getDeleted());
        assertFalse(sibling.getDeleted(), "ab.txt 不应被误删");
        assertFalse(other.getDeleted(), "z/d.txt 不应被删");
        // 只对命中的 3 条 save
        verify(fileMappingRepository, times(3)).save(any(FileMapping.class));
    }

    @Test
    void markDeletedByPrefix_singleFileNoChildren() {
        FileMapping file = active(0, "42", "a/b.txt", FileType.TEXT_FILE);
        FileMapping sibling = active(0, "42", "a/b.txt.bak", FileType.TEXT_FILE);
        wireFindByOwner(0, "42", new ArrayList<>(List.of(file, sibling)));
        when(fileMappingRepository.save(any(FileMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.markDeletedByPrefix(0, "42", "a/b.txt");

        assertTrue(file.getDeleted());
        assertFalse(sibling.getDeleted(), "a/b.txt.bak 是兄弟，不应被删");
        verify(fileMappingRepository, times(1)).save(any(FileMapping.class));
    }

    // ===================== moveFileMappingTree =====================

    @Test
    void moveFileMappingTree_rewritesSubtreePaths() {
        FileMapping root = active(0, "42", "a", FileType.DIRECTORY_FILE);
        FileMapping child = active(0, "42", "a/b.txt", FileType.TEXT_FILE);
        FileMapping deep = active(0, "42", "a/sub/c.txt", FileType.TEXT_FILE);
        FileMapping untouched = active(0, "42", "other.txt", FileType.TEXT_FILE);
        wireFindByOwner(0, "42", new ArrayList<>(List.of(root, child, deep, untouched)));
        when(fileMappingRepository.save(any(FileMapping.class))).thenAnswer(inv -> inv.getArgument(0));

        manager.moveFileMappingTree(0, "42", "a", "x/y");

        assertEquals("x/y", root.getVirtualPath());
        assertEquals("x", root.getParentPath());
        assertEquals("y", root.getFileName());

        assertEquals("x/y/b.txt", child.getVirtualPath());
        assertEquals("x/y", child.getParentPath());
        assertEquals("b.txt", child.getFileName());

        assertEquals("x/y/sub/c.txt", deep.getVirtualPath());
        assertEquals("x/y/sub", deep.getParentPath());
        assertEquals("c.txt", deep.getFileName());

        assertEquals("other.txt", untouched.getVirtualPath(), "树外节点不动");
        verify(fileMappingRepository, times(3)).save(any(FileMapping.class));
    }

    // ===================== hasMappingUnderPath =====================

    @Test
    void hasMappingUnderPath_exactMatch_true() {
        wireFindByOwner(0, "42", List.of(active(0, "42", "a/b.txt", FileType.TEXT_FILE)));
        assertTrue(manager.hasMappingUnderPath(0, "42", "a/b.txt"));
    }

    @Test
    void hasMappingUnderPath_childPrefix_true() {
        wireFindByOwner(0, "42", List.of(active(0, "42", "a/sub/c.txt", FileType.TEXT_FILE)));
        assertTrue(manager.hasMappingUnderPath(0, "42", "a"));
    }

    @Test
    void hasMappingUnderPath_onlySiblingPrefix_false() {
        // "ab.txt" 不应被当成 "a" 的子节点
        wireFindByOwner(0, "42", List.of(active(0, "42", "ab.txt", FileType.TEXT_FILE)));
        assertFalse(manager.hasMappingUnderPath(0, "42", "a"));
    }

    @Test
    void hasMappingUnderPath_empty_false() {
        wireFindByOwner(0, "42", List.of());
        assertFalse(manager.hasMappingUnderPath(0, "42", "a"));
    }

    // ===================== hasMappingParentConflict =====================

    @Test
    void hasMappingParentConflict_parentIsFile_true() {
        // 父 "a" 是个文件（非目录），冲突
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("a")))
                .thenReturn(Optional.of(active(0, "42", "a", FileType.TEXT_FILE)));

        assertTrue(manager.hasMappingParentConflict(0, "42", "a/b.txt"));
    }

    @Test
    void hasMappingParentConflict_parentIsDirectory_false() {
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("a")))
                .thenReturn(Optional.of(active(0, "42", "a", FileType.DIRECTORY_FILE)));

        assertFalse(manager.hasMappingParentConflict(0, "42", "a/b.txt"));
    }

    @Test
    void hasMappingParentConflict_noParentMapping_false() {
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("a")))
                .thenReturn(Optional.empty());

        assertFalse(manager.hasMappingParentConflict(0, "42", "a/b.txt"));
    }

    @Test
    void hasMappingParentConflict_topLevelNoParent_false() {
        // 顶层 "x"，getParentPath 返回 ""，循环立刻结束，不查 repository
        assertFalse(manager.hasMappingParentConflict(0, "42", "x"));
        verify(fileMappingRepository, never())
                .findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(anyInt(), anyString(), anyString());
    }

    @Test
    void hasMappingParentConflict_deepParentIsFile_true() {
        // a 是目录、a/b 是文件 -> a/b/c.txt 的祖先链冲突
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("a/b")))
                .thenReturn(Optional.of(active(0, "42", "a/b", FileType.TEXT_FILE)));

        assertTrue(manager.hasMappingParentConflict(0, "42", "a/b/c.txt"));
    }

    // ===================== hasMoveDestinationConflict =====================

    @Test
    void hasMoveDestinationConflict_destinationOccupiedByForeign_true() {
        // 移动 a -> x；x/b.txt 已被一个不在被移子树内的 mapping 占用
        wireFindByOwner(0, "42", List.of(
                active(0, "42", "a", FileType.DIRECTORY_FILE),
                active(0, "42", "a/b.txt", FileType.TEXT_FILE)));
        // 目标 a -> x： x（root）无占用，x/b.txt 被占
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("x")))
                .thenReturn(Optional.empty());
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("x/b.txt")))
                .thenReturn(Optional.of(active(0, "42", "x/b.txt", FileType.TEXT_FILE)));

        assertTrue(manager.hasMoveDestinationConflict(0, "42", "a", "x"));
    }

    @Test
    void hasMoveDestinationConflict_noOccupancy_false() {
        wireFindByOwner(0, "42", List.of(
                active(0, "42", "a", FileType.DIRECTORY_FILE),
                active(0, "42", "a/b.txt", FileType.TEXT_FILE)));
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(anyInt(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertFalse(manager.hasMoveDestinationConflict(0, "42", "a", "x"));
    }

    @Test
    void hasMoveDestinationConflict_destinationWithinMovingSubtree_false() {
        // 占用的目标恰好就是被移子树自身的成员（movingPathSet 包含），不算冲突
        wireFindByOwner(0, "42", List.of(
                active(0, "42", "a", FileType.DIRECTORY_FILE),
                active(0, "42", "a/b", FileType.DIRECTORY_FILE)));
        // a -> a/b ：被 startsWith 守卫拦掉是上层逻辑；这里单测 conflict 函数行为——
        // 目标 a/b 与 a/b/b 都在 movingPathSet 里则不算冲突
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(anyInt(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String vp = inv.getArgument(2);
                    if ("a/b".equals(vp)) {
                        return Optional.of(active(0, "42", "a/b", FileType.DIRECTORY_FILE));
                    }
                    return Optional.empty();
                });

        // 移动 a -> a/b：oldPath "a" -> dest "a/b"（在 movingPathSet 中），"a/b" -> "a/b/b"（无占用）
        assertFalse(manager.hasMoveDestinationConflict(0, "42", "a", "a/b"));
    }

    @Test
    void hasMoveDestinationConflict_emptySource_false() {
        wireFindByOwner(0, "42", List.of());
        assertFalse(manager.hasMoveDestinationConflict(0, "42", "a", "x"));
    }

    // ===================== mapPhysicalTreeToVirtualPaths + walkAndMapChildren =====================

    @Test
    void mapPhysicalTree_registersEveryNodeWithVirtualPath() throws IOException {
        wireStore();
        // 造物理目录树： root/ {f1.txt, sub/ {f2.txt, deep/ {f3.txt}}}
        File root = tempDir.resolve("phys/root").toFile();
        root.mkdirs();
        Files.writeString(new File(root, "f1.txt").toPath(), "1");
        File sub = new File(root, "sub");
        sub.mkdirs();
        Files.writeString(new File(sub, "f2.txt").toPath(), "2");
        File deep = new File(sub, "deep");
        deep.mkdirs();
        Files.writeString(new File(deep, "f3.txt").toPath(), "3");

        manager.mapPhysicalTreeToVirtualPaths(0, "42", "vroot", root);

        // root 节点 + 每个后代都登记
        assertNotNull(store.get(key(0, "42", "vroot")));
        assertEquals(FileType.DIRECTORY_FILE, store.get(key(0, "42", "vroot")).getFileType());
        assertNotNull(store.get(key(0, "42", "vroot/f1.txt")));
        assertEquals(FileType.TEXT_FILE, store.get(key(0, "42", "vroot/f1.txt")).getFileType());
        assertNotNull(store.get(key(0, "42", "vroot/sub")));
        assertEquals(FileType.DIRECTORY_FILE, store.get(key(0, "42", "vroot/sub")).getFileType());
        assertNotNull(store.get(key(0, "42", "vroot/sub/f2.txt")));
        assertNotNull(store.get(key(0, "42", "vroot/sub/deep")));
        assertNotNull(store.get(key(0, "42", "vroot/sub/deep/f3.txt")));

        // 共 6 个节点
        assertEquals(6, store.size());
        // 验证 parentPath 正确
        assertEquals("vroot/sub", store.get(key(0, "42", "vroot/sub/f2.txt")).getParentPath());
        assertEquals("vroot/sub/deep", store.get(key(0, "42", "vroot/sub/deep/f3.txt")).getParentPath());
        // targetPath 指向真实物理文件
        assertEquals(new File(root, "f1.txt").toPath().toAbsolutePath().normalize().toString(),
                store.get(key(0, "42", "vroot/f1.txt")).getTargetPath());
    }

    @Test
    void mapPhysicalTree_singleFile_onlyRootRegistered() throws IOException {
        wireStore();
        File single = writeFile("phys/lonely.txt", "x");

        manager.mapPhysicalTreeToVirtualPaths(1, "public", "lonely.txt", single);

        assertEquals(1, store.size());
        FileMapping m = store.get(key(1, "public", "lonely.txt"));
        assertNotNull(m);
        assertEquals(FileType.TEXT_FILE, m.getFileType());
    }

    // ===================== cloneMappingSubtreeToPublic =====================

    @Test
    void cloneSubtreeToPublic_rewritesUserAndPathsAndSavesAll() {
        FileMapping root = active(0, "42", "src", FileType.DIRECTORY_FILE);
        root.setTargetPath("/phys/src");
        FileMapping child = active(0, "42", "src/a.txt", FileType.TEXT_FILE);
        child.setTargetPath("/phys/src/a.txt");
        child.setFileSize(123L);
        FileMapping deep = active(0, "42", "src/sub/b.txt", FileType.TEXT_FILE);
        deep.setTargetPath("/phys/src/sub/b.txt");
        FileMapping unrelated = active(0, "42", "other.txt", FileType.TEXT_FILE);
        wireFindByOwner(0, "42", List.of(root, child, deep, unrelated));
        // 目标 public 无任何冲突
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(1), eq("public"), anyString()))
                .thenReturn(Optional.empty());

        manager.cloneMappingSubtreeToPublic("42", "src", "pubdir");

        ArgumentCaptor<List<FileMapping>> captor = ArgumentCaptor.forClass(List.class);
        verify(fileMappingRepository).saveAll(captor.capture());
        List<FileMapping> saved = captor.getValue();
        // 仅克隆 src 子树 3 条，不含 other.txt
        assertEquals(3, saved.size());
        Map<String, FileMapping> byVp = saved.stream()
                .collect(Collectors.toMap(FileMapping::getVirtualPath, m -> m));
        assertTrue(byVp.containsKey("pubdir"));
        assertTrue(byVp.containsKey("pubdir/a.txt"));
        assertTrue(byVp.containsKey("pubdir/sub/b.txt"));

        FileMapping clonedChild = byVp.get("pubdir/a.txt");
        assertEquals(1, clonedChild.getOpenType());
        assertEquals("public", clonedChild.getUserId());
        assertEquals("pubdir", clonedChild.getParentPath());
        assertEquals("a.txt", clonedChild.getFileName());
        assertEquals(FileType.TEXT_FILE, clonedChild.getFileType());
        assertEquals(123L, clonedChild.getFileSize());
        assertEquals("/phys/src/a.txt", clonedChild.getTargetPath(), "targetPath 指向同一物理文件");
        assertFalse(clonedChild.getDeleted());
        assertNotNull(clonedChild.getCreateTime());

        FileMapping clonedDeep = byVp.get("pubdir/sub/b.txt");
        assertEquals("pubdir/sub", clonedDeep.getParentPath());
    }

    @Test
    void cloneSubtreeToPublic_emptySource_throws() {
        wireFindByOwner(0, "42", List.of(active(0, "42", "other", FileType.DIRECTORY_FILE)));
        assertThrows(ServiceException.class,
                () -> manager.cloneMappingSubtreeToPublic("42", "src", "pubdir"));
        verify(fileMappingRepository, never()).saveAll(any());
    }

    @Test
    void cloneSubtreeToPublic_targetPathConflict_throws() {
        FileMapping root = active(0, "42", "src", FileType.DIRECTORY_FILE);
        wireFindByOwner(0, "42", List.of(root));
        // 目标 pubdir 已存在 -> 冲突
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(1), eq("public"), eq("pubdir")))
                .thenReturn(Optional.of(active(1, "public", "pubdir", FileType.DIRECTORY_FILE)));

        assertThrows(ServiceException.class,
                () -> manager.cloneMappingSubtreeToPublic("42", "src", "pubdir"));
        verify(fileMappingRepository, never()).saveAll(any());
    }

    // ===================== savePublicFileMapping =====================

    @Test
    void savePublicFileMapping_directorySource_delegatesToClone() {
        // 源是目录 -> 走 cloneMappingSubtreeToPublic 分支
        // hasMappingUnderPath(1,public,target) / hasMappingParentConflict 都不命中
        wireFindByOwner(1, "public", List.of());            // public 下无任何 mapping
        wireFindByOwner(0, "42", List.of(active(0, "42", "src", FileType.DIRECTORY_FILE)));
        // 源根 mapping 是目录
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(0), eq("42"), eq("src")))
                .thenReturn(Optional.of(active(0, "42", "src", FileType.DIRECTORY_FILE)));
        // public 目标无冲突
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(eq(1), eq("public"), anyString()))
                .thenReturn(Optional.empty());

        manager.savePublicFileMapping("pubdir", Path.of("/phys/src"), "42", "src");

        verify(fileMappingRepository).saveAll(any());
    }

    @Test
    void savePublicFileMapping_fileSource_mapsPhysicalTree() throws IOException {
        wireStore();
        // public 下查询走 store（空），findByOwner 用于 hasMappingUnderPath
        wireFindByOwner(1, "public", List.of());
        // 源根不是目录（Optional.empty 即非目录分支）-> 走 mapPhysicalTreeToVirtualPaths
        File physical = writeFile("phys/file.txt", "data");

        manager.savePublicFileMapping("file.txt", physical.toPath(), "42", "file.txt");

        // 物理树登记到 public
        FileMapping m = store.get(key(1, "public", "file.txt"));
        assertNotNull(m);
        assertEquals(1, m.getOpenType());
        assertEquals("public", m.getUserId());
        assertEquals(FileType.TEXT_FILE, m.getFileType());
    }

    @Test
    void savePublicFileMapping_targetUnderPathConflict_throws() {
        // hasMappingUnderPath(1,public,"pubdir") 命中 -> 抛冲突
        wireFindByOwner(1, "public", List.of(active(1, "public", "pubdir/existing.txt", FileType.TEXT_FILE)));

        assertThrows(ServiceException.class,
                () -> manager.savePublicFileMapping("pubdir", Path.of("/phys/src"), "42", "src"));
    }
}
