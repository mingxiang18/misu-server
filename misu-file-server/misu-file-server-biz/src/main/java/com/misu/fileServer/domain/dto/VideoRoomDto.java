package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

    @ApiModelProperty("当前放映视频路径")
    private String videoPath;

    @ApiModelProperty("创建人id")
    private String creatorId;

    @ApiModelProperty("当前用户是否房间创建者标识")
    private Boolean creatorFlag = false;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}