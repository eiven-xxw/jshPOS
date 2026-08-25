package com.jingshanghui.pos.foundation.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.context.VerifiedDeviceTenantScope;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 补强上游在租户缺失时忽略租户条件的行为：foundation Mapper 必须失败关闭。
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class StrictTenantMapperGuard {

    private final TrustedTenantContext tenantContext;
    private final VerifiedDeviceTenantScope deviceTenantScope;

    @Before("execution(* com.jingshanghui.pos.foundation.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() {
        if (deviceTenantScope.isActive()) {
            return;
        }
        tenantContext.requirePrincipal();
    }
}
