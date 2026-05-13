package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.VideoTranscodeJobDto;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.dto.VideoTranscodeTaskAdminSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.io.File;
import java.util.List;

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
}
