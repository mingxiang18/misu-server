package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 播放用户自己选中的视频请求
 */
@Data
public class PlayMyVideoRequestDto {

    @NotBlank(message = "视频名称不能为空")
    @ApiModelProperty("视频名称")
    private String roomName;

    @NotNull(message = "目录开放标识不能为空")
    @ApiModelProperty("目录开放标识，0-私人目录，1-公共目录")
    private Integer directoryOpenFlag;

    @NotBlank(message = "视频文件路径不能为空")
    @ApiModelProperty("视频文件路径（相对用户根目录）")
    private String filePath;

    @ApiModelProperty("是否优先使用转码视频")
    private Boolean preferTranscoded = false;
}
