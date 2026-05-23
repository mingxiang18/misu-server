package com.misu.chat.domain.dto;

import lombok.Data;

@Data
public class BotAvatarRequest {
    /** data URL（base64） */
    private String avatar;
}
