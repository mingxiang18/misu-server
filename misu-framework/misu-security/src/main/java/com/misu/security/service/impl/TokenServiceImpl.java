package com.misu.security.service.impl;

import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/**
 * token验证处理
 *
 */
@Slf4j
@Service
public class TokenServiceImpl implements TokenService {
    // 令牌自定义标识
    @Value("${token.header:Authorization}")
    private String header;

    // 令牌秘钥，开发环境使用，生产环境的需重新生成后配置到配置文件
    @Value("${token.secret:vDW2/YL5VGg3wAub8ohhz2l+OUjtYa1k4twm4nNNzAY=}")
    private String secret;

    // 令牌有效期（毫秒，默认60分钟）
    @Value("${token.expireTtl:3600000}")
    private long expireTtl;

    /**
     * 自用的生成secret的方法
     */
    public static void main(String[] args) {
        SecureRandom secureRandom = new SecureRandom();
        byte[] key = new byte[32]; // 256 位
        secureRandom.nextBytes(key);
        // 转换为 Base64 字符串
        String base64Key = Base64.getEncoder().encodeToString(key);
        System.out.println("Base64 Encoded Key: " + base64Key);
    }

    /**
     * 从token获取用户信息
     *
     * @param token
     * @return LoginUser
     */
    @Override
    public LoginUser getLoginUser(String token) {
        Claims claims = parseToken(token);

        return new LoginUser(claims.get("userId", Long.class),
                claims.get("userName", String.class),
                claims.get("authorities", List.class));
    }

    /**
     * 创建令牌
     *
     * @param loginUser 用户信息
     * @return 令牌
     */
    @Override
    public String createUserToken(LoginUser loginUser) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", loginUser.getUserId());
        claims.put("userName", loginUser.getUserName());
        claims.put("authorities", loginUser.getAuthorities());
        return createToken(claims);
    }

    /**
     * 验证令牌是否有效
     *
     * @param token token令牌
     * @return 令牌
     */
    @Override
    public boolean verifyToken(String token) {
        try {
            Claims claims = parseToken(token);
            return claims.getExpiration().after(new Date());
        }catch (Exception e) {
            log.debug("token【" + token + "】解析失败");
            return false;
        }
    }

    /**
     * 生成jwt
     * 使用Hs512算法, 私匙使用固定秘钥
     *
     * @param claims    设置的信息
     * @return
     */
    @Override
    public String createToken(Map<String, Object> claims) {
        return createToken(claims, expireTtl);
    }

    /**
     * 生成jwt
     * 使用Hs512算法, 私匙使用固定秘钥
     *
     * @param claims    设置的信息
     * @return
     */
    @Override
    public String createToken(Map<String, Object> claims, long expireTtl) {
        //生成 HMAC 密钥，根据提供的字节数组长度选择适当的 HMAC 算法，并返回相应的 SecretKey 对象。
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        // 设置jwt的body
        JwtBuilder builder = Jwts.builder()
                // 设置签名使用的签名算法和签名使用的秘钥
                .signWith(key)
                // 如果有私有声明，一定要先设置这个自己创建的私有的声明，这个是给builder的claim赋值，一旦写在标准的声明赋值之后，就是覆盖了那些标准的声明的
                .claims(claims)
                // 签发时间
                .issuedAt(new Date())
                // 设置过期时间
                .expiration(new Date(System.currentTimeMillis() + expireTtl));
        return builder.compact();
    }

    /**
     * Token解密
     *
     * @param token 加密后的token
     * @return
     */
    @Override
    public Claims parseToken(String token) {
        //生成 HMAC 密钥，根据提供的字节数组长度选择适当的 HMAC 算法，并返回相应的 SecretKey 对象。
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        // 得到DefaultJwtParser
        JwtParser jwtParser = Jwts.parser()
                // 设置签名的秘钥
                .verifyWith(key)
                .build();
        Jws<Claims> jws = jwtParser.parseSignedClaims(token);
        Claims claims = jws.getPayload();
        return claims;
    }
}
