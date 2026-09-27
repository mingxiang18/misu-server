package com.misu.ops.console;

import com.misu.ops.config.OpsWebSocketLimitsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ConsoleWebSocketHandlerTest {

    @Test
    void rejectsOversizedBinaryConsoleInputBeforeBridgeLookup() {
        ConsoleWebSocketHandler handler = new ConsoleWebSocketHandler(mock(ConsoleWebSocketBridgeService.class));
        BinaryMessage message = new BinaryMessage(ByteBuffer.allocate(
                OpsWebSocketLimitsConfig.MAX_BINARY_MESSAGE_BYTES + 1));

        assertThrows(Exception.class, () -> handler.handleBinaryMessage(mock(WebSocketSession.class), message));
    }

    @Test
    void rejectsOversizedTextConsoleInputBeforeBridgeLookup() {
        ConsoleWebSocketHandler handler = new ConsoleWebSocketHandler(mock(ConsoleWebSocketBridgeService.class));
        TextMessage message = new TextMessage("x".repeat(
                OpsWebSocketLimitsConfig.MAX_TEXT_MESSAGE_BYTES / 2 + 1));

        assertThrows(Exception.class, () -> handler.handleTextMessage(mock(WebSocketSession.class), message));
    }
}
