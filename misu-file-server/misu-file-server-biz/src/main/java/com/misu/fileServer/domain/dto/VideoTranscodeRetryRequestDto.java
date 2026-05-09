package com.misu.fileServer.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class VideoTranscodeRetryRequestDto {

    @NotBlank
    private String taskId;
}
