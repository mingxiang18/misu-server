package com.misu.ops.controller;

import com.misu.ops.OpsProperties;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.ops.security.OpsOriginPolicy;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OpsControllerConsolePathTest {

    @Test
    void exchangeUsesTargetPathCookieAndSameHostEntry() {
        OpsProperties properties = new OpsProperties();
        properties.setProxySharedSecret("proxy-secret");
        properties.setNacosUrl("https://api.misu.chat/nacos/");
        properties.setHeadlampUrl("https://api.misu.chat/ops/headlamp/");
        CurrentAccountVerifier verifier = adminVerifier();
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsController controller = new OpsController(properties, null,
                new OpsOriginPolicy(properties), store, null, null);

        assertEquals("https://api.misu.chat/nacos/_ops/exchange", controller.exchangeUrl(ConsoleTarget.NACOS));
        assertEquals("https://api.misu.chat/ops/headlamp/_ops/exchange",
                controller.exchangeUrl(ConsoleTarget.HEADLAMP));

        for (ConsoleTarget target : ConsoleTarget.values()) {
            OpsSessionStore.Ticket ticket = store.issueTicket(
                    new LoginUser(7L, "admin", java.util.List.of("ADMIN")), target);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("Host", "api.misu.chat");
            request.addHeader("Origin", "https://server.misu.chat");
            request.addHeader("X-Ops-Target", target.id());
            request.addHeader("X-Ops-Proxy-Key", "proxy-secret");
            MockHttpServletResponse response = new MockHttpServletResponse();

            controller.exchangeConsoleSession(request, response, ticket.token());

            String cookie = response.getHeader("Set-Cookie");
            assertTrue(cookie.contains("Path=" + target.cookiePath()), cookie);
            assertTrue(cookie.contains("HttpOnly"), cookie);
            assertTrue(cookie.contains("Secure"), cookie);
            assertEquals("https://api.misu.chat/" + target.cookiePath().substring(1),
                    response.getHeader("Location"));
        }
    }

    @Test
    void browserCannotSelectExchangeTargetHeader() {
        OpsProperties properties = new OpsProperties();
        properties.setProxySharedSecret("proxy-secret");
        properties.setNacosUrl("https://api.misu.chat/nacos/");
        OpsSessionStore store = new OpsSessionStore(properties, adminVerifier());
        OpsOriginPolicy policy = new OpsOriginPolicy(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("Host", "api.misu.chat");
        request.addHeader("X-Ops-Target", "nacos");
        request.addHeader("X-Ops-Proxy-Key", "proxy-secret");
        org.junit.jupiter.api.Assertions.assertThrows(
                com.misu.common.exception.ServiceException.class,
                () -> policy.resolveConsoleTarget(request));
    }

    private CurrentAccountVerifier adminVerifier() {
        return new CurrentAccountVerifier() {
            @Override
            public LoginUser requireAdmin(LoginUser tokenUser) {
                return tokenUser;
            }

            @Override
            public LoginUser requireAdmin(Long userId, String userName) {
                return new LoginUser(userId, userName, java.util.List.of("ADMIN"));
            }
        };
    }
}
