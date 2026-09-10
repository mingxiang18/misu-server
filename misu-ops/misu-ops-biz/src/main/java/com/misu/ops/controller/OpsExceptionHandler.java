package com.misu.ops.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.common.exception.ServiceException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.servlet.http.HttpServletResponse;

@RestControllerAdvice
public class OpsExceptionHandler {

    @ExceptionHandler(ServiceException.class)
    public AjaxResult handleServiceException(ServiceException exception, HttpServletResponse response) {
        int code = exception.getCode() == null ? 500 : exception.getCode();
        response.setStatus(code);
        return AjaxResult.error(code, exception.getMessage());
    }
}
