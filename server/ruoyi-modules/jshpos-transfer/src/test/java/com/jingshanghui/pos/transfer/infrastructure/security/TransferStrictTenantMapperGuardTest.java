package com.jingshanghui.pos.transfer.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TransferStrictTenantMapperGuardTest {
    @Test
    void requiresTrustedPrincipalBeforeEveryMapperCall() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new TransferStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
