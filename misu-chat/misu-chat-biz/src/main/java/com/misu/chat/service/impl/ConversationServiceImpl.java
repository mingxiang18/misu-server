package com.misu.chat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.constant.BbSendMessageType;
import com.bb.bot.entity.bb.BbMessageContent;
import com.misu.account.dto.UserBriefDto;
import com.misu.chat.domain.dto.ConversationDto;
import com.misu.chat.domain.entity.ChatConversation;
import com.misu.chat.domain.entity.ChatConversationMember;
import com.misu.chat.domain.entity.ChatMessage;
import com.misu.chat.repository.ChatConversationMemberRepository;
import com.misu.chat.repository.ChatConversationRepository;
import com.misu.chat.repository.ChatMessageRepository;
import com.misu.chat.service.ConversationService;
import com.misu.chat.service.UserInfoService;
import jakarta.annotation.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ConversationServiceImpl implements ConversationService {

    public static final String TYPE_PRIVATE = "PRIVATE";
    public static final String TYPE_GROUP = "GROUP";
    public static final String ROLE_OWNER = "OWNER";
    public static final String ROLE_MEMBER = "MEMBER";
    private static final String BOT_TITLE = "冥想bb";

    @Resource
    private ChatConversationRepository conversationRepository;

    @Resource
    private ChatConversationMemberRepository memberRepository;

    @Resource
    private ChatMessageRepository messageRepository;

    @Resource
    private UserInfoService userInfoService;

    @Override
    @Transactional("chatTransactionManager")
    public ChatConversation getOrCreatePrivateConversation(String userId) {
        return conversationRepository.findFirstByOwnerUserIdAndType(userId, TYPE_PRIVATE)
                .orElseGet(() -> {
                    ChatConversation conv = new ChatConversation();
                    conv.setType(TYPE_PRIVATE);
                    conv.setTitle(BOT_TITLE);
                    conv.setOwnerUserId(userId);
                    conv.setCreateTime(LocalDateTime.now());
                    conv.setLastMessageAt(LocalDateTime.now());
                    ChatConversation saved = conversationRepository.save(conv);

                    ChatConversationMember member = new ChatConversationMember();
                    member.setConversationId(saved.getId());
                    member.setMemberUserId(userId);
                    member.setRole(ROLE_OWNER);
                    member.setJoinedAt(LocalDateTime.now());
                    memberRepository.save(member);
                    return saved;
                });
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<ConversationDto> listMyConversations(String userId) {
        List<Long> convIds = memberRepository.findByMemberUserId(userId).stream()
                .map(ChatConversationMember::getConversationId)
                .collect(Collectors.toList());
        if (convIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatConversation> convs = conversationRepository.findByIdInOrderByLastMessageAtDesc(convIds);

        return convs.stream().map(c -> {
            ConversationDto dto = new ConversationDto();
            dto.setId(c.getId());
            dto.setType(c.getType());
            dto.setTitle(c.getTitle() != null ? c.getTitle() : BOT_TITLE);
            dto.setOwnerUserId(c.getOwnerUserId());
            dto.setMemberCount(memberRepository.findByConversationId(c.getId()).size() + 1); // +bb
            dto.setLastMessageAt(c.getLastMessageAt());

            List<ChatMessage> latest = messageRepository.pageHistory(c.getId(), null, PageRequest.of(0, 1));
            if (!latest.isEmpty()) {
                ChatMessage m = latest.get(0);
                dto.setLastMessage(previewOf(m.getContentJson()));
                if (TYPE_GROUP.equals(c.getType())) {
                    dto.setLastSenderName(senderNameOf(m));
                }
            }
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public ChatConversation getById(Long conversationId) {
        return conversationRepository.findById(conversationId).orElse(null);
    }

    @Override
    @Transactional("chatTransactionManager")
    public ChatConversation createGroup(String ownerUserId, String title, List<String> memberUserIds) {
        ChatConversation conv = new ChatConversation();
        conv.setType(TYPE_GROUP);
        conv.setTitle(title);
        conv.setOwnerUserId(ownerUserId);
        conv.setCreateTime(LocalDateTime.now());
        conv.setLastMessageAt(LocalDateTime.now());
        ChatConversation saved = conversationRepository.save(conv);
        // bb 用 groupId = conv-{id}
        saved.setBbGroupId("conv-" + saved.getId());
        conversationRepository.save(saved);

        // 群主
        addMemberInternal(saved.getId(), ownerUserId, ROLE_OWNER);
        // 其他成员去重、排除群主
        if (memberUserIds != null) {
            memberUserIds.stream().filter(uid -> uid != null && !uid.equals(ownerUserId)).distinct()
                    .forEach(uid -> addMemberInternal(saved.getId(), uid, ROLE_MEMBER));
        }
        return saved;
    }

    @Override
    @Transactional("chatTransactionManager")
    public void addMembers(Long conversationId, List<String> memberUserIds) {
        if (memberUserIds == null) {
            return;
        }
        memberUserIds.stream().filter(uid -> uid != null).distinct()
                .forEach(uid -> addMemberInternal(conversationId, uid, ROLE_MEMBER));
    }

    @Override
    @Transactional("chatTransactionManager")
    public void removeMember(Long conversationId, String memberUserId) {
        memberRepository.deleteByConversationIdAndMemberUserId(conversationId, memberUserId);
    }

    private void addMemberInternal(Long conversationId, String userId, String role) {
        if (memberRepository.existsByConversationIdAndMemberUserId(conversationId, userId)) {
            return;
        }
        ChatConversationMember m = new ChatConversationMember();
        m.setConversationId(conversationId);
        m.setMemberUserId(userId);
        m.setRole(role);
        m.setJoinedAt(LocalDateTime.now());
        memberRepository.save(m);
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public boolean isMember(Long conversationId, String userId) {
        return memberRepository.existsByConversationIdAndMemberUserId(conversationId, userId);
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<ChatConversationMember> getMembers(Long conversationId) {
        return memberRepository.findByConversationId(conversationId);
    }

    @Override
    @Transactional("chatTransactionManager")
    public void touchLastMessageAt(Long conversationId) {
        conversationRepository.findById(conversationId).ifPresent(c -> {
            LocalDateTime now = LocalDateTime.now();
            c.setLastMessageAt(now);
            c.setUpdateTime(now);
            conversationRepository.save(c);
        });
    }

    /** 取最后一条消息的预览文本 */
    private String previewOf(String contentJson) {
        try {
            List<BbMessageContent> list = JSON.parseArray(contentJson, BbMessageContent.class);
            if (list == null || list.isEmpty()) {
                return "";
            }
            for (BbMessageContent c : list) {
                if (BbSendMessageType.TEXT.equals(c.getType()) && c.getData() != null) {
                    return c.getData().toString();
                }
            }
            String type = list.get(0).getType();
            if (type != null && type.toLowerCase().contains("image")) {
                return "[图片]";
            }
            if (type != null && type.toLowerCase().contains("file")) {
                return "[文件]";
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    private String senderNameOf(ChatMessage m) {
        if (!"USER".equals(m.getSenderType()) || m.getSenderUserId() == null) {
            return BOT_TITLE;
        }
        Map<String, UserBriefDto> map = userInfoService.batchGet(List.of(m.getSenderUserId()));
        UserBriefDto u = map.get(m.getSenderUserId());
        if (u == null) {
            return m.getSenderUserId();
        }
        return u.getNickName() != null ? u.getNickName() : u.getUserName();
    }
}
