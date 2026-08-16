package com.jingshanghui.pos.procurement.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ProcurementStrictTenantMapperGuardTest {

    @Test
    void requiresTrustedPrincipalBeforeEveryMapperCall() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new ProcurementStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
