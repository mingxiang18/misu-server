package com.misu.chat.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 机器人Bot相关配置
 */
@Data
@Configuration
public class ChatConfig {

    @Value("${bot.enable:false}")
    private Boolean enable;

    @Value("${bot.serverPort:}")
    private Integer serverPort;

    @Value("${bot.serverWebSocketUrl:}")
    private String serverWebSocketUrl;

}
