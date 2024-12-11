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
public class FileRequestDto {

    @ApiModelProperty("文件路径")
    @NotBlank(message = "文件路径不能为空")
    private String filePath;

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;

    public FileRequestDto(String filePath, Integer openType) {
        this.filePath = filePath;
        this.openType = openType;
    }
}
