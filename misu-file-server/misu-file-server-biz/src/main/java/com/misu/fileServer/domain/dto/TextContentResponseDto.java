package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TextContentResponseDto {

    @ApiModelProperty("文件内容（UTF-8 解码）")
    private String content;

    @ApiModelProperty("文件大小（字节）")
    private Long sizeBytes;

    @ApiModelProperty("BOM 标记：'utf-8-bom' 或 null")
    private String encodingHint;

    @ApiModelProperty("文件是否疑似二进制（含 NUL 字节等）")
    private Boolean binaryLikely;
}
