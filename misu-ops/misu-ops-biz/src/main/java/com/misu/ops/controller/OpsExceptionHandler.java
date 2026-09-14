package com.misu.ops.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.common.exception.ServiceException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class OpsExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException exception, HttpServletResponse response) {
        int code = exception.getCode() == null ? 500 : exception.getCode();
        response.setStatus(code);
        return AjaxResult.error(code, exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public AjaxResult handleUnreadableBody(HttpMessageNotReadableException exception,
                                           HttpServletRequest request, HttpServletResponse response) {
        if (!isDatabaseRequest(request)) {
            if (!isAiSessionRequest(request)) {
                throw exception;
            }
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return AjaxResult.error(HttpServletResponse.SC_BAD_REQUEST, "AI_INVALID_REQUEST");
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return AjaxResult.error(HttpServletResponse.SC_BAD_REQUEST, "OPS_DB_INVALID_REQUEST");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public AjaxResult handleArgumentTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                  HttpServletRequest request, HttpServletResponse response) {
        if (!isDatabaseRequest(request)) {
            throw exception;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        return AjaxResult.error(HttpServletResponse.SC_BAD_REQUEST, "OPS_DB_INVALID_REQUEST");
    }

    private static boolean isDatabaseRequest(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && path != null && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return "/api/database".equals(path) || (path != null && path.startsWith("/api/database/"));
    }

    private static boolean isAiSessionRequest(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && path != null && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return "/api/ai/sessions".equals(path);
    }
}
