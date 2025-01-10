package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 查询磁力链接详情
 *
 * @author misu
 */
@Data
public class UserTorrentQueryRequestDto {

    @ApiModelProperty("用户id")
    private String userId;

    @ApiModelProperty("用户文件状态：0-未同步到文件，1-已同步到用户文件，2-同步失败 ")
    private Integer userFileState;

    @ApiModelProperty("用户文件路径")
    private String userFilePath;

    @ApiModelProperty("用户与磁力文件关联id")
    private Long userTorrentId;

    @ApiModelProperty("磁力文件的hash")
    private String torrentHash;

    @ApiModelProperty("关键字")
    private String keyword;

    @ApiModelProperty("磁力文件的hash列表")
    private List<String> torrentHashList;

    @ApiModelProperty("服务器文件状态：状态：0-未下载，10-已暂停，20-下载中，30-已完成，99-失败")
    private Integer serverFileState;

    @ApiModelProperty("服务器文件下载状态（筛选用）：状态：0-未完成，30-已完成（已完成下载和同步），99-失败（下载或同步失败）")
    private Integer completeState;
}
