package com.misu.chat.connection;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.constant.BbSendMessageType;
import com.bb.bot.constant.MessageType;
import com.bb.bot.entity.bb.BbSocketClientMessage;
import com.bb.bot.entity.bb.MessageUser;
import com.misu.account.dto.UserBriefDto;
import com.misu.chat.config.ChatConfig;
import com.misu.chat.domain.entity.ChatConversation;
import com.misu.chat.domain.message.ChatResponseMessage;
import com.misu.chat.domain.message.ChatTokenMessage;
import com.misu.chat.domain.message.ChatUserMessage;
import com.misu.chat.handler.ChatMockBotResponder;
import com.misu.chat.service.BotProfileService;
import com.misu.chat.service.ConversationService;
import com.misu.chat.service.MessageService;
import com.misu.chat.service.UserInfoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 入站消息路由：落库 → 越权校验 → 群广播 → 决定是否触发 bot（@bot / 私聊必回 / 群小概率随机）。
 * bot 回复：dev 走 mock，prod 转发给 bb。把这套从 WebSocket 端剥出来，端点只管认证/连接。
 */
@Slf4j
@Component
public class ChatInboundRouter {

    private static final String TYPE_GROUP = "GROUP";

    @Resource
    private ConversationService conversationService;
    @Resource
    private MessageService messageService;
    @Resource
    private UserInfoService userInfoService;
    @Resource
    private ChatBroadcaster broadcaster;
    @Resource
    private ChatMockBotResponder mockResponder;
    @Resource
    private ChatConnectionManager connectionManager;
    @Resource
    private ChatConfig chatConfig;
    @Resource
    private BotProfileService botProfileService;

    public void handle(WebSocket senderWs, ChatTokenMessage token, ChatUserMessage msg) {
        String userId = token.getUserId().toString();

        // 解析会话；私聊兜底创建
        ChatConversation conv;
        if (msg.getConversationId() == null) {
            conv = conversationService.getOrCreatePrivateConversation(userId);
        } else {
            conv = conversationService.getById(msg.getConversationId());
        }
        if (conv == null || !conversationService.isMember(conv.getId(), userId)) {
            sendControl(senderWs, "forbidden", msg.getMessageId());
            return;
        }
        Long conversationId = conv.getId();

        // 落库 + 回填会话时间
        String atCsv = (msg.getAtUserIds() == null || msg.getAtUserIds().isEmpty())
                ? null : String.join(",", msg.getAtUserIds());
        messageService.saveUserMessage(conversationId, userId, msg.getMessageId(), msg.getMessageContentList(), atCsv);
        conversationService.touchLastMessageAt(conversationId);

        boolean isGroup = TYPE_GROUP.equals(conv.getType());
        if (isGroup) {
            // 把发言广播给其他在线成员（带发言人昵称/头像）
            UserBriefDto u = userInfoService.batchGet(List.of(userId)).get(userId);
            Map<String, Object> fan = new LinkedHashMap<>();
            fan.put("conversationId", conversationId);
            fan.put("senderType", "USER");
            fan.put("senderUserId", userId);
            fan.put("senderNickname", u != null && u.getNickName() != null ? u.getNickName()
                    : (u != null ? u.getUserName() : token.getUserName()));
            fan.put("senderAvatar", u != null ? u.getAvatar() : null);
            fan.put("messageList", msg.getMessageContentList());
            broadcaster.broadcastExcept(conversationId, fan, userId);

            // 触发 bot：@bot 或 小概率随机插话
            // @bot 既认前端传来的 atUserIds（从 @ 选择窗选中 bb 时带上），
            // 也兜底从正文文本里识别「@{bb名称}」——用户直接手打 @ 而没走选择窗时也能触发。
            boolean atBot = msg.getAtUserIds() != null && msg.getAtUserIds().contains(chatConfig.getBotUserId());
            if (!atBot && mentionsBotByText(msg)) {
                atBot = true;
                // 文本识别命中时补进 atUserIds，保证转发给 bb 的 atUserList 也带上 bot
                if (msg.getAtUserIds() == null) {
                    msg.setAtUserIds(new ArrayList<>());
                }
                if (!msg.getAtUserIds().contains(chatConfig.getBotUserId())) {
                    msg.getAtUserIds().add(chatConfig.getBotUserId());
                }
            }
            double rate = chatConfig.getGroupRandomReplyRate() == null ? 0 : chatConfig.getGroupRandomReplyRate();
            if (atBot || Math.random() < rate) {
                triggerBot(conv, userId, token, msg);
            }
        } else {
            // 私聊：bot 必回
            triggerBot(conv, userId, token, msg);
        }
    }

    private void triggerBot(ChatConversation conv, String userId, ChatTokenMessage token, ChatUserMessage msg) {
        // dev：mock 回复（走真实落库+广播+流式）
        if (Boolean.TRUE.equals(chatConfig.getMockBotReply())) {
            mockResponder.respond(conv.getId(), msg.getMessageId());
            return;
        }
        // prod：转发给 bb
        WebSocket bbWebSocket = connectionManager.getBbWebSocket();
        if (bbWebSocket == null || !bbWebSocket.isOpen()) {
            sendControl(connectionManager.getUserBotClientWebSocket(userId), "bot_offline", msg.getMessageId());
            return;
        }
        boolean isGroup = TYPE_GROUP.equals(conv.getType());
        BbSocketClientMessage bm = new BbSocketClientMessage();
        bm.setMessageType(isGroup ? MessageType.GROUP : MessageType.PRIVATE);
        if (isGroup) {
            bm.setGroupId(conv.getBbGroupId());
            // 透传 @：把被 @ 的人放进 atUserList，bb 据此判断自己（botUserId）是否被呼叫。
            // 不带这个字段，bb 收到群消息时不知道被 @，就不会回复。
            if (msg.getAtUserIds() != null && !msg.getAtUserIds().isEmpty()) {
                bm.setAtUserList(msg.getAtUserIds().stream()
                        .map(uid -> new MessageUser(uid, chatConfig.getBotUserId().equals(uid)))
                        .collect(Collectors.toList()));
            }
        }
        bm.setUserId(userId);
        bm.setSender(new MessageUser(userId, token.getUserName()));
        bm.setMessageId(msg.getMessageId());
        // 文本里去掉「@bb名称」再发给 bb：bb 的关键字规则按纯文本匹配（如「图」精确匹配 ^/?(下*图|全图)$），
        // 若把「@冥想bb 图」原样发过去会匹配不上。bb 自己被 @ 的判定走 atUserList（上面已带 botFlag）。
        String text = msg.getMessageContentList().stream()
                .filter(c -> BbSendMessageType.TEXT.equals(c.getType()) && c.getData() != null)
                .map(c -> c.getData().toString())
                .collect(Collectors.joining(" "));
        String botName = botName();
        if (botName != null && !botName.isBlank()) {
            text = text.replace("@" + botName, " ");
        }
        bm.setMessage(text.replaceAll("\\s+", " ").trim());
        bm.setMessageContentList(msg.getMessageContentList());
        bm.setSendTime(LocalDateTime.now());
        bbWebSocket.send(JSON.toJSONString(bm));
    }

    /** bb 的全局显示名（默认「冥想bb」）；取不到返回 null。 */
    private String botName() {
        try {
            return botProfileService.getProfile().getName();
        } catch (Exception e) {
            return null;
        }
    }

    /** 兜底：用户手打「@{bb名称}」而没走 @ 选择窗时，从正文文本里识别是否在叫 bb。 */
    private boolean mentionsBotByText(ChatUserMessage msg) {
        if (msg.getMessageContentList() == null || msg.getMessageContentList().isEmpty()) {
            return false;
        }
        String botName = botName();
        if (botName == null || botName.isBlank()) {
            return false;
        }
        String needle = "@" + botName;
        return msg.getMessageContentList().stream()
                .filter(c -> BbSendMessageType.TEXT.equals(c.getType()) && c.getData() != null)
                .anyMatch(c -> c.getData().toString().contains(needle));
    }

    private void sendControl(WebSocket ws, String type, String receiveMessageId) {
        if (ws == null || !ws.isOpen()) {
            return;
        }
        ChatResponseMessage ctrl = new ChatResponseMessage();
        ctrl.setType(type);
        ctrl.setReceiveMessageId(receiveMessageId);
        ws.send(JSON.toJSONString(ctrl));
    }
}
