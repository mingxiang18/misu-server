package com.misu.ops.ai;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.session.OpsSessionStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Opens only fixed AI CLI profiles through ChannelExec; it never opens a shell. */
@Component
public class AiCliConnectionService {

    static final String WRAPPER = "/usr/local/bin/misu-ai-cli";
    private static final String SSH_USER = "root";

    static String commandFor(AiCliTool tool) {
        return switch (tool) {
            case CODEX -> WRAPPER + " codex";
            case CLAUDE -> WRAPPER + " claude";
        };
    }

    private final OpsProperties properties;
    private final OpsSessionStore sessions;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<String> opening = ConcurrentHashMap.newKeySet();

    public AiCliConnectionService(OpsProperties properties, OpsSessionStore sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    public void requireConfigured() {
        requireFile(properties.getKnownHostsPath(), "SSH known_hosts");
        requireFile(properties.getPrivateKeyPath(), "SSH 私钥");
    }

    public void open(OpsSessionStore.AiSession aiSession, WebSocketSession webSocket) {
        synchronized (cancelled) {
            cancelled.remove(aiSession.id());
            opening.add(aiSession.id());
        }
        Session ssh = null;
        ChannelExec channel = null;
        try {
            requireConfigured();
            JSch jsch = new JSch();
            jsch.setKnownHosts(properties.getKnownHostsPath());
            jsch.addIdentity(properties.getPrivateKeyPath());
            OpsProperties.Node worker = properties.getSsh().getNodes().get("worker");
            if (worker == null || worker.getHost() == null || worker.getHost().isBlank()) {
                throw new ServiceException(HttpStatus.ERROR, "工作节点未正确配置");
            }
            if (!SSH_USER.equals(properties.getSsh().getUser())) {
                throw new ServiceException(HttpStatus.ERROR, "AI CLI SSH 用户必须为 root");
            }
            ssh = jsch.getSession(SSH_USER, worker.getHost(),
                    worker.getPort() > 0 ? worker.getPort() : properties.getSsh().getPort());
            ssh.setConfig("StrictHostKeyChecking", "yes");
            ssh.connect(properties.getSsh().getConnectTimeoutMillis());

            channel = (ChannelExec) ssh.openChannel("exec");
            channel.setCommand(commandFor(aiSession.tool()));
            channel.setPty(true);
            channel.setPtyType("xterm-256color");
            channel.setPtySize(aiSession.cols(), aiSession.rows(), 0, 0);
            InputStream input = channel.getInputStream();
            // Request the extended-data stream before connect. PTY servers
            // commonly merge stderr into stdout, but this also preserves
            // wrapper diagnostics when they do not.
            InputStream error = channel.getErrStream();
            OutputStream output = channel.getOutputStream();
            channel.connect(properties.getSsh().getConnectTimeoutMillis());
            if (cancelled.contains(aiSession.id()) || !webSocket.isOpen()) {
                throw new IOException("AI CLI WebSocket closed during connect");
            }
            sessions.validateAiSession(aiSession);
            Connection connection = new Connection(aiSession, webSocket, ssh, channel, input, error, output);
            synchronized (cancelled) {
                if (cancelled.contains(aiSession.id()) || !webSocket.isOpen()) {
                    throw new IOException("AI CLI WebSocket closed during connect");
                }
                connections.put(aiSession.id(), connection);
            }
            connection.startReader();
        } catch (JSchException | IOException ex) {
            disconnect(channel, ssh);
            sessions.revokeAiSession(aiSession.id());
            throw new ServiceException(HttpStatus.ERROR, "AI CLI 连接失败");
        } catch (RuntimeException ex) {
            disconnect(channel, ssh);
            sessions.revokeAiSession(aiSession.id());
            throw ex;
        } finally {
            synchronized (cancelled) {
                opening.remove(aiSession.id());
                cancelled.remove(aiSession.id());
            }
        }
    }

    public void write(String sessionId, byte[] data) throws IOException {
        Connection connection = requireConnection(sessionId);
        sessions.touchAiSession(connection.aiSession);
        synchronized (connection.output) {
            connection.output.write(data);
            connection.output.flush();
        }
    }

    /** Validates the live AI CLI session and refreshes its idle timeout without sending CLI input. */
    public void ping(String sessionId) {
        Connection connection = requireConnection(sessionId);
        sessions.touchAiSession(connection.aiSession);
    }

    public void resize(String sessionId, int cols, int rows) {
        Connection connection = requireConnection(sessionId);
        sessions.touchAiSession(connection.aiSession);
        connection.channel.setPtySize(normalize(cols, 80), normalize(rows, 24), 0, 0);
    }

    public void close(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        Connection connection;
        synchronized (cancelled) {
            if (opening.contains(sessionId)) cancelled.add(sessionId);
            connection = connections.remove(sessionId);
            if (connection != null) {
                cancelled.add(sessionId);
                cancelled.remove(sessionId);
            }
        }
        if (connection != null) {
            connection.close();
            try {
                if (connection.webSocket.isOpen()) connection.webSocket.close();
            } catch (IOException ignored) {
            }
        }
        sessions.revokeAiSession(sessionId);
    }

    public void closeForUser(Long userId) {
        connections.values().stream()
                .filter(connection -> userId.equals(connection.aiSession.userId()))
                .map(connection -> connection.aiSession.id())
                .forEach(this::close);
    }

    @Scheduled(fixedDelay = 15000)
    public void revalidateConnections() {
        connections.values().forEach(connection -> {
            try {
                sessions.validateAiSession(connection.aiSession);
            } catch (RuntimeException ex) {
                close(connection.aiSession.id());
                closeWebSocket(connection.webSocket);
            }
        });
    }

    @PreDestroy
    public void closeAll() {
        synchronized (cancelled) {
            cancelled.addAll(opening);
        }
        connections.keySet().forEach(this::close);
    }

    private Connection requireConnection(String sessionId) {
        Connection connection = connections.get(sessionId);
        if (connection == null) throw new ServiceException(HttpStatus.GONE, "AI CLI 会话已关闭");
        return connection;
    }

    private void requireFile(String value, String label) {
        if (value == null || value.isBlank() || !Files.isRegularFile(Path.of(value))) {
            throw new ServiceException(HttpStatus.ERROR, label + "未正确配置");
        }
    }

    private int normalize(int value, int fallback) {
        return Math.max(1, Math.min(value <= 0 ? fallback : value, 400));
    }

    private void closeWebSocket(WebSocketSession webSocket) {
        try {
            if (webSocket.isOpen()) webSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void disconnect(ChannelExec channel, Session ssh) {
        if (channel != null) channel.disconnect();
        if (ssh != null) ssh.disconnect();
    }

    private final class Connection {
        private final OpsSessionStore.AiSession aiSession;
        private final WebSocketSession webSocket;
        private final Session ssh;
        private final ChannelExec channel;
        private final InputStream input;
        private final InputStream error;
        private final OutputStream output;
        private final Object sendLock = new Object();
        private final AtomicInteger readers = new AtomicInteger(2);

        private Connection(OpsSessionStore.AiSession aiSession, WebSocketSession webSocket,
                            Session ssh, ChannelExec channel, InputStream input, InputStream error,
                            OutputStream output) {
            this.aiSession = aiSession;
            this.webSocket = webSocket;
            this.ssh = ssh;
            this.channel = channel;
            this.input = input;
            this.error = error;
            this.output = output;
        }

        private void startReader() {
            startReader(input, "ops-ai-cli-reader");
            startReader(error, "ops-ai-cli-stderr-reader");
        }

        private void startReader(InputStream source, String threadName) {
            Thread reader = new Thread(() -> {
                char[] buffer = new char[8192];
                try (Reader textReader = new InputStreamReader(source,
                        StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT))) {
                    int length;
                    while ((length = textReader.read(buffer)) >= 0) {
                        if (length == 0) continue;
                        sessions.validateAiSession(aiSession);
                        if (webSocket.isOpen()) {
                            synchronized (sendLock) {
                                if (webSocket.isOpen()) {
                                    webSocket.sendMessage(new TextMessage(new String(buffer, 0, length)));
                                }
                            }
                        } else {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                    closeWebSocket(webSocket);
                } finally {
                    if (readers.decrementAndGet() == 0) {
                        AiCliConnectionService.this.close(aiSession.id());
                    }
                }
            }, threadName);
            reader.setDaemon(true);
            reader.start();
        }

        private void close() {
            try { input.close(); } catch (IOException ignored) { }
            try { error.close(); } catch (IOException ignored) { }
            channel.disconnect();
            ssh.disconnect();
        }
    }
}
