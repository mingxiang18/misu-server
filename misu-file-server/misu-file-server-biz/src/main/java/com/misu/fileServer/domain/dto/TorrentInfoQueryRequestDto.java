package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 查询磁力信息
 *
 * @author misu
 */
@Data
public class TorrentInfoQueryRequestDto {

    @ApiModelProperty("磁力文件的hash")
    private String torrentHash;

    @ApiModelProperty("磁力文件名称")
    private String torrentName;

    @ApiModelProperty("服务器文件状态：状态：0-未下载，10-已暂停，20-下载中，30-已完成，99-失败")
    private Integer serverFileState;

    @ApiModelProperty("服务器文件是否完成标识")
    private Boolean completeFlag;
}
