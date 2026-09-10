package com.misu.ops.session;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.security.dto.LoginUser;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class OpsSessionStore {

    private final OpsProperties properties;
    private final CurrentAccountVerifier accountVerifier;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Object lifecycleLock = new Object();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, ConsoleSession> consoleSessions = new ConcurrentHashMap<>();
    private final Map<String, SshSession> sshSessions = new ConcurrentHashMap<>();

    public OpsSessionStore(OpsProperties properties, CurrentAccountVerifier accountVerifier) {
        this.properties = properties;
        this.accountVerifier = accountVerifier;
    }

    public Ticket issueTicket(LoginUser currentUser, ConsoleTarget target) {
        String token = randomToken();
        Ticket ticket = new Ticket(token, target, currentUser.getUserId(), currentUser.getUserName(),
                Instant.now().plusSeconds(properties.getTicketTtlSeconds()));
        tickets.put(token, ticket);
        return ticket;
    }

    public Ticket consumeTicket(String token) {
        synchronized (lifecycleLock) {
            return consumeTicketLocked(token);
        }
    }

    private Ticket consumeTicketLocked(String token) {
        if (token == null || token.isBlank()) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维票据无效或已过期");
        }
        Ticket ticket = tickets.remove(token);
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维票据无效或已过期");
        }
        accountVerifier.requireAdmin(ticket.userId(), ticket.userName());
        return ticket;
    }

    public ConsoleSession createConsoleSession(Ticket ticket) {
        synchronized (lifecycleLock) {
            return createConsoleSessionLocked(ticket);
        }
    }

    /** Atomically consumes a ticket and creates its session after host validation. */
    public ConsoleSession exchangeConsoleSession(String token, ConsoleTarget expectedTarget) {
        synchronized (lifecycleLock) {
            Ticket ticket = consumeTicketLocked(token);
            if (ticket.target() != expectedTarget) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "运维票据目标与当前控制台不一致");
            }
            return createConsoleSessionLocked(ticket);
        }
    }

    private ConsoleSession createConsoleSessionLocked(Ticket ticket) {
        ConsoleSession session = new ConsoleSession(randomToken(), ticket.target(), ticket.userId(), ticket.userName());
        consoleSessions.put(session.id(), session);
        return session;
    }

    public ConsoleSession requireConsoleSession(String sessionId, String expectedTarget) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维会话无效或已过期");
        }
        ConsoleSession session = consoleSessions.get(sessionId);
        if (session == null || !session.target().id().equals(expectedTarget)) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维会话无效或已过期");
        }
        validateAndTouch(session, consoleSessions);
        return session;
    }

    public void validateConsoleSession(String sessionId, String expectedTarget) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维会话无效或已过期");
        }
        ConsoleSession session = consoleSessions.get(sessionId);
        if (session == null || !session.target().id().equals(expectedTarget)) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维会话无效或已过期");
        }
        validateAndCheckRole(session, consoleSessions);
    }

    public void revokeConsoleSession(String sessionId) {
        if (sessionId != null) {
            synchronized (lifecycleLock) {
                consoleSessions.remove(sessionId);
            }
        }
    }

    public synchronized SshSession createSshSession(LoginUser currentUser, String nodeId, int cols, int rows) {
        if (sshSessions.size() >= properties.getMaxSshSessions()) {
            throw new ServiceException(HttpStatus.CONFLICT, "当前终端连接数已达上限");
        }
        SshSession session = new SshSession(randomToken(), nodeId, currentUser.getUserId(), currentUser.getUserName(),
                normalizeDimension(cols, 80), normalizeDimension(rows, 24), properties.getSshHandshakeTtlSeconds());
        sshSessions.put(session.id(), session);
        return session;
    }

    /** Claims the one-time WebSocket credential before any SSH connection is opened. */
    public SshSession claimSshSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "终端会话无效或已被使用");
        }
        SshSession session = sshSessions.get(sessionId);
        if (session == null || expired(session) || !session.claim()) {
            if (session != null && expired(session)) {
                sshSessions.remove(sessionId, session);
            }
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "终端会话无效或已被使用");
        }
        try {
            accountVerifier.requireAdmin(session.userId(), session.userName());
            session.touch();
            return session;
        } catch (RuntimeException ex) {
            sshSessions.remove(sessionId);
            throw ex;
        }
    }

    public void touchSshSession(SshSession session) {
        validateAndTouch(session, sshSessions);
    }

    public void validateSshSession(SshSession session) {
        validateAndCheckRole(session, sshSessions);
    }

    public void revokeSshSession(String sessionId) {
        if (sessionId != null) {
            sshSessions.remove(sessionId);
        }
    }

    public void requireSshOwner(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "只能关闭自己的终端会话");
        }
        SshSession session = sshSessions.get(sessionId);
        if (session == null || !userId.equals(session.userId())) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "只能关闭自己的终端会话");
        }
        if (expired(session)) {
            sshSessions.remove(sessionId, session);
            throw new ServiceException(HttpStatus.GONE, "SSH 会话已关闭");
        }
    }

    public List<SshSession> activeSshSessions() {
        return new ArrayList<>(sshSessions.values());
    }

    public void revokeAllForUser(Long userId) {
        if (userId == null) {
            return;
        }
        synchronized (lifecycleLock) {
            tickets.values().removeIf(ticket -> ticket.userId().equals(userId));
            consoleSessions.values().removeIf(session -> session.userId().equals(userId));
            sshSessions.values().removeIf(session -> session.userId().equals(userId));
        }
    }

    @Scheduled(fixedDelay = 30000)
    public void removeExpiredSessions() {
        Instant now = Instant.now();
        tickets.values().removeIf(ticket -> ticket.expiresAt().isBefore(now));
        consoleSessions.values().removeIf(this::expired);
        sshSessions.values().removeIf(session -> !session.claimed() && expired(session));
    }

    private <T extends ExpiringSession> void validateAndTouch(T session, Map<String, T> sessions) {
        validateAndCheckRole(session, sessions);
        session.touch();
    }

    private <T extends ExpiringSession> void validateAndCheckRole(T session, Map<String, T> sessions) {
        Instant now = Instant.now();
        if (sessions.get(session.id()) != session || expired(session)) {
            sessions.remove(session.id(), session);
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "运维会话无效或已过期");
        }
        if (session.lastRoleCheck().plusSeconds(properties.getRoleCheckSeconds()).isBefore(now)) {
            try {
                accountVerifier.requireAdmin(session.userId(), session.userName());
                session.markRoleChecked(now);
            } catch (RuntimeException ex) {
                sessions.remove(session.id(), session);
                throw ex;
            }
        }
    }

    private boolean expired(ExpiringSession session) {
        Instant now = Instant.now();
        return session.createdAt().plusSeconds(properties.getSessionMaxSeconds()).isBefore(now)
                || (session instanceof SshSession ssh && !ssh.claimed()
                && ssh.pendingExpiresAt().isBefore(now))
                || session.lastAccess().plusSeconds(properties.getSessionIdleSeconds()).isBefore(now);
    }

    private int normalizeDimension(int value, int fallback) {
        return Math.max(1, Math.min(value <= 0 ? fallback : value, 400));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record Ticket(String token, ConsoleTarget target, Long userId, String userName, Instant expiresAt) {
    }

    public static final class ConsoleSession implements ExpiringSession {
        private final String id;
        private final ConsoleTarget target;
        private final Long userId;
        private final String userName;
        private final Instant createdAt = Instant.now();
        private volatile Instant lastAccess = createdAt;
        private volatile Instant lastRoleCheck = createdAt;

        public ConsoleSession(String id, ConsoleTarget target, Long userId, String userName) {
            this.id = id;
            this.target = target;
            this.userId = userId;
            this.userName = userName;
        }

        public String id() { return id; }
        public ConsoleTarget target() { return target; }
        public Long userId() { return userId; }
        public String userName() { return userName; }
        public Instant createdAt() { return createdAt; }
        public Instant lastAccess() { return lastAccess; }
        public Instant lastRoleCheck() { return lastRoleCheck; }
        public void touch() { lastAccess = Instant.now(); }
        public void markRoleChecked(Instant time) { lastRoleCheck = time; }
    }

    public static final class SshSession implements ExpiringSession {
        private final String id;
        private final String nodeId;
        private final Long userId;
        private final String userName;
        private final int cols;
        private final int rows;
        private final Instant pendingExpiresAt;
        private final Instant createdAt = Instant.now();
        private volatile Instant lastAccess = createdAt;
        private volatile Instant lastRoleCheck = createdAt;
        private boolean claimed;

        public SshSession(String id, String nodeId, Long userId, String userName,
                          int cols, int rows, long handshakeTtlSeconds) {
            this.id = id;
            this.nodeId = nodeId;
            this.userId = userId;
            this.userName = userName;
            this.cols = cols;
            this.rows = rows;
            this.pendingExpiresAt = createdAt.plusSeconds(handshakeTtlSeconds);
        }

        public synchronized boolean claim() {
            if (claimed) {
                return false;
            }
            claimed = true;
            return true;
        }

        public synchronized boolean claimed() { return claimed; }

        public String id() { return id; }
        public String nodeId() { return nodeId; }
        public Long userId() { return userId; }
        public String userName() { return userName; }
        public int cols() { return cols; }
        public int rows() { return rows; }
        public Instant pendingExpiresAt() { return pendingExpiresAt; }
        public Instant createdAt() { return createdAt; }
        public Instant lastAccess() { return lastAccess; }
        public Instant lastRoleCheck() { return lastRoleCheck; }
        public void touch() { lastAccess = Instant.now(); }
        public void markRoleChecked(Instant time) { lastRoleCheck = time; }
    }

    public interface ExpiringSession {
        String id();
        Long userId();
        String userName();
        Instant createdAt();
        Instant lastAccess();
        Instant lastRoleCheck();
        void touch();
        void markRoleChecked(Instant time);
    }
}
