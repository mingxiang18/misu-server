package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.FileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FileVersionRepository extends JpaRepository<FileVersion, Long> {

    List<FileVersion> findByMappingIdOrderByVersionNoDesc(Long mappingId);

    @Query("SELECT MAX(fv.versionNo) FROM FileVersion fv WHERE fv.mappingId = :mappingId")
    Integer findMaxVersionNoByMappingId(@Param("mappingId") Long mappingId);

    long countByMappingId(Long mappingId);

    /** 取最旧的 N 个版本（用于超额淘汰） */
    @Query("SELECT fv FROM FileVersion fv WHERE fv.mappingId = :mappingId ORDER BY fv.versionNo ASC")
    List<FileVersion> findOldestByMapping(@Param("mappingId") Long mappingId);

    void deleteByMappingId(Long mappingId);
}
