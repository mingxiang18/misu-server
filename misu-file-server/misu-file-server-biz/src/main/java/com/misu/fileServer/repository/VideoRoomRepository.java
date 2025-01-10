package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.VideoRoom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

public interface VideoRoomRepository extends JpaRepository<VideoRoom, Long>, JpaSpecificationExecutor<VideoRoom>, QuerydslPredicateExecutor<VideoRoom> {

}
