package com.jingshanghui.pos.reporting.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class ReportingStrictTenantMapperGuardTest {
    @Test void requiresTrustedTenantBeforeEveryMapperCall() {
        TrustedTenantContext context=mock(TrustedTenantContext.class);
        new ReportingStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
