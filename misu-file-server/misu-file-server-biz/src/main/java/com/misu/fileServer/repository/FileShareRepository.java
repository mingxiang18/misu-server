package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.FileShare;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface FileShareRepository extends JpaRepository<FileShare, Long> {

    Optional<FileShare> findFirstByShareToken(String shareToken);

    Page<FileShare> findByOwnerUserIdAndRevokedFalseOrderByCreateTimeDesc(String ownerUserId, Pageable pageable);

    /**
     * 原子加 1 download_count，并返回受影响行数。
     */
    @Modifying
    @Query("UPDATE FileShare fs SET fs.downloadCount = fs.downloadCount + 1, fs.updateTime = :now WHERE fs.id = :id")
    int incrementDownloadCount(@Param("id") Long id, @Param("now") LocalDateTime now);
}
