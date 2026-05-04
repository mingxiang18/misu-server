package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AddRssRuleRequestDto {

    @ApiModelProperty("rss订阅id")
    @NotNull(message = "rss订阅id不能为空")
    private Long rssId;

    @ApiModelProperty("规则名称")
    private String ruleName;

    @ApiModelProperty("包含关键词，多个用逗号分隔")
    private String includeKeywords;

    @ApiModelProperty("排除关键词，多个用逗号分隔")
    private String excludeKeywords;

    @ApiModelProperty("正则")
    private String regex;

    @ApiModelProperty("下载路径，为空则使用订阅默认目录")
    private String downloadPath;

    @ApiModelProperty("是否启用")
    private Boolean enabled = true;

    @ApiModelProperty("是否自动下载")
    private Boolean autoDownload = false;

    @ApiModelProperty("备注")
    private String remark;
}
