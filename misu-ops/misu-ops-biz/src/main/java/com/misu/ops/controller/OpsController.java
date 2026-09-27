package com.misu.ops.controller;

import com.misu.common.domain.AjaxResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.misu.ops.OpsProperties;
import com.misu.ops.console.ConsoleWebSocketBridgeService;
import com.misu.ops.ai.AiCliConnectionService;
import com.misu.ops.ai.AiCliTool;
import com.misu.ops.security.OpsAuthorization;
import com.misu.ops.security.OpsOriginPolicy;
import com.misu.ops.ssh.SshConnectionService;
import com.misu.ops.session.ConsoleTarget;
import com.misu.ops.session.OpsSessionStore;
import com.misu.security.annotation.Anonymous;
import com.misu.security.dto.LoginUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api")
@Slf4j
public class OpsController {

    private final OpsProperties properties;
    private final OpsAuthorization authorization;
    private final OpsOriginPolicy originPolicy;
    private final OpsSessionStore sessions;
    private final SshConnectionService ssh;
    private final ConsoleWebSocketBridgeService consoleWebSockets;
    private final AiCliConnectionService aiCli;

    @org.springframework.beans.factory.annotation.Autowired
    public OpsController(OpsProperties properties, OpsAuthorization authorization,
                          OpsOriginPolicy originPolicy, OpsSessionStore sessions,
                          SshConnectionService ssh, ConsoleWebSocketBridgeService consoleWebSockets,
                          AiCliConnectionService aiCli) {
        this.properties = properties;
        this.authorization = authorization;
        this.originPolicy = originPolicy;
        this.sessions = sessions;
        this.ssh = ssh;
        this.consoleWebSockets = consoleWebSockets;
        this.aiCli = aiCli;
    }

    /** Compatibility constructor for focused controller tests that do not exercise AI endpoints. */
    public OpsController(OpsProperties properties, OpsAuthorization authorization,
                          OpsOriginPolicy originPolicy, OpsSessionStore sessions,
                          SshConnectionService ssh, ConsoleWebSocketBridgeService consoleWebSockets) {
        this(properties, authorization, originPolicy, sessions, ssh, consoleWebSockets, null);
    }

    @GetMapping("/endpoints")
    public AjaxResult endpoints() {
        authorization.requireCurrentAdmin();
        return AjaxResult.success(Map.of(
                "nacosUrl", properties.getNacosUrl(),
                "headlampUrl", properties.getHeadlampUrl()));
    }

    @PostMapping("/console/tickets")
    public AjaxResult createConsoleTicket(HttpServletRequest request,
                                           @Valid @RequestBody ConsoleTicketRequest body) {
        originPolicy.requireAllowedMainOrigin(request);
        LoginUser current = authorization.requireCurrentAdmin();
        ConsoleTarget target = ConsoleTarget.parse(body.getTarget());
        target.url(properties); // fail closed when an upstream is not configured
        OpsSessionStore.Ticket ticket = sessions.issueTicket(current, target);
        return AjaxResult.success(new ConsoleTicketResponse(ticket.token(), target.url(properties), exchangeUrl(target)));
    }

    /**
     * This endpoint is intended for a top-level form POST from the main site.
     * It consumes the ticket before setting the target-scoped ops cookie. The
     * browser Origin is deliberately not required; sidecar trust, Host and
     * the one-time target-bound ticket are the exchange authorization.
     */
    @Anonymous
    @PostMapping(value = "/console-sessions/exchange",
            consumes = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> exchangeConsoleSession(HttpServletRequest request,
                                                        HttpServletResponse response,
                                                        @RequestParam("ticket") String ticket) {
        return exchangeConsoleSessionInternal(request, response, ticket);
    }

    private ResponseEntity<Void> exchangeConsoleSessionInternal(HttpServletRequest request,
                                                                HttpServletResponse response,
                                                                String ticketToken) {
        // Resolve and validate the destination Host before consuming the
        // one-time ticket, then atomically consume/create under the store lock.
        ConsoleTarget hostTarget = originPolicy.requireTrustedConsoleTarget(request);
        OpsSessionStore.ConsoleSession session = sessions.exchangeConsoleSession(ticketToken, hostTarget);
        log.info("运维控制台会话已创建: target={} activeSessionCount={}",
                hostTarget.id(), sessions.activeConsoleSessionCount());
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), session.id())
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("None")
                .path(session.target().cookiePath())
                .maxAge(properties.getSessionMaxSeconds())
                .build();
        response.setHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.LOCATION, session.target().url(properties));
        return ResponseEntity.status(HttpStatus.SEE_OTHER).build();
    }

    @PostMapping("/sessions/revoke")
    public AjaxResult revokeSessions(HttpServletRequest request, HttpServletResponse response) {
        originPolicy.requireAllowedMainOrigin(request);
        LoginUser current = authorization.requireCurrentAdmin();
        sessions.revokeAllForUser(current.getUserId());
        ssh.closeForUser(current.getUserId());
        consoleWebSockets.closeForUser(current.getUserId());
        if (aiCli != null) {
            aiCli.closeForUser(current.getUserId());
        }
        for (ConsoleTarget target : ConsoleTarget.values()) {
            response.addHeader(HttpHeaders.SET_COOKIE, expiredConsoleCookie(target).toString());
        }
        return AjaxResult.success();
    }

    private ResponseCookie expiredConsoleCookie(ConsoleTarget target) {
        return ResponseCookie.from(properties.getCookieName(), "")
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("None")
                .path(target.cookiePath())
                .maxAge(0)
                .build();
    }

    @PostMapping("/ssh/sessions")
    public AjaxResult createSshSession(HttpServletRequest request, @Valid @RequestBody SshSessionRequest body) {
        originPolicy.requireAllowedMainOrigin(request);
        LoginUser current = authorization.requireCurrentAdmin();
        requireNode(body.getNodeId());
        OpsSessionStore.SshSession session = sessions.createSshSession(
                current, body.getNodeId(), body.getCols(), body.getRows());
        String websocketUrl = properties.getPublicBaseUrl() + "/ops/ws/ssh/" + session.id();
        return AjaxResult.success(new SshSessionResponse(session.id(), websocketUrl));
    }

    @DeleteMapping("/ssh/sessions/{sessionId}")
    public AjaxResult closeSshSession(HttpServletRequest request,
                                     @org.springframework.web.bind.annotation.PathVariable("sessionId") String sessionId) {
        originPolicy.requireAllowedMainOrigin(request);
        LoginUser current = authorization.requireCurrentAdmin();
        sessions.requireSshOwner(sessionId, current.getUserId());
        ssh.close(sessionId);
        return AjaxResult.success();
    }

    @PostMapping("/ai/sessions")
    public AjaxResult createAiSession(HttpServletRequest request, @Valid @RequestBody AiSessionRequest body) {
        originPolicy.requireAllowedMainOrigin(request);
        LoginUser current = authorization.requireCurrentAdmin();
        aiCli.requireConfigured();
        OpsSessionStore.AiSession session = sessions.createAiSession(
                current, body.getTool(), body.getCols(), body.getRows());
        String websocketUrl = properties.getPublicBaseUrl() + "/ops/ws/ai/" + session.id();
        return AjaxResult.success(new AiSessionResponse(session.id(), websocketUrl, session.tool().name()));
    }

    @DeleteMapping("/ai/sessions/{sessionId}")
    public AjaxResult closeAiSession(HttpServletRequest request,
                                     @org.springframework.web.bind.annotation.PathVariable("sessionId") String sessionId) {
        originPolicy.requireAllowedMainOrigin(request);
        LoginUser current = authorization.requireCurrentAdmin();
        sessions.requireAiOwner(sessionId, current.getUserId());
        aiCli.close(sessionId);
        return AjaxResult.success();
    }

    private void requireNode(String nodeId) {
        if (nodeId == null || !("master".equals(nodeId) || "worker".equals(nodeId))
                || properties.getSsh().getNodes().get(nodeId) == null) {
            throw new com.misu.common.exception.ServiceException(
                    com.misu.common.constant.HttpStatus.BAD_REQUEST, "不支持的节点");
        }
    }

    String exchangeUrl(ConsoleTarget target) {
        String configured = target.url(properties);
        try {
            java.net.URI uri = java.net.URI.create(configured);
            if (uri.getScheme() == null || uri.getRawAuthority() == null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw new IllegalArgumentException("console URL must be an absolute URL without query");
            }
            String path = uri.getRawPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            if (!path.endsWith("/")) {
                path += "/";
            }
            return uri.getScheme() + "://" + uri.getRawAuthority() + path + "_ops/exchange";
        } catch (IllegalArgumentException ex) {
            throw new com.misu.common.exception.ServiceException(
                    com.misu.common.constant.HttpStatus.ERROR, "控制台地址配置无效");
        }
    }

    @Getter
    @Setter
    public static class ConsoleTicketRequest {
        @NotBlank
        private String target;
    }

    @Getter
    @Setter
    public static class SshSessionRequest {
        @NotBlank
        private String nodeId;
        @Min(1)
        @Max(400)
        private int cols = 80;
        @Min(1)
        @Max(400)
        private int rows = 24;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class AiSessionRequest {
        @NotNull
        private AiCliTool tool;
        @Min(1)
        @Max(400)
        private int cols = 80;
        @Min(1)
        @Max(400)
        private int rows = 24;
    }

    public record ConsoleTicketResponse(String ticket, String entryUrl, String exchangeUrl) {
    }

    public record SshSessionResponse(String sessionId, String websocketUrl) {
    }

    public record AiSessionResponse(String sessionId, String websocketUrl, String tool) {
    }
}
