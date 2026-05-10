package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 创建者视角下的分享详情，含 token；用于"我的分享"列表与创建后的回执。
 */
@Data
@NoArgsConstructor
public class ShareResponseDto {

    @ApiModelProperty("分享记录 id（撤销时使用）")
    private Long id;

    @ApiModelProperty("外链 token（拼接到 /share/{token} 即为公开链接）")
    private String shareToken;

    @ApiModelProperty("文件名（创建时快照，文件被改名也保留原名）")
    private String fileName;

    @ApiModelProperty("文件类型")
    private String fileType;

    @ApiModelProperty("文件大小（字节）")
    private Long fileSize;

    @ApiModelProperty("源文件路径")
    private String sourceVirtualPath;

    @ApiModelProperty("公开类型 0/1")
    private Integer openType;

    @ApiModelProperty("过期时间")
    private LocalDateTime expireTime;

    @ApiModelProperty("是否设置密码")
    private Boolean hasPassword;

    @ApiModelProperty("下载次数上限；null 不限")
    private Integer maxDownloads;

    @ApiModelProperty("已下载次数")
    private Integer downloadCount;

    @ApiModelProperty("是否已过期（基于 expireTime 实时计算）")
    private Boolean expired;

    @ApiModelProperty("是否已用完下载次数")
    private Boolean exhausted;

    @ApiModelProperty("是否被创建者撤销")
    private Boolean revoked;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}
