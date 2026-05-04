package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RssRuleDto {

    @ApiModelProperty("规则id")
    @JsonProperty("ruleId")
    private Long id;

    @ApiModelProperty("rss订阅id")
    private Long rssId;

    @ApiModelProperty("规则名称")
    private String ruleName;

    @ApiModelProperty("包含关键词，多个用逗号分隔")
    private String includeKeywords;

    @ApiModelProperty("排除关键词，多个用逗号分隔")
    private String excludeKeywords;

    @ApiModelProperty("正则")
    private String regex;

    @ApiModelProperty("下载路径")
    private String downloadPath;

    @ApiModelProperty("是否启用")
    private Boolean enabled;

    @ApiModelProperty("是否自动下载")
    private Boolean autoDownload;

    @ApiModelProperty("备注")
    private String remark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss",timezone = "Asia/Shanghai")
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDateTime createTime;
}
