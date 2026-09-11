package com.misu.ops.session;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
