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
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void exchangeRejectsUntrustedHeadersAndWrongHostBeforeTicketConsumption() {
        OpsProperties properties = new OpsProperties();
        properties.setProxySharedSecret("proxy-secret");
        properties.setNacosUrl("https://api.misu.chat/nacos/");
        properties.setHeadlampUrl("https://api.misu.chat/ops/headlamp/");
        OpsSessionStore store = new OpsSessionStore(properties, adminVerifier());
        OpsController controller = new OpsController(properties, null,
                new OpsOriginPolicy(properties), store, null, null);

        OpsSessionStore.Ticket validTicket = store.issueTicket(
                new LoginUser(7L, "admin", java.util.List.of("ADMIN")), ConsoleTarget.NACOS);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("Host", "api.misu.chat");
        request.addHeader("X-Ops-Target", "nacos");
        request.addHeader("X-Ops-Proxy-Key", "proxy-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(com.misu.common.exception.ServiceException.class,
                () -> controller.exchangeConsoleSession(request, response, validTicket.token()));

        OpsSessionStore.Ticket wrongHostTicket = store.issueTicket(
                new LoginUser(7L, "admin", java.util.List.of("ADMIN")), ConsoleTarget.NACOS);
        MockHttpServletRequest wrongHost = trustedRequest("nacos", "wrong.misu.chat");
        assertThrows(com.misu.common.exception.ServiceException.class,
                () -> controller.exchangeConsoleSession(wrongHost, new MockHttpServletResponse(), wrongHostTicket.token()));
        // Host validation happens before the store consumes the ticket.
        controller.exchangeConsoleSession(trustedRequest("nacos", "api.misu.chat"),
                new MockHttpServletResponse(), wrongHostTicket.token());

        OpsSessionStore.Ticket replayTicket = store.issueTicket(
                new LoginUser(7L, "admin", java.util.List.of("ADMIN")), ConsoleTarget.NACOS);
        controller.exchangeConsoleSession(trustedRequest("nacos", "api.misu.chat"),
                new MockHttpServletResponse(), replayTicket.token());
        assertThrows(com.misu.common.exception.ServiceException.class,
                () -> controller.exchangeConsoleSession(trustedRequest("nacos", "api.misu.chat"),
                        new MockHttpServletResponse(), replayTicket.token()));
    }

    private MockHttpServletRequest trustedRequest(String target, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", host);
        request.addHeader("X-Ops-Target", target);
        request.addHeader("X-Ops-Proxy-Key", "proxy-secret");
        return request;
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
