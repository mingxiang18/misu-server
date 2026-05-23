package com.misu.chat.controller;

import com.misu.chat.domain.dto.ConversationDto;
import com.misu.chat.domain.entity.ChatConversation;
import com.misu.chat.service.ChatService;
import com.misu.chat.service.ConversationService;
import com.misu.chat.service.MessageService;
import com.misu.common.domain.AjaxResult;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

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

    @Resource
    private ChatService botService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private MessageService messageService;

    /**
     * 获取提供客户端连接的服务端的socket的url
     */
    @GetMapping({"/getServerWebSocketUrl"})
    @ApiOperation(value="获取提供客户端连接的服务端的socket的url")
    public AjaxResult getClientWebSocketUrl() {
        return AjaxResult.success(botService.getServerWebSocketUrl());
    }

    /**
     * 获取授权token
     */
    @GetMapping({"/getAccessToken"})
    @ApiOperation(value="获取授权token")
    public AjaxResult getAccessToken() {
        return AjaxResult.success(botService.getAccessToken());
    }

    /**
     * 我参与的会话列表（last_message_at 倒序）
     */
    @GetMapping("/conversation/list")
    @ApiOperation(value = "我的会话列表")
    public AjaxResult listConversations() {
        return AjaxResult.success(conversationService.listMyConversations(currentUserId()));
    }

    /**
     * 取/惰性创建当前用户与 bb 的 1对1 私聊会话
     */
    @GetMapping("/conversation/private")
    @ApiOperation(value = "取/创建与bb的私聊会话")
    public AjaxResult getPrivateConversation() {
        ChatConversation conv = conversationService.getOrCreatePrivateConversation(currentUserId());
        ConversationDto dto = new ConversationDto();
        dto.setId(conv.getId());
        dto.setType(conv.getType());
        dto.setTitle(conv.getTitle());
        dto.setOwnerUserId(conv.getOwnerUserId());
        dto.setMemberCount(2);
        dto.setLastMessageAt(conv.getLastMessageAt());
        return AjaxResult.success(dto);
    }

    /**
     * 会话历史消息游标分页（beforeId 为空取最新一页），返回正序（旧→新）
     */
    @GetMapping("/conversation/{id}/message/page")
    @ApiOperation(value = "会话历史消息分页")
    public AjaxResult pageMessages(@PathVariable("id") Long id,
                                   @RequestParam(value = "beforeId", required = false) Long beforeId,
                                   @RequestParam(value = "size", required = false) Integer size) {
        if (!conversationService.isMember(id, currentUserId())) {
            return AjaxResult.error(403, "无权访问该会话");
        }
        int pageSize = (size == null || size <= 0) ? DEFAULT_PAGE_SIZE : size;
        return AjaxResult.success(messageService.pageHistory(id, beforeId, pageSize));
    }

    private String currentUserId() {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        return loginUser.getUserId().toString();
    }
}
