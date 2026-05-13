package com.misu.fileServer.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 转码任务详情（管理员面板专用）。
 *
 * <p>融合 DB 行 + 磁盘最新状态。</p>
 */
@Data
public class VideoTranscodeJobDto {

    private String taskId;

    private String state;

    private String queueState;

    private Integer progress;

    private String message;

    private String sourcePath;

    private Integer sourceOpenType;

    private String sourceUserId;

    private String sourceVirtualPath;

    private String outputPath;

    private String previewPath;

    private String profileVersion;

    private Integer retryCount;

    private Integer enqueueCount;

    private LocalDateTime lastEnqueuedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Boolean retryable;

    private Boolean reTranscodeable;

    private Boolean priority;
}
