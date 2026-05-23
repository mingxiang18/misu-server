package com.misu.chat.connection;

import com.alibaba.fastjson2.JSON;
import com.misu.chat.domain.entity.ChatConversationMember;
import com.misu.chat.domain.message.ChatResponseMessage;
import com.misu.chat.service.ConversationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 按会话把一条 ChatResponseMessage 下发给在线成员。
 * （把广播逻辑与连接管理分开：ChatConnectionManager 只管 socket↔userId 映射。）
 */
@Slf4j
@Component
public class ChatBroadcaster {

    @Resource
    private ChatConnectionManager connectionManager;

    @Resource
    private ConversationService conversationService;

    /**
     * 下发给会话全部在线成员（私聊即那一个用户）。
     * 调用前请确保 message.conversationId 已设置。
     */
    public void broadcast(Long conversationId, ChatResponseMessage message) {
        List<ChatConversationMember> members = conversationService.getMembers(conversationId);
        String payload = JSON.toJSONString(message);
        for (ChatConversationMember member : members) {
            WebSocket ws = connectionManager.getUserBotClientWebSocket(member.getMemberUserId());
            if (ws != null && ws.isOpen()) {
                ws.send(payload);
            }
        }
    }

    /**
     * 下发给会话里除某个 userId 之外的其他在线成员（群里转发自己发言用，避免回声）。
     */
    public void broadcastExcept(Long conversationId, ChatResponseMessage message, String exceptUserId) {
        List<ChatConversationMember> members = conversationService.getMembers(conversationId);
        String payload = JSON.toJSONString(message);
        for (ChatConversationMember member : members) {
            if (member.getMemberUserId().equals(exceptUserId)) {
                continue;
            }
            WebSocket ws = connectionManager.getUserBotClientWebSocket(member.getMemberUserId());
            if (ws != null && ws.isOpen()) {
                ws.send(payload);
            }
        }
    }
}
