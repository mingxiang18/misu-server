package com.misu.fileServer.domain.dto;

import lombok.Data;

@Data
public class VideoTranscodeStatusDto {
    private String taskId;
    private String state;
    private Integer progress;
    private String message;
    private String previewPath;
    private String transcodedPath;
}
