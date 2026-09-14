package com.misu.ops.session;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.ops.ai.AiCliTool;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
class OpsSessionStoreTest {

    private final OpsProperties properties = new OpsProperties();
    private final TestVerifier verifier = new TestVerifier();
    private final LoginUser admin = new LoginUser(7L, "admin", java.util.List.of("ADMIN"));

    @Test
    void ticketIsBoundToTargetAndCanOnlyBeConsumedOnce() {
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.Ticket ticket = store.issueTicket(admin, ConsoleTarget.NACOS);

        assertEquals(ConsoleTarget.NACOS, store.consumeTicket(ticket.token()).target());
        ServiceException secondUse = assertThrows(ServiceException.class,
                () -> store.consumeTicket(ticket.token()));
        assertEquals(HttpStatus.UNAUTHORIZED, secondUse.getCode());
    }

    @Test
    void consoleExchangeRejectsWrongTargetBeforeConsumingAndRemainsSingleUse() {
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.Ticket ticket = store.issueTicket(admin, ConsoleTarget.NACOS);

        ServiceException wrongTarget = assertThrows(ServiceException.class,
                () -> store.exchangeConsoleSession(ticket.token(), ConsoleTarget.HEADLAMP));
        assertEquals(HttpStatus.FORBIDDEN, wrongTarget.getCode());
        assertEquals(ConsoleTarget.NACOS,
                store.exchangeConsoleSession(ticket.token(), ConsoleTarget.NACOS).target());
        ServiceException replay = assertThrows(ServiceException.class,
                () -> store.exchangeConsoleSession(ticket.token(), ConsoleTarget.NACOS));
        assertEquals(HttpStatus.UNAUTHORIZED, replay.getCode());
    }

    @Test
    void exchangedConsoleSessionIsImmediatelyVisibleToTheProxyAuthPath() {
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.Ticket ticket = store.issueTicket(admin, ConsoleTarget.HEADLAMP);

        OpsSessionStore.ConsoleSession session = store.exchangeConsoleSession(
                ticket.token(), ConsoleTarget.HEADLAMP);

        assertEquals(1, store.activeConsoleSessionCount());
        assertSame(session, store.requireConsoleSession(session.id(), ConsoleTarget.HEADLAMP.id()));
    }

    @Test
    void refreshingSameUserAndTargetReplacesTheOldSession() {
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.ConsoleSession oldSession = store.createConsoleSession(
                store.issueTicket(admin, ConsoleTarget.NACOS));
        OpsSessionStore.ConsoleSession newSession = store.createConsoleSession(
                store.issueTicket(admin, ConsoleTarget.NACOS));

        assertEquals(1, store.activeConsoleSessionCount());
        assertThrows(ServiceException.class,
                () -> store.requireConsoleSession(oldSession.id(), ConsoleTarget.NACOS.id()));
        assertSame(newSession, store.requireConsoleSession(newSession.id(), ConsoleTarget.NACOS.id()));
    }

    @Test
    void sshCredentialIsSingleUseAndAccountRevocationRemovesSession() {
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.SshSession ssh = store.createSshSession(admin, "master", 80, 24);

        assertEquals(ssh, store.claimSshSession(ssh.id()));
        assertThrows(ServiceException.class, () -> store.claimSshSession(ssh.id()));

        verifier.revoked = true;
        properties.setRoleCheckSeconds(0);
        assertThrows(ServiceException.class, () -> store.validateSshSession(ssh));
        assertThrows(ServiceException.class, () -> store.validateSshSession(ssh));
    }

    @Test
    void expiredUnclaimedSshCredentialCannotBeClaimed() {
        properties.setSessionMaxSeconds(0);
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.SshSession ssh = store.createSshSession(admin, "worker", 80, 24);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> store.claimSshSession(ssh.id()));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getCode());
    }

    @Test
    void ticketAndConsoleSessionLimitsAreGlobalAndPerUser() {
        properties.setMaxConsoleTickets(4);
        properties.setMaxConsoleTicketsPerUser(1);
        properties.setMaxConsoleSessions(2);
        properties.setMaxConsoleSessionsPerUser(1);
        OpsSessionStore store = new OpsSessionStore(properties, verifier);

        OpsSessionStore.Ticket adminTicket = store.issueTicket(admin, ConsoleTarget.NACOS);
        assertThrows(ServiceException.class,
                () -> store.issueTicket(admin, ConsoleTarget.HEADLAMP));
        LoginUser otherAdmin = new LoginUser(8L, "other-admin", java.util.List.of("ADMIN"));
        OpsSessionStore.Ticket otherTicket = store.issueTicket(otherAdmin, ConsoleTarget.HEADLAMP);
        assertThrows(ServiceException.class,
                () -> store.issueTicket(otherAdmin, ConsoleTarget.NACOS));

        store.createConsoleSession(adminTicket);
        store.createConsoleSession(otherTicket);
        LoginUser thirdAdmin = new LoginUser(9L, "third-admin", java.util.List.of("ADMIN"));
        OpsSessionStore.Ticket thirdTicket = store.issueTicket(thirdAdmin, ConsoleTarget.NACOS);
        assertThrows(ServiceException.class, () -> store.createConsoleSession(thirdTicket));
        store.issueTicket(new LoginUser(10L, "fourth-admin", java.util.List.of("ADMIN")), ConsoleTarget.NACOS);
        assertThrows(ServiceException.class,
                () -> store.issueTicket(new LoginUser(11L, "fifth-admin", java.util.List.of("ADMIN")),
                        ConsoleTarget.NACOS));
        assertEquals(2, store.activeConsoleSessionCount());
    }

    @Test
    void expiredConsoleSessionIsReclaimedBeforeApplyingLimit() throws InterruptedException {
        properties.setMaxConsoleSessions(1);
        properties.setMaxConsoleSessionsPerUser(1);
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        store.createConsoleSession(store.issueTicket(admin, ConsoleTarget.NACOS));
        properties.setSessionMaxSeconds(0);
        Thread.sleep(2);

        OpsSessionStore.ConsoleSession replacement = store.createConsoleSession(
                store.issueTicket(admin, ConsoleTarget.NACOS));
        properties.setSessionMaxSeconds(900);
        assertSame(replacement, store.requireConsoleSession(replacement.id(), ConsoleTarget.NACOS.id()));
    }

    @Test
    void aiCredentialIsSingleUseAndBoundToTheCreatingAdmin() {
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.AiSession ai = store.createAiSession(admin, AiCliTool.CODEX, 80, 24);

        assertSame(ai, store.claimAiSession(ai.id()));
        assertThrows(ServiceException.class, () -> store.claimAiSession(ai.id()));

        OpsSessionStore.AiSession other = store.createAiSession(
                new LoginUser(8L, "other-admin", java.util.List.of("ADMIN")), AiCliTool.CLAUDE, 80, 24);
        assertThrows(ServiceException.class, () -> store.requireAiOwner(other.id(), admin.getUserId()));
    }

    @Test
    void expiredUnclaimedAiCredentialCannotBeClaimed() {
        properties.setSessionMaxSeconds(0);
        OpsSessionStore store = new OpsSessionStore(properties, verifier);
        OpsSessionStore.AiSession ai = store.createAiSession(admin, AiCliTool.CODEX, 80, 24);

        ServiceException exception = assertThrows(ServiceException.class,
                () -> store.claimAiSession(ai.id()));
        assertEquals(HttpStatus.UNAUTHORIZED, exception.getCode());
    }

    @Test
    void aiSessionLimitsAreGlobalAndPerUser() {
        properties.setMaxAiSessions(4);
        properties.setMaxAiSessionsPerUser(2);
        OpsSessionStore store = new OpsSessionStore(properties, verifier);

        store.createAiSession(admin, AiCliTool.CODEX, 80, 24);
        store.createAiSession(admin, AiCliTool.CLAUDE, 80, 24);
        assertThrows(ServiceException.class,
                () -> store.createAiSession(admin, AiCliTool.CODEX, 80, 24));
        store.createAiSession(new LoginUser(8L, "other-admin", java.util.List.of("ADMIN")),
                AiCliTool.CLAUDE, 80, 24);
        store.createAiSession(new LoginUser(9L, "third-admin", java.util.List.of("ADMIN")),
                AiCliTool.CODEX, 80, 24);
        assertThrows(ServiceException.class,
                () -> store.createAiSession(new LoginUser(10L, "fourth-admin", java.util.List.of("ADMIN")),
                        AiCliTool.CLAUDE, 80, 24));
        assertEquals(4, properties.getMaxAiSessions());
        assertEquals(2, properties.getMaxAiSessionsPerUser());
    }

    private static final class TestVerifier implements CurrentAccountVerifier {
        private boolean revoked;

        @Override
        public LoginUser requireAdmin(LoginUser tokenUser) {
            return requireAdmin(tokenUser.getUserId(), tokenUser.getUserName());
        }

        @Override
        public LoginUser requireAdmin(Long userId, String userName) {
            if (revoked) {
                throw new ServiceException(HttpStatus.FORBIDDEN, "revoked");
            }
            return new LoginUser(userId, userName, java.util.List.of("ADMIN"));
        }
    }
}
