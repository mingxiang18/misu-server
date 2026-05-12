package com.misu.fileServer.controller;

import com.alibaba.fastjson2.JSON;
import com.misu.common.constant.HttpStatus;
import com.misu.common.domain.AjaxResult;
import com.misu.common.exception.ServiceException;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 视频转码 worker 管理接口（透传到 misu-ffmpeg-worker pod）。
 *
 * <p>worker pod 的 HTTP API 走 ClusterIP，本身不做鉴权；外部访问统一经此 controller
 * 转发，由 misu-security 的 JWT 过滤链 + 这里的 ADMIN 校验把关。
 *
 * <p>实现细节：故意不复用项目的 RestClient / RestUtils 组件 —— 那两个共用同一个
 * RestClient bean，bean 在 prod 被 {@code rest.proxyIp/proxyPort} 配成走外网 HTTP
 * 代理，代理不认 {@code *.svc.cluster.local} 域名，所有请求返回 502 Bad Gateway。
 * 这里改用 JDK 自带的 {@link HttpClient}（无代理、无 LB 拦截器），直接打 ClusterIP。
 */
@Validated
@RestController
@RequestMapping("/transcodeWorker")
@Api("视频转码 Worker 管理接口")
public class TranscodeWorkerController {

    private static final Logger log = LoggerFactory.getLogger(TranscodeWorkerController.class);

    /** stdlib HttpClient：NO_PROXY，连接超时 3s，请求超时通过单次 send 设置 5s。 */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

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

    private Map<String, Object> callGet(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(workerBaseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return execute(req, "GET", path);
    }

    private Map<String, Object> callPost(String path) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(workerBaseUrl + path))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return execute(req, "POST", path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(HttpRequest req, String method, String path) {
        try {
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            int code = resp.statusCode();
            String body = resp.body();
            if (code / 100 != 2) {
                log.warn("worker {} {} → {} body={}", method, path, code, body);
                throw new ServiceException(code, "转码 worker 返回 " + code + ": " + body);
            }
            if (body == null || body.isEmpty()) {
                return Map.of();
            }
            return JSON.parseObject(body, Map.class);
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("worker {} {} failed: {}", method, path, e.toString());
            throw new ServiceException(HttpStatus.ERROR, "无法访问转码 worker：" + e.getMessage());
        }
    }

    private void checkAdmin() {
        if (!AuthorityUtil.hasAuthority(UserRole.ADMIN)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "只有 ADMIN 用户可以管理转码 worker");
        }
    }
}
