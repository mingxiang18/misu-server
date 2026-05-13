package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.domain.dto.VideoTranscodeBatchRequestDto;
import com.misu.fileServer.domain.dto.VideoTranscodeRetryRequestDto;
import com.misu.fileServer.service.VideoTranscodeService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/jobs")
    @ApiOperation(value = "分页查询转码任务（DB 镜像 + 磁盘 reconcile）")
    public AjaxResult queryJobs(@RequestParam(value = "state", required = false) String state,
                                @RequestParam(value = "queueState", required = false) String queueState,
                                @RequestParam(value = "keyword", required = false) String keyword,
                                @RequestParam(value = "page", required = false, defaultValue = "0") int page,
                                @RequestParam(value = "size", required = false, defaultValue = "20") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 200);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return AjaxResult.success(videoTranscodeService.queryJobs(state, queueState, keyword, pageable));
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

    @PostMapping("/retryBatch")
    @ApiOperation(value = "按 taskId 列表批量重试失败任务")
    public AjaxResult retryBatch(@Valid @RequestBody VideoTranscodeBatchRequestDto requestDto) {
        return AjaxResult.success(videoTranscodeService.retryJobsBatch(requestDto.getTaskIds()));
    }

    @PostMapping("/reTranscodeBatch")
    @ApiOperation(value = "按 taskId 列表强制重新转码（含 SUCCESS / DONE）")
    public AjaxResult reTranscodeBatch(@Valid @RequestBody VideoTranscodeBatchRequestDto requestDto) {
        return AjaxResult.success(videoTranscodeService.reTranscodeJobsBatch(requestDto.getTaskIds()));
    }

    @PostMapping("/prioritizeBatch")
    @ApiOperation(value = "按 taskId 列表把队列内任务提为最优先")
    public AjaxResult prioritizeBatch(@Valid @RequestBody VideoTranscodeBatchRequestDto requestDto) {
        return AjaxResult.success(videoTranscodeService.prioritizeJobsBatch(requestDto.getTaskIds()));
    }

    @PostMapping("/recoverRunningTasks")
    @ApiOperation(value = "恢复运行中断的视频转码任务")
    public AjaxResult recoverRunningTasks() {
        return AjaxResult.success(videoTranscodeService.recoverRunningTasks());
    }
}
