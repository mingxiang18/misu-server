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
     * message 可为 ChatResponseMessage 或等价的 Map（mock 回复用 Map，避开 bb SDK 的 BbMessageContent 无公共构造器）。
     * 调用前请确保 message 里的 conversationId 已设置。
     */
    public void broadcast(Long conversationId, Object message) {
        broadcastExcept(conversationId, message, null);
    }

    /**
     * 下发给会话里除某个 userId 之外的其他在线成员（群里转发自己发言用，避免回声；exceptUserId 为 null 即不排除）。
     */
    public void broadcastExcept(Long conversationId, Object message, String exceptUserId) {
        List<ChatConversationMember> members = conversationService.getMembers(conversationId);
        String payload = JSON.toJSONString(message);
        for (ChatConversationMember member : members) {
            if (exceptUserId != null && member.getMemberUserId().equals(exceptUserId)) {
                continue;
            }
            WebSocket ws = connectionManager.getUserBotClientWebSocket(member.getMemberUserId());
            if (ws != null && ws.isOpen()) {
                ws.send(payload);
            }
        }
    }
}
