package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.TorrentFileMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TorrentFileMappingRepository extends JpaRepository<TorrentFileMapping, Long> {

    List<TorrentFileMapping> findByOpenTypeAndUserIdAndDeletedFalse(Integer openType, String userId);

    Optional<TorrentFileMapping> findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(Integer openType,
                                                                                           String userId,
                                                                                           String virtualPath);
}
