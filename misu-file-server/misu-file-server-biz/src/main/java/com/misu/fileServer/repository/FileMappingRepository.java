package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.FileMapping;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileMappingRepository extends JpaRepository<FileMapping, Long> {

    List<FileMapping> findByOpenTypeAndUserIdAndDeletedFalse(Integer openType, String userId);

    List<FileMapping> findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(Integer openType,
                                                                                                          String userId,
                                                                                                          String parentPath);

    Optional<FileMapping> findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(Integer openType,
                                                                                     String userId,
                                                                                     String virtualPath);
}
