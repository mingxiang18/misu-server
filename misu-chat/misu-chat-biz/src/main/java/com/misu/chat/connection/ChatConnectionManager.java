package com.misu.chat.connection;

import com.misu.chat.domain.message.ChatTokenMessage;
import lombok.Getter;
import org.java_websocket.WebSocket;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器人连接管理者
 */
@Component
public class ChatConnectionManager {
    /**
     * 与bb机器人的webSocket连接
     */
    @Getter
    private WebSocket bbWebSocket;

    /**
     * 用户与bot的webSocket连接
     */
    private final Map<WebSocket, ChatTokenMessage> botUserConnectionMap = new ConcurrentHashMap<>();

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
    public void registerUserBotClientWebSocket(ChatTokenMessage userInfo, WebSocket userBotClientWebSocket) {
        botUserConnectionMap.put(userBotClientWebSocket, userInfo);
        userBotConnectionMap.put(userInfo.getUserId().toString(), userBotClientWebSocket);
    }

    /**
     * 移除用户与bot的webSocket连接
     */
    public void removeUserBotClientWebSocket(WebSocket userBotClientWebSocket) {
        if (botUserConnectionMap.containsKey(userBotClientWebSocket)) {
            ChatTokenMessage userInfo = botUserConnectionMap.get(userBotClientWebSocket);
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
    public ChatTokenMessage getBotClientWebSocketUser(WebSocket userBotClientWebSocket) {
        return botUserConnectionMap.get(userBotClientWebSocket);
    }
}
