package com.misu.account.domain.dto.auth;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 登录实体
 */
@Data
public class LoginResponseDto {

    @ApiModelProperty(value = "用户名")
    private String userName;

    @ApiModelProperty("用户token")
    private String token;
}
