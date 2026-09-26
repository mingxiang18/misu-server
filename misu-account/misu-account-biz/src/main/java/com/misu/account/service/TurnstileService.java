package com.misu.account.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TurnstileService {
    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final String LOGIN_ACTION = "login";

    private final boolean enabled;
    private final String siteKey;
    private final String secret;
    private final Set<String> hostnames;
    private final RestClient restClient;

    @Autowired
    public TurnstileService(@Value("${turnstile.enabled:false}") boolean enabled,
                            @Value("${turnstile.site-key:}") String siteKey,
                            @Value("${TURNSTILE_SECRET:}") String secret,
                            @Value("${turnstile.hostnames:}") String hostnames,
                            RestClient.Builder builder) {
        this(enabled, siteKey, secret, hostnames, builder.requestFactory(requestFactory()).build());
    }

    TurnstileService(boolean enabled, String siteKey, String secret, String hostnames, RestClient restClient) {
        this.enabled = enabled;
        this.siteKey = siteKey;
        this.secret = secret;
        this.hostnames = Arrays.stream(hostnames.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.restClient = restClient;
    }

    public Map<String, Object> publicConfig() {
        return Map.of("enabled", enabled, "siteKey", enabled ? siteKey : "");
    }

    public void verifyLogin(String token) {
        if (!enabled) {
            return;
        }
        if (secret.isBlank() || siteKey.isBlank() || hostnames.isEmpty()) {
            throw new ServiceException(HttpStatus.ERROR, "登录验证暂不可用，请稍后重试");
        }
        if (token == null || token.isBlank() || token.length() > 2048) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "请完成人机验证后重试");
        }

        var form = new LinkedMultiValueMap<String, String>();
        form.add("secret", secret);
        form.add("response", token);
        SiteverifyResponse result;
        try {
            result = restClient.post().uri(SITEVERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(SiteverifyResponse.class);
        } catch (RestClientException ex) {
            throw new ServiceException(HttpStatus.ERROR, "登录验证暂不可用，请稍后重试");
        }
        if (result == null || !result.success()
                || !LOGIN_ACTION.equals(result.action())
                || !hostnames.contains(result.hostname())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "人机验证失败，请重试");
        }
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(5000);
        return factory;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SiteverifyResponse(boolean success, String hostname, String action) { }
}
