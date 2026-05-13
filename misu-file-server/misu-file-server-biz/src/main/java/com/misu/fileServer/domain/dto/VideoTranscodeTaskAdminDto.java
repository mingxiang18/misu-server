package com.misu.fileServer.domain.dto;

import lombok.Data;

@Data
public class VideoTranscodeTaskAdminDto {

    private String taskId;

    private String queueState;

    private String state;

    private Integer progress;

    private String message;

    private String sourcePath;

    private String outputPath;

    private String previewPath;

    private String statusPath;

    private String taskPath;

    private String updateTime;

    private Boolean retryable;

    private Boolean priority;
}
