package com.misu.ops.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.config.OpsWebSocketLimitsConfig;
import com.misu.ops.session.OpsSessionStore;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component("aiCliWebSocketHandler")
public class AiCliWebSocketHandler extends AbstractWebSocketHandler {

    private final OpsSessionStore sessions;
    private final AiCliConnectionService aiCli;
    private final ObjectMapper objectMapper;

    public AiCliWebSocketHandler(OpsSessionStore sessions, AiCliConnectionService aiCli,
                                 ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.aiCli = aiCli;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocket) throws Exception {
        String sessionId = sessionId(webSocket);
        try {
            OpsSessionStore.AiSession session = sessions.claimAiSession(sessionId);
            aiCli.open(session, webSocket);
        } catch (RuntimeException ex) {
            closeUnauthorized(webSocket);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocket, TextMessage message) throws Exception {
        String text = message.getPayload();
        if (text.getBytes(StandardCharsets.UTF_8).length > OpsWebSocketLimitsConfig.MAX_TEXT_MESSAGE_BYTES) {
            throw new IOException("AI CLI 输入过大");
        }
        if (!text.startsWith("{")) {
            write(webSocket, text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        JsonNode command = objectMapper.readTree(text);
        String type = command.path("type").asText("");
        if ("input".equals(type)) {
            String data = command.path("data").asText(null);
            if (data == null) throw new IOException("AI CLI 输入缺失");
            write(webSocket, data.getBytes(StandardCharsets.UTF_8));
        } else if ("resize".equals(type)) {
            try {
                aiCli.resize(sessionId(webSocket), command.path("cols").asInt(80),
                        command.path("rows").asInt(24));
            } catch (ServiceException ex) {
                if (!isClosedSession(ex)) throw ex;
                closeAfterSessionEnded(webSocket);
            }
        } else {
            throw new IOException("不支持的 AI CLI 消息");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession webSocket, BinaryMessage message) throws Exception {
        java.nio.ByteBuffer payload = message.getPayload();
        if (payload.remaining() > OpsWebSocketLimitsConfig.MAX_BINARY_MESSAGE_BYTES) {
            throw new IOException("AI CLI 输入过大");
        }
        byte[] data = new byte[payload.remaining()];
        payload.get(data);
        write(webSocket, data);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocket, CloseStatus status) {
        aiCli.close(sessionId(webSocket));
    }

    private String sessionId(WebSocketSession webSocket) {
        return (String) webSocket.getAttributes().get("opsAiSessionId");
    }

    private void write(WebSocketSession webSocket, byte[] data) throws IOException {
        try {
            aiCli.write(sessionId(webSocket), data);
        } catch (ServiceException ex) {
            if (!isClosedSession(ex)) throw ex;
            closeAfterSessionEnded(webSocket);
        }
    }

    private boolean isClosedSession(ServiceException exception) {
        return Integer.valueOf(HttpStatus.GONE).equals(exception.getCode());
    }

    private void closeAfterSessionEnded(WebSocketSession webSocket) {
        try {
            if (webSocket.isOpen()) webSocket.close(CloseStatus.NORMAL);
        } catch (IOException ignored) {
            // The peer may have closed concurrently; there is no useful error
            // to report for this already-ended AI session.
        }
    }

    private void closeUnauthorized(WebSocketSession webSocket) throws IOException {
        if (webSocket.isOpen()) webSocket.close(CloseStatus.POLICY_VIOLATION);
    }
}
