package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 查询磁力链接详情
 *
 * @author misu
 */
@Data
public class RssDetailRequestDto {

    @ApiModelProperty("rss订阅id")
    @NotNull(message = "rss订阅id不能为空")
    @JsonProperty("rssId")
    private Long rssId;
}
