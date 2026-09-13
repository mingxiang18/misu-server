package com.misu.ops.database;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;

/** Keeps production database connections on the existing in-cluster MySQL service. */
final class DatabaseConnectionPolicy {
    static final String FIXED_JDBC_URL =
            "jdbc:mysql://mysql-inner.mysql.svc.cluster.local:3316/?useUnicode=true&characterEncoding=UTF-8"
                    + "&serverTimezone=Asia/Shanghai&useSSL=false&allowMultiQueries=false";

    private DatabaseConnectionPolicy() {
    }

    static void requireAllowed(String url, String[] activeProfiles) {
        if (FIXED_JDBC_URL.equals(url)) {
            return;
        }
        if (activeProfiles != null && Arrays.asList(activeProfiles).contains("test") && isLoopbackTestUrl(url)) {
            return;
        }
        throw new IllegalStateException("ops.database.url must use the fixed MySQL service");
    }

    static boolean isLoopbackTestUrl(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            return false;
        }
        try {
            URI uri = new URI(url.substring("jdbc:".length()));
            String host = uri.getHost();
            return ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host))
                    && uri.getPort() == 3316
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null
                    && uri.getPath() != null && uri.getPath().startsWith("/")
                    && (uri.getQuery() == null
                    || !uri.getQuery().toLowerCase(Locale.ROOT).contains("allowmultiqueries=true"));
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
