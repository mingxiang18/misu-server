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
public class DeleteRssRequestDto {

    @ApiModelProperty("rss订阅id")
    @NotNull(message = "rss订阅id不能为空")
    private Long rssId;
}
