package com.misu.chat.service;

/**
 * bot相关Service
 *
 * @author misu
 */
public interface ChatService {

    /**
     * 获取提供客户端连接的服务端的socket的url
     */
    String getServerWebSocketUrl();

    /**
     * 获取bot的访问token
     */
    String getAccessToken();
}
