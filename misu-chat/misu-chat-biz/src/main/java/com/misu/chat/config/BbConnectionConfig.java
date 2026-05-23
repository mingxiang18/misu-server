package com.misu.chat.config;

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

    /** bb-bot 的 HTTP 基址（用量查询等 REST），如 http://bb-bot.bb-bot:8099。内网直连，不走代理。 */
    @Value("${bot.bbConnection.httpUrl:}")
    private String httpUrl;

    /** 调 bb 用量查询接口的固定 token，对应 bb 侧 ai-agent.billing.queryApiToken。 */
    @Value("${bot.bbConnection.usageApiToken:}")
    private String usageApiToken;
}
