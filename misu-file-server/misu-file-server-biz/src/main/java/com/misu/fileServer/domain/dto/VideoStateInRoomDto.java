package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 放映室信息实体
 *
 * @author misu
 */
@Data
public class VideoStateInRoomDto {

    @ApiModelProperty("放映室id")
    @JsonSerialize(using = ToStringSerializer.class)
    private String roomId;

    @ApiModelProperty("目录开放标识，0-私人目录，1-公共目录")
    private Integer directoryOpenFlag;

    @ApiModelProperty("目录路径")
    private String directoryPath;

    @ApiModelProperty("当前放映视频路径")
    private String videoPath;

    @ApiModelProperty("当前状态：play-播放，pause-暂停")
    private String state;

    @ApiModelProperty("视频进度")
    private LocalTime videoTime;

    @ApiModelProperty("同步时间")
    private LocalDateTime syncTime;

    @ApiModelProperty("修正时间后的播放时间")
    private LocalTime playTime;

    @ApiModelProperty("放映室用户列表")
    private List<VideoRoomUserDto> videoRoomUserList;
}