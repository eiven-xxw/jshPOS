package com.jingshanghui.pos.returns.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 任意退货退款 Mapper 执行前必须存在可信租户和操作者，缺失时失败关闭。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 55)
@RequiredArgsConstructor
public class ReturnsStrictTenantMapperGuard {
    private final TrustedTenantContext tenantContext;

    /** 在 SQL 进入 MyBatis 前校验可信主体，客户端 tenant_id 永不参与授权。 */
    @Before("execution(* com.jingshanghui.pos.returns.infrastructure.persistence.mapper..*(..))")
    public void requireTrustedTenantBeforeMapperAccess() {
        tenantContext.requirePrincipal();
    }
}
