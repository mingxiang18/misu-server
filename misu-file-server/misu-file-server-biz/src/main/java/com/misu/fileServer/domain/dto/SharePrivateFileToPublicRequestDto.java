package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 私人文件共享到公共目录请求。
 */
@Data
public class SharePrivateFileToPublicRequestDto {

    @ApiModelProperty("私人目录中的文件或文件夹路径")
    @NotBlank(message = "源文件路径不能为空")
    private String sourceFilePath;

    @ApiModelProperty("公共目录目标文件夹路径")
    @NotBlank(message = "目标目录不能为空")
    private String targetDirectoryPath;
}
