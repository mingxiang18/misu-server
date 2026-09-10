package com.misu.ops.console;

import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;

@Component
public class ConsoleWebSocketHandler extends AbstractWebSocketHandler {

    private final ConsoleWebSocketBridgeService bridges;

    public ConsoleWebSocketHandler(ConsoleWebSocketBridgeService bridges) {
        this.bridges = bridges;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        ConsoleWebSocketBridgeService.Bridge bridge = bridge(session);
        if (bridge == null) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        bridges.attach(bridge, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ConsoleWebSocketBridgeService.Bridge bridge = bridge(session);
        if (bridge != null) {
            bridge.forwardText(message.getPayload());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ConsoleWebSocketBridgeService.Bridge bridge = bridge(session);
        if (bridge != null) {
            ByteBuffer payload = message.getPayload();
            byte[] bytes = new byte[payload.remaining()];
            payload.get(bytes);
            bridge.forwardBinary(bytes);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        ConsoleWebSocketBridgeService.Bridge bridge = bridge(session);
        if (bridge != null) {
            bridge.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConsoleWebSocketBridgeService.Bridge bridge = bridge(session);
        if (bridge != null) {
            bridge.close(status);
        }
    }

    private ConsoleWebSocketBridgeService.Bridge bridge(WebSocketSession session) {
        Object value = session.getAttributes().get(ConsoleWebSocketConfig.BRIDGE_ATTRIBUTE);
        return value instanceof ConsoleWebSocketBridgeService.Bridge bridge ? bridge : null;
    }
}
