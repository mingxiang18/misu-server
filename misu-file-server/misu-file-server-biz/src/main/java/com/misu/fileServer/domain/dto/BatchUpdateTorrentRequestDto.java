package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 批量更新磁力链接请求类
 *
 * @author misu
 */
@Data
public class BatchUpdateTorrentRequestDto {

    @ApiModelProperty("用户与磁力文件关联的id列表")
    @NotEmpty(message = "用户磁力文件id不能为空")
    private List<Long> userTorrentIdList;

    @ApiModelProperty("服务器文件状态，支持的修改状态：10-暂停，20-下载")
    @NotNull(message = "服务器文件状态不能为空")
    private Integer serverFileState;
}
