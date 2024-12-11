package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 公共文件上传实体
 *
 * @author misu
 */
@Data
public class FileUploadRequest {

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;

    @ApiModelProperty("文件名")
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @ApiModelProperty("公共文件路径")
    @NotBlank(message = "文件路径不能为空")
    private String filePath;

    @ApiModelProperty("文件数据")
    @NotNull(message = "文件不能为空")
    private MultipartFile file;

    @ApiModelProperty("是否覆盖同名文件")
    @NotNull(message = "是否覆盖同名文件标识不能为空")
    private Boolean coverFlag = false;
}
