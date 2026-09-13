package com.misu.ops.database;

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

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.contains("/api/database") || "GET".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_BYTES) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "OPS_DB_INVALID_REQUEST");
            return;
        }
        filterChain.doFilter(new BoundedRequest(request), response);
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
                throw new IOException("request body too large");
            }
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
