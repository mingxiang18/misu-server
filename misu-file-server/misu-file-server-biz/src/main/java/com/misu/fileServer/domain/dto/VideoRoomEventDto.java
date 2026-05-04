package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VideoRoomEventDto {

    @ApiModelProperty("事件id")
    @JsonProperty("eventId")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @ApiModelProperty("放映室id")
    private String roomId;

    @ApiModelProperty("事件类型")
    private String eventType;

    @ApiModelProperty("用户id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @ApiModelProperty("用户名")
    private String userName;

    @ApiModelProperty("播放状态")
    private String state;

    @ApiModelProperty("视频进度秒数")
    private Long videoTimeSeconds;

    @ApiModelProperty("客户端发送时间戳")
    private Long clientSendTime;

    @ApiModelProperty("服务端接收时间戳")
    private Long serverReceiveTime;

    @ApiModelProperty("内容")
    private String content;

    @ApiModelProperty("扩展内容")
    private String payload;

    @ApiModelProperty("创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Shanghai")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime createTime;
}
