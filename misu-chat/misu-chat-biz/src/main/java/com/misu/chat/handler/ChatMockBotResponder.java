package com.misu.chat.handler;

import com.alibaba.fastjson2.JSON;
import com.misu.chat.connection.ChatBroadcaster;
import com.misu.chat.service.ConversationService;
import com.misu.chat.service.MessageService;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * dev 专用：本地无 bb 上游时，模拟一条机器人流式回复。
 * 走真实的「广播帧 + end 落库」链路，等价于 BbMessageHandler 收到 bb 流式回复时的处理，
 * 只是回复文本是 mock 的。由 chat.mockBotReply=true 开启。
 */
@Slf4j
@Component
public class ChatMockBotResponder {

    @Resource
    private MessageService messageService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private ChatBroadcaster broadcaster;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "chat-mock-bot");
                t.setDaemon(true);
                return t;
            });

    private static final String[] REPLIES = {
            "收到～我在这儿陪着你。先深呼吸一下，把注意力放回呼吸上，我们慢慢聊。",
            "嗯嗯，我听到了。要不要试试睡前的 4-7-8 呼吸法？吸气 4 秒、屏息 7 秒、呼气 8 秒，重复四轮。",
            "今天也辛苦啦，给自己泡杯热茶歇一会儿吧。需要的话我可以放一段轻音乐歌单。",
            "我在的～有什么想聊的都可以告诉我，我会一直陪着你。"
    };

    private final AtomicInteger seq = new AtomicInteger(0);

    /**
     * 给某会话生成一条 mock 机器人流式回复。
     * @param conversationId 会话
     * @param replyToMessageId 触发它的用户消息 id（回执用，可空）
     */
    public void respond(Long conversationId, String replyToMessageId) {
        String full = REPLIES[Math.floorMod(seq.getAndIncrement(), REPLIES.length)];
        String streamId = UUID.randomUUID().toString();
        // 把文本切成若干小片，逐帧 delta 下发，模拟打字流式效果
        List<String> chunks = chunk(full, 6);

        // start 帧：创建空气泡
        scheduler.schedule(() -> sendFrame(conversationId, streamId, "start", null, replyToMessageId),
                300, TimeUnit.MILLISECONDS);

        long delay = 450;
        for (String c : chunks) {
            final String piece = c;
            scheduler.schedule(() -> sendFrame(conversationId, streamId, "delta", piece, replyToMessageId),
                    delay, TimeUnit.MILLISECONDS);
            delay += 70;
        }

        final long endDelay = delay;
        scheduler.schedule(() -> {
            sendFrame(conversationId, streamId, "end", null, replyToMessageId);
            // end 时落库整条
            Map<String, Object> textContent = new LinkedHashMap<>();
            textContent.put("type", "text");
            textContent.put("data", full);
            try {
                messageService.saveBotMessage(conversationId, streamId, JSON.toJSONString(List.of(textContent)));
                conversationService.touchLastMessageAt(conversationId);
            } catch (Exception e) {
                log.warn("mock bot 回复落库失败: {}", e.getMessage());
            }
        }, endDelay, TimeUnit.MILLISECONDS);
    }

    private void sendFrame(Long conversationId, String streamId, String state, String deltaText, String replyToMessageId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("conversationId", conversationId);
        resp.put("senderType", "BOT");
        resp.put("receiveMessageId", replyToMessageId);
        resp.put("streamId", streamId);
        resp.put("streamState", state);
        List<Map<String, Object>> messageList = new ArrayList<>();
        if (deltaText != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", "text");
            c.put("data", deltaText);
            messageList.add(c);
        }
        resp.put("messageList", messageList);
        broadcaster.broadcast(conversationId, resp);
    }

    /** 按字符数切片 */
    private List<String> chunk(String s, int size) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += size) {
            out.add(s.substring(i, Math.min(s.length(), i + size)));
        }
        return out;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
