package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class PageResponseDto<T> {

    @ApiModelProperty("当前页数据")
    private List<T> items;

    @ApiModelProperty("总数")
    private Long total;

    @ApiModelProperty("页码（从 1 开始）")
    private Integer pageNumber;

    @ApiModelProperty("每页大小")
    private Integer pageSize;

    public PageResponseDto(List<T> items, Long total, Integer pageNumber, Integer pageSize) {
        this.items = items;
        this.total = total;
        this.pageNumber = pageNumber;
        this.pageSize = pageSize;
    }
}
