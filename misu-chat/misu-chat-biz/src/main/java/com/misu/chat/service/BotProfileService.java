package com.misu.chat.service;

import com.misu.chat.domain.dto.BotProfileDto;

public interface BotProfileService {

    /** 取 bb 全局资料（不存在则建默认） */
    BotProfileDto getProfile();

    /** 设置 bb 全局头像（ADMIN 调用） */
    void updateAvatar(String avatarDataUrl);
}
