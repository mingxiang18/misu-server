package com.misu.fileServer.websocket;

import com.misu.fileServer.dao.VideoRoomDao;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Component
public class VideoRoomHandshakeInterceptor implements HandshakeInterceptor {

    @Resource
    private TokenService tokenService;

    @Resource
    private VideoRoomDao videoRoomDao;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        List<String> tokenList = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().get("token");
        String token = tokenList == null || tokenList.isEmpty() ? null : tokenList.get(0);
        if (StringUtils.isBlank(token) || !tokenService.verifyAccessToken(token)) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String path = request.getURI().getPath();
        String roomId = StringUtils.substringAfterLast(path, "/");
        if (StringUtils.isBlank(roomId) || videoRoomDao.selectByRoomId(roomId).isEmpty()) {
            response.setStatusCode(HttpStatus.NOT_FOUND);
            return false;
        }

        LoginUser loginUser = tokenService.getLoginUser(token);
        attributes.put(VideoRoomWebSocketHandler.ROOM_ID_ATTRIBUTE, roomId);
        attributes.put(VideoRoomWebSocketHandler.LOGIN_USER_ATTRIBUTE, loginUser);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
