package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
public class VideoRoomWsMessageDto {

    @ApiModelProperty("消息类型")
    private String type;

    @ApiModelProperty("播放状态")
    private String state;

    @ApiModelProperty("视频进度秒数")
    private Long videoTimeSeconds;

    @ApiModelProperty("客户端发送时间戳")
    private Long clientSendTime;

    @ApiModelProperty("评论内容")
    private String content;

    @ApiModelProperty("扩展内容")
    private String payload;
}
