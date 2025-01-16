package com.misu.bot.domain.bot;

import lombok.Data;

/**
 * 机器人认证消息实体
 */
@Data
public class BotAuthMessage {

    /**
     * 认证token
     */
    private String token;

}
