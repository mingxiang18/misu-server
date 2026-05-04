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

    @ApiModelProperty("服务器文件上传速度 (bytes/s)")
    private Integer serverFileUploadSpeed;

    @ApiModelProperty("剩余时间（秒）")
    private Long eta;

    @ApiModelProperty("已下载大小（字节）")
    private Long downloaded;

    @ApiModelProperty("已上传大小（字节）")
    private Long uploaded;

    @ApiModelProperty("已完成大小（字节）")
    private Long completed;

    @ApiModelProperty("剩余大小（字节）")
    private Long amountLeft;

    @ApiModelProperty("种子数")
    private Integer numSeeds;

    @ApiModelProperty("用户数")
    private Integer numLeechs;

    @ApiModelProperty("完成的种子数量")
    private Integer numComplete;

    @ApiModelProperty("未完成的用户数量")
    private Integer numIncomplete;

    @ApiModelProperty("Tracker")
    private String tracker;

    @ApiModelProperty("保存路径")
    private String savePath;

    @ApiModelProperty("内容路径")
    private String contentPath;

    @ApiModelProperty("分类")
    private String category;

    @ApiModelProperty("标签")
    private String tags;

    @ApiModelProperty("添加时间（Unix Epoch）")
    private Long addedOn;

    @ApiModelProperty("完成时间（Unix Epoch）")
    private Long completionOn;

    @ApiModelProperty("最后活动时间（Unix Epoch）")
    private Long lastActivity;

    @ApiModelProperty("分享率")
    private Double ratio;

    @ApiModelProperty("下载限速（字节/秒）")
    private Integer dlLimit;

    @ApiModelProperty("上传限速（字节/秒）")
    private Integer upLimit;

    @ApiModelProperty("备注，下载失败原因等")
    private String remark;
}
