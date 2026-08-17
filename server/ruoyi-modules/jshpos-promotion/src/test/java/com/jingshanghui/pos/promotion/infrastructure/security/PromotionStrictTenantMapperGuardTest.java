package com.jingshanghui.pos.promotion.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/** 促销 Mapper 可信租户前置守卫回归。 */
class PromotionStrictTenantMapperGuardTest {
    @Test
    void requiresTrustedPrincipalBeforeEveryMapperCall() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new PromotionStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
