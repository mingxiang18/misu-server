package com.misu.ops.ssh;

import com.misu.ops.security.OpsOriginPolicy;
import com.misu.ops.OpsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
public class OpsWebSocketConfig implements WebSocketConfigurer {

    private final WebSocketHandler handler;
    private final OpsOriginPolicy originPolicy;
    private final OpsProperties properties;

    public OpsWebSocketConfig(@Qualifier("opsWebSocketHandler") WebSocketHandler handler,
                              OpsOriginPolicy originPolicy, OpsProperties properties) {
        this.handler = handler;
        this.originPolicy = originPolicy;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/ssh/{sessionId}")
                .addInterceptors(new OpsHandshakeInterceptor(originPolicy))
                .setAllowedOrigins(properties.getAllowedOrigins().toArray(new String[0]));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain opsWebSocketSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(new OrRequestMatcher(new AntPathRequestMatcher("/ws/ssh/**"),
                        new AntPathRequestMatcher("/ops/ws/ssh/**")))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private static final class OpsHandshakeInterceptor implements HandshakeInterceptor {
        private final OpsOriginPolicy originPolicy;

        private OpsHandshakeInterceptor(OpsOriginPolicy originPolicy) {
            this.originPolicy = originPolicy;
        }

        @Override
        public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                       org.springframework.http.server.ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            String origin = request.getHeaders().getOrigin();
            if (!originPolicy.isAllowedWebSocketOrigin(origin)) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }
            String path = request.getURI().getPath();
            int marker = path.lastIndexOf("/ws/ssh/");
            if (marker < 0 || path.substring(marker + "/ws/ssh/".length()).isBlank()
                    || path.substring(marker + "/ws/ssh/".length()).contains("/")) {
                response.setStatusCode(HttpStatus.BAD_REQUEST);
                return false;
            }
            attributes.put("opsSshSessionId", path.substring(marker + "/ws/ssh/".length()));
            return true;
        }

        @Override
        public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                   org.springframework.http.server.ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
