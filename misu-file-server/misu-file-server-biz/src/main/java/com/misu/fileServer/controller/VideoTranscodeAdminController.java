package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.domain.dto.VideoTranscodeRetryRequestDto;
import com.misu.fileServer.service.VideoTranscodeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/videoTranscodeAdmin")
@Api("视频转码管理接口")
public class VideoTranscodeAdminController {

    @Resource
    private VideoTranscodeService videoTranscodeService;

    @GetMapping("/getTaskSummary")
    @ApiOperation(value = "获取视频转码任务状态")
    public AjaxResult getTaskSummary() {
        return AjaxResult.success(videoTranscodeService.getAdminTaskSummary());
    }

    @PostMapping("/retryFailedTask")
    @ApiOperation(value = "重试单个失败视频转码任务")
    public AjaxResult retryFailedTask(@Valid @RequestBody VideoTranscodeRetryRequestDto requestDto) {
        videoTranscodeService.retryFailedTask(requestDto.getTaskId());
        return AjaxResult.success();
    }

    @PostMapping("/retryAllFailedTasks")
    @ApiOperation(value = "重试全部失败视频转码任务")
    public AjaxResult retryAllFailedTasks() {
        return AjaxResult.success(videoTranscodeService.retryAllFailedTasks());
    }

    @PostMapping("/recoverRunningTasks")
    @ApiOperation(value = "恢复运行中断的视频转码任务")
    public AjaxResult recoverRunningTasks() {
        return AjaxResult.success(videoTranscodeService.recoverRunningTasks());
    }
}
