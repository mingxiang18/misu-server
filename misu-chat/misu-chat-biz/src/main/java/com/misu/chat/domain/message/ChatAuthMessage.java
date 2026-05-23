package com.misu.chat.domain.message;

import lombok.Data;

/**
 * 机器人认证消息实体
 */
@Data
public class ChatAuthMessage {

    /**
     * 认证token
     */
    private String token;

}
