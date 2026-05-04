package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DeleteRssRuleRequestDto {

    @ApiModelProperty("规则id")
    @NotNull(message = "规则id不能为空")
    private Long ruleId;
}
