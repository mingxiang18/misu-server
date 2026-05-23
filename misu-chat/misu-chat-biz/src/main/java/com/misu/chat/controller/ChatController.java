package com.misu.chat.controller;

import com.misu.account.dto.UserBriefDto;
import com.misu.chat.config.ChatConfig;
import com.misu.chat.domain.dto.AddMembersRequest;
import com.misu.chat.domain.dto.ConversationDto;
import com.misu.chat.domain.dto.CreateGroupRequest;
import com.misu.chat.domain.dto.MemberDto;
import com.misu.chat.domain.entity.ChatConversation;
import com.misu.chat.domain.entity.ChatConversationMember;
import com.misu.chat.service.ChatService;
import com.misu.chat.service.ConversationService;
import com.misu.chat.service.MessageService;
import com.misu.chat.service.UserInfoService;
import com.misu.common.constant.HttpStatus;
import com.misu.common.domain.AjaxResult;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 聊天相关Controller
 *
 * @author misu
 */
@RestController
@RequestMapping("/chat")
@Api("聊天相关接口")
public class ChatController {

    private static final int DEFAULT_PAGE_SIZE = 30;
    private static final String BOT_NAME = "冥想bb";

    @Resource
    private ChatService botService;
    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private UserInfoService userInfoService;
    @Resource
    private ChatConfig chatConfig;

    /** 获取提供客户端连接的服务端的socket的url */
    @GetMapping({"/getServerWebSocketUrl"})
    @ApiOperation(value = "获取提供客户端连接的服务端的socket的url")
    public AjaxResult getClientWebSocketUrl() {
        return AjaxResult.success(botService.getServerWebSocketUrl());
    }

    /** 获取授权token */
    @GetMapping({"/getAccessToken"})
    @ApiOperation(value = "获取授权token")
    public AjaxResult getAccessToken() {
        return AjaxResult.success(botService.getAccessToken());
    }

    /** 我参与的会话列表（last_message_at 倒序） */
    @GetMapping("/conversation/list")
    @ApiOperation(value = "我的会话列表")
    public AjaxResult listConversations() {
        return AjaxResult.success(conversationService.listMyConversations(currentUserId()));
    }

    /** 取/惰性创建当前用户与 bb 的 1对1 私聊会话 */
    @GetMapping("/conversation/private")
    @ApiOperation(value = "取/创建与bb的私聊会话")
    public AjaxResult getPrivateConversation() {
        ChatConversation conv = conversationService.getOrCreatePrivateConversation(currentUserId());
        return AjaxResult.success(toDto(conv, 2));
    }

    /** 会话历史消息游标分页（beforeId 为空取最新一页），返回正序（旧→新） */
    @GetMapping("/conversation/{id}/message/page")
    @ApiOperation(value = "会话历史消息分页")
    public AjaxResult pageMessages(@PathVariable("id") Long id,
                                   @RequestParam(value = "beforeId", required = false) Long beforeId,
                                   @RequestParam(value = "size", required = false) Integer size) {
        String me = currentUserId();
        if (!conversationService.isMember(id, me)) {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        int pageSize = (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : size;
        return AjaxResult.success(messageService.pageHistory(id, beforeId, pageSize, me));
    }

    /** 创建群聊（创建者为群主，bb 自动隐式参与） */
    @PostMapping("/conversation/group")
    @ApiOperation(value = "创建群聊")
    public AjaxResult createGroup(@RequestBody CreateGroupRequest req) {
        String title = (req.getTitle() == null || req.getTitle().isBlank()) ? "新的群聊" : req.getTitle().trim();
        ChatConversation conv = conversationService.createGroup(currentUserId(), title, req.getMemberUserIds());
        int memberCount = conversationService.getMembers(conv.getId()).size() + 1; // +bb
        return AjaxResult.success(toDto(conv, memberCount));
    }

    /** 加成员（仅群主） */
    @PostMapping("/conversation/{id}/member")
    @ApiOperation(value = "添加群成员")
    public AjaxResult addMembers(@PathVariable("id") Long id, @RequestBody AddMembersRequest req) {
        ChatConversation conv = conversationService.getById(id);
        if (conv == null || !isOwner(conv)) {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "只有群主可以添加成员");
        }
        conversationService.addMembers(id, req.getUserIds());
        return AjaxResult.success();
    }

    /** 移除成员（群主踢人 / 本人退群） */
    @DeleteMapping("/conversation/{id}/member/{userId}")
    @ApiOperation(value = "移除群成员/退群")
    public AjaxResult removeMember(@PathVariable("id") Long id, @PathVariable("userId") String userId) {
        ChatConversation conv = conversationService.getById(id);
        if (conv == null) {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "会话不存在");
        }
        String me = currentUserId();
        boolean selfLeave = me.equals(userId);
        if (!isOwner(conv) && !selfLeave) {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "只有群主可以移除其他成员");
        }
        conversationService.removeMember(id, userId);
        return AjaxResult.success();
    }

    /** 群成员列表（含 bb） */
    @GetMapping("/conversation/{id}/member/list")
    @ApiOperation(value = "群成员列表")
    public AjaxResult listMembers(@PathVariable("id") Long id) {
        if (!conversationService.isMember(id, currentUserId())) {
            return AjaxResult.error(HttpStatus.FORBIDDEN, "无权访问该会话");
        }
        String me = currentUserId();
        List<ChatConversationMember> members = conversationService.getMembers(id);
        Set<String> ids = members.stream().map(ChatConversationMember::getMemberUserId).collect(Collectors.toSet());
        Map<String, UserBriefDto> userMap = userInfoService.batchGet(ids);

        List<MemberDto> result = new ArrayList<>();
        for (ChatConversationMember m : members) {
            MemberDto dto = new MemberDto();
            dto.setUserId(m.getMemberUserId());
            dto.setRole(m.getRole());
            dto.setBotFlag(false);
            dto.setSelf(me.equals(m.getMemberUserId()));
            UserBriefDto u = userMap.get(m.getMemberUserId());
            if (u != null) {
                dto.setNickName(u.getNickName() != null ? u.getNickName() : u.getUserName());
                dto.setAvatar(u.getAvatar());
            }
            result.add(dto);
        }
        // bb 隐式成员
        MemberDto bot = new MemberDto();
        bot.setUserId(chatConfig.getBotUserId());
        bot.setNickName(BOT_NAME);
        bot.setRole("MEMBER");
        bot.setBotFlag(true);
        result.add(bot);

        return AjaxResult.success(result);
    }

    private ConversationDto toDto(ChatConversation conv, int memberCount) {
        ConversationDto dto = new ConversationDto();
        dto.setId(conv.getId());
        dto.setType(conv.getType());
        dto.setTitle(conv.getTitle());
        dto.setOwnerUserId(conv.getOwnerUserId());
        dto.setMemberCount(memberCount);
        dto.setLastMessageAt(conv.getLastMessageAt());
        return dto;
    }

    private boolean isOwner(ChatConversation conv) {
        return conv.getOwnerUserId() != null && conv.getOwnerUserId().equals(currentUserId());
    }

    private String currentUserId() {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        return loginUser.getUserId().toString();
    }
}
