package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 创建放映室请求实体
 *
 * @author misu
 */
@Data
public class CreateVideoRoomRequestDto {

    @ApiModelProperty("放映室名称")
    private String roomName;

    @ApiModelProperty("视频路径")
    private String videoPath;

    @ApiModelProperty("目录开放标识，0-私人目录，1-公共目录")
    private Integer directoryOpenFlag;

    @ApiModelProperty("目录路径")
    private String directoryPath;
}
