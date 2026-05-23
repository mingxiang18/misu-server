package com.misu.chat.handler;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.client.BbClientMessageHandler;
import com.bb.bot.constant.BbSendMessageType;
import com.bb.bot.constant.BbStreamState;
import com.bb.bot.entity.bb.BbMessageContent;
import com.bb.bot.entity.bb.BbSocketServerMessage;
import com.misu.chat.connection.ChatBroadcaster;
import com.misu.chat.domain.message.ChatResponseMessage;
import com.misu.chat.service.ConversationService;
import com.misu.chat.service.MessageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * bb机器人消息处理器：定位会话 → 落库（流式合并）→ 广播给会话在线成员。
 */
@Slf4j
@Component
public class BbMessageHandler implements BbClientMessageHandler {

    @Resource
    private ConversationService conversationService;

    @Resource
    private MessageService messageService;

    @Resource
    private ChatBroadcaster broadcaster;

    /** streamId -> 已累积文本，end 帧时合并落库 */
    private final Map<String, StringBuilder> streamBuffers = new ConcurrentHashMap<>();

    @Override
    public void handleMessage(WebSocket webSocket, BbSocketServerMessage message) {
        Long conversationId = resolveConversationId(message);
        if (conversationId == null) {
            return;
        }

        // 去掉 @ 内容（localImage/netImage/localFile/netFile 原样透传）
        List<BbMessageContent> contentList = message.getMessageList() == null ? List.of()
                : message.getMessageList().stream()
                .filter(c -> !BbSendMessageType.AT.equals(c.getType()))
                .collect(Collectors.toList());

        String streamId = message.getStreamId();
        String streamState = message.getStreamState();

        // 持久化：流式累积，end 落一条；非流式直接落
        if (streamId != null) {
            String deltaText = contentList.stream()
                    .filter(c -> BbSendMessageType.TEXT.equals(c.getType()) && c.getData() != null)
                    .map(c -> c.getData().toString())
                    .collect(Collectors.joining());
            streamBuffers.computeIfAbsent(streamId, k -> new StringBuilder()).append(deltaText);
            if (BbStreamState.END.equals(streamState)) {
                StringBuilder buf = streamBuffers.remove(streamId);
                // bb SDK 的 BbMessageContent 无公共构造器，用普通 map 拼出 [{type,data}] 的 JSON 落库
                Map<String, Object> textContent = new LinkedHashMap<>();
                textContent.put("type", BbSendMessageType.TEXT);
                textContent.put("data", buf == null ? "" : buf.toString());
                messageService.saveBotMessage(conversationId, streamId, JSON.toJSONString(List.of(textContent)));
                conversationService.touchLastMessageAt(conversationId);
            }
        } else {
            messageService.saveBotMessage(conversationId, null, JSON.toJSONString(contentList));
            conversationService.touchLastMessageAt(conversationId);
        }

        // 下发给会话在线成员（私聊即该用户）
        ChatResponseMessage resp = new ChatResponseMessage();
        resp.setConversationId(conversationId);
        resp.setSenderType("BOT");
        resp.setReceiveMessageId(message.getReceiveMessageId());
        resp.setStreamId(streamId);
        resp.setStreamState(streamState);
        resp.setMessageList(contentList);
        broadcaster.broadcast(conversationId, resp);
    }

    /**
     * 私聊：用 userId 反查该用户与 bb 的私聊会话。群聊（groupId=conv-{id}）在阶段2 接入。
     */
    private Long resolveConversationId(BbSocketServerMessage message) {
        String groupId = message.getGroupId();
        if (groupId != null && !groupId.isBlank()) {
            // 阶段2：解析 conv-{id}
            if (groupId.startsWith("conv-")) {
                try {
                    return Long.valueOf(groupId.substring("conv-".length()));
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            return null;
        }
        String userId = message.getUserId();
        if (userId == null) {
            return null;
        }
        return conversationService.getOrCreatePrivateConversation(userId).getId();
    }
}
