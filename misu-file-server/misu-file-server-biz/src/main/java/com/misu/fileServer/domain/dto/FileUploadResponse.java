package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 公共文件上传实体
 *
 * @author misu
 */
@Data
@NoArgsConstructor
public class FileUploadResponse {

    @ApiModelProperty("上传状态代码，1-上传成功，2-文件已存在")
    private Integer uploadState;

    @ApiModelProperty("上传状态描述")
    private String uploadStateMessage;

    public FileUploadResponse(Integer uploadState, String uploadStateMessage) {
        this.uploadState = uploadState;
        this.uploadStateMessage = uploadStateMessage;
    }
}
