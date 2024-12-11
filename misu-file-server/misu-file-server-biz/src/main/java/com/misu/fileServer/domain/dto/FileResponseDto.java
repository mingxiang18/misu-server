package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.File;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 文件响应dto
 */
@Data
public class FileResponseDto {

    @ApiModelProperty("随机一个UUID")
    private String fileId = UUID.randomUUID().toString();

    @ApiModelProperty("文件名称")
    private String fileName;

    @ApiModelProperty("文件类型")
    private String fileType;

    @ApiModelProperty("文件路径")
    private String filePath;

    @ApiModelProperty("文件大小")
    private Long fileSize;

    @ApiModelProperty("文件上传时间")
    private LocalDateTime fileUploadTime;

    @ApiModelProperty("文件预览链接")
    private String previewLink;

    @ApiModelProperty("文件下载链接")
    private String downloadLink;

    @JsonIgnore
    @ApiModelProperty("文件数据")
    private File file;
}
