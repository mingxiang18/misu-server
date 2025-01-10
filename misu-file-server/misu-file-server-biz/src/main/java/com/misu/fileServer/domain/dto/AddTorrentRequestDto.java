package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 添加磁力链接请求类
 *
 * @author misu
 */
@Data
public class AddTorrentRequestDto {

    @ApiModelProperty("用户文件路径")
    @NotBlank(message = "文件保存路径不能为空")
    private String userFilePath;

    @ApiModelProperty("磁力文件的url")
    @NotBlank(message = "磁力文件的url不能为空")
    private String torrentUrl;
}
