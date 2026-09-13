package com.misu.ops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.websocket.server.ServerContainer;
import org.springframework.boot.web.servlet.ServletContextInitializer;

/** Explicit container bounds shared by the console and SSH WebSocket endpoints. */
@Configuration
public class OpsWebSocketLimitsConfig {

    public static final int MAX_TEXT_MESSAGE_BYTES = 1_000_000;
    public static final int MAX_BINARY_MESSAGE_BYTES = 8_000_000;

    @Bean
    public ServletContextInitializer webSocketContainerLimits() {
        return servletContext -> {
            Object attribute = servletContext.getAttribute(ServerContainer.class.getName());
            if (attribute instanceof ServerContainer container) {
                container.setDefaultMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BYTES);
                container.setDefaultMaxBinaryMessageBufferSize(MAX_BINARY_MESSAGE_BYTES);
            }
        };
    }
}
