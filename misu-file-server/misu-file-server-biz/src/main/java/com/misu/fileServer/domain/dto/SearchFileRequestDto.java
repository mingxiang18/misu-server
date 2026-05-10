package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SearchFileRequestDto {

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;

    @ApiModelProperty("搜索关键词，按文件名模糊匹配（不区分大小写）")
    @NotBlank(message = "搜索关键词不能为空")
    @Size(min = 1, max = 100, message = "搜索关键词长度需为 1-100")
    private String keyword;

    @ApiModelProperty("文件类型过滤：image / video / directory / other；为空则全部")
    private String fileType;

    @ApiModelProperty("页码，从 1 开始")
    @Min(value = 1, message = "页码必须 >= 1")
    private Integer pageNumber = 1;

    @ApiModelProperty("每页大小")
    @Min(value = 1, message = "每页大小必须 >= 1")
    @Max(value = 200, message = "每页大小不能超过 200")
    private Integer pageSize = 50;
}
