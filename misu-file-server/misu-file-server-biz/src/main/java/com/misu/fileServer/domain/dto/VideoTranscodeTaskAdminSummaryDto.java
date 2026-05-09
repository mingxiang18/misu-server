package com.misu.fileServer.domain.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class VideoTranscodeTaskAdminSummaryDto {

    private long waitingCount;

    private long runningCount;

    private long failedCount;

    private long doneCount;

    private long skippedCount;

    private List<VideoTranscodeTaskAdminDto> tasks = new ArrayList<>();
}
