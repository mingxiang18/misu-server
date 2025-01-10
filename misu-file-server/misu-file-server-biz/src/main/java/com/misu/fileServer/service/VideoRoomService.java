package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.*;

/**
 * 放映室相关Service
 *
 * @author misu
 */
public interface VideoRoomService {

    /**
     * 根据id获取对应的放映室数据
     */
    VideoRoomDto getVideoRoomFromId(String roomId);

    /**
     * 根据id获取对应的放映进度
     */
    VideoStateInRoomDto getVideoState(String roomId);

    /**
     * 根据id获取对应的放映室分享链接
     */
    String getVideoRoomShareUrl(String roomId);

    /**
     * 添加视频到放映室
     */
    VideoRoomDto createVideoRoom(CreateVideoRoomRequestDto createVideoRoomRequestDto);

    /**
     * 更新视频播放进度
     */
    void updateVideoState(UpdateVideoStateRequestDto updateVideoStateRequestDto);

    /**
     * 退出放映室
     */
    void quitVideoRoom(VideoRoomRequestDto videoRoomRequestDto);

    /**
     * 关闭放映室
     */
    void closeVideoRoom(VideoRoomRequestDto videoRoomRequestDto);
}
