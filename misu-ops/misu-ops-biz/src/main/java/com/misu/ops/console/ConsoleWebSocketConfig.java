package com.misu.ops.console;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.controller.CookieSupport;
import com.misu.ops.security.OpsOriginPolicy;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableWebSocket
public class ConsoleWebSocketConfig implements WebSocketConfigurer {

    public static final String BRIDGE_ATTRIBUTE = "opsConsoleBridge";
    private static final String PROTOCOL_ATTRIBUTE = "opsConsoleSubprotocol";
    private static final String KEY_HEADER = "Sec-WebSocket-Key";

    private final ConsoleWebSocketHandler handler;
    private final ConsoleWebSocketBridgeService bridges;
    private final OpsSessionStore sessions;
    private final OpsProperties properties;
    private final OpsOriginPolicy originPolicy;

    public ConsoleWebSocketConfig(ConsoleWebSocketHandler handler,
                                  ConsoleWebSocketBridgeService bridges,
                                  OpsSessionStore sessions,
                                  OpsProperties properties,
                                  OpsOriginPolicy originPolicy) {
        this.handler = handler;
        this.bridges = bridges;
        this.sessions = sessions;
        this.properties = properties;
        this.originPolicy = originPolicy;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        ConsoleHandshakeInterceptor interceptor = new ConsoleHandshakeInterceptor();
        registry.addHandler(handler, "/ws/console/{target}")
                .addInterceptors(interceptor)
                .setHandshakeHandler(new ConsoleHandshakeHandler(interceptor));
    }

    @org.springframework.context.annotation.Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain consoleWebSocketSecurity(HttpSecurity http) throws Exception {
        // Spring Security's matcher sees the context path on the embedded
        // servlet request; keep both forms so /ops/ws/console/** reaches the
        // handshake interceptor instead of the JWT chain.
        http.securityMatcher(new OrRequestMatcher(new AntPathRequestMatcher("/ws/console/**"),
                        new AntPathRequestMatcher("/ops/ws/console/**")))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private final class ConsoleHandshakeInterceptor implements HandshakeInterceptor {
        private final Map<String, ConsoleWebSocketBridgeService.Bridge> pending = new ConcurrentHashMap<>();
        private final java.util.Set<String> upgraded = ConcurrentHashMap.newKeySet();

        private void markUpgraded(String key) {
            if (key != null) {
                upgraded.add(key);
            }
        }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            try {
                requireTrustedProxy(request);
                ConsoleTarget target = targetFromPath(request.getURI().getPath());
                if (!target.id().equalsIgnoreCase(request.getHeaders().getFirst(
                        ConsoleWebSocketBridgeService.TARGET_HEADER))) {
                    throw new ServiceException(HttpStatus.FORBIDDEN, "控制台 WS 目标无效");
                }
                originPolicy.requireConsoleHost(request.getHeaders().getFirst(HttpHeaders.HOST), target);
                originPolicy.requireConsoleWebSocketOrigin(
                        request.getHeaders().getFirst(HttpHeaders.ORIGIN), target);
                String sessionId = CookieSupport.read(request.getHeaders().getFirst(HttpHeaders.COOKIE),
                        properties.getCookieName());
                OpsSessionStore.ConsoleSession consoleSession = sessions.requireConsoleSession(sessionId, target.id());
                ConsoleWebSocketBridgeService.PreparedBridge prepared = bridges.prepare(
                        request.getHeaders(), target, consoleSession);
                attributes.put(BRIDGE_ATTRIBUTE, prepared.bridge());
                if (prepared.selectedProtocol() != null && !prepared.selectedProtocol().isBlank()) {
                    attributes.put(PROTOCOL_ATTRIBUTE, prepared.selectedProtocol());
                }
                String key = request.getHeaders().getFirst(KEY_HEADER);
                if (key != null) {
                    pending.put(key, prepared.bridge());
                    // The upstream may close between prepare() and the
                    // container's handshake callback. Do not leave a closed
                    // bridge waiting for an attach that can never arrive.
                    if (prepared.bridge().isClosed() && pending.remove(key, prepared.bridge())) {
                        prepared.bridge().close(org.springframework.web.socket.CloseStatus.SERVER_ERROR);
                        throw new ServiceException(HttpStatus.ERROR, "控制台 WS 上游已关闭");
                    }
                }
                return true;
            } catch (ServiceException ex) {
                response.setStatusCode(HttpStatusCode.valueOf(ex.getCode()));
                return false;
            }
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
            String key = request.getHeaders().getFirst(KEY_HEADER);
            ConsoleWebSocketBridgeService.Bridge bridge = key == null ? null : pending.remove(key);
            boolean handshakeSucceeded = key != null && upgraded.remove(key);
            if ((exception != null || !handshakeSucceeded) && bridge != null) {
                bridge.close(org.springframework.web.socket.CloseStatus.SERVER_ERROR);
            }
        }

        private void requireTrustedProxy(ServerHttpRequest request) {
            InetAddress address = request.getRemoteAddress() == null
                    ? null : request.getRemoteAddress().getAddress();
            String supplied = request.getHeaders().getFirst("X-Ops-Proxy-Key");
            String expected = properties.getProxySharedSecret();
            if (address == null || !address.isLoopbackAddress()
                    || expected == null || expected.isBlank() || supplied == null
                    || !java.security.MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                throw new ServiceException(HttpStatus.UNAUTHORIZED, "代理授权失败");
            }
        }

        private ConsoleTarget targetFromPath(String path) {
            String prefix = "/ws/console/";
            int marker = path.lastIndexOf(prefix);
            String value = marker < 0 ? "" : path.substring(marker + prefix.length());
            if (value.isBlank() || value.contains("/")) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "控制台 WS 路径无效");
            }
            return ConsoleTarget.parse(value);
        }
    }

    private static final class ConsoleHandshakeHandler implements HandshakeHandler {
        private final ThreadLocal<String> selectedProtocol = new ThreadLocal<>();
        private final ConsoleHandshakeInterceptor interceptor;

        private ConsoleHandshakeHandler(ConsoleHandshakeInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public boolean doHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
            selectedProtocol.set((String) attributes.get(PROTOCOL_ATTRIBUTE));
            try {
                // Keep the real handler intact. AbstractHandshakeHandler
                // unwraps WebSocketHandlerDecorator before checking
                // SubProtocolCapable, so a decorator cannot advertise the
                // upstream-selected protocol reliably.
                boolean result = new DefaultHandshakeHandler() {
                    @Override
                    protected String selectProtocol(List<String> requestedProtocols,
                                                    WebSocketHandler handler) {
                        String selected = selectedProtocol.get();
                        if (selected != null && requestedProtocols.contains(selected)) {
                            return selected;
                        }
                        return super.selectProtocol(requestedProtocols, handler);
                    }
                }.doHandshake(request, response, wsHandler, attributes);
                if (result) {
                    interceptor.markUpgraded(request.getHeaders().getFirst(KEY_HEADER));
                }
                return result;
            } finally {
                selectedProtocol.remove();
            }
        }
    }
}
