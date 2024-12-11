package com.misu.web.user;

import com.alibaba.fastjson2.JSON;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.account.repository.SysUserRepository;
import com.misu.account.domain.entity.SysUser;
import com.misu.account.dao.impl.UserDaoImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Slf4j
@SpringBootTest
public class UserTest {
    @Autowired
    private SysUserRepository userRepository;

    @Autowired
    private UserDaoImpl userDaoImpl;

    @Autowired
    private TokenService tokenService;

    public static void main(String[] args) {
        SecureRandom secureRandom = new SecureRandom();
        byte[] key = new byte[32]; // 256 位
        secureRandom.nextBytes(key);
        // 转换为 Base64 字符串
        String base64Key = Base64.getEncoder().encodeToString(key);
        System.out.println("Base64 Encoded Key: " + base64Key);
    }

    @Test
    public void testToken() throws Exception {
        String token = tokenService.createUserToken(new LoginUser(123l, "123", Arrays.asList("ROLE_USER")));
        System.out.println(token);
        System.out.println();
        System.out.println(JSON.toJSONString(tokenService.getLoginUser(token)));
        System.out.println(tokenService.verifyToken(token));
    }

    @Test
    @Transactional
    @Rollback(false)
    public void test() throws Exception {

        // 初始化 3 个用户
        SysUser u1 = new SysUser();
        u1.setUserName("123");
        u1.setNickName("123");

        this.userRepository.save(u1);
    }

    @Test
    public void testQuery() throws Exception {

        System.out.println(JSON.toJSONString(userDaoImpl.selectUserLoginInfo("123")));
    }
}
