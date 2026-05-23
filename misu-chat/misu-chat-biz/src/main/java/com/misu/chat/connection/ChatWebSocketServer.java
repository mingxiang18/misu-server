package com.misu.chat.connection;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.constant.BbSendMessageType;
import com.bb.bot.constant.MessageType;
import com.bb.bot.entity.bb.BbSocketClientMessage;
import com.bb.bot.entity.bb.MessageUser;
import com.misu.chat.config.ChatConfig;
import com.misu.chat.domain.message.ChatAuthMessage;
import com.misu.chat.domain.message.ChatResponseMessage;
import com.misu.chat.domain.message.ChatTokenMessage;
import com.misu.chat.domain.message.ChatUserMessage;
import com.misu.chat.service.ConversationService;
import com.misu.chat.service.MessageService;
import com.misu.security.service.TokenService;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * 提供用户连接bot的socket服务端
 * @author ren
 */
@Slf4j
public class ChatWebSocketServer extends WebSocketServer {

    private final String name = "chat";
    private final ChatConfig botConfig;
    private final TokenService tokenService;
    private final ChatConnectionManager botConnectionManager;
    private final MessageService messageService;
    private final ConversationService conversationService;

    /**
     * 构造方法
     */
    public ChatWebSocketServer(ChatConfig botConfig, TokenService tokenService, ChatConnectionManager botConnectionManager,
                               MessageService messageService, ConversationService conversationService) {
        super(new InetSocketAddress(botConfig.getServerPort()));
        this.botConfig = botConfig;
        this.tokenService = tokenService;
        this.botConnectionManager = botConnectionManager;
        this.messageService = messageService;
        this.conversationService = conversationService;
        log.info("【" + name + "】WebSocket服务器初始化:" + botConfig.getServerPort());
        this.start();
    }

    /**
     * 打开连接时的方法
     */
    @Override
    public void onOpen(WebSocket webSocket, ClientHandshake clientHandshake) {
        log.info("【" + name + "】WebSocket服务器连接到客户端：" + webSocket.getRemoteSocketAddress());
    }

    /**
     * 收到消息时
     *
     * @param s
     */
    @Override
    public void onMessage(WebSocket webSocket, String s) {
        //判断当前连接是否注册
        if (!botConnectionManager.hasRegisterFlag(webSocket)) {
            try {
                //格式化为认证消息
                ChatAuthMessage authMessage = JSON.parseObject(s, ChatAuthMessage.class);

                Claims claims = tokenService.parseToken(authMessage.getToken());
                //token解析成功后取出token信息记录到map
                ChatTokenMessage userInfo = JSON.parseObject(claims.get("userInfo", String.class), ChatTokenMessage.class);
                botConnectionManager.registerUserBotClientWebSocket(userInfo, webSocket);
                //回 auth_ok ack，前端收到后才 flush 待发队列（避免认证窗口期发的消息被当成 auth 包）
                ChatResponseMessage authOk = new ChatResponseMessage();
                authOk.setType("auth_ok");
                webSocket.send(JSON.toJSONString(authOk));
            }catch (Exception e) {
                ChatResponseMessage botResponseMessage = new ChatResponseMessage();
                botResponseMessage.setType("auth_error");
                webSocket.send(JSON.toJSONString(botResponseMessage));
                //认证不通过则关闭连接
                webSocket.close();
            }

            return;
        }

        //消息处理
        handleMessage(webSocket, s);
    }

    /**
     * 当连接关闭时
     *
     * @param i
     * @param s
     * @param b
     */@Override
    public void onClose(WebSocket webSocket, int i, String s, boolean b) {
        log.info("【" + name + "】WebSocket与客户端"  + webSocket.getRemoteSocketAddress() + "连接关闭:" + s);
        botConnectionManager.removeUserBotClientWebSocket(webSocket);
    }

    /**
     * 发生error时
     *
     * @param e
     */
    @Override
    public void onError(WebSocket webSocket, Exception e) {
        log.error("【" + name + "】WebSocket服务器出现异常", e);
    }

    @Override
    public void onStart() {
        log.info("【" + name + "】WebSocket服务器启动成功");
    }

    /**
     * 消息处理：落库 → 越权校验 → 转发 bb。
     * 落库在转发之前，bb 离线也不丢用户消息。
     */
    private void handleMessage(WebSocket webSocket, String s) {
        //将Json转为实体
        ChatUserMessage userMessage = JSON.parseObject(s, ChatUserMessage.class);

        //获取当前socket对应的用户信息
        ChatTokenMessage tokenMessage = botConnectionManager.getBotClientWebSocketUser(webSocket);
        String userId = tokenMessage.getUserId().toString();

        //解析会话；私聊兜底创建
        Long conversationId = userMessage.getConversationId();
        if (conversationId == null) {
            conversationId = conversationService.getOrCreatePrivateConversation(userId).getId();
        }

        //越权校验：非会话成员直接拒（业务错误，前端 toast，不登出，与 401 严格分开）
        if (!conversationService.isMember(conversationId, userId)) {
            ChatResponseMessage forbidden = new ChatResponseMessage();
            forbidden.setType("forbidden");
            forbidden.setReceiveMessageId(userMessage.getMessageId());
            webSocket.send(JSON.toJSONString(forbidden));
            return;
        }

        //落库 + 回填会话排序时间
        String atCsv = (userMessage.getAtUserIds() == null || userMessage.getAtUserIds().isEmpty())
                ? null : String.join(",", userMessage.getAtUserIds());
        messageService.saveUserMessage(conversationId, userId, userMessage.getMessageId(),
                userMessage.getMessageContentList(), atCsv);
        conversationService.touchLastMessageAt(conversationId);

        //封装bb机器人协议实体（1对1：PRIVATE；群聊路由在阶段2）
        BbSocketClientMessage bbSocketClientMessage = new BbSocketClientMessage();
        bbSocketClientMessage.setMessageType(MessageType.PRIVATE);
        bbSocketClientMessage.setUserId(userId);
        bbSocketClientMessage.setSender(new MessageUser(userId, tokenMessage.getUserName()));
        bbSocketClientMessage.setMessageId(userMessage.getMessageId());
        bbSocketClientMessage.setMessage(userMessage.getMessageContentList().stream()
                .filter(bbMessageContent -> BbSendMessageType.TEXT.equals(bbMessageContent.getType()))
                .map(bbMessageContent -> bbMessageContent.getData().toString())
                .collect(Collectors.joining(" ")));
        bbSocketClientMessage.setMessageContentList(userMessage.getMessageContentList());
        bbSocketClientMessage.setSendTime(LocalDateTime.now());

        //向bb机器人发送消息
        WebSocket bbWebSocket = botConnectionManager.getBbWebSocket();
        if (bbWebSocket != null && bbWebSocket.isOpen()) {
            bbWebSocket.send(JSON.toJSONString(bbSocketClientMessage));
        }else {
            //与 bb 的上游连接暂时断开（多为 bb 重新发布，SDK 重连线程几秒内会补上）。
            //回 bot_offline 控制消息并带上 receiveMessageId，前端据此自动重发这一条，而不是丢弃。
            //（消息已落库，重发不会重复入库——saveUserMessage 在前端重发时会再写一条，
            // 故前端仅在收到回复前的「未送达」才重发；幂等由 clientMessageId 兜底，阶段2 视需要去重。）
            ChatResponseMessage botResponseMessage = new ChatResponseMessage();
            botResponseMessage.setType("bot_offline");
            botResponseMessage.setReceiveMessageId(userMessage.getMessageId());
            webSocket.send(JSON.toJSONString(botResponseMessage));
        }
    }

}
