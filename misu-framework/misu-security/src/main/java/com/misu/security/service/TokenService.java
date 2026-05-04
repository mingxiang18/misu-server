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
     * 创建用户刷新令牌
     *
     * @param loginUser 用户信息
     * @return 刷新令牌
     */
    String createRefreshToken(LoginUser loginUser);

    /**
     * 验证令牌是否有效
     *
     * @param token token令牌
     * @return 令牌
     */
    boolean verifyToken(String token);

    /**
     * 验证访问令牌是否有效
     *
     * @param token token令牌
     * @return 是否有效
     */
    boolean verifyAccessToken(String token);

    /**
     * 验证刷新令牌是否有效
     *
     * @param token token令牌
     * @return 是否有效
     */
    boolean verifyRefreshToken(String token);

    /**
     * 创建授权令牌
     *
     * @param claims 授权信息
     * @return 令牌
     */
    String createToken(Map<String, Object> claims);

    /**
     * 创建授权令牌
     *
     * @param claims 授权信息
     * @param expireTtl 超时时间
     * @return 令牌
     */
    String createToken(Map<String, Object> claims, long expireTtl);

    /**
     * 解析授权令牌
     *
     * @param token 令牌
     * @return 解析信息
     */
    Claims parseToken(String token);
}
