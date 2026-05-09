package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.service.VideoRoomService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 放映室相关Controller
 *
 * @author misu
 */
@Validated
@RestController
@RequestMapping("/videoRoom")
@Api("放映室相关接口")
public class VideoRoomController {

    @Resource
    private VideoRoomService videoRoomService;

    /**
     * 根据id获取对应的放映室数据
     */
    @GetMapping({"/getVideoRoomFromId"})
    @ApiOperation(value="根据id获取对应的放映室数据")
    public AjaxResult getVideoRoomFromId(@Valid VideoRoomRequestDto videoRoomRequestDto) {
        return AjaxResult.success(videoRoomService.getVideoRoomFromId(videoRoomRequestDto.getRoomId()));
    }

    /**
     * 根据id获取对应的放映进度
     */
    @GetMapping({"/getVideoState"})
    @ApiOperation(value="根据id获取对应的放映进度")
    public AjaxResult getVideoState(@Valid VideoRoomRequestDto videoRoomRequestDto) {
        return AjaxResult.success(videoRoomService.getVideoState(videoRoomRequestDto.getRoomId()));
    }

    /**
     * 获取房间成员列表
     */
    @GetMapping({"/getRoomMembers"})
    @ApiOperation(value="获取房间成员列表")
    public AjaxResult getRoomMembers(@Valid VideoRoomRequestDto videoRoomRequestDto) {
        return AjaxResult.success(videoRoomService.getRoomMembers(videoRoomRequestDto.getRoomId()));
    }

    /**
     * 发送放映室评论
     */
    @PostMapping({"/sendComment"})
    @ApiOperation(value="发送放映室评论")
    public AjaxResult sendComment(@Valid @RequestBody SendVideoRoomCommentRequestDto requestDto) {
        videoRoomService.sendComment(requestDto);
        return AjaxResult.success();
    }

    /**
     * 获取放映室评论
     */
    @GetMapping({"/getComments"})
    @ApiOperation(value="获取放映室评论")
    public AjaxResult getComments(@Valid VideoRoomRequestDto videoRoomRequestDto) {
        return AjaxResult.success(videoRoomService.getComments(videoRoomRequestDto.getRoomId()));
    }

    /**
     * 根据id获取对应的放映室分享链接
     */
    @GetMapping({"/getVideoRoomShareUrl"})
    @ApiOperation(value="根据id获取对应的放映室分享链接")
    public AjaxResult getVideoRoomShareUrl(@Valid VideoRoomRequestDto videoRoomRequestDto) {
        return AjaxResult.success(videoRoomService.getVideoRoomShareUrl(videoRoomRequestDto.getRoomId()));
    }

    @GetMapping({"/myActiveRoom"})
    @ApiOperation(value="获取当前用户活动放映室")
    public AjaxResult getMyActiveRoom() {
        return AjaxResult.success(videoRoomService.getMyActiveRoom());
    }

    @PostMapping({"/playMyVideo"})
    @ApiOperation(value="播放当前用户选中的视频")
    public AjaxResult playMyVideo(@Valid @RequestBody PlayMyVideoRequestDto requestDto) {
        return AjaxResult.success(videoRoomService.playMyVideo(requestDto));
    }

    /**
     * 更新放映室视频状态
     */
    @PostMapping({"/updateVideoState"})
    @ApiOperation(value="更新放映室视频状态")
    public AjaxResult updateVideoState(@Valid @RequestBody UpdateVideoStateRequestDto updateVideoStateRequestDto) {
        videoRoomService.updateVideoState(updateVideoStateRequestDto);
        return AjaxResult.success();
    }

    /**
     * 退出放映室
     */
    @PostMapping({"/quitVideoRoom"})
    @ApiOperation(value="退出放映室")
    public AjaxResult quitVideoRoom(@Valid @RequestBody VideoRoomRequestDto videoRoomRequestDto) {
        videoRoomService.quitVideoRoom(videoRoomRequestDto);
        return AjaxResult.success();
    }

    /**
     * 关闭放映室
     */
    @PostMapping({"/closeVideoRoom"})
    @ApiOperation(value="关闭放映室")
    public AjaxResult closeVideoRoom(@Valid @RequestBody VideoRoomRequestDto videoRoomRequestDto) {
        videoRoomService.closeVideoRoom(videoRoomRequestDto);
        return AjaxResult.success();
    }

    /**
     * 播放放映室当前视频
     */
    @GetMapping({"/video"})
    @ApiOperation(value="播放放映室当前视频")
    public void streamRoomVideo(@Valid VideoRoomRequestDto videoRoomRequestDto,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        videoRoomService.streamRoomVideo(videoRoomRequestDto.getRoomId(), request, response);
    }
}
