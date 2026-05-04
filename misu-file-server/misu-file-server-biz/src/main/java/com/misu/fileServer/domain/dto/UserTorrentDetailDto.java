package com.misu.fileServer.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
public class UserTorrentDetailDto {

    @ApiModelProperty("用户与磁力文件关联的id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userTorrentId;

    @ApiModelProperty("用户id")
    @JsonIgnore
    private String userId;

    @ApiModelProperty("用户文件路径")
    private String userFilePath;

    @ApiModelProperty("用户文件状态：0-未同步到文件，1-已同步到用户文件")
    private Integer userFileState;

    @ApiModelProperty("用户文件同步失败原因")
    private String failedReason;

    @ApiModelProperty("磁力文件的hash")
    private String torrentHash;

    @ApiModelProperty("磁力文件的url")
    private String torrentUrl;

    @ApiModelProperty("磁力文件名称")
    private String torrentName;

    @ApiModelProperty("文件大小")
    private Long totalSize;

    @ApiModelProperty("服务器文件状态：状态：0-未下载，10-已暂停，20-下载中，30-已完成，99-失败")
    private Integer serverFileState;

    @JsonIgnore
    @ApiModelProperty("服务器文件下载路径")
    private String serverDownloadPath;

    @ApiModelProperty("qBitTorrent服务器文件状态")
    private String torrentState;

    @ApiModelProperty("服务器文件下载进度 (进度/100)")
    private Double serverFileProgress;

    @ApiModelProperty("服务器文件下载速度 (bytes/s)")
    private Integer serverFileDownloadSpeed;

    @ApiModelProperty("备注，下载失败原因等")
    private String remark;
}
