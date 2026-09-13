package com.misu.ops.console;

import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConsoleWebSocketBridgeServiceTest {

    @Test
    void failedUpstreamHandshakeReleasesReservedSlot() {
        OpsProperties properties = new OpsProperties();
        properties.setNacosUrl("https://ops-nacos.example/nacos/");
        properties.setNacosUpstreamUrl("http://127.0.0.1:1/nacos/");
        properties.setConsoleWebSocketConnectTimeoutMillis(200);
        OpsSessionStore sessions = new OpsSessionStore(properties, new AdminVerifier());
        OpsSessionStore.Ticket ticket = sessions.issueTicket(
                new LoginUser(1L, "admin", java.util.List.of("ADMIN")), ConsoleTarget.NACOS);
        OpsSessionStore.ConsoleSession session = sessions.createConsoleSession(ticket);
        ConsoleWebSocketBridgeService service = new ConsoleWebSocketBridgeService(properties, sessions,
                new NacosUpstreamAuthService(properties));

        HttpHeaders headers = new HttpHeaders();
        headers.set(ConsoleWebSocketBridgeService.ORIGINAL_URI_HEADER, "/nacos/v1/ns?serviceName=demo%2Fx");
        assertThrows(ServiceException.class, () -> service.prepare(headers, ConsoleTarget.NACOS, session));
        assertEquals(0, service.activeBridgeCount());
    }

    @Test
    void localWebSocketEchoBridgesPathQueryProtocolTextAndBinary() throws Exception {
        try (LocalWebSocketServer upstream = new LocalWebSocketServer()) {
            OpsProperties properties = new OpsProperties();
            properties.setNacosUrl("https://ops-nacos.example/nacos/");
            properties.setNacosUpstreamUrl("http://127.0.0.1:" + upstream.port() + "/nacos/");
            properties.setConsoleWebSocketConnectTimeoutMillis(2000);
            OpsSessionStore sessions = new OpsSessionStore(properties, new AdminVerifier());
            OpsSessionStore.Ticket ticket = sessions.issueTicket(
                    new LoginUser(1L, "admin", java.util.List.of("ADMIN")), ConsoleTarget.NACOS);
            OpsSessionStore.ConsoleSession session = sessions.createConsoleSession(ticket);
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles("test");
            ConsoleWebSocketBridgeService service = new ConsoleWebSocketBridgeService(properties, sessions,
                    new NacosUpstreamAuthService(properties, environment));

            HttpHeaders headers = new HttpHeaders();
            headers.set(ConsoleWebSocketBridgeService.ORIGINAL_URI_HEADER,
                    "/nacos/socket?x=a%2Fb");
            headers.set("Cookie", "User-Token=main; NACOS_AUTH_TOKEN=browser");
            headers.set("X-Forwarded-User", "attacker");
            headers.set("X-Forwarded-Groups", "attacker-group");
            headers.set("X-Forwarded-Group", "attacker-compat-group");
            headers.set("X-Forwarded-Email", "attacker@example.com");
            headers.set("X-Forwarded-Id-Token", "attacker-token");
            headers.set(ConsoleWebSocketBridgeService.UPSTREAM_COOKIE_HEADER,
                    "NACOS_AUTH_TOKEN=upstream; User-Token=must-remove");
            headers.set("Sec-WebSocket-Protocol", "console.v1, console.v2");

            ConsoleWebSocketBridgeService.PreparedBridge prepared =
                    service.prepare(headers, ConsoleTarget.NACOS, session);
            assertEquals("console.v1", prepared.selectedProtocol());
            assertEquals("GET /nacos/socket?x=a%2Fb HTTP/1.1", upstream.requestLine());
            assertEquals("console.v1, console.v2", upstream.requestedProtocol());
            org.junit.jupiter.api.Assertions.assertFalse(upstream.handshake().contains("User-Token"));
            org.junit.jupiter.api.Assertions.assertFalse(upstream.handshake().contains("Authorization:"));
            org.junit.jupiter.api.Assertions.assertFalse(upstream.handshake().contains("X-Forwarded-User:"));
            org.junit.jupiter.api.Assertions.assertFalse(upstream.handshake().contains("X-Forwarded-Groups:"));
            org.junit.jupiter.api.Assertions.assertFalse(upstream.handshake().contains("X-Forwarded-Group:"));
            org.junit.jupiter.api.Assertions.assertFalse(upstream.handshake().contains("X-Forwarded-Email:"));
            org.junit.jupiter.api.Assertions.assertFalse(upstream.handshake().contains("X-Forwarded-Id-Token:"));

            WebSocketSession downstream = mock(WebSocketSession.class);
            when(downstream.isOpen()).thenReturn(true);
            CountDownLatch messages = new CountDownLatch(2);
            List<WebSocketMessage<?>> received = Collections.synchronizedList(new java.util.ArrayList<>());
            doAnswer(invocation -> {
                received.add(invocation.getArgument(0));
                messages.countDown();
                return null;
            }).when(downstream).sendMessage(org.mockito.ArgumentMatchers.any());
            service.attach(prepared.bridge(), downstream);
            prepared.bridge().forwardText("ping");

            org.junit.jupiter.api.Assertions.assertTrue(messages.await(2, TimeUnit.SECONDS));
            assertEquals("ping", ((org.springframework.web.socket.TextMessage) received.get(0)).getPayload());
            assertEquals(3, ((org.springframework.web.socket.BinaryMessage) received.get(1)).getPayload().remaining());
            prepared.bridge().close(org.springframework.web.socket.CloseStatus.NORMAL);
            assertEquals(0, service.activeBridgeCount());
        }
    }

    @Test
    void headlampWebSocketUsesSessionIdentityAndDropsClientIdentityHeaders() throws Exception {
        try (LocalWebSocketServer upstream = new LocalWebSocketServer()) {
            OpsProperties properties = new OpsProperties();
            properties.setHeadlampUrl("https://ops-headlamp.example/ops/headlamp/");
            properties.setHeadlampUpstreamUrl("http://127.0.0.1:" + upstream.port() + "/");
            properties.setConsoleWebSocketConnectTimeoutMillis(2000);
            OpsSessionStore sessions = new OpsSessionStore(properties, new AdminVerifier());
            OpsSessionStore.Ticket ticket = sessions.issueTicket(
                    new LoginUser(1L, "admin", java.util.List.of("ADMIN")), ConsoleTarget.HEADLAMP);
            OpsSessionStore.ConsoleSession session = sessions.createConsoleSession(ticket);
            ConsoleWebSocketBridgeService service = new ConsoleWebSocketBridgeService(properties, sessions);

            HttpHeaders headers = new HttpHeaders();
            headers.set(ConsoleWebSocketBridgeService.ORIGINAL_URI_HEADER,
                    "/ops/headlamp/wsMultiplexer");
            headers.set(HttpHeaders.COOKIE, properties.getCookieName() + "=" + session.id());
            headers.set("Sec-WebSocket-Protocol", "console.v1");
            headers.set("X-Forwarded-User", "attacker");
            headers.set("X-Forwarded-Groups", "attacker-group");
            headers.set("X-Forwarded-Group", "attacker-compat-group");
            headers.set("X-Forwarded-Email", "attacker@example.com");
            headers.set("X-Forwarded-Id-Token", "attacker-token");

            ConsoleWebSocketBridgeService.PreparedBridge prepared =
                    service.prepare(headers, ConsoleTarget.HEADLAMP, session);
            String handshake = upstream.handshake();
            org.junit.jupiter.api.Assertions.assertTrue(handshake.contains("X-Forwarded-User: admin"), handshake);
            org.junit.jupiter.api.Assertions.assertFalse(handshake.contains("attacker"), handshake);
            org.junit.jupiter.api.Assertions.assertFalse(handshake.contains("X-Forwarded-Groups:"), handshake);
            org.junit.jupiter.api.Assertions.assertFalse(handshake.contains("X-Forwarded-Group:"), handshake);
            org.junit.jupiter.api.Assertions.assertFalse(handshake.contains("X-Forwarded-Email:"), handshake);
            org.junit.jupiter.api.Assertions.assertFalse(handshake.contains("X-Forwarded-Id-Token:"), handshake);
            prepared.bridge().close(org.springframework.web.socket.CloseStatus.NORMAL);
            assertEquals(0, service.activeBridgeCount());
        }
    }

    private static final class LocalWebSocketServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private volatile String requestLine;
        private volatile String requestedProtocol;
        private volatile String handshake;

        private LocalWebSocketServer() throws IOException {
            server = new ServerSocket(0);
            thread = new Thread(this::serve, "ops-local-ws-test");
            thread.start();
        }

        int port() { return server.getLocalPort(); }
        String requestLine() { return requestLine; }
        String requestedProtocol() { return requestedProtocol; }
        String handshake() { return handshake == null ? "" : handshake; }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(3000);
                String request = readHeaders(socket);
                handshake = request;
                String[] lines = request.split("\\r\\n");
                requestLine = lines[0];
                String key = header(request, "Sec-WebSocket-Key");
                requestedProtocol = header(request, "Sec-WebSocket-Protocol");
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII)));
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + "Sec-WebSocket-Protocol: console.v1\r\n\r\n";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                Frame frame = readFrame(socket);
                writeFrame(socket, (byte) 0x81, frame.payload());
                writeFrame(socket, (byte) 0x82, new byte[]{1, 2, 3});
                Thread.sleep(100);
            } catch (Exception ignored) {
                // The bridge closes the socket as part of test cleanup.
            }
        }

        private static String readHeaders(Socket socket) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IOException("EOF in handshake");
                out.write(value);
                state = value == new byte[]{13, 10, 13, 10}[state] ? state + 1 : (value == 13 ? 1 : 0);
            }
            return out.toString(StandardCharsets.US_ASCII);
        }

        private static String header(String request, String name) {
            for (String line : request.split("\\r\\n")) {
                if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                    return line.substring(name.length() + 1).trim();
                }
            }
            return "";
        }

        private static Frame readFrame(Socket socket) throws IOException {
            int first = socket.getInputStream().read();
            int second = socket.getInputStream().read();
            int length = second & 0x7f;
            byte[] mask = socket.getInputStream().readNBytes(4);
            byte[] payload = socket.getInputStream().readNBytes(length);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            return new Frame(first, payload);
        }

        private static void writeFrame(Socket socket, byte opcode, byte[] payload) throws IOException {
            socket.getOutputStream().write(new byte[]{opcode, (byte) payload.length});
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(3000);
        }

        private record Frame(int opcode, byte[] payload) { }
    }

    private static final class AdminVerifier implements CurrentAccountVerifier {
        @Override
        public LoginUser requireAdmin(LoginUser tokenUser) {
            return tokenUser;
        }

        @Override
        public LoginUser requireAdmin(Long userId, String userName) {
            return new LoginUser(userId, userName, java.util.List.of("ADMIN"));
        }
    }
}
