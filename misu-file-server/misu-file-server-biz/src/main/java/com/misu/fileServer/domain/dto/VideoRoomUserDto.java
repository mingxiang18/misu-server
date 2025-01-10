package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 放映室观看用户信息实体
 *
 * @author misu
 */
@Data
@NoArgsConstructor
public class VideoRoomUserDto {

    @ApiModelProperty("用户名")
    private String userName;

    @ApiModelProperty("同步时间")
    private LocalDateTime syncTime;

    public VideoRoomUserDto(String userName, LocalDateTime syncTime) {
        this.userName = userName;
        this.syncTime = syncTime;
    }
}