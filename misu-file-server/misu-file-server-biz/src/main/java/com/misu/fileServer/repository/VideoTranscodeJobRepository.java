package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.VideoTranscodeJob;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VideoTranscodeJobRepository extends JpaRepository<VideoTranscodeJob, String> {

    Optional<VideoTranscodeJob> findByTaskId(String taskId);

    List<VideoTranscodeJob> findByTaskIdIn(List<String> taskIds);

    @Query("SELECT j FROM VideoTranscodeJob j "
            + "WHERE (:state IS NULL OR j.state = :state) "
            + "AND (:queueState IS NULL OR j.queueState = :queueState) "
            + "AND (:keyword IS NULL OR LOWER(j.sourceVirtualPath) LIKE :keyword "
            + "     OR LOWER(j.sourcePath) LIKE :keyword "
            + "     OR LOWER(j.taskId) LIKE :keyword) "
            + "ORDER BY j.updateTime DESC NULLS LAST, j.createTime DESC")
    Page<VideoTranscodeJob> searchJobs(@Param("state") String state,
                                       @Param("queueState") String queueState,
                                       @Param("keyword") String keyword,
                                       Pageable pageable);
}
