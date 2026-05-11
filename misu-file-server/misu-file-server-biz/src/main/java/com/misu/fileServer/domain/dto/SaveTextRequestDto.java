package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SaveTextRequestDto {

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;

    @ApiModelProperty("文件路径")
    @NotBlank(message = "文件路径不能为空")
    private String filePath;

    @ApiModelProperty("新的文件内容（UTF-8）")
    private String content;
}
