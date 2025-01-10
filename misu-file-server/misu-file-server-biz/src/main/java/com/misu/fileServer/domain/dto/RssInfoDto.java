package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RssInfoDto {

    @ApiModelProperty("rss订阅id")
    @JsonProperty("rssId")
    private Long id;

    @ApiModelProperty("rss订阅链接")
    private String rssUrl;

    @ApiModelProperty("rss订阅名称")
    private String rssName;

    @ApiModelProperty("rss订阅下载的文件保存位置")
    private String downloadPath;

    @ApiModelProperty("备注")
    private String remark;

    @ApiModelProperty("创建人id")
    private String creatorId;

    @ApiModelProperty("rss订阅状态，0-未知，1-可用，99-不可用")
    private Integer state;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss",timezone = "Asia/Shanghai")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime createTime;

}