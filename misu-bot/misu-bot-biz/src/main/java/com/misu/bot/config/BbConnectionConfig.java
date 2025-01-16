package com.misu.bot.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * bb-bot连接相关配置
 */
@Data
@Configuration
public class BbConnectionConfig {

    @Value("${bot.bbConnection.url:}")
    private String url;

    @Value("${bot.bbConnection.appId:}")
    private String appId;

    @Value("${bot.bbConnection.secret:}")
    private String secret;
}
