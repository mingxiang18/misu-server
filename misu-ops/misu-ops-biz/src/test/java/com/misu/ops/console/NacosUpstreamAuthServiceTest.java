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
}
