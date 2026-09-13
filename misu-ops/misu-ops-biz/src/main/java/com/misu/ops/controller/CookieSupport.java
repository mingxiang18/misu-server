package com.misu.ops.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CookieSupport {
    private static final Set<String> DEFAULT_MAIN_COOKIE_NAMES = Set.of(
            "user-token", "user-refresh-token", "user-info", "misu_ops_session");

    private CookieSupport() {
    }

    public static String read(HttpServletRequest request, String name) {
        // A proxy forwards the raw Cookie header. Prefer it over the servlet
        // convenience parser so duplicate host/domain cookies keep the same
        // fail-closed semantics in every container.
        String header = header(request);
        if (header != null) {
            return read(header, name);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String value = null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                if (value != null && !value.equals(cookie.getValue())) {
                    // Host-only and domain cookies may have the same name. Do
                    // not select a value based on the order supplied by the client.
                    return null;
                }
                value = cookie.getValue();
            }
        }
        return value;
    }

    public static String read(String header, String name) {
        if (header == null || name == null) {
            return null;
        }
        String value = null;
        for (String part : header.split(";")) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            if (name.equals(part.substring(0, separator).trim())) {
                String candidate = part.substring(separator + 1).trim();
                if (value != null && !value.equals(candidate)) {
                    return null;
                }
                value = candidate;
            }
        }
        return value;
    }

    /** Returns the raw Cookie header, with servlet parsed cookies as a test/container fallback. */
    public static String header(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders("Cookie");
        if (headers != null) {
            List<String> values = java.util.Collections.list(headers);
            if (!values.isEmpty()) {
                return String.join("; ", values);
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null || cookies.length == 0) {
            return null;
        }
        List<String> values = new ArrayList<>(cookies.length);
        for (Cookie cookie : cookies) {
            values.add(cookie.getName() + "=" + cookie.getValue());
        }
        return String.join("; ", values);
    }

    /**
     * Keep the upstream console's own cookies while removing every occurrence
     * of credentials belonging to the main site or this proxy.
     */
    public static String filterUpstreamCookies(String header) {
        return filterUpstreamCookies(header, DEFAULT_MAIN_COOKIE_NAMES);
    }

    public static String filterUpstreamCookies(String header, Iterable<String> mainCookieNames) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Set<String> blocked = new HashSet<>(DEFAULT_MAIN_COOKIE_NAMES);
        if (mainCookieNames != null) {
            for (String name : mainCookieNames) {
                if (name != null && !name.isBlank()) {
                    blocked.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        List<String> kept = new ArrayList<>();
        for (String part : header.split(";")) {
            String cookie = part.trim();
            if (cookie.isEmpty()) {
                continue;
            }
            int separator = cookie.indexOf('=');
            String name = separator <= 0 ? cookie : cookie.substring(0, separator).trim();
            if (!blocked.contains(name.toLowerCase(Locale.ROOT))) {
                kept.add(cookie);
            }
        }
        return kept.isEmpty() ? null : String.join("; ", kept);
    }

    /**
     * Browser Bearer credentials are never safe to forward to a console. The
     * server-side Nacos auth path does not use this helper, so non-Bearer
     * credentials remain available for explicitly trusted future callers.
     */
    public static String filterUpstreamAuthorization(String authorization, String cookieHeader) {
        return filterUpstreamAuthorization(authorization, cookieHeader, DEFAULT_MAIN_COOKIE_NAMES);
    }

    public static String filterUpstreamAuthorization(String authorization, String cookieHeader,
                                                     Iterable<String> mainCookieNames) {
        if (authorization == null || authorization.isBlank()
                || authorization.indexOf('\r') >= 0 || authorization.indexOf('\n') >= 0) {
            return null;
        }
        if (authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return authorization.trim();
    }
}
