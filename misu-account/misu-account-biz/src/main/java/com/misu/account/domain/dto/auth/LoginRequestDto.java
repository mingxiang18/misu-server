package com.misu.account.domain.dto.auth;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录实体
 */
@Data
public class LoginRequestDto {

    @NotBlank(message = "用户名不能为空")
    @ApiModelProperty(value = "用户名", required = true)
    private String userName;

    @NotBlank(message = "密码不能为空")
    @ApiModelProperty(value = "密码", required = true)
    private String password;

    @ApiModelProperty(value = "Turnstile 验证令牌（启用人机验证时必填）")
    private String turnstileToken;
}
