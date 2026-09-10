package com.misu.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = OpsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "token.secret=01234567890123456789012345678901",
                "ops.nacos-url=https://ops-nacos.server.misu.chat/nacos/",
                "ops.headlamp-url=https://ops-k8s.server.misu.chat/",
                "ops.allowed-origins=https://server.misu.chat",
                "ops.cookie-secure=false"
        })
@TestExecutionListeners(
        listeners = {DependencyInjectionTestExecutionListener.class, DirtiesContextTestExecutionListener.class},
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class OpsApplicationContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextStartsWithWebSocketSecurityAndNoNacosBootstrap() {
        assertNotNull(applicationContext.getBean(OpsProperties.class));
        assertNotNull(applicationContext.getBean(com.misu.ops.ssh.OpsWebSocketConfig.class));
    }
}
