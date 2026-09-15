package com.misu.ops.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.ops.config.OpsWebSocketLimitsConfig;
import com.misu.ops.session.OpsSessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

class OpsWebSocketHandlerTest {

    @Test
    void rejectsOversizedBinaryTerminalInputBeforeCopying() {
        OpsWebSocketHandler handler = new OpsWebSocketHandler(
                mock(OpsSessionStore.class), mock(SshConnectionService.class), new ObjectMapper());
        BinaryMessage message = new BinaryMessage(ByteBuffer.allocate(
                OpsWebSocketLimitsConfig.MAX_BINARY_MESSAGE_BYTES + 1));

        assertThrows(Exception.class, () -> handler.handleBinaryMessage(mock(WebSocketSession.class), message));
    }

    @Test
    void rejectsOversizedTextTerminalInputBeforeParsing() {
        OpsWebSocketHandler handler = new OpsWebSocketHandler(
                mock(OpsSessionStore.class), mock(SshConnectionService.class), new ObjectMapper());
        TextMessage message = new TextMessage("x".repeat(
                OpsWebSocketLimitsConfig.MAX_TEXT_MESSAGE_BYTES / 2 + 1));

        assertThrows(Exception.class, () -> handler.handleTextMessage(mock(WebSocketSession.class), message));
    }

    @Test
    void pingTouchesTheLiveSessionWithoutWritingToSsh() throws Exception {
        SshConnectionService ssh = mock(SshConnectionService.class);
        WebSocketSession webSocket = mock(WebSocketSession.class);
        when(webSocket.getAttributes()).thenReturn(Map.of("opsSshSessionId", "ssh-session"));
        OpsWebSocketHandler handler = new OpsWebSocketHandler(
                mock(OpsSessionStore.class), ssh, new ObjectMapper());

        handler.handleTextMessage(webSocket, new TextMessage("{\"type\":\"ping\"}"));

        verify(ssh).ping("ssh-session");
        verifyNoMoreInteractions(ssh);
    }
}
