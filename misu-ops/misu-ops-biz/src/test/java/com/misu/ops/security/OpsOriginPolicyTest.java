package com.misu.ops.security;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpsOriginPolicyTest {

    @Test
    void acceptsConfiguredOriginAndRejectsLookalikeOrigin() {
        OpsProperties properties = new OpsProperties();
        properties.setAllowedOrigins(java.util.List.of("https://server.misu.chat"));
        OpsOriginPolicy policy = new OpsOriginPolicy(properties);

        MockHttpServletRequest trusted = requestWithOrigin("https://server.misu.chat");
        assertDoesNotThrow(() -> policy.requireAllowedMainOrigin(trusted));

        MockHttpServletRequest lookalike = requestWithOrigin("https://server.misu.chat.attacker.example");
        ServiceException exception = assertThrows(ServiceException.class,
                () -> policy.requireAllowedMainOrigin(lookalike));
        assertEquals(HttpStatus.FORBIDDEN, exception.getCode());
    }

    private MockHttpServletRequest requestWithOrigin(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", origin);
        return request;
    }
}
