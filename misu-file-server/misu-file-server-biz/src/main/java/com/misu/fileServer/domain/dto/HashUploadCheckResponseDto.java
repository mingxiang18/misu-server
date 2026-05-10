package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class HashUploadCheckResponseDto {

    /** 1 = 秒传成功；2 = 同名文件已存在且未要求覆盖；0 = 需要走完整分片上传 */
    @ApiModelProperty("秒传状态码：0-需要继续上传，1-秒传成功，2-同名冲突")
    private Integer state;

    @ApiModelProperty("说明")
    private String message;

    public HashUploadCheckResponseDto(Integer state, String message) {
        this.state = state;
        this.message = message;
    }
}
