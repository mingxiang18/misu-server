package com.misu.ops.controller;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.security.OpsOriginPolicy;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProxyAuthControllerTest {

    @Test
    void proxyAuthRequiresLoopbackAndSharedSecret() {
        OpsProperties properties = new OpsProperties();
        properties.setProxySharedSecret("test-secret");
        properties.setNacosUrl("https://ops-nacos.server.misu.chat/nacos/");
        CurrentAccountVerifier verifier = new CurrentAccountVerifier() {
            @Override
            public LoginUser requireAdmin(LoginUser tokenUser) {
                return tokenUser;
            }

            @Override
            public LoginUser requireAdmin(Long userId, String userName) {
                return new LoginUser(userId, userName, java.util.List.of("ADMIN"));
            }
        };
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.Ticket ticket = store.issueTicket(
                new LoginUser(1L, "admin", java.util.List.of("ADMIN")), ConsoleTarget.NACOS);
        OpsSessionStore.ConsoleSession session = store.createConsoleSession(ticket);
        ProxyAuthController controller = new ProxyAuthController(properties, store,
                new OpsOriginPolicy(properties));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Ops-Proxy-Key", "test-secret");
        request.addHeader("X-Ops-Target", "nacos");
        request.addHeader("Authorization", "Bearer main");
        request.setCookies(new jakarta.servlet.http.Cookie(properties.getCookieName(), session.id()));
        request.addHeader("Cookie", "User-Token=main; NACOS_AUTH_TOKEN=upstream; User-Info=bad; custom=ok; MISU_OPS_SESSION=ops");
        var response = controller.authorizeProxy(request);
        assertEquals(204, response.getStatusCode().value());
        assertEquals("NACOS_AUTH_TOKEN=upstream; custom=ok",
                response.getHeaders().getFirst("X-Ops-Upstream-Cookie"));
        assertEquals(null, response.getHeaders().getFirst("X-Ops-Upstream-Authorization"));

        MockHttpServletRequest wrongSecret = new MockHttpServletRequest();
        wrongSecret.setRemoteAddr("127.0.0.1");
        wrongSecret.addHeader("X-Ops-Proxy-Key", "wrong");
        wrongSecret.addHeader("X-Ops-Target", "nacos");
        wrongSecret.setCookies(new jakarta.servlet.http.Cookie(properties.getCookieName(), session.id()));
        ServiceException exception = assertThrows(ServiceException.class,
                () -> controller.authorizeProxy(wrongSecret));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getCode());
    }
}
