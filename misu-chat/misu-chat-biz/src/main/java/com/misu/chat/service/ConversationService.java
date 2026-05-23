package com.misu.chat.service;

import com.misu.chat.domain.dto.ConversationDto;
import com.misu.chat.domain.entity.ChatConversation;
import com.misu.chat.domain.entity.ChatConversationMember;

import java.util.List;

public interface ConversationService {

    /** 取/惰性创建当前用户与 bb 的 1对1 私聊会话 */
    ChatConversation getOrCreatePrivateConversation(String userId);

    /** 我参与的会话列表（last_message_at 倒序） */
    List<ConversationDto> listMyConversations(String userId);

    /** 该用户是否为会话成员（越权校验） */
    boolean isMember(Long conversationId, String userId);

    /** 会话成员列表 */
    List<ChatConversationMember> getMembers(Long conversationId);

    /** 发消息时回填 last_message_at */
    void touchLastMessageAt(Long conversationId);
}
