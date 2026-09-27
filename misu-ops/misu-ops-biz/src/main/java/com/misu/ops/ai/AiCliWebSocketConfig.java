package com.misu.ops.ai;

import com.misu.ops.OpsProperties;
import com.misu.ops.security.OpsOriginPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
public class AiCliWebSocketConfig implements WebSocketConfigurer {

    private final WebSocketHandler handler;
    private final OpsOriginPolicy originPolicy;
    private final OpsProperties properties;

    public AiCliWebSocketConfig(@Qualifier("aiCliWebSocketHandler") WebSocketHandler handler,
                                OpsOriginPolicy originPolicy, OpsProperties properties) {
        this.handler = handler;
        this.originPolicy = originPolicy;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/ai/{sessionId}")
                .addInterceptors(new AiHandshakeInterceptor(originPolicy))
                .setAllowedOrigins(properties.getAllowedOrigins().toArray(new String[0]));
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain aiWebSocketSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(new OrRequestMatcher(new AntPathRequestMatcher("/ws/ai/**"),
                        new AntPathRequestMatcher("/ops/ws/ai/**")))
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        return http.build();
    }

    private static final class AiHandshakeInterceptor implements HandshakeInterceptor {
        private final OpsOriginPolicy originPolicy;

        private AiHandshakeInterceptor(OpsOriginPolicy originPolicy) {
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
            int marker = path.lastIndexOf("/ws/ai/");
            if (marker < 0 || path.substring(marker + "/ws/ai/".length()).isBlank()
                    || path.substring(marker + "/ws/ai/".length()).contains("/")) {
                response.setStatusCode(HttpStatus.BAD_REQUEST);
                return false;
            }
            attributes.put("opsAiSessionId", path.substring(marker + "/ws/ai/".length()));
            return true;
        }

        @Override
        public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                   org.springframework.http.server.ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Exception exception) {
        }
    }
}
