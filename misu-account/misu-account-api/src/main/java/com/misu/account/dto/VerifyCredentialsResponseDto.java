package com.misu.account.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.util.List;

/**
 * 凭证校验结果（内部接口）
 */
@Data
public class VerifyCredentialsResponseDto {

    @ApiModelProperty(value = "校验是否通过")
    private boolean success;

    @ApiModelProperty(value = "用户id")
    private Long userId;

    @ApiModelProperty(value = "用户名")
    private String userName;

    @ApiModelProperty(value = "权限列表")
    private List<String> authorities;

    @ApiModelProperty(value = "失败原因")
    private String reason;
}
