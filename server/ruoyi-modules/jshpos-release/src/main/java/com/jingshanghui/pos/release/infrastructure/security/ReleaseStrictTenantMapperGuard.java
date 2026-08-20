package com.jingshanghui.pos.release.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 所有发布Mapper调用前强制存在可信租户主体，后台任务也必须显式建立受控上下文。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
public class ReleaseStrictTenantMapperGuard {
    private final TrustedTenantContext tenantContext;
    @Before("execution(* com.jingshanghui.pos.release.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() { tenantContext.requirePrincipal(); }
}
