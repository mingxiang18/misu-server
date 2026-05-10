package com.misu.fileServer.audit;

import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.entity.FileAuditLog;
import com.misu.fileServer.repository.FileAuditLogRepository;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 切面：拦截 @Audited 方法，写一条 file_audit_log。
 * 异步写库（fileExecutor）以避免阻塞主请求。
 */
@Slf4j
@Aspect
@Component
public class FileAuditAspect {

    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();
    private static final ExpressionParser SPEL = new SpelExpressionParser();

    @Resource
    private FileAuditLogRepository fileAuditLogRepository;

    @Resource
    private ThreadPoolTaskExecutor fileExecutor;

    @Resource
    private MeterRegistry meterRegistry;

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        long startNanos = System.nanoTime();
        Object ret = null;
        Throwable thrown = null;
        try {
            ret = pjp.proceed();
            return ret;
        } catch (Throwable t) {
            thrown = t;
            throw t;
        } finally {
            // Q5：业务 metric — 操作耗时 + 计数（按 action + status 分桶）
            try {
                String status = thrown == null ? "success"
                        : (thrown instanceof ServiceException se ? "fail_" + se.getCode() : "fail_500");
                Timer.builder("misu.file.action")
                        .description("业务操作耗时和次数（按 @Audited 切入）")
                        .tag("action", audited.value())
                        .tag("status", status)
                        .register(meterRegistry)
                        .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            } catch (Exception ignore) { }
            try {
                writeLog(pjp, audited, ret, thrown);
            } catch (Exception ignore) {
                log.warn("write audit log failed: action={}", audited.value(), ignore);
            }
        }
    }

    private void writeLog(ProceedingJoinPoint pjp, Audited audited, Object ret, Throwable thrown) {
        FileAuditLog log = new FileAuditLog();
        log.setActionType(audited.value());
        log.setCreateTime(LocalDateTime.now());

        // 当前用户
        LoginUser loginUser = LoginMessageUtil.getLoginUser().orElse(null);
        if (loginUser != null) {
            log.setUserId(String.valueOf(loginUser.getUserId()));
            log.setUserName(loginUser.getUserName());
        }

        // 请求元信息（IP / UA / RequestId）
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            log.setIp(extractIp(req));
            String ua = req.getHeader("User-Agent");
            log.setUserAgent(ua == null ? null : (ua.length() > 480 ? ua.substring(0, 480) : ua));
            String rid = req.getHeader("X-Request-Id");
            log.setRequestId(rid != null ? rid : UUID.randomUUID().toString());
        } else {
            log.setRequestId(UUID.randomUUID().toString());
        }

        // 取目标 (openType, virtualPath)：先 SpEL，后通用字段探测
        try {
            MethodSignature sig = (MethodSignature) pjp.getSignature();
            Method method = sig.getMethod();
            Object[] args = pjp.getArgs();
            MethodBasedEvaluationContext ctx =
                    new MethodBasedEvaluationContext(pjp.getTarget(), method, args, NAME_DISCOVERER);

            String openTypeExpr = audited.openTypeExpr();
            if (!openTypeExpr.isEmpty()) {
                Object v = SPEL.parseExpression(openTypeExpr).getValue(ctx);
                if (v instanceof Number) log.setTargetOpenType(((Number) v).intValue());
            }
            String pathExpr = audited.virtualPathExpr();
            if (!pathExpr.isEmpty()) {
                Object v = SPEL.parseExpression(pathExpr).getValue(ctx);
                if (v != null) log.setTargetVirtualPath(String.valueOf(v));
            }
            // 如果未给表达式，扫一下入参，按通用字段名 openType / filePath / fileName 自动找
            if (log.getTargetOpenType() == null || log.getTargetVirtualPath() == null) {
                autoFillTargetFromArgs(args, log);
            }
        } catch (Exception ignore) {
            // 取参失败不影响主流程
        }

        // 状态
        if (thrown == null) {
            log.setStatusCode(200);
        } else if (thrown instanceof ServiceException se) {
            log.setStatusCode(se.getCode());
            String msg = se.getMessage();
            log.setErrorMessage(msg == null ? null : (msg.length() > 480 ? msg.substring(0, 480) : msg));
        } else {
            log.setStatusCode(500);
            String msg = thrown.getMessage();
            log.setErrorMessage(msg == null ? thrown.getClass().getSimpleName()
                    : (msg.length() > 480 ? msg.substring(0, 480) : msg));
        }

        // 异步写库，不阻塞响应
        fileExecutor.execute(() -> {
            try {
                fileAuditLogRepository.save(log);
            } catch (Exception e) {
                FileAuditAspect.log.warn("audit log persist failed", e);
            }
        });
    }

    private static String extractIp(HttpServletRequest req) {
        String h = req.getHeader("X-Forwarded-For");
        if (h != null && !h.isBlank()) {
            int comma = h.indexOf(',');
            return (comma > 0 ? h.substring(0, comma) : h).trim();
        }
        h = req.getHeader("X-Real-IP");
        if (h != null && !h.isBlank()) return h;
        return req.getRemoteAddr();
    }

    private static void autoFillTargetFromArgs(Object[] args, FileAuditLog log) {
        for (Object a : args) {
            if (a == null) continue;
            tryGet(a, "getOpenType", Integer.class).ifPresent(log::setTargetOpenType);
            tryGet(a, "getFilePath", String.class).ifPresent(log::setTargetVirtualPath);
        }
    }

    private static <T> java.util.Optional<T> tryGet(Object bean, String getter, Class<T> type) {
        try {
            Method m = bean.getClass().getMethod(getter);
            Object v = m.invoke(bean);
            if (type.isInstance(v)) return java.util.Optional.of(type.cast(v));
        } catch (Exception ignore) { }
        return java.util.Optional.empty();
    }
}
