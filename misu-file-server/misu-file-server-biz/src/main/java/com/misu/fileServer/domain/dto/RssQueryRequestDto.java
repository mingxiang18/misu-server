package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 查询磁力链接详情
 *
 * @author misu
 */
@Data
public class RssQueryRequestDto {

    @ApiModelProperty("rss订阅id")
    @JsonProperty("rssId")
    private Long rssId;

    @ApiModelProperty("rss订阅链接")
    private String rssUrl;

    @ApiModelProperty("rss订阅名称")
    private String rssName;

    @ApiModelProperty("rss订阅状态，0-未知，1-可用，99-不可用")
    private Integer state;

    @ApiModelProperty("创建人id")
    private String creatorId;

    @ApiModelProperty("关键字")
    private String keyword;
}
