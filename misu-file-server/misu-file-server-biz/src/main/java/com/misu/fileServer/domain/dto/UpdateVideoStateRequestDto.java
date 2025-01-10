package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;

/**
 * 放映室信息实体
 *
 * @author misu
 */
@Data
public class UpdateVideoStateRequestDto {

    @ApiModelProperty("放映室id")
    @NotNull(message = "放映室id不能为空")
    private String roomId;

    @ApiModelProperty("当前状态：play-播放，pause-暂停")
    @NotBlank(message = "放映室当前状态不能为空")
    private String state;

    @ApiModelProperty("视频进度")
    @JsonFormat(pattern = "HH:mm:ss")
    @NotNull(message = "放映室视频进度不能为空")
    private LocalTime videoTime;
}