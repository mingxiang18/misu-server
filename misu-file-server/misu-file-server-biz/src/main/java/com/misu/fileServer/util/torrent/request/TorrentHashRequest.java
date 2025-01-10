package com.misu.fileServer.util.torrent.request;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TorrentHashRequest {

    @ApiModelProperty("过滤 hash 值（多个 hash 用 `|` 分隔）")
    private String hashes;

    public TorrentHashRequest(String hashes) {
        this.hashes = hashes;
    }
}
