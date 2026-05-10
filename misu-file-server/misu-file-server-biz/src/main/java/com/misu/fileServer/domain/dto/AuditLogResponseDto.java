package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
public class AuditLogResponseDto {
    @ApiModelProperty("审计日志 id")
    private Long id;

    @ApiModelProperty("操作类型")
    private String actionType;

    @ApiModelProperty("操作者 userId")
    private String userId;

    @ApiModelProperty("操作者用户名")
    private String userName;

    @ApiModelProperty("目标 openType")
    private Integer targetOpenType;

    @ApiModelProperty("目标虚拟路径")
    private String targetVirtualPath;

    @ApiModelProperty("来源 IP")
    private String ip;

    @ApiModelProperty("User-Agent")
    private String userAgent;

    @ApiModelProperty("结果状态码（200=成功）")
    private Integer statusCode;

    @ApiModelProperty("失败原因")
    private String errorMessage;

    @ApiModelProperty("请求 ID")
    private String requestId;

    @ApiModelProperty("创建时间")
    private LocalDateTime createTime;
}
