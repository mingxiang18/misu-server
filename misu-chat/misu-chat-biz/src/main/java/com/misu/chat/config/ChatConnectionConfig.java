package com.misu.chat.config;

import com.bb.bot.client.BbWebSocketClient;
import com.bb.bot.constant.BbCapability;
import com.misu.chat.connection.ChatConnectionManager;
import com.misu.chat.connection.ChatInboundRouter;
import com.misu.chat.connection.ChatWebSocketServer;
import com.misu.chat.handler.BbMessageHandler;
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
public class ChatConnectionConfig {

    @Resource
    private BbConnectionConfig bbConnectionConfig;

    @Resource
    private BbMessageHandler bbMessageHandler;

    @Resource
    private ChatConnectionManager botConnectionManager;

    @Resource
    private ChatConfig botConfig;

    @Resource
    private TokenService tokenService;

    @Resource
    private ChatInboundRouter inboundRouter;

    /**
     * 注册与bb机器人的连接
     */
    @PostConstruct
    public void registerBbWebSocket () {
        //与bb-bot建立连接，握手上报 stream / file 能力位：
        //服务端据此走 streamId/streamState 帧式真流式并下发 localFile/netFile 附件
        //connectInterval 同时是 SDK 重连检查线程的轮询间隔：bb 断开后最长隔这么久才补一次重连。
        //调到 5s（原 30s），把 bb 重新发布期间的「上游离线窗口」缩到几秒，配合前端自动重发基本无感。
        BbWebSocketClient bbWebSocketClient = new BbWebSocketClient("bb-bot",
                bbConnectionConfig.getAppId(), bbConnectionConfig.getSecret(),
                5000,
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
        return new ChatWebSocketServer(botConfig, tokenService, botConnectionManager, inboundRouter);
    }
}
