package com.misu.bot.handler;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.client.BbClientMessageHandler;
import com.bb.bot.constant.BbSendMessageType;
import com.bb.bot.entity.bb.BbSocketServerMessage;
import com.misu.bot.connection.BotConnectionManager;
import com.misu.bot.domain.bot.BotResponseMessage;
import jakarta.annotation.Resource;
import org.java_websocket.WebSocket;
import org.springframework.stereotype.Component;

/**
 * bb机器人消息处理器
 */
@Component
public class BbMessageHandler implements BbClientMessageHandler {

    @Resource
    private BotConnectionManager botConnectionManager;

    @Override
    public void handleMessage(WebSocket webSocket, BbSocketServerMessage message) {
        //获取与对应用户建立了连接的WebSocket连接
        WebSocket userBotClientWebSocket = botConnectionManager.getUserBotClientWebSocket(message.getUserId());
        //如果存在该连接，封装消息并发送
        if (userBotClientWebSocket != null && userBotClientWebSocket.isOpen()) {
            BotResponseMessage botResponseMessage = new BotResponseMessage();
            botResponseMessage.setReceiveMessageId(message.getReceiveMessageId());
            //去掉@消息
            botResponseMessage.setMessageList(message.getMessageList()
                    .stream()
                    .filter(bbMessageContent -> !BbSendMessageType.AT.equals(bbMessageContent.getType()))
                    .toList());
            userBotClientWebSocket.send(JSON.toJSONString(botResponseMessage));
        }
    }
}
