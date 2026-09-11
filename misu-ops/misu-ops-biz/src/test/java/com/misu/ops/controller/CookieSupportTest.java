package com.misu.ops.controller;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CookieSupportTest {

    @Test
    void removesRepeatedDefaultAndConfiguredMainCookies() {
        String header = "User-Token=one; upstream=ok; USER-TOKEN=two; X-Main=secret; "
                + "User-Refresh-Token=refresh; upstream2=ok2";

        assertEquals("upstream=ok; upstream2=ok2",
                CookieSupport.filterUpstreamCookies(header, List.of("X-Main")));
    }

    @Test
    void rejectsConflictingDuplicateSessionCookie() {
        assertNull(CookieSupport.read("MISU_OPS_SESSION=host; MISU_OPS_SESSION=domain",
                "MISU_OPS_SESSION"));
        assertEquals("same", CookieSupport.read("MISU_OPS_SESSION=same; MISU_OPS_SESSION=same",
                "MISU_OPS_SESSION"));
    }

    @Test
    void readsRawProxyCookieHeaderWhenServletCookiesAreUnavailable() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Cookie", "MISU_OPS_SESSION=session-from-proxy");

        assertEquals("session-from-proxy", CookieSupport.read(request, "MISU_OPS_SESSION"));
    }

    @Test
    void stripsAuthorizationWhenItMatchesAnyConfiguredMainToken() {
        assertNull(CookieSupport.filterUpstreamAuthorization("Bearer custom-token",
                "X-Main=custom-token", List.of("X-Main")));
        assertEquals("Bearer upstream-token", CookieSupport.filterUpstreamAuthorization(
                "Bearer upstream-token", "X-Main=custom-token", List.of("X-Main")));
    }

    @Test
    void stripsAuthorizationWhenOneOfConflictingDuplicateTokensMatches() {
        assertNull(CookieSupport.filterUpstreamAuthorization("Bearer custom-token",
                "X-Main=other-token; X-Main=custom-token", List.of("X-Main")));
    }
}
