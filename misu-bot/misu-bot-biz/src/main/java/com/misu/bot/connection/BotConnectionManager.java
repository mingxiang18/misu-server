package com.misu.bot.connection;

import com.misu.bot.domain.bot.BotTokenMessage;
import lombok.Getter;
import org.java_websocket.WebSocket;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人连接管理者
 */
@Component
public class BotConnectionManager {
    /**
     * 与bb机器人的webSocket连接
     */
    @Getter
    private WebSocket bbWebSocket;

    /**
     * 用户与bot的webSocket连接
     */
    private final Map<WebSocket, BotTokenMessage> botUserConnectionMap = new ConcurrentHashMap<>();

    /**
     * 用户与bot的webSocket连接
     */
    private final Map<String, WebSocket> userBotConnectionMap = new ConcurrentHashMap<>();

    /**
     * 注册bb-bot机器人的webSocket连接
     */
    public void registerBbWebSocket(WebSocket bbWebSocket) {
        this.bbWebSocket = bbWebSocket;
    }

    /**
     * 注册用户与bot的webSocket连接
     */
    public void registerUserBotClientWebSocket(BotTokenMessage userInfo, WebSocket userBotClientWebSocket) {
        botUserConnectionMap.put(userBotClientWebSocket, userInfo);
        userBotConnectionMap.put(userInfo.getUserId().toString(), userBotClientWebSocket);
    }

    /**
     * 移除用户与bot的webSocket连接
     */
    public void removeUserBotClientWebSocket(WebSocket userBotClientWebSocket) {
        if (botUserConnectionMap.containsKey(userBotClientWebSocket)) {
            BotTokenMessage userInfo = botUserConnectionMap.get(userBotClientWebSocket);
            userBotConnectionMap.remove(userInfo.getUserId().toString());
        }
        botUserConnectionMap.remove(userBotClientWebSocket);
    }

    /**
     * 获取当前连接是否注册
     */
    public Boolean hasRegisterFlag(WebSocket userBotClientWebSocket) {
        return botUserConnectionMap.containsKey(userBotClientWebSocket);
    }

    /**
     * 获取用户与bot的webSocket连接
     */
    public WebSocket getUserBotClientWebSocket(String userId) {
        return userBotConnectionMap.get(userId);
    }

    /**
     * 获取用户与bot的webSocket连接
     */
    public BotTokenMessage getBotClientWebSocketUser(WebSocket userBotClientWebSocket) {
        return botUserConnectionMap.get(userBotClientWebSocket);
    }
}
