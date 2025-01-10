package com.misu.fileServer.util.torrent.request;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

@Data
public class DeleteTorrentRequest {

    @ApiModelProperty("过滤 hash 值（多个 hash 用 `|` 分隔）")
    private String hashes;

    @ApiModelProperty("是否删除下载好的文件")
    private Boolean deleteFiles = false;
}
