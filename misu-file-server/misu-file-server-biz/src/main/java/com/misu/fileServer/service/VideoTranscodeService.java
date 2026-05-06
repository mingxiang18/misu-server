package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;

import java.io.File;

public interface VideoTranscodeService {

    VideoTranscodeStatusDto getOrCreateTranscodeStatus(File sourceFile);

    File getTranscodedFile(File sourceFile);

    File getVideoPreviewFile(File sourceFile);

    long getMaxBytes();
}
