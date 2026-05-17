package com.misu.account.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 凭证校验请求（内部接口，供 WebDAV 等以用户名+密码鉴权的场景使用）
 */
@Data
public class VerifyCredentialsRequestDto {

    @ApiModelProperty(value = "用户名")
    private String userName;

    @ApiModelProperty(value = "明文密码")
    private String password;
}
