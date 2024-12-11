package com.misu.account.domain.dto.auth;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 注册响应实体
 */
@Data
public class RegisterResponseDto {

    @ApiModelProperty(value = "用户名")
    private String userName;
}
