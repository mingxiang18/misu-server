package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 放映室信息实体
 *
 * @author misu
 */
@Data
public class VideoRoomDto {

    @ApiModelProperty("放映室id")
    private String roomId;

    @ApiModelProperty("放映室名称")
    private String roomName;

    @ApiModelProperty("目录开放标识，0-私人目录，1-公共目录")
    private Integer directoryOpenFlag;

    @ApiModelProperty("目录路径")
    private String directoryPath;

    @ApiModelProperty("当前放映视频路径")
    private String videoPath;

    @ApiModelProperty("创建人id")
    private String creatorId;

    @ApiModelProperty("当前用户是否房间创建者标识")
    private Boolean creatorFlag = false;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}