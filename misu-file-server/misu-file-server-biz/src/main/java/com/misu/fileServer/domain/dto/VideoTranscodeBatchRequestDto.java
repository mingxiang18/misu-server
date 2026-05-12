package com.misu.fileServer.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class VideoTranscodeBatchRequestDto {

    @NotEmpty
    private List<String> taskIds;
}
