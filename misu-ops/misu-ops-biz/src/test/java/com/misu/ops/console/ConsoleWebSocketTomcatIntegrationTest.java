package com.misu.ops.console;

import com.misu.ops.OpsApplication;
import com.misu.ops.OpsProperties;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the actual Spring MVC/Tomcat WebSocket handshake and close path. */
@SpringBootTest(classes = OpsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "token.secret=01234567890123456789012345678901",
        "server.servlet.context-path=/ops",
        "ops.proxy-shared-secret=integration-secret",
        "ops.cookie-secure=false",
        "ops.nacos-url=http://localhost:1/nacos/",
        "ops.nacos-username=test-nacos-user",
        "ops.nacos-password=test-nacos-password",
        "ops.allowed-origins=http://localhost",
        "ops.console-web-socket-connect-timeout-millis=2000"
})
class ConsoleWebSocketTomcatIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private OpsProperties properties;

    @Autowired
    private OpsSessionStore sessions;

    @Autowired
    private ConsoleWebSocketBridgeService bridges;

    @Autowired
    private NacosUpstreamAuthService nacosAuth;

    @Autowired
    private List<AbstractUrlHandlerMapping> handlerMappings;

    @MockBean
    private CurrentAccountVerifier accountVerifier;

    @Test
    void tomcatNegotiatesProtocolAndAdminRoleRevocationClosesDownstreamAndUpstream() throws Exception {
        try (ControlledWebSocketServer upstream = new ControlledWebSocketServer();
             NacosLoginServer auth = new NacosLoginServer()) {
            TestConnection connection = connect(upstream, auth);
            assertEquals("console.v1", connection.client().protocol);
            assertTrue(upstream.handshake.get(2, TimeUnit.SECONDS));
            assertTrue(auth.login.get(2, TimeUnit.SECONDS));
            assertEquals("username=test-nacos-user&password=test-nacos-password", auth.body);
            assertEquals("Bearer nacos-test-token", upstream.authorization);
            assertEquals("Bearer nacos-test-token", nacosAuth.authorization(connection.session().id()));
            assertEquals("GET /nacos/socket?x=a%2Fb HTTP/1.1", upstream.requestLine);
            assertEquals("console.v1, console.v2", upstream.requestedProtocol);

            properties.setRoleCheckSeconds(0);
            org.mockito.Mockito.doThrow(new IllegalStateException("ADMIN revoked"))
                    .when(accountVerifier).requireAdmin(7L, "admin");
            bridges.revalidateBridges();

            assertTrue(connection.closed().get(3, TimeUnit.SECONDS));
            assertTrue(upstream.closed.get(3, TimeUnit.SECONDS));
            assertEquals(0, bridges.activeBridgeCount());
            assertEquals(0, nacosAuth.cachedSessionCount());
        }
    }

    @Test
    void tomcatClosesBothEndsWhenSessionExpires() throws Exception {
        try (ControlledWebSocketServer upstream = new ControlledWebSocketServer();
             NacosLoginServer auth = new NacosLoginServer()) {
            TestConnection connection = connect(upstream, auth);
            assertEquals("console.v1", connection.client().protocol);
            assertTrue(auth.login.get(2, TimeUnit.SECONDS));
            assertEquals("Bearer nacos-test-token", nacosAuth.authorization(connection.session().id()));

            properties.setSessionIdleSeconds(0);
            bridges.revalidateBridges();

            assertTrue(connection.closed().get(3, TimeUnit.SECONDS));
            assertTrue(upstream.closed.get(3, TimeUnit.SECONDS));
            assertEquals(0, bridges.activeBridgeCount());
            assertEquals(0, nacosAuth.cachedSessionCount());
        }
    }

    @Test
    void tomcatForwardsOnlyVerifiedHeadlampIdentityToUpstream() throws Exception {
        try (ControlledWebSocketServer upstream = new ControlledWebSocketServer()) {
            properties.setSessionIdleSeconds(900);
            properties.setRoleCheckSeconds(60);
            properties.setHeadlampUrl("http://localhost:" + port + "/ops/headlamp/");
            properties.setHeadlampUpstreamUrl("http://127.0.0.1:" + upstream.port() + "/");
            OpsSessionStore.Ticket ticket = sessions.issueTicket(
                    new LoginUser(7L, "admin", List.of("ADMIN")), ConsoleTarget.HEADLAMP);
            OpsSessionStore.ConsoleSession session = sessions.createConsoleSession(ticket);
            RawWebSocketClient client = RawWebSocketClient.connect(
                    port, session.id(), properties.getCookieName(), "headlamp", "/ops/headlamp/wsMultiplexer");
            assertEquals("console.v1", client.protocol);
            assertTrue(upstream.handshake.get(2, TimeUnit.SECONDS));
            assertEquals("admin", upstream.forwardedUser);
            assertEquals("", upstream.forwardedGroups);
            assertEquals("", upstream.forwardedGroup);
            assertEquals("", upstream.forwardedEmail);
            assertEquals("", upstream.forwardedIdToken);

            bridges.closeForSession(session.id());
            assertTrue(client.closed.get(3, TimeUnit.SECONDS));
            assertTrue(upstream.closed.get(3, TimeUnit.SECONDS));
            assertEquals(0, bridges.activeBridgeCount());
        }
    }

    @Test
    void duplicateWebSocketKeyIsRejectedWhileTheFirstHandshakeIsPending() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ControlledWebSocketServer upstream = new ControlledWebSocketServer(true);
             NacosLoginServer auth = new NacosLoginServer()) {
            properties.setSessionIdleSeconds(900);
            properties.setRoleCheckSeconds(60);
            properties.setNacosUrl("http://localhost:" + port + "/nacos/");
            properties.setNacosUpstreamUrl("http://127.0.0.1:" + upstream.port() + "/nacos/");
            properties.setNacosAuthUrl("http://127.0.0.1:" + auth.port() + "/nacos/");
            OpsSessionStore.Ticket ticket = sessions.issueTicket(
                    new LoginUser(7L, "admin", List.of("ADMIN")), ConsoleTarget.NACOS);
            OpsSessionStore.ConsoleSession session = sessions.createConsoleSession(ticket);
            String key = Base64.getEncoder().encodeToString("duplicate-key-16".getBytes(StandardCharsets.US_ASCII));

            var first = executor.submit(() -> RawWebSocketClient.connect(
                    port, session.id(), properties.getCookieName(), "nacos", "/nacos/socket", key));
            assertTrue(upstream.requestReceived.get(2, TimeUnit.SECONDS));

            IOException duplicate = assertThrows(IOException.class, () -> RawWebSocketClient.connect(
                    port, session.id(), properties.getCookieName(), "nacos", "/nacos/socket", key));
            assertTrue(duplicate.getMessage().contains("409"), duplicate.getMessage());

            upstream.releaseHandshake.countDown();
            try (RawWebSocketClient client = first.get(3, TimeUnit.SECONDS)) {
                assertEquals("console.v1", client.protocol);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private TestConnection connect(ControlledWebSocketServer upstream, NacosLoginServer auth) throws Exception {
        properties.setSessionIdleSeconds(900);
        properties.setRoleCheckSeconds(60);
        assertTrue(handlerMappings.stream().anyMatch(mapping -> mapping.getHandlerMap().keySet().stream()
                .anyMatch(path -> path.contains("/ws/console/"))),
                () -> "WebSocket mappings: " + handlerMappings.stream()
                        .map(AbstractUrlHandlerMapping::getHandlerMap).toList());
        properties.setProxySharedSecret("integration-secret");
        properties.setNacosUrl("http://localhost:" + port + "/nacos/");
        properties.setNacosUpstreamUrl("http://127.0.0.1:" + upstream.port() + "/nacos/");
        properties.setNacosAuthUrl("http://127.0.0.1:" + auth.port() + "/nacos/");
        OpsSessionStore.Ticket ticket = sessions.issueTicket(
                new LoginUser(7L, "admin", List.of("ADMIN")), ConsoleTarget.NACOS);
        OpsSessionStore.ConsoleSession session = sessions.createConsoleSession(ticket);
        RawWebSocketClient client = RawWebSocketClient.connect(
                port, session.id(), properties.getCookieName());
        return new TestConnection(session, client, client.closed);
    }

    private record TestConnection(OpsSessionStore.ConsoleSession session, RawWebSocketClient client,
                                  CompletableFuture<Boolean> closed) {
    }

    /** Raw client keeps the downstream handshake proof independent of mock sessions. */
    private static final class RawWebSocketClient implements AutoCloseable {
        private final Socket socket;
        private final String protocol;
        private final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        private final Thread reader;

        private RawWebSocketClient(Socket socket, String protocol) {
            this.socket = socket;
            this.protocol = protocol;
            this.reader = new Thread(this::readUntilClose, "ops-tomcat-downstream-test");
            this.reader.start();
        }

        static RawWebSocketClient connect(int port, String sessionId, String cookieName) throws Exception {
            return connect(port, sessionId, cookieName, "nacos", "/nacos/socket?x=a%2Fb");
        }

        static RawWebSocketClient connect(int port, String sessionId, String cookieName,
                                          String target, String originalUri) throws Exception {
            String key = Base64.getEncoder().encodeToString("tomcat-test-key!".getBytes(StandardCharsets.US_ASCII));
            return connect(port, sessionId, cookieName, target, originalUri, key);
        }

        static RawWebSocketClient connect(int port, String sessionId, String cookieName,
                                          String target, String originalUri, String key) throws Exception {
            Socket socket = new Socket("localhost", port);
            socket.setSoTimeout(5000);
            String request = "GET /ops/ws/console/" + target + " HTTP/1.1\r\n"
                    + "Host: localhost:" + port + "\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Protocol: console.v1, console.v2\r\n"
                    + "Origin: http://localhost:" + port + "\r\n"
                    + "X-Ops-Proxy-Key: integration-secret\r\n"
                    + "X-Ops-Target: " + target + "\r\nX-Ops-Console-Target: " + target + "\r\n"
                    + "X-Ops-Original-URI: " + originalUri + "\r\n"
                    + "X-Forwarded-User: attacker\r\nX-Forwarded-Groups: attacker-group\r\n"
                    + "X-Forwarded-Group: attacker-compat-group\r\nX-Forwarded-Email: attacker@example.com\r\n"
                    + "X-Forwarded-Id-Token: attacker-token\r\n"
                    + "Cookie: " + cookieName + "=" + sessionId + "\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = readHeaders(socket);
            if (!response.startsWith("HTTP/1.1 101")) {
                String length = header(response, "Content-Length");
                int contentLength = length.isBlank() ? 0 : Integer.parseInt(length);
                String body = new String(socket.getInputStream().readNBytes(contentLength), StandardCharsets.UTF_8);
                throw new IOException("Tomcat WebSocket handshake failed: " + response + body);
            }
            return new RawWebSocketClient(socket, header(response, "Sec-WebSocket-Protocol"));
        }

        private static String readHeaders(Socket socket) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] end = {13, 10, 13, 10};
            int matched = 0;
            while (matched < end.length) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IOException("EOF in handshake");
                out.write(value);
                matched = value == end[matched] ? matched + 1 : (value == end[0] ? 1 : 0);
            }
            return out.toString(StandardCharsets.US_ASCII);
        }

        private static String header(String response, String name) {
            for (String line : response.split("\\r\\n")) {
                if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) {
                    return line.substring(name.length() + 1).trim();
                }
            }
            return "";
        }

        private void readUntilClose() {
            try {
                while (true) {
                    int first = socket.getInputStream().read();
                    int second = socket.getInputStream().read();
                    if (first < 0 || second < 0) break;
                    int length = second & 0x7f;
                    if (length == 126) {
                        length = (socket.getInputStream().read() << 8) | socket.getInputStream().read();
                    }
                    socket.getInputStream().readNBytes(length);
                    if ((first & 0x0f) == 0x8) break;
                }
                closed.complete(true);
            } catch (SocketTimeoutException ex) {
                closed.completeExceptionally(ex);
            } catch (Exception ex) {
                closed.completeExceptionally(ex);
            }
        }

        @Override
        public void close() throws Exception {
            socket.close();
            reader.join(1000);
        }
    }

    private static final class ControlledWebSocketServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CompletableFuture<Boolean> handshake = new CompletableFuture<>();
        private final CompletableFuture<Boolean> requestReceived = new CompletableFuture<>();
        private final CompletableFuture<Boolean> closed = new CompletableFuture<>();
        private final CountDownLatch releaseHandshake;
        private volatile String requestLine;
        private volatile String requestedProtocol;
        private volatile String authorization;
        private volatile String forwardedUser;
        private volatile String forwardedGroups;
        private volatile String forwardedGroup;
        private volatile String forwardedEmail;
        private volatile String forwardedIdToken;

        private ControlledWebSocketServer() throws IOException {
            this(false);
        }

        private ControlledWebSocketServer(boolean delayedHandshake) throws IOException {
            server = new ServerSocket(0);
            releaseHandshake = delayedHandshake ? new CountDownLatch(1) : null;
            thread = new Thread(this::serve, "ops-tomcat-upstream-test");
            thread.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                String request = readHeaders(socket);
                String[] lines = request.split("\\r\\n");
                requestLine = lines[0];
                String key = header(request, "Sec-WebSocket-Key");
                requestedProtocol = header(request, "Sec-WebSocket-Protocol");
                authorization = header(request, "Authorization");
                forwardedUser = header(request, "X-Forwarded-User");
                forwardedGroups = header(request, "X-Forwarded-Groups");
                forwardedGroup = header(request, "X-Forwarded-Group");
                forwardedEmail = header(request, "X-Forwarded-Email");
                forwardedIdToken = header(request, "X-Forwarded-Id-Token");
                requestReceived.complete(true);
                if (releaseHandshake != null) {
                    releaseHandshake.await(3, TimeUnit.SECONDS);
                }
                String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                .getBytes(StandardCharsets.US_ASCII)));
                String response = "HTTP/1.1 101 Switching Protocols\r\n"
                        + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n"
                        + "Sec-WebSocket-Protocol: console.v1\r\n\r\n";
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                handshake.complete(true);
                while (true) {
                    Frame frame = readFrame(socket);
                    if ((frame.opcode() & 0x0f) == 0x8) {
                        closed.complete(true);
                        return;
                    }
                }
            } catch (SocketTimeoutException ex) {
                closed.completeExceptionally(ex);
            } catch (Exception ex) {
                closed.completeExceptionally(ex);
            }
        }

        private static String readHeaders(Socket socket) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] end = {13, 10, 13, 10};
            int matched = 0;
            while (matched < end.length) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IOException("EOF in handshake");
                out.write(value);
                matched = value == end[matched] ? matched + 1 : (value == end[0] ? 1 : 0);
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
            if (first < 0 || second < 0) throw new IOException("EOF in frame");
            int length = second & 0x7f;
            if (length == 126) {
                length = (socket.getInputStream().read() << 8) | socket.getInputStream().read();
            }
            byte[] mask = socket.getInputStream().readNBytes(4);
            byte[] payload = socket.getInputStream().readNBytes(length);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
            return new Frame(first, payload);
        }

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(3000);
            if (!closed.isDone()) {
                closed.complete(true);
            }
        }

        private record Frame(int opcode, byte[] payload) {
        }
    }

    private static final class NacosLoginServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final CompletableFuture<Boolean> login = new CompletableFuture<>();
        private volatile String body;

        private NacosLoginServer() throws IOException {
            server = new ServerSocket(0);
            thread = new Thread(this::serve, "ops-nacos-login-test");
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            try (Socket socket = server.accept()) {
                socket.setSoTimeout(5000);
                String request = readHeaders(socket);
                int contentLength = Integer.parseInt(header(request, "Content-Length"));
                body = new String(socket.getInputStream().readNBytes(contentLength), StandardCharsets.UTF_8);
                String responseBody = "{\"accessToken\":\"nacos-test-token\",\"tokenTtl\":1800,\"username\":\"test\"}";
                String response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                        + "Content-Length: " + responseBody.getBytes(StandardCharsets.US_ASCII).length
                        + "\r\nConnection: close\r\n\r\n" + responseBody;
                socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                login.complete(true);
            } catch (Exception ex) {
                login.completeExceptionally(ex);
            }
        }

        private static String readHeaders(Socket socket) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] end = {13, 10, 13, 10};
            int matched = 0;
            while (matched < end.length) {
                int value = socket.getInputStream().read();
                if (value < 0) throw new IOException("EOF in auth request");
                out.write(value);
                matched = value == end[matched] ? matched + 1 : (value == end[0] ? 1 : 0);
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

        @Override
        public void close() throws Exception {
            server.close();
            thread.join(3000);
        }
    }
}
