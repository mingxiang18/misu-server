package com.misu.account.domain.dto.auth;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新token请求实体
 */
@Data
public class RefreshTokenRequestDto {

    @NotBlank(message = "refreshToken不能为空")
    @ApiModelProperty("用户长期刷新token")
    private String refreshToken;
}
