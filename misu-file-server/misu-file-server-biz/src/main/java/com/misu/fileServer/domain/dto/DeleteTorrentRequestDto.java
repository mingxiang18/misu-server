package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 删除磁力链接请求类
 *
 * @author misu
 */
@Data
public class DeleteTorrentRequestDto {

    @ApiModelProperty("用户与磁力文件关联的id")
    @NotNull(message = "用户磁力文件id不能为空")
    private Long userTorrentId;
}
