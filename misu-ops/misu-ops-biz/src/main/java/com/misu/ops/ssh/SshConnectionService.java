package com.misu.ops.ssh;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.session.OpsSessionStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.annotation.PreDestroy;

@Slf4j
@Component
public class SshConnectionService {

    private final OpsProperties properties;
    private final OpsSessionStore sessions;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    /** Session ids closed while the SSH handshake is still in progress. */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    private final Set<String> opening = ConcurrentHashMap.newKeySet();

    public SshConnectionService(OpsProperties properties, OpsSessionStore sessions) {
        this.properties = properties;
        this.sessions = sessions;
    }

    public void open(OpsSessionStore.SshSession sshSession, WebSocketSession webSocket) {
        synchronized (cancelled) {
            cancelled.remove(sshSession.id());
            opening.add(sshSession.id());
        }
        OpsProperties.Node node = properties.getSsh().getNodes().get(sshSession.nodeId());
        Session ssh = null;
        ChannelShell channel = null;
        try {
            if (node == null || node.getHost() == null || node.getHost().isBlank()) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "不支持的节点");
            }
            requireFile(properties.getKnownHostsPath(), "known_hosts");
            requireFile(properties.getPrivateKeyPath(), "SSH 私钥");
            JSch jsch = new JSch();
            jsch.setKnownHosts(properties.getKnownHostsPath());
            jsch.addIdentity(properties.getPrivateKeyPath());
            ssh = jsch.getSession(properties.getSsh().getUser(), node.getHost(),
                    node.getPort() > 0 ? node.getPort() : properties.getSsh().getPort());
            ssh.setConfig("StrictHostKeyChecking", "yes");
            ssh.connect(properties.getSsh().getConnectTimeoutMillis());

            channel = (ChannelShell) ssh.openChannel("shell");
            channel.setPty(true);
            channel.setPtyType("xterm-256color");
            channel.setPtySize(sshSession.cols(), sshSession.rows(), 0, 0);
            // JSch requires streams to be obtained before connect().
            InputStream input = channel.getInputStream();
            OutputStream output = channel.getOutputStream();
            channel.connect(properties.getSsh().getConnectTimeoutMillis());
            if (cancelled.contains(sshSession.id()) || !webSocket.isOpen()) {
                throw new IOException("SSH WebSocket closed during connect");
            }
            sessions.validateSshSession(sshSession);
            Connection connection = new Connection(sshSession, webSocket, ssh, channel, input, output);
            synchronized (cancelled) {
                if (cancelled.contains(sshSession.id()) || !webSocket.isOpen()) {
                    throw new IOException("SSH WebSocket closed during connect");
                }
                connections.put(sshSession.id(), connection);
            }
            log.info("SSH 会话开始: userId={} node={}", sshSession.userId(), node.getId());
            connection.startReader();
        } catch (JSchException | IOException ex) {
            disconnect(channel, ssh);
            sessions.revokeSshSession(sshSession.id());
            log.warn("SSH 连接失败: userId={} node={}", sshSession.userId(), node.getId());
            throw new ServiceException(HttpStatus.ERROR, "SSH 节点连接失败");
        } catch (RuntimeException ex) {
            disconnect(channel, ssh);
            sessions.revokeSshSession(sshSession.id());
            throw ex;
        } finally {
            synchronized (cancelled) {
                opening.remove(sshSession.id());
                cancelled.remove(sshSession.id());
            }
        }
    }

    public void write(String sessionId, byte[] data) throws IOException {
        Connection connection = requireConnection(sessionId);
        sessions.touchSshSession(connection.sshSession);
        synchronized (connection.output) {
            connection.output.write(data);
            connection.output.flush();
        }
    }

    /** Validates the live SSH session and refreshes its idle timeout without writing to the node. */
    public void ping(String sessionId) {
        Connection connection = requireConnection(sessionId);
        sessions.touchSshSession(connection.sshSession);
    }

    public void resize(String sessionId, int cols, int rows) {
        Connection connection = requireConnection(sessionId);
        sessions.touchSshSession(connection.sshSession);
        connection.channel.setPtySize(normalize(cols, 80), normalize(rows, 24), 0, 0);
    }

    public void close(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Connection connection;
        synchronized (cancelled) {
            if (opening.contains(sessionId)) {
                cancelled.add(sessionId);
            }
            connection = connections.remove(sessionId);
            if (connection != null) {
                cancelled.add(sessionId);
                cancelled.remove(sessionId);
            }
        }
        if (connection != null) {
            connection.close();
            try {
                if (connection.webSocket.isOpen()) {
                    connection.webSocket.close();
                }
            } catch (IOException ignored) {
            }
            log.info("SSH 会话结束: userId={} node={} result=closed",
                    connection.sshSession.userId(), connection.sshSession.nodeId());
        }
        sessions.revokeSshSession(sessionId);
    }

    int openingCount() {
        return opening.size();
    }

    public void closeForUser(Long userId) {
        connections.values().stream()
                .filter(connection -> userId.equals(connection.sshSession.userId()))
                .map(connection -> connection.sshSession.id())
                .forEach(this::close);
    }

    @PreDestroy
    public void closeAll() {
        synchronized (cancelled) {
            cancelled.addAll(opening);
        }
        connections.keySet().forEach(this::close);
    }

    @Scheduled(fixedDelay = 15000)
    public void revalidateConnections() {
        connections.values().forEach(connection -> {
            try {
                sessions.validateSshSession(connection.sshSession);
            } catch (RuntimeException ex) {
                close(connection.sshSession.id());
                closeWebSocket(connection.webSocket);
            }
        });
    }

    private Connection requireConnection(String sessionId) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            throw new ServiceException(HttpStatus.GONE, "SSH 会话已关闭");
        }
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
            if (webSocket.isOpen()) {
                webSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void disconnect(ChannelShell channel, Session ssh) {
        if (channel != null) {
            channel.disconnect();
        }
        if (ssh != null) {
            ssh.disconnect();
        }
    }

    private final class Connection {
        private final OpsSessionStore.SshSession sshSession;
        private final WebSocketSession webSocket;
        private final Session ssh;
        private final ChannelShell channel;
        private final InputStream input;
        private final OutputStream output;

        private Connection(OpsSessionStore.SshSession sshSession, WebSocketSession webSocket,
                            Session ssh, ChannelShell channel, InputStream input, OutputStream output) {
            this.sshSession = sshSession;
            this.webSocket = webSocket;
            this.ssh = ssh;
            this.channel = channel;
            this.input = input;
            this.output = output;
        }

        private void startReader() {
            Thread reader = new Thread(() -> {
                char[] buffer = new char[8192];
                try {
                    Reader textReader = new InputStreamReader(input,
                            StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT));
                    Utf8Chunker chunker = new Utf8Chunker();
                    int length;
                    while ((length = textReader.read(buffer)) >= 0) {
                        if (length == 0) {
                            continue;
                        }
                        sessions.validateSshSession(sshSession);
                        String text = chunker.accept(buffer, length);
                        if (webSocket.isOpen() && !text.isEmpty()) {
                            // Terminal output is UTF-8 in the supported node locale.
                            webSocket.sendMessage(new TextMessage(text));
                        } else {
                            if (!webSocket.isOpen()) {
                                break;
                            }
                        }
                    }
                    String text = chunker.finish();
                    if (!text.isEmpty() && webSocket.isOpen()) {
                        webSocket.sendMessage(new TextMessage(text));
                    }
                } catch (Exception ex) {
                    if (webSocket.isOpen()) {
                        closeWebSocket(webSocket);
                    }
                } finally {
                    SshConnectionService.this.close(sshSession.id());
                }
            }, "ops-ssh-reader");
            reader.setDaemon(true);
            reader.start();
        }

        private void close() {
            try {
                input.close();
            } catch (IOException ignored) {
            }
            channel.disconnect();
            ssh.disconnect();
        }
    }

    /** Keeps a UTF-16 surrogate pair together when a Reader fills at its boundary. */
    static final class Utf8Chunker {
        private char pendingHighSurrogate;

        String accept(char[] chars, int length) {
            if (length <= 0) {
                return "";
            }
            String text = new String(chars, 0, length);
            if (pendingHighSurrogate != 0) {
                text = pendingHighSurrogate + text;
                pendingHighSurrogate = 0;
            }
            if (Character.isHighSurrogate(text.charAt(text.length() - 1))) {
                pendingHighSurrogate = text.charAt(text.length() - 1);
                return text.substring(0, text.length() - 1);
            }
            return text;
        }

        String finish() {
            if (pendingHighSurrogate == 0) {
                return "";
            }
            String result = String.valueOf(pendingHighSurrogate);
            pendingHighSurrogate = 0;
            return result;
        }
    }
}
