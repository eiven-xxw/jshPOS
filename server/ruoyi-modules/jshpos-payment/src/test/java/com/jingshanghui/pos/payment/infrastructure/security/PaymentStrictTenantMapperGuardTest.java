package com.jingshanghui.pos.payment.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 所有支付 Mapper 调用前必须由可信上下文失败关闭。 */
class PaymentStrictTenantMapperGuardTest {

    @Test
    void requiresTrustedPrincipalBeforeMapperAccess() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new PaymentStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
