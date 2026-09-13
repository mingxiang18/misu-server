package com.misu.ops.console;

import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NacosUpstreamAuthServiceTest {

    @Test
    void emptyCredentialsDisableUpstreamAuthorization() {
        OpsProperties properties = new OpsProperties();
        NacosUpstreamAuthService service = new NacosUpstreamAuthService(properties);

        assertNull(service.authorization("ops-session"));
        assertEquals(0, service.cachedSessionCount());
    }

    @Test
    void partialCredentialsFailClosed() {
        OpsProperties properties = new OpsProperties();
        properties.setNacosUsername("nacos-ops");
        NacosUpstreamAuthService service = new NacosUpstreamAuthService(properties);

        assertThrows(ServiceException.class, () -> service.authorization("ops-session"));
        assertEquals(0, service.cachedSessionCount());
    }

    @Test
    void productionFixedClusterHttpIsAccepted() {
        OpsProperties properties = new OpsProperties();
        properties.setNacosUsername("nacos-ops");
        properties.setNacosPassword("secret");
        properties.setNacosUpstreamUrl(NacosUpstreamAuthService.FIXED_UPSTREAM_URL);
        NacosUpstreamAuthService service = new NacosUpstreamAuthService(properties);

        assertEquals(NacosUpstreamAuthService.FIXED_UPSTREAM_URL, service.validatedUpstreamUri().toString());
        assertEquals(NacosUpstreamAuthService.FIXED_UPSTREAM_URL, service.validatedAuthBaseUri().toString());
    }

    @Test
    void configuredCredentialsRejectHttpLoopbackAliasAndCheckAuthAndUpstreamUrls() {
        OpsProperties properties = new OpsProperties();
        properties.setNacosUsername("nacos-ops");
        properties.setNacosPassword("secret");
        properties.setNacosUpstreamUrl("http://127.0.0.1:8848/nacos/");
        properties.setNacosAuthUrl("http://localhost:8848/nacos/");
        NacosUpstreamAuthService service = new NacosUpstreamAuthService(properties);

        assertThrows(ServiceException.class, () -> service.authorization("ops-session"));
        assertEquals(0, service.cachedSessionCount());
    }

    @Test
    void configuredCredentialsRejectLookalikeFixedService() {
        OpsProperties properties = new OpsProperties();
        properties.setNacosUsername("nacos-ops");
        properties.setNacosPassword("secret");
        properties.setNacosUpstreamUrl("http://nacos.misu-server.svc.cluster.local.evil:8848/nacos/");
        NacosUpstreamAuthService service = new NacosUpstreamAuthService(properties);

        assertThrows(ServiceException.class, service::validatedUpstreamUri);
        assertEquals(0, service.cachedSessionCount());
    }

    @Test
    void configuredCredentialsRejectHttpUpstreamAliasEvenWithHttpsAuthUrl() {
        OpsProperties properties = new OpsProperties();
        properties.setNacosUsername("nacos-ops");
        properties.setNacosPassword("secret");
        properties.setNacosUpstreamUrl("http://localhost:8848/nacos/");
        properties.setNacosAuthUrl("https://auth.example/nacos/");
        NacosUpstreamAuthService service = new NacosUpstreamAuthService(properties);

        assertThrows(ServiceException.class, () -> service.authorization("ops-session"));
        assertEquals(0, service.cachedSessionCount());
    }
}
