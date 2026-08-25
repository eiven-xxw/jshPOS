package com.jingshanghui.pos.foundation.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.context.VerifiedDeviceTenantScope;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StrictTenantMapperGuardTest {

    @Test
    void requiresTrustedPrincipalBeforeEveryFoundationMapperInvocation() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        VerifiedDeviceTenantScope deviceScope = new VerifiedDeviceTenantScope();

        new StrictTenantMapperGuard(context, deviceScope).requireTrustedTenantBeforeMapperAccess();

        verify(context).requirePrincipal();
    }

    @Test
    void permitsOnlyTheShortLivedVerifiedDeviceScope() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        VerifiedDeviceTenantScope deviceScope = new VerifiedDeviceTenantScope();
        StrictTenantMapperGuard guard = new StrictTenantMapperGuard(context, deviceScope);

        deviceScope.execute(new VerifiedDeviceTenantScope.DeviceIdentity("TENANT_A", 1001L, 1101L, "DEVICE_A"),
            () -> {
                guard.requireTrustedTenantBeforeMapperAccess();
                return null;
            });

        verifyNoInteractions(context);
        guard.requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
