package com.misu.ops.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.ops.OpsProperties;
import com.misu.ops.console.ConsoleWebSocketBridgeService;
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

import java.util.Map;

@Validated
@RestController
@RequestMapping("/api")
public class OpsController {

    private final OpsProperties properties;
    private final OpsAuthorization authorization;
    private final OpsOriginPolicy originPolicy;
    private final OpsSessionStore sessions;
    private final SshConnectionService ssh;
    private final ConsoleWebSocketBridgeService consoleWebSockets;

    public OpsController(OpsProperties properties, OpsAuthorization authorization,
                          OpsOriginPolicy originPolicy, OpsSessionStore sessions,
                          SshConnectionService ssh, ConsoleWebSocketBridgeService consoleWebSockets) {
        this.properties = properties;
        this.authorization = authorization;
        this.originPolicy = originPolicy;
        this.sessions = sessions;
        this.ssh = ssh;
        this.consoleWebSockets = consoleWebSockets;
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
     * It consumes the ticket before setting the host-only ops cookie.
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
        originPolicy.requireAllowedMainOrigin(request);
        // Resolve and validate the destination Host before consuming the
        // one-time ticket, then atomically consume/create under the store lock.
        ConsoleTarget hostTarget = originPolicy.resolveConsoleTarget(request);
        OpsSessionStore.ConsoleSession session = sessions.exchangeConsoleSession(ticketToken, hostTarget);
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), session.id())
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .path(session.target().cookiePath())
                .maxAge(properties.getSessionMaxSeconds())
                .build();
        response.setHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(HttpHeaders.LOCATION, session.target().url(properties));
        return ResponseEntity.status(HttpStatus.SEE_OTHER).build();
    }

    @Anonymous
    @PostMapping("/console-sessions/logout")
    public AjaxResult logout(HttpServletRequest request, HttpServletResponse response) {
        originPolicy.requireAllowedMainOrigin(request);
        String sessionId = CookieSupport.read(request, properties.getCookieName());
        sessions.revokeConsoleSession(sessionId);
        consoleWebSockets.closeForSession(sessionId);
        ResponseCookie cookie = ResponseCookie.from(properties.getCookieName(), "")
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .path(ConsoleTarget.NACOS.cookiePath())
                .maxAge(0)
                .build();
        response.setHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return AjaxResult.success();
    }

    @PostMapping("/sessions/revoke")
    public AjaxResult revokeSessions(HttpServletRequest request) {
        originPolicy.requireAllowedMainOrigin(request);
        LoginUser current = authorization.requireCurrentAdmin();
        sessions.revokeAllForUser(current.getUserId());
        ssh.closeForUser(current.getUserId());
        consoleWebSockets.closeForUser(current.getUserId());
        return AjaxResult.success();
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

    public record ConsoleTicketResponse(String ticket, String entryUrl, String exchangeUrl) {
    }

    public record SshSessionResponse(String sessionId, String websocketUrl) {
    }
}
