package com.misu.bot.connection;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.constant.BbSendMessageType;
import com.bb.bot.constant.MessageType;
import com.bb.bot.entity.bb.BbSocketClientMessage;
import com.bb.bot.entity.bb.MessageUser;
import com.misu.bot.config.BotConfig;
import com.misu.bot.domain.bot.BotAuthMessage;
import com.misu.bot.domain.bot.BotResponseMessage;
import com.misu.bot.domain.bot.BotTokenMessage;
import com.misu.bot.domain.bot.BotUserMessage;
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
public class BotWebSocketServer extends WebSocketServer {

    private final String name = "bot";
    private final BotConfig botConfig;
    private final TokenService tokenService;
    private final BotConnectionManager botConnectionManager;

    /**
     * 构造方法
     */
    public BotWebSocketServer(BotConfig botConfig, TokenService tokenService, BotConnectionManager botConnectionManager) {
        super(new InetSocketAddress(botConfig.getServerPort()));
        this.botConfig = botConfig;
        this.tokenService = tokenService;
        this.botConnectionManager = botConnectionManager;
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
                BotAuthMessage authMessage = JSON.parseObject(s, BotAuthMessage.class);

                Claims claims = tokenService.parseToken(authMessage.getToken());
                //token解析成功后取出token信息记录到map
                BotTokenMessage userInfo = JSON.parseObject(claims.get("userInfo", String.class), BotTokenMessage.class);
                botConnectionManager.registerUserBotClientWebSocket(userInfo, webSocket);
                //回 auth_ok ack，前端收到后才 flush 待发队列（避免认证窗口期发的消息被当成 auth 包）
                BotResponseMessage authOk = new BotResponseMessage();
                authOk.setType("auth_ok");
                webSocket.send(JSON.toJSONString(authOk));
            }catch (Exception e) {
                BotResponseMessage botResponseMessage = new BotResponseMessage();
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
     * 消息处理
     */
    private void handleMessage(WebSocket webSocket, String s) {
        //将Json转为实体
        BotUserMessage botUserMessage = JSON.parseObject(s, BotUserMessage.class);

        //获取当前socket对应的用户信息
        BotTokenMessage botTokenMessage = botConnectionManager.getBotClientWebSocketUser(webSocket);

        //封装bb机器人协议实体
        BbSocketClientMessage bbSocketClientMessage = new BbSocketClientMessage();
        bbSocketClientMessage.setMessageType(MessageType.PRIVATE);
        bbSocketClientMessage.setUserId(botTokenMessage.getUserId().toString());
        bbSocketClientMessage.setSender(new MessageUser(botTokenMessage.getUserId().toString(), botTokenMessage.getUserName()));
        bbSocketClientMessage.setMessageId(botUserMessage.getMessageId());
        bbSocketClientMessage.setMessage(botUserMessage.getMessageContentList().stream()
                .filter(bbMessageContent -> BbSendMessageType.TEXT.equals(bbMessageContent.getType()))
                .map(bbMessageContent -> bbMessageContent.getData().toString())
                .collect(Collectors.joining(" ")));
        bbSocketClientMessage.setMessageContentList(botUserMessage.getMessageContentList());
        bbSocketClientMessage.setSendTime(LocalDateTime.now());

        //向bb机器人发送消息
        WebSocket bbWebSocket = botConnectionManager.getBbWebSocket();
        if (bbWebSocket != null && bbWebSocket.isOpen()) {
            bbWebSocket.send(JSON.toJSONString(bbSocketClientMessage));
        }else {
            //与 bb 的上游连接暂时断开（多为 bb 重新发布，SDK 重连线程几秒内会补上）。
            //回 bot_offline 控制消息并带上 receiveMessageId，前端据此自动重发这一条，而不是丢弃。
            BotResponseMessage botResponseMessage = new BotResponseMessage();
            botResponseMessage.setType("bot_offline");
            botResponseMessage.setReceiveMessageId(botUserMessage.getMessageId());
            webSocket.send(JSON.toJSONString(botResponseMessage));
        }
    }

}
