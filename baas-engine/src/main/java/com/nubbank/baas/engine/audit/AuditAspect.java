package com.nubbank.baas.engine.audit;

import com.nubbank.baas.engine.tenant.PartnerContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.Arrays;
import java.util.UUID;

/**
 * Intercepts all non-read-only @Transactional service methods and writes
 * an audit log entry. Covers every service in baas-engine automatically —
 * new services are audited for free with no manual wiring required.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditAspect {

    private final AuditLogService auditLogService;

    @Around("execution(public * com.nubbank.baas.engine.*..*Service.*(..))" +
            " && @annotation(transactional)" +
            " && !@annotation(org.springframework.transaction.annotation.Transactional(readOnly=true))")
    public Object auditServiceMethod(ProceedingJoinPoint pjp, Transactional transactional) throws Throwable {
        if (transactional.readOnly()) {
            return pjp.proceed();
        }

        PartnerContext ctx = PartnerContext.get();
        if (ctx == null) {
            return pjp.proceed();
        }

        String className = pjp.getTarget().getClass().getSimpleName();
        String methodName = pjp.getSignature().getName();
        String action = className.replace("Service", "").toUpperCase() + "_" + toSnakeUpper(methodName);

        UUID entityId = extractFirstUuid(pjp.getArgs());
        String entityType = className.replace("Service", "").toUpperCase();

        String changedBy = ctx.userId() != null ? ctx.userId() : ctx.partnerId();

        Object result = pjp.proceed();

        auditLogService.log(entityType, entityId, action, changedBy, null, null);

        return result;
    }

    private UUID extractFirstUuid(Object[] args) {
        return Arrays.stream(args)
            .filter(a -> a instanceof UUID)
            .map(a -> (UUID) a)
            .findFirst()
            .orElse(null);
    }

    private String toSnakeUpper(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toUpperCase().replaceFirst("^_", "");
    }
}
