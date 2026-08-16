package com.jingshanghui.pos.order.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OrderStrictTenantMapperGuardTest {

    @Test
    void failsClosedThroughTheSingleTrustedContextBeforeMapperAccess() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new OrderStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
