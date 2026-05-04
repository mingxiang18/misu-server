package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class RssItemQueryRequestDto {

    @ApiModelProperty("rss订阅id")
    @NotNull(message = "rss订阅id不能为空")
    private Long rssId;

    @ApiModelProperty("匹配状态，0-未匹配，1-已匹配")
    private Integer matchState;

    @ApiModelProperty("下载状态，0-未下载，1-已下载，2-失败")
    private Integer downloadState;

    @ApiModelProperty("关键字")
    private String keyword;
}
