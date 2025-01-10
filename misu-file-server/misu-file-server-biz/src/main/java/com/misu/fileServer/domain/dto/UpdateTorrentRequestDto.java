package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 添加磁力链接请求类
 *
 * @author misu
 */
@Data
public class UpdateTorrentRequestDto {

    @ApiModelProperty("用户与磁力文件关联的id")
    @NotNull(message = "用户磁力文件id不能为空")
    private Long userTorrentId;

    @ApiModelProperty("用户文件路径")
    private String userFilePath;

    @ApiModelProperty("服务器文件状态，支持的修改状态：10-暂停，20-下载")
    private Integer serverFileState;
}
