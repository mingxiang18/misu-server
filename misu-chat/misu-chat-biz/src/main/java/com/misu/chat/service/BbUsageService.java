package com.misu.chat.service;

import com.misu.chat.domain.dto.UsageDto;

/**
 * 查询某用户在 bb-bot 的 AI 用量/额度。独立 service，不塞进现有大 service。
 */
public interface BbUsageService {

    /**
     * 查指定用户某月用量。month 为空=当前月。任何失败返回 available=false（不抛）。
     */
    UsageDto queryUsage(String userId, String month);
}
