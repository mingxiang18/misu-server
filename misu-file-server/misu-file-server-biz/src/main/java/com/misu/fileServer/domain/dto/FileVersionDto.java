package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class FileVersionDto {

    @ApiModelProperty("版本记录 id（还原/删除时使用）")
    private Long id;

    @ApiModelProperty("递增版本号")
    private Integer versionNo;

    @ApiModelProperty("快照时的文件大小（字节）")
    private Long fileSize;

    @ApiModelProperty("快照时的 MD5（可空）")
    private String fileMd5;

    @ApiModelProperty("快照时的原文件名")
    private String originalFileName;

    @ApiModelProperty("触发原因：OVERWRITE/TEXT_EDIT/HASH_DEDUP/RESTORE_DEMOTE")
    private String snapshotReason;

    @ApiModelProperty("触发快照的用户 id")
    private String snapshotByUserId;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}
