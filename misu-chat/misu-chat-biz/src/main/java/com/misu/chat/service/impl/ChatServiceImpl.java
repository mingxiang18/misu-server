package com.misu.chat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.misu.chat.config.ChatConfig;
import com.misu.chat.domain.message.ChatTokenMessage;
import com.misu.chat.service.ChatService;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * bot相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class ChatServiceImpl implements ChatService {

    @Resource
    private TokenService tokenService;

    @Resource
    private ChatConfig botConfig;

    // 令牌有效期（毫秒，默认5分钟）
    @Value("${bot.token.expireTtl:300000}")
    private long expireTtl;

    @Override
    public String getServerWebSocketUrl() {
        return botConfig.getServerWebSocketUrl();
    }

    /**
     * 获取bot的访问token
     */
    @Override
    public String getAccessToken() {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        ChatTokenMessage userInfo = new ChatTokenMessage();
        userInfo.setUserId(loginUser.getUserId());
        userInfo.setUserName(loginUser.getUserName());
        userInfo.setAuthorities(loginUser.getAuthorities());

        Map<String, Object> claims = new HashMap<>();
        claims.put("userInfo", JSON.toJSONString(userInfo));
        return tokenService.createToken(claims, expireTtl);
    }

}
