package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RssItemDto {

    @ApiModelProperty("条目id")
    @JsonProperty("itemId")
    private Long id;

    @ApiModelProperty("rss订阅id")
    private Long rssId;

    @ApiModelProperty("标题")
    private String title;

    @ApiModelProperty("torrent hash")
    private String torrentHash;

    @ApiModelProperty("磁力链接")
    private String torrentUrl;

    @ApiModelProperty("描述")
    private String description;

    @ApiModelProperty("发布者")
    private String author;

    @ApiModelProperty("匹配状态，0-未匹配，1-已匹配")
    private Integer matchState;

    @ApiModelProperty("下载状态，0-未下载，1-已下载，2-失败")
    private Integer downloadState;

    @ApiModelProperty("匹配规则id")
    private Long matchedRuleId;

    @ApiModelProperty("错误信息")
    private String errorMessage;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss",timezone = "Asia/Shanghai")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime publishTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss",timezone = "Asia/Shanghai")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime updatedTime;
}
