package com.misu.ops.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.config.OpsWebSocketLimitsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCliWebSocketHandlerTest {

    @Test
    void rejectsOversizedInputBeforeForwarding() {
        AiCliWebSocketHandler handler = new AiCliWebSocketHandler(
                null, null, new ObjectMapper());
        TextMessage text = new TextMessage("x".repeat(OpsWebSocketLimitsConfig.MAX_TEXT_MESSAGE_BYTES / 2 + 1));
        assertThrows(Exception.class, () -> handler.handleTextMessage(null, text));
        TextMessage utf8Text = new TextMessage("界".repeat(
                OpsWebSocketLimitsConfig.MAX_TEXT_MESSAGE_BYTES / 3 + 1));
        assertThrows(Exception.class, () -> handler.handleTextMessage(null, utf8Text));

        BinaryMessage binary = new BinaryMessage(ByteBuffer.allocate(
                OpsWebSocketLimitsConfig.MAX_BINARY_MESSAGE_BYTES + 1));
        assertThrows(Exception.class, () -> handler.handleBinaryMessage(null, binary));
    }

    @Test
    void ignoresLateInputAndResizeAfterSessionHasEnded() throws Exception {
        ServiceException closed = new ServiceException(HttpStatus.GONE, "AI CLI 会话已关闭");
        AiCliConnectionService aiCli = new AiCliConnectionService(null, null) {
            @Override
            public void write(String sessionId, byte[] data) throws IOException {
                throw closed;
            }

            @Override
            public void resize(String sessionId, int cols, int rows) {
                throw closed;
            }
        };
        RecordingWebSocketSession inputWebSocket = webSocket();
        AiCliWebSocketHandler handler = new AiCliWebSocketHandler(
                null, aiCli, new ObjectMapper());

        handler.handleTextMessage(inputWebSocket, new TextMessage("late"));
        RecordingWebSocketSession resizeWebSocket = webSocket();
        handler.handleTextMessage(resizeWebSocket, new TextMessage(
                "{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));

        org.junit.jupiter.api.Assertions.assertEquals(1, inputWebSocket.closeCount);
        org.junit.jupiter.api.Assertions.assertEquals(1, resizeWebSocket.closeCount);
        org.junit.jupiter.api.Assertions.assertEquals(CloseStatus.NORMAL, inputWebSocket.lastCloseStatus);
        org.junit.jupiter.api.Assertions.assertEquals(CloseStatus.NORMAL, resizeWebSocket.lastCloseStatus);
    }

    @Test
    void stillPropagatesNonClosedSessionErrors() throws Exception {
        ServiceException failure = new ServiceException(HttpStatus.ERROR, "连接异常");
        AiCliConnectionService aiCli = new AiCliConnectionService(null, null) {
            @Override
            public void resize(String sessionId, int cols, int rows) {
                throw failure;
            }
        };
        RecordingWebSocketSession webSocket = webSocket();
        AiCliWebSocketHandler handler = new AiCliWebSocketHandler(
                null, aiCli, new ObjectMapper());

        ServiceException actual = assertThrows(ServiceException.class, () -> handler.handleTextMessage(
                webSocket, new TextMessage("{\"type\":\"resize\",\"cols\":120,\"rows\":40}")));

        org.junit.jupiter.api.Assertions.assertSame(failure, actual);
        org.junit.jupiter.api.Assertions.assertEquals(0, webSocket.closeCount);
    }

    private RecordingWebSocketSession webSocket() {
        RecordingWebSocketSession webSocket = new RecordingWebSocketSession();
        webSocket.attributes.put("opsAiSessionId", "closed");
        return webSocket;
    }

    private static final class RecordingWebSocketSession implements WebSocketSession {
        private final Map<String, Object> attributes = new HashMap<>();
        private int closeCount;
        private CloseStatus lastCloseStatus;
        private boolean open = true;

        @Override public String getId() { return "test"; }
        @Override public URI getUri() { return URI.create("ws://test"); }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return attributes; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSize) { }
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int messageSize) { }
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return new ArrayList<>(); }
        @Override public void sendMessage(WebSocketMessage<?> message) { }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { close(CloseStatus.NORMAL); }
        @Override public void close(CloseStatus status) {
            closeCount++;
            lastCloseStatus = status;
            open = false;
        }
    }
}
