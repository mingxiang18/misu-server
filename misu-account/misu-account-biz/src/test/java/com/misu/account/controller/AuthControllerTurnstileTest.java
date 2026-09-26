package com.misu.account.controller;

import com.misu.account.domain.dto.auth.LoginRequestDto;
import com.misu.account.domain.dto.auth.LoginResponseDto;
import com.misu.account.service.AuthService;
import com.misu.account.service.TurnstileService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTurnstileTest {
    @Test
    void loginRequestBindsTurnstileTokenWithoutRealAuthentication() throws Exception {
        var controller = new AuthController();
        var authService = mock(AuthService.class);
        ReflectionTestUtils.setField(controller, "authService", authService);
        ReflectionTestUtils.setField(controller, "turnstileService", mock(TurnstileService.class));
        var response = new LoginResponseDto();
        response.setToken("mock-access-token");
        response.setRefreshToken("mock-refresh-token");
        when(authService.login(any(LoginRequestDto.class))).thenReturn(response);

        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"mock-user\",\"password\":\"mock-password\",\"turnstileToken\":\"mock-challenge\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("mock-access-token"));

        var request = ArgumentCaptor.forClass(LoginRequestDto.class);
        verify(authService).login(request.capture());
        assertThat(request.getValue().getTurnstileToken()).isEqualTo("mock-challenge");
    }
}
