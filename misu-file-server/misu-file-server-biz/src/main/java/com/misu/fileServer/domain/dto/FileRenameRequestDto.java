package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件获取实体
 *
 * @author misu
 */
@Data
@NoArgsConstructor
public class FileRenameRequestDto {

    @ApiModelProperty("原文件路径")
    @NotBlank(message = "原文件路径不能为空")
    private String originFilePath;

    @ApiModelProperty("新的文件路径")
    @NotBlank(message = "新的文件路径不能为空")
    private String newFilePath;

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;
}
