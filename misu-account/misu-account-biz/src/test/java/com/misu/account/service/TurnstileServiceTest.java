package com.misu.account.service;

import com.misu.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.http.HttpMethod.POST;

class TurnstileServiceTest {
    private static final String URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    @Test
    void disabledLoginDoesNotCallCloudflare() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        new TurnstileService(false, "", "", "", builder.build()).verifyLogin(null);
        server.verify();
    }

    @Test
    void publicConfigNeverExposesSecret() {
        var service = new TurnstileService(true, "public-site-key", "private-secret", "server.misu.chat",
                RestClient.builder().build());
        assertThat(service.publicConfig()).containsEntry("siteKey", "public-site-key")
                .doesNotContainKey("secret");
    }

    @Test
    void acceptsMatchingHostnameAndAction() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andExpect(method(POST))
                .andRespond(withSuccess("{\"success\":true,\"hostname\":\"server.misu.chat\",\"action\":\"login\"}", MediaType.APPLICATION_JSON));

        new TurnstileService(true, "test-site-key", "test-secret", "server.misu.chat", builder.build())
                .verifyLogin("fresh-token");
        server.verify();
    }

    @Test
    void rejectsWrongHostnameActionAndReplayedToken() {
        assertInvalidResponse("{\"success\":true,\"hostname\":\"attacker.example\",\"action\":\"login\"}");
        assertInvalidResponse("{\"success\":true,\"hostname\":\"server.misu.chat\",\"action\":\"other\"}");
        assertInvalidResponse("{\"success\":false,\"error-codes\":[\"timeout-or-duplicate\"]}");
    }

    @Test
    void rejectsMissingTokenOrServerConfiguration() {
        var client = RestClient.builder().build();
        assertThatThrownBy(() -> new TurnstileService(true, "key", "secret", "server.misu.chat", client)
                .verifyLogin(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new TurnstileService(true, "key", "", "server.misu.chat", client)
                .verifyLogin("token")).isInstanceOf(ServiceException.class);
    }

    @Test
    void cloudflareFailureRejectsLogin() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withServerError());
        assertThatThrownBy(() -> new TurnstileService(true, "key", "secret", "server.misu.chat", builder.build())
                .verifyLogin("token")).isInstanceOf(ServiceException.class);
        server.verify();
    }

    private void assertInvalidResponse(String response) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(URL)).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new TurnstileService(true, "key", "secret", "server.misu.chat", builder.build())
                .verifyLogin("token")).isInstanceOf(ServiceException.class);
        server.verify();
    }
}
