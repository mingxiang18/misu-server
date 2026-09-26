package com.misu.account.service.impl;

import com.misu.account.domain.dto.auth.LoginRequestDto;
import com.misu.account.service.TurnstileService;
import com.misu.account.service.UserService;
import com.misu.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthServiceImplTurnstileTest {
    @Test
    void rejectsChallengeBeforeLookingUpUser() {
        var service = new AuthServiceImpl();
        var verifier = mock(TurnstileService.class);
        var userService = mock(UserService.class);
        ReflectionTestUtils.setField(service, "turnstileService", verifier);
        ReflectionTestUtils.setField(service, "userService", userService);
        doThrow(new ServiceException(400, "人机验证失败")).when(verifier).verifyLogin("invalid");

        var request = new LoginRequestDto();
        request.setUserName("admin");
        request.setPassword("password");
        request.setTurnstileToken("invalid");

        assertThatThrownBy(() -> service.login(request)).isInstanceOf(ServiceException.class);
        verifyNoInteractions(userService);
    }
}
