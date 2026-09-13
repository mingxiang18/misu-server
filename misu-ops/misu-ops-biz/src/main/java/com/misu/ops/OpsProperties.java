package com.misu.ops;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "ops")
public class OpsProperties {

    private String accountBaseUrl = "http://localhost:30261/account";
    private String nacosUrl = "";
    private String headlampUrl = "";
    private String nacosUpstreamUrl = "";
    /** Server-side Nacos console account; never expose these values to a browser. */
    private String nacosUsername = "";
    private String nacosPassword = "";
    /** Optional override for the Nacos auth endpoint, useful for isolated tests. */
    private String nacosAuthUrl = "";
    private String headlampUpstreamUrl = "";
    /** Public fixed qBittorrent entry and the server-only fixed upstream. */
    private String qbittorrentUrl = "https://server.misu.chat/ops/qbittorrent/";
    private String qbittorrentUpstreamUrl = "";
    private String qbittorrentUsername = "";
    private String qbittorrentPassword = "";
    private String publicBaseUrl = "";
    private String proxySharedSecret = "";
    private String knownHostsPath = "";
    private String privateKeyPath = "";
    private String cookieName = "MISU_OPS_SESSION";
    private boolean cookieSecure = true;
    private long ticketTtlSeconds = 30;
    private long sessionIdleSeconds = 900;
    private long sessionMaxSeconds = 7200;
    private long roleCheckSeconds = 30;
    private long sshHandshakeTtlSeconds = 30;
    private int maxSshSessions = 4;
    private int maxConsoleTickets = 128;
    private int maxConsoleTicketsPerUser = 8;
    private int maxConsoleSessions = 32;
    private int maxConsoleSessionsPerUser = 4;
    private int maxConsoleWebSockets = 8;
    private long consoleWebSocketConnectTimeoutMillis = 5000;
    private long accountConnectTimeoutMillis = 3000;
    private long accountReadTimeoutMillis = 5000;
    private DatabaseProperties database = new DatabaseProperties();
    /** Cookie names owned by the main site and never forwarded to a console. */
    private List<String> mainCookieNames = new ArrayList<>(List.of(
            "User-Token", "User-Refresh-Token", "User-Info"));
    private List<String> allowedOrigins = new ArrayList<>(List.of("https://server.misu.chat"));
    private SshProperties ssh = new SshProperties();

    @Getter
    @Setter
    public static class SshProperties {
        private int connectTimeoutMillis = 10000;
        private String user = "root";
        private int port = 22;
        private Map<String, Node> nodes = defaultNodes();

        private static Map<String, Node> defaultNodes() {
            Map<String, Node> defaults = new LinkedHashMap<>();
            defaults.put("master", new Node("master", "主节点", "10.8.0.1", 22));
            defaults.put("worker", new Node("worker", "工作节点", "10.8.0.26", 22));
            return defaults;
        }
    }

    @Getter
    @Setter
    public static class Node {
        private String id;
        private String name;
        private String host;
        private int port;

        public Node() {
        }

        public Node(String id, String name, String host, int port) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.port = port;
        }
    }

    @Getter
    @Setter
    public static class DatabaseProperties {
        private boolean enabled;
        private String url = "";
        private String username = "";
        private String password = "";
        private List<String> allowedSchemas = new ArrayList<>();
        private int maximumPoolSize = 4;
        private long connectionTimeoutMillis = 2000;
        private long idleTimeoutMillis = 60000;
        private int queryTimeoutSeconds = 5;
        private int maxConcurrent = 4;
        private int maxPageSize = 100;
    }
}
