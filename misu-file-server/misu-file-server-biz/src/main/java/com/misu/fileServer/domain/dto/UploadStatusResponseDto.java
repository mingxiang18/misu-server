package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 续传探测响应：列出已落盘的分片索引；前端可据此跳过这些分片，仅传剩下的。
 */
@Data
@NoArgsConstructor
public class UploadStatusResponseDto {

    @ApiModelProperty("已上传分片索引列表（升序）")
    private List<Integer> uploadedChunks;

    @ApiModelProperty("已经全部传完，可以触发合并")
    private Boolean allUploaded;

    @ApiModelProperty("目标 virtualPath（用于前端校验定位）")
    private String virtualPath;
}
