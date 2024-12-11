package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 文件下载请求实体
 *
 * @author misu
 */
@Data
public class FileDownloadRequestDto {

    @ApiModelProperty("文件下载token不能为空")
    @NotBlank(message = "文件下载token不能为空")
    private String fileToken;
}
