package com.jingshanghui.pos.member.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 任意会员 Mapper 执行前都必须存在可信租户和操作者。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 55)
@RequiredArgsConstructor
public class MemberStrictTenantMapperGuard {
    private final TrustedTenantContext tenantContext;
    @Before("execution(* com.jingshanghui.pos.member.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() { tenantContext.requirePrincipal(); }
}
