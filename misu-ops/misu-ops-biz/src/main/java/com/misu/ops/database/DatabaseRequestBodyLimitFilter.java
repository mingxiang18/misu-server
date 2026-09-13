package com.misu.ops.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.common.domain.AjaxResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Bounds database write bodies before Jackson can materialize arbitrary maps. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class DatabaseRequestBodyLimitFilter extends OncePerRequestFilter {
    public static final long MAX_BODY_BYTES = 64 * 1024;
    private final ObjectMapper objectMapper;

    public DatabaseRequestBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri != null && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        boolean databasePath = "/api/database".equals(uri) || (uri != null && uri.startsWith("/api/database/"));
        return !databasePath || "GET".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            writeError(response);
            return;
        }
        try {
            filterChain.doFilter(new BoundedRequest(request), response);
        } catch (BodyTooLargeException ex) {
            writeError(response);
        } catch (ServletException ex) {
            if (hasCause(ex, BodyTooLargeException.class)) writeError(response);
            else throw ex;
        }
    }

    private void writeError(HttpServletResponse response) throws IOException {
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), AjaxResult.error(400, "OPS_DB_INVALID_REQUEST"));
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) return true;
        }
        return false;
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private ServletInputStream boundedInputStream;

        private BoundedRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (boundedInputStream == null) {
                boundedInputStream = new BoundedInputStream(super.getInputStream());
            }
            return boundedInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }

    private static final class BoundedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private long count;

        private BoundedInputStream(ServletInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) increment(1);
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = delegate.read(bytes, offset, length);
            if (read > 0) increment(read);
            return read;
        }

        private void increment(int amount) throws IOException {
            count += amount;
            if (count > MAX_BODY_BYTES) {
                throw new BodyTooLargeException();
            }
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }

    private static final class BodyTooLargeException extends IOException {
        private BodyTooLargeException() {
            super("request body too large");
        }
    }
}
