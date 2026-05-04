package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.VideoRoomEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface VideoRoomEventRepository extends JpaRepository<VideoRoomEvent, Long>, JpaSpecificationExecutor<VideoRoomEvent> {

    List<VideoRoomEvent> findTop100ByRoomIdAndEventTypeOrderByCreateTimeDescIdDesc(String roomId, String eventType);
}
