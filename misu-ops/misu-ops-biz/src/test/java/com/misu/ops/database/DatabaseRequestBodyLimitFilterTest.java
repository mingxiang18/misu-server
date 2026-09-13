package com.misu.ops.database;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseRequestBodyLimitFilterTest {
    private final DatabaseRequestBodyLimitFilter filter = new DatabaseRequestBodyLimitFilter();

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
    void boundedChunkedBodyFailsWhileBeingRead() {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public long getContentLengthLong() {
                return -1L;
            }
        };
        request.setRequestURI("/ops/api/database/misu/tables");
        request.setMethod("POST");
        request.setContent(new byte[(int) DatabaseRequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThrows(IOException.class, () -> filter.doFilter(request, response, chain((servletRequest, ignoredResponse) -> {
            byte[] buffer = new byte[8192];
            while (servletRequest.getInputStream().read(buffer) >= 0) {
                // drain the bounded stream
            }
        })));
    }

    private MockHttpServletRequest request(String method, byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest();
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
