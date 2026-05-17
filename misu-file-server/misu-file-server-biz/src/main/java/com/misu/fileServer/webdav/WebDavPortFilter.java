package com.misu.fileServer.webdav;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 端口隔离：WebDAV 仅在独立端口提供，业务 API 仅在主端口提供。
 *
 * <ul>
 *   <li>WebDAV 端口上的非 {@code /dav} 请求 → 404</li>
 *   <li>主端口上的 {@code /dav} 请求 → 404（WebDAV 不经网关、不与业务 API 同端口暴露）</li>
 * </ul>
 */
public class WebDavPortFilter implements Filter {

    private final int webdavPort;

    public WebDavPortFilter(int webdavPort) {
        this.webdavPort = webdavPort;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String davPrefix = httpRequest.getContextPath() + "/dav";
        String uri = httpRequest.getRequestURI();
        boolean davRequest = uri.equals(davPrefix) || uri.startsWith(davPrefix + "/");
        boolean onWebdavPort = httpRequest.getLocalPort() == webdavPort;

        if (onWebdavPort != davRequest) {
            // setStatus 而非 sendError：避免触发 ERROR 派发被 Spring Security 改写成 403
            httpResponse.setStatus(HttpServletResponse.SC_NOT_FOUND);
            httpResponse.setContentLength(0);
            return;
        }
        chain.doFilter(request, response);
    }
}
