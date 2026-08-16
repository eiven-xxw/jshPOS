package com.jingshanghui.pos.costing.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CostingStrictTenantMapperGuardTest {

    @Test
    void requiresTrustedPrincipalBeforeEveryCostMapperCall() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new CostingStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
