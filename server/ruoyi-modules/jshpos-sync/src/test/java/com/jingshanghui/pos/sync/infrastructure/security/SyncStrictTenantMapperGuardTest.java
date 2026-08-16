package com.jingshanghui.pos.sync.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SyncStrictTenantMapperGuardTest {

    @Test
    void everyMapperCallFailsClosedThroughTrustedContext() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new SyncStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
