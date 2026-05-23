package com.misu.chat.service;

import com.misu.chat.domain.dto.ConversationDto;
import com.misu.chat.domain.entity.ChatConversation;
import com.misu.chat.domain.entity.ChatConversationMember;

import java.util.List;

public interface ConversationService {

    /** 取/惰性创建当前用户与 bb 的 1对1 私聊会话 */
    ChatConversation getOrCreatePrivateConversation(String userId);

    /** 按 id 取会话（不存在返回 null） */
    ChatConversation getById(Long conversationId);

    /** 创建群聊：创建者为 OWNER，bb 隐式参与；返回新会话 */
    ChatConversation createGroup(String ownerUserId, String title, java.util.List<String> memberUserIds);

    /** 加成员（已在群里则跳过） */
    void addMembers(Long conversationId, java.util.List<String> memberUserIds);

    /** 移除成员（踢人/退群） */
    void removeMember(Long conversationId, String memberUserId);

    /** 我参与的会话列表（last_message_at 倒序） */
    List<ConversationDto> listMyConversations(String userId);

    /** 该用户是否为会话成员（越权校验） */
    boolean isMember(Long conversationId, String userId);

    /** 会话成员列表 */
    List<ChatConversationMember> getMembers(Long conversationId);

    /** 发消息时回填 last_message_at */
    void touchLastMessageAt(Long conversationId);

    /** 标记会话已读（把成员的 last_read_at 置为当前时间） */
    void markRead(Long conversationId, String userId);
}
