package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 回收站项响应。比 FileResponseDto 多了 deleteTime / originalPath 用于展示。
 */
@Data
@NoArgsConstructor
public class TrashFileResponseDto {

    @ApiModelProperty("文件 mapping id，用于还原 / 永久删除")
    private Long id;

    @ApiModelProperty("文件名")
    private String fileName;

    @ApiModelProperty("文件类型")
    private String fileType;

    @ApiModelProperty("文件大小")
    private Long fileSize;

    @ApiModelProperty("原始虚拟路径（删除前）")
    private String originalPath;

    @ApiModelProperty("原始父目录")
    private String originalParentPath;

    @ApiModelProperty("删除时间（取 update_time）")
    private LocalDateTime deleteTime;
}
