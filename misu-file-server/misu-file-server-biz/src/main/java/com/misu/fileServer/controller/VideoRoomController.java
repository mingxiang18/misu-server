package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.service.VideoRoomService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
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
     * 根据id获取对应的放映室分享链接
     */
    @GetMapping({"/getVideoRoomShareUrl"})
    @ApiOperation(value="根据id获取对应的放映室分享链接")
    public AjaxResult getVideoRoomShareUrl(@Valid VideoRoomRequestDto videoRoomRequestDto) {
        return AjaxResult.success(videoRoomService.getVideoRoomShareUrl(videoRoomRequestDto.getRoomId()));
    }

    /**
     * 创建放映室
     */
    @PostMapping({"/createVideoRoom"})
    @ApiOperation(value="创建放映室")
    public AjaxResult createVideoRoom(@Valid @RequestBody CreateVideoRoomRequestDto createVideoRoomRequestDto) {
        return AjaxResult.success(videoRoomService.createVideoRoom(createVideoRoomRequestDto));
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
}
