package com.misu.ops;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertNotNull(applicationContext.getBean("webSocketContainerLimits", ServletContextInitializer.class));
        assertEquals(1_000_000, com.misu.ops.config.OpsWebSocketLimitsConfig.MAX_TEXT_MESSAGE_BYTES);
        assertEquals(8_000_000, com.misu.ops.config.OpsWebSocketLimitsConfig.MAX_BINARY_MESSAGE_BYTES);
        assertTrue(applicationContext.getBeansOfType(UserDetailsService.class).isEmpty(),
                "misu-ops uses JWT/account verification and must not create a default in-memory user");
    }
}
