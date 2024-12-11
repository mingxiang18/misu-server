package com.misu.account.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.security.constant.UserRole;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.account.dao.UserDao;
import com.misu.account.domain.dto.auth.*;
import com.misu.account.domain.entity.QSysUser;
import com.misu.account.domain.entity.SysUser;
import com.misu.account.domain.entity.SysUserRole;
import com.misu.account.repository.SysUserRepository;
import com.misu.account.repository.SysUserRoleRepository;
import com.misu.account.service.AuthService;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证相关业务
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    @Autowired
    private UserDao userDao;

    @Autowired
    private SysUserRepository userRepository;

    @Autowired
    private SysUserRoleRepository userRoleRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    @Value("${register.enable:false}")
    private Boolean registerEnable;

    @Override
    public LoginResponseDto login(LoginRequestDto loginRequestDto) {

        LoginUserDto loginUserDto = userDao.selectUserLoginInfo(loginRequestDto.getUserName());

        if (loginUserDto == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户不存在或密码错误");
        }

        //判断密码是否匹配
        if (passwordEncoder.matches(loginRequestDto.getPassword(), loginUserDto.getPassword())) {
            //登录成功后获取用户名并设置token返回
            LoginResponseDto loginResponseDto = new LoginResponseDto();
            loginResponseDto.setUserName(loginUserDto.getUserName());
            loginResponseDto.setToken(
                    tokenService.createUserToken(new LoginUser(loginUserDto.getUserId(), loginUserDto.getUserName(), loginUserDto.getAuthorities())));
            return loginResponseDto;
        }else {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户不存在或密码错误");
        }
    }

    @Override
    public RegisterResponseDto register(RegisterRequestDto registerRequestDto) {
        if (!registerEnable) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前不开放注册");
        }
        // 构建条件：用户名或手机号已存在
        BooleanExpression usernameExists = QSysUser.sysUser.userName.eq(registerRequestDto.getUserName());
        if (userRepository.exists(usernameExists)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "用户名已存在");
        }
        BooleanExpression phoneNumberExists = QSysUser.sysUser.phoneNumber.eq(registerRequestDto.getPhoneNumber());
        if (userRepository.exists(phoneNumberExists)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "手机号已存在");
        }

        //加密密码
        String encodePassword = passwordEncoder.encode(registerRequestDto.getPassword());

        //封装用户实体并保存
        SysUser sysUser = new SysUser();
        sysUser.setUserName(registerRequestDto.getUserName());
        sysUser.setNickName(registerRequestDto.getNickName());
        sysUser.setEmail(registerRequestDto.getEmail());
        sysUser.setPhoneNumber(registerRequestDto.getPhoneNumber());
        sysUser.setPassword(encodePassword);
        userRepository.save(sysUser);

        //封装为普通用户权限并保存
        SysUserRole sysUserRole = new SysUserRole();
        sysUserRole.setUserId(sysUser.getUserId());
        sysUserRole.setRoleId(UserRole.USER);
        userRoleRepository.save(sysUserRole);

        //返回成功信息
        RegisterResponseDto registerResponseDto = new RegisterResponseDto();
        registerResponseDto.setUserName(registerRequestDto.getUserName());
        return registerResponseDto;
    }
}
