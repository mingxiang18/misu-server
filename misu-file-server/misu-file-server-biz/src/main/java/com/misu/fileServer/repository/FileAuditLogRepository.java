package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.FileAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface FileAuditLogRepository extends JpaRepository<FileAuditLog, Long> {

    /**
     * 综合筛选 — 任意条件可缺省，按 create_time 倒序分页。
     */
    @Query("SELECT a FROM FileAuditLog a "
            + "WHERE (:userId IS NULL OR a.userId = :userId) "
            + "AND (:actionType IS NULL OR a.actionType = :actionType) "
            + "AND (:since IS NULL OR a.createTime >= :since) "
            + "AND (:until IS NULL OR a.createTime <= :until) "
            + "ORDER BY a.createTime DESC")
    Page<FileAuditLog> search(@Param("userId") String userId,
                               @Param("actionType") String actionType,
                               @Param("since") LocalDateTime since,
                               @Param("until") LocalDateTime until,
                               Pageable pageable);
}
