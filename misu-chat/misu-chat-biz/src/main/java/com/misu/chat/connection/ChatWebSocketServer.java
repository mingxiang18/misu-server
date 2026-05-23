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
    private final ChatInboundRouter inboundRouter;

    /**
     * 构造方法
     */
    public ChatWebSocketServer(ChatConfig botConfig, TokenService tokenService, ChatConnectionManager botConnectionManager,
                               ChatInboundRouter inboundRouter) {
        super(new InetSocketAddress(botConfig.getServerPort()));
        this.botConfig = botConfig;
        this.tokenService = tokenService;
        this.botConnectionManager = botConnectionManager;
        this.inboundRouter = inboundRouter;
        // SO_REUSEADDR：重启时若旧实例的端口仍处于 TIME_WAIT，也能立即重新绑定，
        // 避免快速重启 / prod 滚动发布时 WS 端口偶发「绑定失败 → 无监听」。
        this.setReuseAddr(true);
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
     * 消息处理：解析后交给 ChatInboundRouter（落库 / 越权 / 群广播 / bot 触发）。
     */
    private void handleMessage(WebSocket webSocket, String s) {
        ChatUserMessage userMessage = JSON.parseObject(s, ChatUserMessage.class);
        ChatTokenMessage tokenMessage = botConnectionManager.getBotClientWebSocketUser(webSocket);
        inboundRouter.handle(webSocket, tokenMessage, userMessage);
    }

}
