package com.misu.chat.service;

import com.bb.bot.entity.bb.BbMessageContent;
import com.misu.chat.domain.dto.MessageDto;
import com.misu.chat.domain.entity.ChatMessage;

import java.util.List;

public interface MessageService {

    ChatMessage saveUserMessage(Long conversationId, String senderUserId, String clientMessageId,
                                List<BbMessageContent> content, String atUserIds);

    /** bb 回复落库；contentJson 为 List&lt;BbMessageContent&gt; 的 JSON（bb SDK 的 BbMessageContent 无公共构造器，故传 JSON） */
    ChatMessage saveBotMessage(Long conversationId, String streamId, String contentJson);

    /** 按会话游标分页（beforeId 为 null 取最新一页），返回正序（旧→新）。 */
    List<MessageDto> pageHistory(Long conversationId, Long beforeId, int size);
}
