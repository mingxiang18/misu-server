package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.TorrentUserRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface TorrentUserRelationRepository extends JpaRepository<TorrentUserRelation, Long>, JpaSpecificationExecutor<TorrentUserRelation>, QuerydslPredicateExecutor<TorrentUserRelation> {

}
