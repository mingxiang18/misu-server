package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.VideoTranscodeJobDto;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.dto.VideoTranscodeTaskAdminSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.File;
import java.util.List;
import java.util.Map;

public interface VideoTranscodeService {

    VideoTranscodeStatusDto getOrCreateTranscodeStatus(File sourceFile);

    /**
     * 与上面的重载相比，多带"源文件归属"上下文，便于落库后做关键字搜索 / 按用户筛选。
     * 调用方拿不到上下文时（如外链 / 共享间接路径），传 null 即可。
     */
    VideoTranscodeStatusDto getOrCreateTranscodeStatus(File sourceFile,
                                                       Integer sourceOpenType,
                                                       String sourceUserId,
                                                       String sourceVirtualPath);

    File getTranscodedFile(File sourceFile);

    File getVideoPreviewFile(File sourceFile);

    long getMaxBytes();

    VideoTranscodeTaskAdminSummaryDto getAdminTaskSummary();

    void retryFailedTask(String taskId);

    int retryAllFailedTasks();

    int recoverRunningTasks();

    /**
     * 分页查询转码任务（管理员面板专用）。
     *
     * @param state      过滤 state（{@link com.misu.fileServer.constant.VideoTranscodeState}），null 表示不过滤
     * @param queueState 过滤 queueState（WAITING / RUNNING / FAILED / DONE / UNKNOWN），null 不过滤
     * @param keyword    源虚拟路径 / 源物理路径 / taskId 关键字（大小写不敏感子串），null / 空不过滤
     */
    Page<VideoTranscodeJobDto> queryJobs(String state, String queueState, String keyword, Pageable pageable);

    /**
     * 批量重试：对指定 taskId 集合中处于 FAILED 队列的任务做 failed → queue 文件迁移。
     *
     * @return 实际迁移数量
     */
    int retryJobsBatch(List<String> taskIds);

    /**
     * 强制对任意状态的任务（含 SUCCESS / DONE）重新转码：清理产物 + 状态文件 + 残留 task 文件后重新 enqueue。
     *
     * @return 实际重排数量
     */
    int reTranscodeJobsBatch(List<String> taskIds);

    /**
     * 把指定 taskId 集合提到队列最前面 —— 通过给 .task 文件加 "!priority-" 前缀实现（worker 按文件名 ASCII
     * 升序领取，'!' 0x21 < '0' 0x30 < 字母）。
     *
     * <p>已在 RUNNING 队列的任务无法被抢占；处于 FAILED / DONE 的任务会先被 re-enqueue 再标 priority。</p>
     *
     * @return 实际提升优先级的任务数量
     */
    int prioritizeJobsBatch(List<String> taskIds);

    /**
     * 移除任务：等待中的直接删除 .task；运行中的写 cancel marker 文件让 worker 脚本主动 kill 自己的 ffmpeg；
     * 所有状态都会清产物 + status + DB 行，使任务从管理界面消失。
     *
     * @return 实际移除数量
     */
    int cancelJobsBatch(List<String> taskIds);

    /**
     * 手动触发"扫描所有视频 → 给尚未转码的入队"一轮（管理员）。异步执行，立即返回；进度见 {@link #getScanStatus()}。
     * 与定时任务 video.transcode.autoScan 共用同一套扫描逻辑。
     */
    void startScanPending();

    /**
     * 自动转码扫描的运行状态（管理员）：是否开启、是否运行中、上次扫描时间 / 扫描数量 / 入队数量 / 错误。
     */
    Map<String, Object> getScanStatus();
}
