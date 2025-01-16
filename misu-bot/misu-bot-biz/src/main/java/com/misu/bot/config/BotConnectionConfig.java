package com.misu.bot.config;

import com.bb.bot.client.BbWebSocketClient;
import com.misu.bot.connection.BotConnectionManager;
import com.misu.bot.connection.BotWebSocketServer;
import com.misu.bot.handler.BbMessageHandler;
import com.misu.security.service.TokenService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import org.java_websocket.server.WebSocketServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;

/**
 * 机器人连接配置
 */
@Data
@Configuration
@ConditionalOnProperty(prefix="bot",name = "enable", havingValue = "true", matchIfMissing = false)
public class BotConnectionConfig {

    @Resource
    private BbConnectionConfig bbConnectionConfig;

    @Resource
    private BbMessageHandler bbMessageHandler;

    @Resource
    private BotConnectionManager botConnectionManager;

    @Resource
    private BotConfig botConfig;

    @Resource
    private TokenService tokenService;

    /**
     * 注册与bb机器人的连接
     */
    @PostConstruct
    public void registerBbWebSocket () {
        //与bb-bot建立连接
        BbWebSocketClient bbWebSocketClient = new BbWebSocketClient("bb-bot",
                bbConnectionConfig.getAppId(), bbConnectionConfig.getSecret(),
                30000,
                URI.create(bbConnectionConfig.getUrl()),
                bbMessageHandler);

        //注册到连接管理器
        botConnectionManager.registerBbWebSocket(bbWebSocketClient);
    }

    /**
     * 初始化bot服务端
     */
    @Bean
    public WebSocketServer botServerSocket () {
        return new BotWebSocketServer(botConfig, tokenService, botConnectionManager);
    }
}
