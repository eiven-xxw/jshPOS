package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 任意 Reporting Mapper 执行前都必须存在可信租户和操作者，任务参数不能替代认证上下文。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 56)
@RequiredArgsConstructor
public class ReportingStrictTenantMapperGuard {
    private final TrustedTenantContext tenantContext;
    @Before("execution(* com.jingshanghui.pos.reporting.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() {
        tenantContext.requirePrincipal();
    }
}
