package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 磁力链接详情
 *
 * @author misu
 */
@Data
public class TorrentInfoDto {

    @ApiModelProperty("磁力文件的hash")
    private String torrentHash;

    @ApiModelProperty("磁力文件的url")
    private String torrentUrl;

    @ApiModelProperty("磁力文件名称")
    private String torrentName;

    @ApiModelProperty("文件大小")
    private Long totalSize;

    @ApiModelProperty("服务器文件状态：状态：0-未下载，10-已暂停，20-下载中，30-已完成，99-失败")
    private Integer state;

    @ApiModelProperty("备注，下载失败原因等")
    private String remark;
}
