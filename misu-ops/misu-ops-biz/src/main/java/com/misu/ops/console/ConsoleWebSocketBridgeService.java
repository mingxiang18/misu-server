package com.misu.ops.console;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.controller.CookieSupport;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bridges a browser console socket to one configured console upstream. */
@Slf4j
@Component
public class ConsoleWebSocketBridgeService {

    public static final String TARGET_HEADER = "X-Ops-Console-Target";
    public static final String ORIGINAL_URI_HEADER = "X-Ops-Original-URI";
    public static final String ORIGINAL_HOST_HEADER = "X-Ops-Original-Host";
    public static final String UPSTREAM_COOKIE_HEADER = "X-Ops-Upstream-Cookie";
    public static final String UPSTREAM_AUTH_HEADER = "X-Ops-Upstream-Authorization";

    private static final int MAX_ORIGINAL_URI_LENGTH = 8192;
    private static final int MAX_PENDING_MESSAGES = 64;
    private static final int MAX_PENDING_BYTES = 8_000_000;
    private static final int MAX_TEXT_MESSAGE_BYTES = 1_000_000;
    private static final int MAX_BINARY_MESSAGE_BYTES = 8_000_000;
    private static final int BAD_GATEWAY = 502;

    private final OpsProperties properties;
    private final OpsSessionStore sessions;
    private final HttpClient httpClient;
    private final Map<String, Bridge> bridges = new ConcurrentHashMap<>();
    private final AtomicInteger activeCount = new AtomicInteger();

    public ConsoleWebSocketBridgeService(OpsProperties properties, OpsSessionStore sessions) {
        this.properties = properties;
        this.sessions = sessions;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConsoleWebSocketConnectTimeoutMillis()))
                .build();
    }

    public PreparedBridge prepare(HttpHeaders headers, ConsoleTarget target,
                                  OpsSessionStore.ConsoleSession consoleSession) {
        String originalUri = headers.getFirst(ORIGINAL_URI_HEADER);
        if (originalUri == null || originalUri.isBlank() || originalUri.length() > MAX_ORIGINAL_URI_LENGTH
                || !originalUri.startsWith("/")) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "控制台 WS 路径无效");
        }

        List<String> requestedProtocols = requestedProtocols(headers);
        reserveSlot();
        Bridge bridge = new Bridge(consoleSession, target);
        boolean registered = false;
        try {
            URI uri = upstreamUri(target, originalUri);
            WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getConsoleWebSocketConnectTimeoutMillis()))
                    .header("Origin", originOf(target.url(properties)));
            addUpstreamHeaders(builder, headers);
            if (!requestedProtocols.isEmpty()) {
                builder.subprotocols(requestedProtocols.get(0),
                        requestedProtocols.subList(1, requestedProtocols.size()).toArray(String[]::new));
            }

            WebSocket upstream = connect(builder, uri, bridge);
            bridge.setUpstream(upstream);
            String selectedProtocol = upstream.getSubprotocol();
            validateSubprotocol(requestedProtocols, selectedProtocol);
            sessions.validateConsoleSession(consoleSession.id(), target.id());
            if (bridge.isClosed()) {
                throw new ServiceException(BAD_GATEWAY, "控制台 WS 上游已关闭");
            }
            registered = bridge.register();
            if (!registered) {
                throw new ServiceException(BAD_GATEWAY, "控制台 WS 上游已关闭");
            }
            return new PreparedBridge(bridge, selectedProtocol);
        } catch (ServiceException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new ServiceException(HttpStatus.ERROR, "控制台 WS 上游不可用");
        } finally {
            if (!registered) {
                bridge.close(CloseStatus.SERVER_ERROR);
            }
        }
    }

    private void validateSubprotocol(List<String> requested, String selected) {
        boolean hasSelected = selected != null && !selected.isBlank();
        if (requested.isEmpty() && hasSelected) {
            throw new ServiceException(BAD_GATEWAY, "控制台 WS 子协议无效");
        }
        if (!requested.isEmpty() && (!hasSelected || !requested.contains(selected))) {
            throw new ServiceException(BAD_GATEWAY, "控制台 WS 子协议协商失败");
        }
    }

    private void reserveSlot() {
        int current = activeCount.incrementAndGet();
        if (current > Math.max(1, properties.getMaxConsoleWebSockets())) {
            activeCount.decrementAndGet();
            throw new ServiceException(HttpStatus.CONFLICT, "控制台 WS 连接数已达上限");
        }
    }

    private WebSocket connect(WebSocket.Builder builder, URI uri, Bridge bridge) {
        CompletableFuture<WebSocket> future = builder.buildAsync(uri, bridge);
        try {
            return future.get(properties.getConsoleWebSocketConnectTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            abortWhenConnected(future);
            throw new ServiceException(HttpStatus.ERROR, "控制台 WS 连接被中断");
        } catch (TimeoutException ex) {
            // buildAsync can still complete after get timed out. Keep the
            // callback alive and abort that socket as soon as it appears.
            abortWhenConnected(future);
            throw new ServiceException(HttpStatus.ERROR, "控制台 WS 上游连接超时");
        } catch (ExecutionException ex) {
            abortWhenConnected(future);
            throw new ServiceException(HttpStatus.ERROR, "控制台 WS 上游不可用");
        }
    }

    private void abortWhenConnected(CompletableFuture<WebSocket> future) {
        future.whenComplete((socket, error) -> {
            if (socket != null) {
                socket.abort();
            }
        });
    }

    private void addUpstreamHeaders(WebSocket.Builder builder, HttpHeaders headers) {
        String browserCookies = joinHeaderValues(headers, HttpHeaders.COOKIE);
        String cookie = headers.getFirst(UPSTREAM_COOKIE_HEADER);
        if (cookie == null) {
            cookie = CookieSupport.filterUpstreamCookies(browserCookies,
                    blockedCookieNames());
        } else {
            // Keep the proxy contract defense-in-depth: even a trusted proxy
            // response must never re-introduce a main-site credential.
            cookie = CookieSupport.filterUpstreamCookies(cookie, blockedCookieNames());
        }
        if (safeHeaderValue(cookie)) {
            builder.header(HttpHeaders.COOKIE, cookie);
        }
        String authorization = headers.getFirst(UPSTREAM_AUTH_HEADER);
        authorization = CookieSupport.filterUpstreamAuthorization(authorization, browserCookies,
                blockedCookieNames());
        if (safeHeaderValue(authorization)) {
            builder.header(HttpHeaders.AUTHORIZATION, authorization);
        }
    }

    private String joinHeaderValues(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : String.join("; ", values);
    }

    private java.util.Set<String> blockedCookieNames() {
        java.util.Set<String> names = new java.util.HashSet<>(properties.getMainCookieNames());
        names.add(properties.getCookieName());
        return names;
    }

    private List<String> requestedProtocols(HttpHeaders headers) {
        List<String> values = headers.get("Sec-WebSocket-Protocol");
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            for (String token : value.split(",")) {
                String protocol = token.trim();
                if (!protocol.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || result.contains(protocol)) {
                    throw new ServiceException(HttpStatus.BAD_REQUEST, "控制台 WS 子协议无效");
                }
                result.add(protocol);
            }
        }
        return result;
    }

    /** Construct from raw request components so encoded paths are not escaped twice. */
    private URI upstreamUri(ConsoleTarget target, String originalUri) {
        try {
            URI base = URI.create(target.upstreamUrl(properties));
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                    || base.getHost() == null || base.getUserInfo() != null
                    || base.getQuery() != null || base.getFragment() != null) {
                throw new IllegalArgumentException("invalid upstream");
            }
            URI request = URI.create("http://ops.invalid" + originalUri);
            if (request.getRawFragment() != null || request.getRawPath() == null
                    || !request.getRawPath().startsWith("/")) {
                throw new IllegalArgumentException("invalid request URI");
            }
            String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
            String query = request.getRawQuery() == null ? "" : "?" + request.getRawQuery();
            return URI.create(scheme + "://" + base.getRawAuthority() + request.getRawPath() + query);
        } catch (IllegalArgumentException ex) {
            throw new ServiceException(HttpStatus.ERROR, "控制台 WS 上游地址无效");
        }
    }

    private String originOf(String configuredUrl) {
        URI uri = URI.create(configuredUrl);
        int port = uri.getPort();
        String authority = uri.getHost() + (port > 0 ? ":" + port : "");
        return uri.getScheme() + "://" + authority;
    }

    private boolean safeHeaderValue(String value) {
        return value != null && !value.isBlank() && value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    void attach(Bridge bridge, WebSocketSession session) {
        bridge.attach(session);
    }

    public void closeForSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        bridges.values().stream()
                .filter(bridge -> sessionId.equals(bridge.session.id()))
                .forEach(bridge -> bridge.close(CloseStatus.NORMAL));
    }

    public void closeForUser(Long userId) {
        if (userId == null) {
            return;
        }
        bridges.values().stream()
                .filter(bridge -> userId.equals(bridge.session.userId()))
                .forEach(bridge -> bridge.close(CloseStatus.NORMAL));
    }

    @Scheduled(fixedDelay = 15000)
    public void revalidateBridges() {
        bridges.values().forEach(bridge -> {
            try {
                sessions.validateConsoleSession(bridge.session.id(), bridge.target.id());
            } catch (RuntimeException ex) {
                bridge.close(CloseStatus.POLICY_VIOLATION);
            }
        });
    }

    @PreDestroy
    public void closeAll() {
        bridges.values().forEach(bridge -> bridge.close(CloseStatus.GOING_AWAY));
    }

    private void releaseSlot() {
        activeCount.updateAndGet(value -> Math.max(0, value - 1));
    }

    int activeBridgeCount() {
        return activeCount.get();
    }

    public record PreparedBridge(Bridge bridge, String selectedProtocol) {
    }

    final class Bridge implements WebSocket.Listener {
        private final String id = UUID.randomUUID().toString();
        private final OpsSessionStore.ConsoleSession session;
        private final ConsoleTarget target;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean slotReleased = new AtomicBoolean();
        private final Object lifecycleLock = new Object();
        private final Object downstreamLock = new Object();
        private final Object upstreamLock = new Object();
        private final Queue<PendingMessage> downstreamPending = new ArrayDeque<>();
        private final Queue<PendingMessage> upstreamPending = new ArrayDeque<>();
        private final StringBuilder textBuffer = new StringBuilder();
        private final ByteArrayOutputStream binaryBuffer = new ByteArrayOutputStream();
        private boolean upstreamSending;
        private int downstreamPendingBytes;
        private int upstreamPendingBytes;
        private volatile WebSocket upstream;
        private volatile WebSocketSession downstream;

        private Bridge(OpsSessionStore.ConsoleSession session, ConsoleTarget target) {
            this.session = session;
            this.target = target;
        }

        boolean isClosed() {
            return closed.get();
        }

        private void setUpstream(WebSocket upstream) {
            this.upstream = upstream;
            if (closed.get()) {
                upstream.abort();
            }
        }

        private boolean register() {
            synchronized (lifecycleLock) {
                if (closed.get()) {
                    return false;
                }
                bridges.put(id, this);
                return true;
            }
        }

        private void attach(WebSocketSession downstream) {
            try {
                sessions.validateConsoleSession(session.id(), target.id());
            } catch (RuntimeException ex) {
                close(CloseStatus.POLICY_VIOLATION);
                closeWebSocket(downstream, CloseStatus.POLICY_VIOLATION);
                return;
            }
            synchronized (downstreamLock) {
                if (closed.get()) {
                    closeWebSocket(downstream, CloseStatus.POLICY_VIOLATION);
                    return;
                }
                this.downstream = downstream;
                drainDownstream();
            }
        }

        void forwardText(String text) {
            if (!validateAndTouch()) {
                return;
            }
            enqueueUpstream(new PendingMessage(text, null));
        }

        void forwardBinary(byte[] bytes) {
            if (!validateAndTouch()) {
                return;
            }
            enqueueUpstream(new PendingMessage(null, bytes));
        }

        private boolean validateAndTouch() {
            try {
                sessions.requireConsoleSession(session.id(), target.id());
                return !closed.get();
            } catch (RuntimeException ex) {
                close(CloseStatus.POLICY_VIOLATION);
                return false;
            }
        }

        private void enqueueUpstream(PendingMessage message) {
            if (message.bytes() > MAX_PENDING_BYTES) {
                close(CloseStatus.TOO_BIG_TO_PROCESS);
                return;
            }
            synchronized (upstreamLock) {
                if (closed.get()) {
                    return;
                }
                if (upstreamPending.size() >= MAX_PENDING_MESSAGES
                        || upstreamPendingBytes + message.bytes() > MAX_PENDING_BYTES) {
                    close(CloseStatus.TOO_BIG_TO_PROCESS);
                    return;
                }
                upstreamPending.add(message);
                upstreamPendingBytes += message.bytes();
                drainUpstream();
            }
        }

        private void drainUpstream() {
            if (upstreamSending || closed.get()) {
                return;
            }
            WebSocket socket = upstream;
            PendingMessage message = upstreamPending.poll();
            if (socket == null || message == null) {
                if (message != null) {
                    upstreamPending.add(message);
                }
                return;
            }
            upstreamPendingBytes -= message.bytes();
            upstreamSending = true;
            CompletableFuture<WebSocket> future = message.text() != null
                    ? socket.sendText(message.text(), true)
                    : socket.sendBinary(ByteBuffer.wrap(message.binary()), true);
            future.whenComplete((ignored, error) -> {
                boolean failed = error != null;
                synchronized (upstreamLock) {
                    upstreamSending = false;
                    if (!failed) {
                        drainUpstream();
                    }
                }
                if (failed) {
                    close(CloseStatus.SERVER_ERROR);
                }
            });
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            setUpstream(webSocket);
            if (!closed.get()) {
                webSocket.request(1);
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            synchronized (textBuffer) {
                textBuffer.append(data);
                if (textBuffer.length() * 2 > MAX_TEXT_MESSAGE_BYTES) {
                    close(CloseStatus.TOO_BIG_TO_PROCESS);
                    return null;
                }
                if (last) {
                    String text = textBuffer.toString();
                    textBuffer.setLength(0);
                    enqueueDownstream(new PendingMessage(text, null));
                }
            }
            if (!closed.get()) {
                webSocket.request(1);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            synchronized (binaryBuffer) {
                binaryBuffer.writeBytes(chunk);
                if (binaryBuffer.size() > MAX_BINARY_MESSAGE_BYTES) {
                    close(CloseStatus.TOO_BIG_TO_PROCESS);
                    return null;
                }
                if (last) {
                    enqueueDownstream(new PendingMessage(null, binaryBuffer.toByteArray()));
                    binaryBuffer.reset();
                }
            }
            if (!closed.get()) {
                webSocket.request(1);
            }
            return null;
        }

        /** Spring's WebSocketSession requires callers to serialize sendMessage. */
        private void enqueueDownstream(PendingMessage message) {
            if (message.bytes() > MAX_PENDING_BYTES) {
                close(CloseStatus.TOO_BIG_TO_PROCESS);
                return;
            }
            synchronized (downstreamLock) {
                if (closed.get()) {
                    return;
                }
                if (downstream == null) {
                    if (downstreamPending.size() >= MAX_PENDING_MESSAGES
                            || downstreamPendingBytes + message.bytes() > MAX_PENDING_BYTES) {
                        close(CloseStatus.TOO_BIG_TO_PROCESS);
                        return;
                    }
                    downstreamPending.add(message);
                    downstreamPendingBytes += message.bytes();
                    return;
                }
                downstreamPending.add(message);
                downstreamPendingBytes += message.bytes();
                drainDownstream();
            }
        }

        private void drainDownstream() {
            WebSocketSession socket = downstream;
            if (socket == null || !socket.isOpen()) {
                return;
            }
            while (!downstreamPending.isEmpty() && !closed.get()) {
                PendingMessage message = downstreamPending.remove();
                downstreamPendingBytes -= message.bytes();
                try {
                    WebSocketMessage<?> webSocketMessage = message.text() != null
                            ? new TextMessage(message.text())
                            : new BinaryMessage(message.binary());
                    socket.sendMessage(webSocketMessage);
                } catch (Exception ex) {
                    close(CloseStatus.SERVER_ERROR);
                    return;
                }
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            close(CloseStatus.NORMAL);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            close(CloseStatus.SERVER_ERROR);
        }

        void close(CloseStatus status) {
            synchronized (lifecycleLock) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                // register() and this transition are serialized. A late
                // close can therefore never be followed by re-insertion.
                bridges.remove(id, this);
            }
            if (slotReleased.compareAndSet(false, true)) {
                releaseSlot();
            }
            synchronized (downstreamLock) {
                downstreamPending.clear();
                downstreamPendingBytes = 0;
            }
            synchronized (upstreamLock) {
                upstreamPending.clear();
                upstreamPendingBytes = 0;
            }
            WebSocket upstreamSocket = upstream;
            if (upstreamSocket != null) {
                upstreamSocket.sendClose(status.getCode(), "bridge closed").exceptionally(error -> null);
                upstreamSocket.abort();
            }
            WebSocketSession downstreamSession = downstream;
            closeWebSocket(downstreamSession, status);
            log.info("控制台 WS 结束: userId={} target={} result={}",
                    session.userId(), target.id(), status.getCode());
        }

        private void closeWebSocket(WebSocketSession webSocket, CloseStatus status) {
            if (webSocket == null || !webSocket.isOpen()) {
                return;
            }
            try {
                webSocket.close(status);
            } catch (Exception ignored) {
            }
        }
    }

    record PendingMessage(String text, byte[] binary) {
        int bytes() {
            return text == null ? (binary == null ? 0 : binary.length) : text.length() * 2;
        }
    }
}
