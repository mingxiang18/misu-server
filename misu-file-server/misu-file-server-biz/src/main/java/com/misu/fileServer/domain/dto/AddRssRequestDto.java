package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * rss订阅详情
 *
 * @author misu
 */
@Data
public class AddRssRequestDto {

    @ApiModelProperty("rss订阅地址")
    @NotBlank(message = "rss订阅地址不能为空")
    private String rssUrl;

    @ApiModelProperty("rss订阅名称")
    @NotBlank(message = "rss订阅名称不能为空")
    private String rssName;

    @ApiModelProperty("rss订阅下载的路径")
    @NotBlank(message = "rss订阅下载的路径不能为空")
    private String downloadPath;

    @ApiModelProperty("备注")
    private String remark;
}
