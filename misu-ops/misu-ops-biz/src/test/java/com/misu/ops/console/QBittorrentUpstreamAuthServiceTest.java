package com.misu.ops.console;

import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.session.ConsoleTarget;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QBittorrentUpstreamAuthServiceTest {

    @Test
    void upstreamIsPinnedToTheExistingServiceAndCannotBeOverriddenByProperties() {
        URI fixed = URI.create(QBittorrentUpstreamAuthService.FIXED_UPSTREAM_URL);
        assertEquals("http", fixed.getScheme());
        assertEquals("q-bit-torrent-pi.misu-server.svc.cluster.local", fixed.getHost());
        assertEquals(30120, fixed.getPort());
        assertEquals("/", fixed.getPath());
        assertEquals(QBittorrentUpstreamAuthService.FIXED_UPSTREAM_URL,
                ConsoleTarget.QBITTORRENT.upstreamUrl(new OpsProperties()));
    }

    @Test
    void missingCredentialsFailClosedBeforeAnyUpstreamRequest() {
        QBittorrentUpstreamAuthService service = new QBittorrentUpstreamAuthService(new OpsProperties());

        assertThrows(ServiceException.class, () -> service.session("ops-session"));
        assertEquals(0, service.cachedSessionCount());
    }
}
