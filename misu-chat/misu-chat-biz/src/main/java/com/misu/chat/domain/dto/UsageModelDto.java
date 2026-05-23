package com.misu.chat.domain.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 单个模型的本月用量明细。
 */
@Data
public class UsageModelDto {
    private String model;
    private Long tokens;
    private BigDecimal costCny;
}
