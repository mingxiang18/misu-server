package com.misu.ops.ssh;

import com.misu.ops.OpsProperties;
import com.misu.ops.security.CurrentAccountVerifier;
import com.misu.ops.session.OpsSessionStore;
import com.misu.security.dto.LoginUser;
import org.springframework.web.socket.WebSocketSession;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SshConnectionServiceTest {

    @Test
    void keepsEmojiTogetherWhenReaderSplitsSurrogatePair() {
        SshConnectionService.Utf8Chunker chunker = new SshConnectionService.Utf8Chunker();
        char[] emoji = "😀".toCharArray();

        assertEquals("", chunker.accept(new char[]{emoji[0]}, 1));
        assertEquals("😀", chunker.accept(new char[]{emoji[1]}, 1));
        assertEquals("", chunker.finish());
    }

    @Test
    void openingIsClearedWhenNodeOrKeyFilesAreInvalidAndDeleteRevokesUnknownSocket() {
        OpsProperties properties = new OpsProperties();
        properties.getSsh().getNodes().put("bad", new OpsProperties.Node("bad", "bad", "", 22));
        OpsSessionStore sessions = new OpsSessionStore(properties, new AdminVerifier());
        SshConnectionService service = new SshConnectionService(properties, sessions);
        OpsSessionStore.SshSession sshSession = sessions.createSshSession(
                new LoginUser(7L, "admin", List.of("ADMIN")), "bad", 80, 24);
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);

        assertThrows(RuntimeException.class, () -> service.open(sshSession, socket));
        assertEquals(0, service.openingCount());

        OpsSessionStore.SshSession deleteSession = sessions.createSshSession(
                new LoginUser(7L, "admin", List.of("ADMIN")), "bad", 80, 24);
        service.close(deleteSession.id());
        assertThrows(RuntimeException.class, () -> sessions.touchSshSession(deleteSession));
    }

    private static final class AdminVerifier implements CurrentAccountVerifier {
        @Override
        public LoginUser requireAdmin(LoginUser tokenUser) { return tokenUser; }

        @Override
        public LoginUser requireAdmin(Long userId, String userName) {
            return new LoginUser(userId, userName, List.of("ADMIN"));
        }
    }
}
