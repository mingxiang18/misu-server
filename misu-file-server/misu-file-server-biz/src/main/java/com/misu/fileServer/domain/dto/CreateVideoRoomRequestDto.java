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
}
