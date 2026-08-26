package com.datagraph.bank.service;

import com.datagraph.bank.security.UserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class OperationAuditAspect {
    private final AuditService auditService;

    public OperationAuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("within(@org.springframework.web.bind.annotation.RestController *)"
            + " && !within(com.datagraph.bank.controller.AuthController)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        long started = System.currentTimeMillis();
        boolean success = false;
        String error = null;
        try {
            Object result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Throwable throwable) {
            error = throwable.getMessage();
            throw throwable;
        } finally {
            try {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                UserPrincipal principal = authentication != null
                        && authentication.getPrincipal() instanceof UserPrincipal value ? value : null;
                ServletRequestAttributes attributes =
                        (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                HttpServletRequest request = attributes == null ? null : attributes.getRequest();
                auditService.operation(
                        principal == null ? null : principal.username(),
                        principal == null ? null : principal.bankCode(),
                        request == null ? "UNKNOWN" : request.getMethod(),
                        joinPoint.getSignature().getDeclaringType().getSimpleName(),
                        joinPoint.getSignature().toShortString(),
                        request == null ? null : request.getRemoteAddr(),
                        request == null ? null : request.getHeader("User-Agent"),
                        System.currentTimeMillis() - started, success, error);
            } catch (Exception ignored) {
                // Audit failure must not break the business request.
            }
        }
    }
}
