package com.misu.ops.security;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.ops.OpsProperties;
import com.misu.security.constant.UserRole;
import com.misu.security.dto.LoginUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

@Slf4j
@Component
public class AccountCurrentUserClient implements CurrentAccountVerifier {

    private final OpsProperties properties;
    private final RestClient restClient;

    public AccountCurrentUserClient(OpsProperties properties) {
        this.properties = properties;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getAccountConnectTimeoutMillis()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getAccountReadTimeoutMillis()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAccountBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public LoginUser requireAdmin(LoginUser tokenUser) {
        if (tokenUser == null || tokenUser.getUserId() == null || !hasText(tokenUser.getUserName())) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        }
        return requireAdmin(tokenUser.getUserId(), tokenUser.getUserName());
    }

    @Override
    public LoginUser requireAdmin(Long userId, String userName) {
        if (userId == null || !hasText(userName)) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "登录状态无效");
        }
        final LoginUser current;
        try {
            current = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/inner/user/getUserFromUsername")
                            .queryParam("username", userName)
                            .build())
                    .retrieve()
                    .body(LoginUser.class);
        } catch (RuntimeException ex) {
            // An unavailable account service must never turn into an allow decision.
            log.warn("账号服务不可用，拒绝运维授权: userId={}", userId);
            throw new ServiceException(HttpStatus.ERROR, "账号服务不可用，暂时无法进行运维授权");
        }
        if (current == null || !Objects.equals(userId, current.getUserId())
                || !Objects.equals(userName, current.getUserName())) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "账号不存在或已失效");
        }
        if (!"0".equals(current.getStatus())
                || !(current.getDelFlag() == null || "0".equals(current.getDelFlag()))) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "账号已停用");
        }
        if (current.getAuthorities() == null || !current.getAuthorities().contains(UserRole.ADMIN)) {
            throw new ServiceException(HttpStatus.FORBIDDEN, "仅管理员可以使用运维中心");
        }
        return current;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
