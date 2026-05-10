package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.FileMapping;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface FileMappingRepository extends JpaRepository<FileMapping, Long> {

    List<FileMapping> findByOpenTypeAndUserIdAndDeletedFalse(Integer openType, String userId);

    List<FileMapping> findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(Integer openType,
                                                                                                          String userId,
                                                                                                          String parentPath);

    Optional<FileMapping> findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(Integer openType,
                                                                                     String userId,
                                                                                     String virtualPath);

    /**
     * 文件名模糊搜索（分页），命中复合索引 idx_fm_owner_deleted_type_name 的左前缀。
     */
    Page<FileMapping> findByOpenTypeAndUserIdAndDeletedFalseAndFileNameContainingIgnoreCaseOrderByFileTypeDescFileNameAsc(
            Integer openType, String userId, String fileName, Pageable pageable);

    /**
     * 限定 fileType 的文件名搜索（分页）。
     */
    Page<FileMapping> findByOpenTypeAndUserIdAndDeletedFalseAndFileTypeAndFileNameContainingIgnoreCaseOrderByFileNameAsc(
            Integer openType, String userId, String fileType, String fileName, Pageable pageable);

    /**
     * 回收站列表（分页），按删除时间倒序。
     */
    Page<FileMapping> findByOpenTypeAndUserIdAndDeletedTrueOrderByUpdateTimeDesc(
            Integer openType, String userId, Pageable pageable);

    /**
     * 子树查询：精确等于 path 或者 virtualPath 以 prefix 起头。配合调用方传入 prefix = path + "/"。
     * 替代旧的"全捞 + 内存 filter startsWith"模式，避免大量 mapping 时内存膨胀。
     */
    @Query("SELECT fm FROM FileMapping fm "
            + "WHERE fm.openType = :openType AND fm.userId = :userId AND fm.deleted = false "
            + "AND (fm.virtualPath = :path OR fm.virtualPath LIKE :prefixLike)")
    List<FileMapping> findActiveSubtree(@Param("openType") Integer openType,
                                        @Param("userId") String userId,
                                        @Param("path") String path,
                                        @Param("prefixLike") String prefixLike);

    /**
     * 哈希秒传：在所有用户范围内查找内容相同（md5+size）且未删除的物理文件，用于秒传指向同一 targetPath。
     */
    List<FileMapping> findFirst5ByFileMd5AndFileSizeAndDeletedFalse(String fileMd5, Long fileSize);

    /**
     * 当前用户已使用容量（不计目录占位）。
     */
    @Query("SELECT COALESCE(SUM(fm.fileSize), 0) FROM FileMapping fm "
            + "WHERE fm.openType = :openType AND fm.userId = :userId "
            + "AND fm.deleted = false AND fm.fileType <> 'directory'")
    long sumUsedBytes(@Param("openType") Integer openType, @Param("userId") String userId);

    /**
     * 当前用户已用文件数（不计目录占位）。
     */
    @Query("SELECT COUNT(fm) FROM FileMapping fm "
            + "WHERE fm.openType = :openType AND fm.userId = :userId "
            + "AND fm.deleted = false AND fm.fileType <> 'directory'")
    long countUsedFiles(@Param("openType") Integer openType, @Param("userId") String userId);

    /**
     * GC 流式扫描：仅处理已逻辑删除且过保留期的 mapping（含 updateTime 为空时回落到 createTime），避免一次性载入全表。
     */
    @Query("SELECT fm FROM FileMapping fm "
            + "WHERE fm.deleted = true "
            + "AND COALESCE(fm.updateTime, fm.createTime) <= :threshold")
    Stream<FileMapping> streamDeletedBefore(@Param("threshold") LocalDateTime threshold);

    /**
     * GC 辅助：取所有未删除 mapping 的不重复 targetPath，避免遍历全表去过滤 active 引用集合。
     */
    @Query("SELECT DISTINCT fm.targetPath FROM FileMapping fm WHERE fm.deleted = false AND fm.targetPath <> ''")
    List<String> findDistinctActiveTargetPaths();
}
