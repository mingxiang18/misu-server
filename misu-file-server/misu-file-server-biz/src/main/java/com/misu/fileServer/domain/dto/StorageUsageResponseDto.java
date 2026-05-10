package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class StorageUsageResponseDto {

    @ApiModelProperty("已用字节数")
    private Long usedBytes;

    @ApiModelProperty("配额字节数；null 表示不限制")
    private Long quotaBytes;

    @ApiModelProperty("文件数量（不含目录）")
    private Long fileCount;

    @ApiModelProperty("公开类型 0-私人 1-公共")
    private Integer openType;
}
