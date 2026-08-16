package com.jingshanghui.pos.costing.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 任意成本 Mapper 执行前强制要求可信租户和操作者，任务和重建也不得绕过。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 52)
@RequiredArgsConstructor
public class CostingStrictTenantMapperGuard {

    private final TrustedTenantContext tenantContext;

    @Before("execution(* com.jingshanghui.pos.costing.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() {
        tenantContext.requirePrincipal();
    }
}
