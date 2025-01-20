package com.misu.fileServer.dao;

import com.misu.fileServer.domain.entity.VideoRoom;

import java.util.Optional;

/**
 * 放映室数据层
 */
public interface VideoRoomDao {

    /**
     * 通过放映室id查询
     */
    Optional<VideoRoom> selectByRoomId(String roomId);

    /**
     * 通过创建人id查询放映室
     */
    Optional<VideoRoom> selectOneByCreatorId(String creatorId);

    /**
     * 保存
     */
    VideoRoom save(VideoRoom entity);

    /**
     * 根据id更新
     */
    long updateById(VideoRoom entity);
}
