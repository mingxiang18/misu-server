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

    @ApiModelProperty("文件播放/预览链接")
    private String streamLink;

    @ApiModelProperty("视频封面链接")
    private String videoPreviewLink;

    @ApiModelProperty("视频转码后播放链接")
    private String transcodedStreamLink;

    @ApiModelProperty("视频转码状态")
    private String transcodeState;

    @ApiModelProperty("视频转码进度")
    private Integer transcodeProgress;

    @ApiModelProperty("视频转码提示")
    private String transcodeMessage;

    @ApiModelProperty("允许转码的最大视频大小")
    private Long transcodeMaxBytes;

    @JsonIgnore
    @ApiModelProperty("文件数据")
    private File file;
}
