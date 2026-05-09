package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.*;
import com.misu.security.dto.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

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
     * 获取房间成员列表
     */
    List<VideoRoomUserDto> getRoomMembers(String roomId);

    /**
     * 发送放映室评论
     */
    void sendComment(SendVideoRoomCommentRequestDto requestDto);

    /**
     * 获取放映室评论
     */
    List<VideoRoomCommentDto> getComments(String roomId);

    /**
     * 记录播放事件
     */
    VideoRoomEventDto recordPlaybackEvent(String roomId, LoginUser loginUser, String state, Long videoTimeSeconds,
                                          Long clientSendTime, String payload);

    /**
     * 记录评论事件
     */
    VideoRoomEventDto recordCommentEvent(String roomId, LoginUser loginUser, String content, Long clientSendTime);

    /**
     * 根据id获取对应的放映室分享链接
     */
    String getVideoRoomShareUrl(String roomId);

    /**
     * 获取当前用户活动放映室
     */
    VideoRoomDto getMyActiveRoom();

    /**
     * 播放当前用户选中的视频（如无活动放映室则创建）
     */
    VideoRoomDto playMyVideo(PlayMyVideoRequestDto requestDto);

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

    /**
     * 播放放映室当前视频。内部文件按房主身份解析，避免观众访问房主私人目录失败。
     */
    void streamRoomVideo(String roomId, HttpServletRequest request, HttpServletResponse response);
}
