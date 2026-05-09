package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.dto.VideoTranscodeTaskAdminDto;
import com.misu.fileServer.domain.dto.VideoTranscodeTaskAdminSummaryDto;

import java.io.File;

public interface VideoTranscodeService {

    VideoTranscodeStatusDto getOrCreateTranscodeStatus(File sourceFile);

    File getTranscodedFile(File sourceFile);

    File getVideoPreviewFile(File sourceFile);

    long getMaxBytes();

    VideoTranscodeTaskAdminSummaryDto getAdminTaskSummary();

    void retryFailedTask(String taskId);

    int retryAllFailedTasks();

    int recoverRunningTasks();
}
