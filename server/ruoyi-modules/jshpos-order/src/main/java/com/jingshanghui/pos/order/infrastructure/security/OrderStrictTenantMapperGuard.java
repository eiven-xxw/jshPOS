package com.jingshanghui.pos.order.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class OrderStrictTenantMapperGuard {

    private final TrustedTenantContext tenantContext;

    @Before("execution(* com.jingshanghui.pos.order.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() {
        tenantContext.requirePrincipal();
    }
}
