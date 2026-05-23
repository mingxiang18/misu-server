package com.misu.chat.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前用户某月 AI 用量/额度。available=false 表示 bb 用量服务取数失败（前端走降级态）。
 */
@Data
public class UsageDto {
    /** yyyy-MM */
    private String month;
    private BigDecimal spentCny = BigDecimal.ZERO;
    private BigDecimal limitCny = BigDecimal.ZERO;
    private BigDecimal remainingCny = BigDecimal.ZERO;
    private long totalTokens = 0L;
    private List<UsageModelDto> models = new ArrayList<>();
    /** 是否成功取到用量（取不到时前端展示"暂不可用·重试"） */
    private boolean available = false;
}
