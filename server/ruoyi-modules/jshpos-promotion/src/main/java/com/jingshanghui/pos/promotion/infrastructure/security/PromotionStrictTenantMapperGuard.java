package com.jingshanghui.pos.promotion.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 任意促销 Mapper 执行前都必须存在可信租户和操作者。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 54)
@RequiredArgsConstructor
public class PromotionStrictTenantMapperGuard {
    private final TrustedTenantContext tenantContext;

    /** 在 SQL 进入 MyBatis 前失败关闭缺失的可信身份。 */
    @Before("execution(* com.jingshanghui.pos.promotion.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() { tenantContext.requirePrincipal(); }
}
