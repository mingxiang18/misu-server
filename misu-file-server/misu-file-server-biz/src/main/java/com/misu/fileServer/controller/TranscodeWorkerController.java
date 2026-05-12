package com.misu.fileServer.controller;

import com.misu.common.constant.HttpStatus;
import com.misu.common.domain.AjaxResult;
import com.misu.common.exception.ServiceException;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/**
 * 视频转码 worker 管理接口（透传到 misu-ffmpeg-worker pod）。
 *
 * <p>worker pod 的 HTTP API 走 ClusterIP，本身不做鉴权；外部访问统一经此 controller
 * 转发，由 misu-security 的 JWT 过滤链 + 这里的 ADMIN 校验把关。
 */
@Validated
@RestController
@RequestMapping("/transcodeWorker")
@Api("视频转码 Worker 管理接口")
public class TranscodeWorkerController {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWorkerController.class);

    @Resource
    private RestClient restClient;

    @Value("${video.transcode.worker.baseUrl:http://misu-ffmpeg-worker.misu-server.svc.cluster.local:18765}")
    private String workerBaseUrl;

    @GetMapping("/health")
    @ApiOperation(value = "Worker 健康检查")
    public AjaxResult health() {
        checkAdmin();
        return AjaxResult.success(callGet("/api/health"));
    }

    @GetMapping("/state")
    @ApiOperation(value = "获取 Worker 当前状态 (encoder / 队列计数 / 当前任务进度)")
    public AjaxResult state() {
        checkAdmin();
        return AjaxResult.success(callGet("/api/state"));
    }

    @GetMapping("/tasks")
    @ApiOperation(value = "列出指定 bucket (queue/running/done/failed) 中的任务")
    public AjaxResult tasks(@RequestParam(value = "bucket", defaultValue = "queue") String bucket,
                            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        checkAdmin();
        return AjaxResult.success(callGet("/api/tasks?bucket=" + bucket + "&limit=" + limit));
    }

    @PostMapping("/recoverRunning")
    @ApiOperation(value = "把 running/ 中所有 .task 文件移回 queue/（worker 异常退出后用）")
    public AjaxResult recoverRunning() {
        checkAdmin();
        return AjaxResult.success(callPost("/api/recover-running"));
    }

    @PostMapping("/retry/{taskId}")
    @ApiOperation(value = "把 failed/<taskId>.task 移回 queue/ 重试")
    public AjaxResult retry(@PathVariable("taskId") String taskId) {
        checkAdmin();
        return AjaxResult.success(callPost("/api/retry/" + taskId));
    }

    private Object callGet(String path) {
        try {
            return restClient.get()
                    .uri(workerBaseUrl + path)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.warn("worker GET {} failed: {}", path, e.getMessage());
            // 502 Bad Gateway：worker pod unreachable / 5xx
            throw new ServiceException(502, "无法访问转码 worker：" + e.getMessage());
        }
    }

    private Object callPost(String path) {
        try {
            return restClient.post()
                    .uri(workerBaseUrl + path)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException e) {
            log.warn("worker POST {} failed: {}", path, e.getMessage());
            // 502 Bad Gateway：worker pod unreachable / 5xx
            throw new ServiceException(502, "无法访问转码 worker：" + e.getMessage());
        }
    }

    private void checkAdmin() {
        if (!AuthorityUtil.hasAuthority(UserRole.ADMIN)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "只有 ADMIN 用户可以管理转码 worker");
        }
    }
}
