package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * rss订阅详情
 *
 * @author misu
 */
@Data
public class UpdateRssRequestDto {

    @ApiModelProperty("rss订阅id")
    @NotNull(message = "rss订阅id不能为空")
    private Long rssId;

    @ApiModelProperty("rss订阅名称")
    private String rssName;

    @ApiModelProperty("rss订阅下载的路径")
    private String downloadPath;

    @ApiModelProperty("备注")
    private String remark;
}
