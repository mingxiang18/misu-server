package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateShareRequestDto {

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;

    @ApiModelProperty("被分享文件的虚拟路径")
    @NotBlank(message = "文件路径不能为空")
    private String filePath;

    /** 默认 24h；上限 30 天 */
    @ApiModelProperty("过期时长（分钟）；默认 1440 (24h)")
    @Min(value = 5, message = "过期时间不能少于 5 分钟")
    @Max(value = 60 * 24 * 30, message = "过期时间不能超过 30 天")
    private Integer expireMinutes = 1440;

    @ApiModelProperty("可选访问密码（4-32 位）")
    @Size(min = 4, max = 32, message = "密码需为 4-32 位")
    private String password;

    @ApiModelProperty("可选下载次数上限")
    @Min(value = 1, message = "下载次数上限至少为 1")
    @Max(value = 100000, message = "下载次数上限过大")
    private Integer maxDownloads;
}
