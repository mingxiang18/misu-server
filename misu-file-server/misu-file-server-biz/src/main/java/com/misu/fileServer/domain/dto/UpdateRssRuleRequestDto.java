package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UpdateRssRuleRequestDto extends AddRssRuleRequestDto {

    @ApiModelProperty("规则id")
    @NotNull(message = "规则id不能为空")
    private Long ruleId;
}
