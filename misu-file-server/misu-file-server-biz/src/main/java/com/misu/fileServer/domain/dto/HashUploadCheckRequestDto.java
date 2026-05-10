package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 哈希秒传上传前校验。前端先算 md5（建议增量分片 hash），命中后直接秒传，
 * 不命中再走原本的分片上传 + 合并流程。
 */
@Data
@NoArgsConstructor
public class HashUploadCheckRequestDto {

    @ApiModelProperty("公开类型，0-私人，1-开放")
    @NotNull(message = "文件公开类型不能为空")
    private Integer openType;

    @ApiModelProperty("文件名")
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    @ApiModelProperty("目标父目录虚拟路径，空字符串代表根目录")
    private String filePath;

    @ApiModelProperty("文件 MD5（32 位 hex）")
    @NotBlank(message = "文件 MD5 不能为空")
    @Pattern(regexp = "^[0-9a-fA-F]{32}$", message = "文件 MD5 格式不合法")
    private String fileMd5;

    @ApiModelProperty("文件大小（字节）")
    @NotNull(message = "文件大小不能为空")
    private Long fileSize;

    @ApiModelProperty("是否覆盖同名文件")
    private Boolean coverFlag = false;
}
