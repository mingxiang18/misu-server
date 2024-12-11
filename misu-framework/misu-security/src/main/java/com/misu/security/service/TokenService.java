package com.misu.security.service;

import com.misu.security.dto.LoginUser;
import io.jsonwebtoken.Claims;

import java.util.Map;

/**
 * token验证处理
 *
 */
public interface TokenService {

    /**
     * 从token获取用户信息
     */
    LoginUser getLoginUser(String token);

    /**
     * 创建用户令牌
     *
     * @param loginUser 用户信息
     * @return 令牌
     */
    String createUserToken(LoginUser loginUser);

    /**
     * 验证令牌是否有效
     *
     * @param token token令牌
     * @return 令牌
     */
    boolean verifyToken(String token);

    /**
     * 创建授权令牌
     *
     * @param claims 授权信息
     * @return 令牌
     */
    String createToken(Map<String, Object> claims);

    Claims parseToken(String token);
}
