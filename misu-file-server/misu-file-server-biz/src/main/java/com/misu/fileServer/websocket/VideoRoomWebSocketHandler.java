package com.misu.fileServer.websocket;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.misu.fileServer.domain.dto.VideoRoomEventDto;
import com.misu.fileServer.domain.dto.VideoRoomWsMessageDto;
import com.misu.fileServer.service.VideoRoomService;
import com.misu.security.dto.LoginUser;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class VideoRoomWebSocketHandler extends TextWebSocketHandler {

    public static final String ROOM_ID_ATTRIBUTE = "roomId";

    public static final String LOGIN_USER_ATTRIBUTE = "loginUser";

    @Resource
    private VideoRoomService videoRoomService;

    private final Map<String, Set<WebSocketSession>> roomSessionMap = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String roomId = getRoomId(session);
        roomSessionMap.computeIfAbsent(roomId, key -> ConcurrentHashMap.newKeySet()).add(session);
        broadcastMemberList(roomId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        VideoRoomWsMessageDto messageDto = JSON.parseObject(message.getPayload(), VideoRoomWsMessageDto.class);
        if (messageDto == null || StringUtils.isBlank(messageDto.getType())) {
            sendError(session, "消息类型不能为空");
            return;
        }

        String roomId = getRoomId(session);
        LoginUser loginUser = getLoginUser(session);
        String type = StringUtils.upperCase(messageDto.getType());
        switch (type) {
            case "PING" -> sendJson(session, buildBaseMessage("PONG")
                    .fluentPut("clientSendTime", messageDto.getClientSendTime())
                    .fluentPut("serverTime", System.currentTimeMillis()));
            case "COMMENT" -> {
                VideoRoomEventDto eventDto = videoRoomService.recordCommentEvent(roomId, loginUser,
                        messageDto.getContent(), messageDto.getClientSendTime());
                broadcast(roomId, buildBaseMessage("COMMENT").fluentPut("event", eventDto));
                broadcastMemberList(roomId);
            }
            case "PLAYBACK" -> {
                VideoRoomEventDto eventDto = videoRoomService.recordPlaybackEvent(roomId, loginUser,
                        messageDto.getState(), messageDto.getVideoTimeSeconds(),
                        messageDto.getClientSendTime(), messageDto.getPayload());
                broadcast(roomId, buildBaseMessage("PLAYBACK").fluentPut("event", eventDto));
                broadcastMemberList(roomId);
            }
            default -> sendError(session, "不支持的消息类型");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session, true);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        if (isClientDisconnect(exception)) {
            log.debug("放映室WebSocket客户端已断开，sessionId：{}", session.getId());
        } else {
            log.warn("放映室WebSocket异常，sessionId：{}", session.getId(), exception);
        }
        removeSession(session, true);
    }

    private void removeSession(WebSocketSession session, boolean broadcastMemberChange) {
        String roomId = getRoomId(session);
        if (StringUtils.isBlank(roomId)) {
            return;
        }
        Set<WebSocketSession> sessionSet = roomSessionMap.get(roomId);
        if (sessionSet != null) {
            sessionSet.remove(session);
            if (sessionSet.isEmpty()) {
                roomSessionMap.remove(roomId);
            } else if (broadcastMemberChange) {
                broadcastMemberList(roomId);
            }
        }
    }

    private void broadcastMemberList(String roomId) {
        List<JSONObject> memberList = roomSessionMap.getOrDefault(roomId, Collections.emptySet())
                .stream()
                .filter(WebSocketSession::isOpen)
                .map(session -> {
                    LoginUser loginUser = getLoginUser(session);
                    return new JSONObject()
                            .fluentPut("userId", loginUser.getUserId())
                            .fluentPut("userName", loginUser.getUserName())
                            .fluentPut("syncTime", System.currentTimeMillis());
                })
                .toList();
        broadcast(roomId, buildBaseMessage("MEMBER_LIST").fluentPut("members", memberList));
    }

    private void broadcast(String roomId, JSONObject message) {
        Set<WebSocketSession> sessionSet = roomSessionMap.get(roomId);
        if (sessionSet == null) {
            return;
        }
        String payload = JSON.toJSONString(message);
        sessionSet.stream()
                .filter(WebSocketSession::isOpen)
                .forEach(session -> {
                    try {
                        synchronized (session) {
                            if (session.isOpen()) {
                                session.sendMessage(new TextMessage(payload));
                            }
                        }
                    } catch (IOException e) {
                        if (isClientDisconnect(e)) {
                            log.debug("放映室WebSocket客户端已断开，sessionId：{}", session.getId());
                        } else {
                            log.warn("放映室WebSocket消息发送失败，sessionId：{}", session.getId(), e);
                        }
                        removeSession(session, false);
                    }
                });
    }

    private void sendError(WebSocketSession session, String message) {
        sendJson(session, buildBaseMessage("ERROR").fluentPut("message", message));
    }

    private void sendJson(WebSocketSession session, JSONObject message) {
        if (!session.isOpen()) {
            removeSession(session, false);
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(JSON.toJSONString(message)));
                }
            }
        } catch (IOException e) {
            if (isClientDisconnect(e)) {
                log.debug("放映室WebSocket客户端已断开，sessionId：{}", session.getId());
            } else {
                log.warn("放映室WebSocket消息发送失败，sessionId：{}", session.getId(), e);
            }
            removeSession(session, false);
        }
    }

    private boolean isClientDisconnect(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && (message.contains("Broken pipe") || message.contains("Connection reset"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private JSONObject buildBaseMessage(String type) {
        return new JSONObject()
                .fluentPut("type", type)
                .fluentPut("serverTime", System.currentTimeMillis());
    }

    private String getRoomId(WebSocketSession session) {
        return (String) session.getAttributes().get(ROOM_ID_ATTRIBUTE);
    }

    private LoginUser getLoginUser(WebSocketSession session) {
        return (LoginUser) session.getAttributes().get(LOGIN_USER_ATTRIBUTE);
    }
}
