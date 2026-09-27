package com.misu.account.service.impl;

import com.misu.account.domain.dto.auth.LoginRequestDto;
import com.misu.account.domain.dto.auth.LoginUserDto;
import com.misu.account.service.TurnstileService;
import com.misu.account.service.UserService;
import com.misu.common.exception.ServiceException;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthServiceImplTurnstileTest {
    @Test
    void rejectsChallengeBeforeLookingUpUser() {
        var service = new AuthServiceImpl();
        var verifier = mock(TurnstileService.class);
        var userService = mock(UserService.class);
        var tokenService = mock(TokenService.class);
        ReflectionTestUtils.setField(service, "turnstileService", verifier);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);
        doThrow(new ServiceException(400, "人机验证失败")).when(verifier).verifyLogin("invalid");

        var request = new LoginRequestDto();
        request.setUserName("admin");
        request.setPassword("password");
        request.setTurnstileToken("invalid");

        assertThatThrownBy(() -> service.login(request)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(userService, tokenService);
    }

    @Test
    void mockedLoginRequiresChallengeBeforeIssuingTokens() {
        var service = new AuthServiceImpl();
        var verifier = mock(TurnstileService.class);
        var userService = mock(UserService.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var tokenService = mock(TokenService.class);
        ReflectionTestUtils.setField(service, "turnstileService", verifier);
        ReflectionTestUtils.setField(service, "userService", userService);
        ReflectionTestUtils.setField(service, "passwordEncoder", passwordEncoder);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);

        var user = new LoginUserDto();
        user.setUserId(1L);
        user.setUserName("mock-user");
        user.setPassword("stored-hash");
        user.setStatus("0");
        user.setDelFlag("0");
        user.setAuthorities(List.of("ADMIN"));
        when(userService.selectUserLoginInfo("mock-user")).thenReturn(user);
        when(passwordEncoder.matches("mock-password", "stored-hash")).thenReturn(true);
        when(tokenService.createUserToken(any(LoginUser.class))).thenReturn("mock-access-token");
        when(tokenService.createRefreshToken(any(LoginUser.class))).thenReturn("mock-refresh-token");

        var request = new LoginRequestDto();
        request.setUserName("mock-user");
        request.setPassword("mock-password");
        request.setTurnstileToken("mock-turnstile-token");

        var response = service.login(request);
        assertThat(response.getToken()).isEqualTo("mock-access-token");
        assertThat(response.getRefreshToken()).isEqualTo("mock-refresh-token");
        var order = inOrder(verifier, userService, passwordEncoder, tokenService);
        order.verify(verifier).verifyLogin("mock-turnstile-token");
        order.verify(userService).selectUserLoginInfo("mock-user");
        order.verify(passwordEncoder).matches("mock-password", "stored-hash");
        order.verify(tokenService).createUserToken(any(LoginUser.class));
        order.verify(tokenService).createRefreshToken(any(LoginUser.class));
    }
}
