package com.misu.bot.config;

import com.bb.bot.client.BbWebSocketClient;
import com.bb.bot.constant.BbCapability;
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
import java.util.List;

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
        //与bb-bot建立连接，握手上报 stream / file 能力位：
        //服务端据此走 streamId/streamState 帧式真流式并下发 localFile/netFile 附件
        BbWebSocketClient bbWebSocketClient = new BbWebSocketClient("bb-bot",
                bbConnectionConfig.getAppId(), bbConnectionConfig.getSecret(),
                30000,
                URI.create(bbConnectionConfig.getUrl()),
                bbMessageHandler,
                List.of(BbCapability.STREAM, BbCapability.FILE));

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
