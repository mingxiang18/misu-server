package com.misu.ops.ssh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.ops.session.OpsSessionStore;
import com.misu.ops.config.OpsWebSocketLimitsConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class OpsWebSocketHandler extends AbstractWebSocketHandler {

    private final OpsSessionStore sessions;
    private final SshConnectionService ssh;
    private final ObjectMapper objectMapper;

    public OpsWebSocketHandler(OpsSessionStore sessions, SshConnectionService ssh, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.ssh = ssh;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocket) throws Exception {
        String sessionId = (String) webSocket.getAttributes().get("opsSshSessionId");
        try {
            OpsSessionStore.SshSession session = sessions.claimSshSession(sessionId);
            ssh.open(session, webSocket);
        } catch (RuntimeException ex) {
            closeUnauthorized(webSocket);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession webSocket, TextMessage message) throws Exception {
        String text = message.getPayload();
        if (text.length() * 2L > OpsWebSocketLimitsConfig.MAX_TEXT_MESSAGE_BYTES) {
            throw new IOException("terminal input too large");
        }
        if (!text.startsWith("{")) {
            ssh.write(sessionId(webSocket), text.getBytes(StandardCharsets.UTF_8));
            return;
        }
        JsonNode command = objectMapper.readTree(text);
        String type = command.path("type").asText("");
        if ("input".equals(type)) {
            String data = command.path("data").asText(null);
            if (data == null) {
                throw new IOException("terminal input is missing");
            }
            ssh.write(sessionId(webSocket), data.getBytes(StandardCharsets.UTF_8));
        } else if ("resize".equals(type)) {
            ssh.resize(sessionId(webSocket), command.path("cols").asInt(80), command.path("rows").asInt(24));
        } else {
            throw new IOException("unsupported terminal message");
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession webSocket, BinaryMessage message) throws Exception {
        java.nio.ByteBuffer payload = message.getPayload();
        if (payload.remaining() > OpsWebSocketLimitsConfig.MAX_BINARY_MESSAGE_BYTES) {
            throw new IOException("terminal input too large");
        }
        byte[] data = new byte[payload.remaining()];
        payload.get(data);
        ssh.write(sessionId(webSocket), data);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession webSocket, CloseStatus status) {
        ssh.close(sessionId(webSocket));
    }

    private String sessionId(WebSocketSession webSocket) {
        return (String) webSocket.getAttributes().get("opsSshSessionId");
    }

    private void closeUnauthorized(WebSocketSession webSocket) throws IOException {
        if (webSocket.isOpen()) {
            webSocket.close(CloseStatus.POLICY_VIOLATION);
        }
    }
}
