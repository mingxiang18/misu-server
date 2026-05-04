package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchDownloadRssItemsRequestDto {

    @ApiModelProperty("RSS条目id列表")
    @NotEmpty(message = "RSS条目id不能为空")
    private List<Long> itemIdList;
}
