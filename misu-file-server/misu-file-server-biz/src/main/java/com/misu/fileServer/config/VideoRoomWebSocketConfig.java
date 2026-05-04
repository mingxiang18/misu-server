package com.misu.fileServer.config;

import com.misu.fileServer.websocket.VideoRoomHandshakeInterceptor;
import com.misu.fileServer.websocket.VideoRoomWebSocketHandler;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class VideoRoomWebSocketConfig implements WebSocketConfigurer {

    @Resource
    private VideoRoomWebSocketHandler videoRoomWebSocketHandler;

    @Resource
    private VideoRoomHandshakeInterceptor videoRoomHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(videoRoomWebSocketHandler, "/videoRoom/ws/{roomId}")
                .addInterceptors(videoRoomHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
