package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量操作请求（删除 / 移动 / 还原 / 永久删除）。
 */
@Data
@NoArgsConstructor
public class BatchFileRequestDto {

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;

    @ApiModelProperty("待处理的文件虚拟路径列表")
    @NotEmpty(message = "文件路径列表不能为空")
    @Size(max = 500, message = "单次批量操作不能超过 500 项")
    private List<String> filePaths;

    @ApiModelProperty("目标父目录虚拟路径，仅 batchMove 需要；空字符串代表根目录")
    private String targetParentPath;
}
