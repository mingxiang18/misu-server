package com.misu.chat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.entity.bb.BbMessageContent;
import com.misu.account.dto.UserBriefDto;
import com.misu.chat.domain.dto.MessageDto;
import com.misu.chat.domain.entity.ChatMessage;
import com.misu.chat.repository.ChatMessageRepository;
import com.misu.chat.service.ChatFileService;
import com.misu.chat.service.MessageService;
import com.misu.chat.service.UserInfoService;
import jakarta.annotation.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MessageServiceImpl implements MessageService {

    private static final String SENDER_USER = "USER";
    private static final String SENDER_BOT = "BOT";

    @Resource
    private ChatMessageRepository messageRepository;

    @Resource
    private UserInfoService userInfoService;

    @Resource
    private ChatFileService chatFileService;

    @Override
    @Transactional("chatTransactionManager")
    public ChatMessage saveUserMessage(Long conversationId, String senderUserId, String clientMessageId,
                                       List<BbMessageContent> content, String atUserIds) {
        // 幂等：bot_offline 自动重发会带同一 clientMessageId，已落库则直接返回，避免重复
        if (clientMessageId != null) {
            ChatMessage exist = messageRepository
                    .findFirstByConversationIdAndClientMessageId(conversationId, clientMessageId)
                    .orElse(null);
            if (exist != null) {
                return exist;
            }
        }
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setSenderType(SENDER_USER);
        m.setSenderUserId(senderUserId);
        m.setClientMessageId(clientMessageId);
        m.setContentJson(JSON.toJSONString(content == null ? Collections.emptyList() : content));
        m.setAtUserIds(atUserIds);
        m.setCreateTime(LocalDateTime.now());
        ChatMessage saved = messageRepository.save(m);
        chatFileService.indexFromMessage(saved); // 新消息才索引（dedup 分支已提前 return）
        return saved;
    }

    @Override
    @Transactional("chatTransactionManager")
    public ChatMessage saveBotMessage(Long conversationId, String streamId, String contentJson) {
        ChatMessage m = new ChatMessage();
        m.setConversationId(conversationId);
        m.setSenderType(SENDER_BOT);
        m.setStreamId(streamId);
        m.setContentJson(contentJson == null ? "[]" : contentJson);
        m.setCreateTime(LocalDateTime.now());
        ChatMessage saved = messageRepository.save(m);
        chatFileService.indexFromMessage(saved); // bb 返回的文件也进群文件
        return saved;
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<MessageDto> pageHistory(Long conversationId, Long beforeId, int size, String currentUserId) {
        List<ChatMessage> desc = messageRepository.pageHistory(conversationId, beforeId, PageRequest.of(0, size));
        // 翻转为正序（旧→新）便于前端顺序追加
        List<ChatMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);

        // 批量取 USER 发送人的昵称/头像
        Set<String> userIds = asc.stream()
                .filter(m -> SENDER_USER.equals(m.getSenderType()) && m.getSenderUserId() != null)
                .map(ChatMessage::getSenderUserId)
                .collect(Collectors.toCollection(HashSet::new));
        Map<String, UserBriefDto> userMap = userInfoService.batchGet(userIds);

        return asc.stream().map(m -> {
            MessageDto dto = new MessageDto();
            dto.setId(m.getId());
            dto.setConversationId(m.getConversationId());
            dto.setClientMessageId(m.getClientMessageId());
            dto.setSenderType(m.getSenderType());
            dto.setSenderUserId(m.getSenderUserId());
            dto.setSelf(SENDER_USER.equals(m.getSenderType())
                    && currentUserId != null && currentUserId.equals(m.getSenderUserId()));
            dto.setStreamId(m.getStreamId());
            dto.setCreateTime(m.getCreateTime());
            dto.setContent(JSON.parseArray(m.getContentJson(), BbMessageContent.class));
            if (SENDER_USER.equals(m.getSenderType()) && m.getSenderUserId() != null) {
                UserBriefDto u = userMap.get(m.getSenderUserId());
                if (u != null) {
                    dto.setSenderNickName(u.getNickName() != null ? u.getNickName() : u.getUserName());
                    dto.setSenderAvatar(u.getAvatar());
                }
            }
            return dto;
        }).collect(Collectors.toList());
    }
}
