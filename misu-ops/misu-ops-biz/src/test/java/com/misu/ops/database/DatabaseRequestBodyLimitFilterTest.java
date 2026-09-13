package com.misu.ops.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DatabaseRequestBodyLimitFilterTest {
    private final DatabaseRequestBodyLimitFilter filter = new DatabaseRequestBodyLimitFilter(new ObjectMapper());

    @Test
    void rejectsKnownOversizedBodyBeforeController() throws Exception {
        MockHttpServletRequest request = request("POST", new byte[(int) DatabaseRequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] called = {false};
        filter.doFilter(request, response, chain((ignoredRequest, ignoredResponse) -> called[0] = true));
        assertEquals(400, response.getStatus());
        assertEquals(false, called[0]);
    }

    @Test
    void boundedChunkedBodyFailsWhileBeingRead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setContextPath("/ops");
        request.setRequestURI("/ops/api/database/misu/tables");
        request.setMethod("POST");
        request.setContent(new byte[(int) DatabaseRequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain((servletRequest, ignoredResponse) -> {
            byte[] buffer = new byte[8192];
            while (servletRequest.getInputStream().read(buffer) >= 0) {
                // drain the bounded stream
            }
        }));
        assertEquals(400, response.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(response.getContentAsString().contains("OPS_DB_INVALID_REQUEST"));
    }

    @Test
    void onlyDatabasePathWithContextPathIsBounded() throws Exception {
        MockHttpServletRequest request = request("POST", new byte[(int) DatabaseRequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
        request.setRequestURI("/ops/api/database-extra");
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] called = {false};
        filter.doFilter(request, response, chain((ignoredRequest, ignoredResponse) -> called[0] = true));
        assertEquals(true, called[0]);
        assertEquals(200, response.getStatus());
    }

    private MockHttpServletRequest request(String method, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/ops");
        request.setRequestURI("/ops/api/database/misu/tables");
        request.setMethod(method);
        request.setContent(body);
        return request;
    }

    private FilterChain chain(ThrowingChain chain) {
        return (request, response) -> chain.run(request, response);
    }

    @FunctionalInterface
    private interface ThrowingChain {
        void run(ServletRequest request, ServletResponse response) throws IOException;
    }
}
