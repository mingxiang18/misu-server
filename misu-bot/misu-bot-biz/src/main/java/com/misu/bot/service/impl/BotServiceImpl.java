package com.misu.bot.service.impl;

import com.alibaba.fastjson2.JSON;
import com.misu.bot.config.BotConfig;
import com.misu.bot.domain.bot.BotTokenMessage;
import com.misu.bot.service.BotService;
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
public class BotServiceImpl implements BotService {

    @Resource
    private TokenService tokenService;

    @Resource
    private BotConfig botConfig;

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
    public String getBotAccessToken() {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        BotTokenMessage userInfo = new BotTokenMessage();
        userInfo.setUserId(loginUser.getUserId());
        userInfo.setUserName(loginUser.getUserName());
        userInfo.setAuthorities(loginUser.getAuthorities());

        Map<String, Object> claims = new HashMap<>();
        claims.put("userInfo", JSON.toJSONString(userInfo));
        return tokenService.createToken(claims, expireTtl);
    }

}
