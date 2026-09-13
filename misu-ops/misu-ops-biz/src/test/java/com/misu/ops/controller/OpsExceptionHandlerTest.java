package com.misu.ops.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpsExceptionHandlerTest {
    private final OpsExceptionHandler handler = new OpsExceptionHandler();

    @Test
    void databaseMalformedBodyUsesStableAjaxError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContextPath("/ops");
        request.setRequestURI("/ops/api/database/misu/tables");
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = handler.handleUnreadableBody(new HttpMessageNotReadableException("bad"), request, response);

        assertEquals(400, response.getStatus());
        assertEquals(400, result.get("code"));
        assertEquals("OPS_DB_INVALID_REQUEST", result.get("msg"));
    }

    @Test
    void nonDatabaseMalformedBodyKeepsExistingExceptionFlow() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/ops/api/console");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpMessageNotReadableException exception = new HttpMessageNotReadableException("bad");

        assertThrows(HttpMessageNotReadableException.class,
                () -> handler.handleUnreadableBody(exception, request, response));
    }
}
