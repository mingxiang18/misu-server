package com.misu.ops.console;

import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.ops.session.ConsoleTarget;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

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

    @Test
    void expiredSessionIsLoggedOutBestEffortBeforeReplacement() {
        OpsProperties properties = new OpsProperties();
        properties.setQbittorrentUsername("qbit-ops");
        properties.setQbittorrentPassword("secret");
        properties.setSessionIdleSeconds(60);
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
        RestClient restClient = restClientBuilder.build();
        TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
        QBittorrentUpstreamAuthService service =
                new QBittorrentUpstreamAuthService(properties, restClient, clock);

        server.expect(requestTo(QBittorrentUpstreamAuthService.FIXED_UPSTREAM_URL + "api/v2/auth/login"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .header("Set-Cookie", "QBT_SID_30120=old; Path=/"));
        server.expect(requestTo(QBittorrentUpstreamAuthService.FIXED_UPSTREAM_URL + "api/v2/auth/logout"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("Cookie", "QBT_SID_30120=old"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo(QBittorrentUpstreamAuthService.FIXED_UPSTREAM_URL + "api/v2/auth/login"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK)
                        .header("Set-Cookie", "QBT_SID_30121=new; Path=/"));

        assertEquals("QBT_SID_30120=old", service.session("ops-session").cookie());
        clock.advanceSeconds(61);
        assertEquals("QBT_SID_30121=new", service.session("ops-session").cookie());
        assertEquals(1, service.cachedSessionCount());
        server.verify();
    }

    private static final class TestClock extends Clock {
        private Instant now;

        private TestClock(Instant now) {
            this.now = now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
