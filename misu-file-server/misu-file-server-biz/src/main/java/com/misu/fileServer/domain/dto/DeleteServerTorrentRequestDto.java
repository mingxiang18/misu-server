package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除服务器磁力任务请求类
 *
 * @author misu
 */
@Data
public class DeleteServerTorrentRequestDto {

    @ApiModelProperty("用户与磁力文件关联的id")
    @NotNull(message = "用户磁力文件id不能为空")
    private Long userTorrentId;

    @ApiModelProperty("是否同时删除qBittorrent中已下载的文件")
    @NotNull(message = "是否删除文件不能为空")
    private Boolean deleteFiles = false;
}
