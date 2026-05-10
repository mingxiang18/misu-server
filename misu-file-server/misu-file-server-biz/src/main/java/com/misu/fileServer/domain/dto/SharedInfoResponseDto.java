package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 公开访问视角下的分享信息（无需登录即可查询；不暴露内部 id / token / sourcePath）。
 */
@Data
@NoArgsConstructor
public class SharedInfoResponseDto {

    @ApiModelProperty("文件名")
    private String fileName;

    @ApiModelProperty("文件类型")
    private String fileType;

    @ApiModelProperty("文件大小（字节）")
    private Long fileSize;

    @ApiModelProperty("是否要求密码")
    private Boolean requirePassword;

    @ApiModelProperty("是否已过期")
    private Boolean expired;

    @ApiModelProperty("是否撤销 / 失效")
    private Boolean revoked;

    @ApiModelProperty("是否已耗尽下载次数")
    private Boolean exhausted;

    @ApiModelProperty("已下载次数")
    private Integer downloadCount;

    @ApiModelProperty("下载次数上限；null 不限")
    private Integer maxDownloads;

    @ApiModelProperty("过期时间")
    private LocalDateTime expireTime;
}
