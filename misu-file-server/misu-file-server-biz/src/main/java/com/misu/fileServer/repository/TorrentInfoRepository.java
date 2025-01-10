package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.TorrentInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface TorrentInfoRepository extends JpaRepository<TorrentInfo, Long>, JpaSpecificationExecutor<TorrentInfo>, QuerydslPredicateExecutor<TorrentInfo> {

}
