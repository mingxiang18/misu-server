package com.misu.account.domain.dto.user;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 重置密码请求实体
 */
@Data
public class ResetPasswordRequestDto {

    @NotNull(message = "用户id不能为空")
    @ApiModelProperty(value = "用户id")
    private Long userId;

    @NotBlank(message = "密码不能为空")
    @ApiModelProperty(value = "新密码")
    private String password;
}
