package com.jingshanghui.pos.inventory.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InventoryStrictTenantMapperGuardTest {

    @Test
    void requiresTrustedPrincipalBeforeEveryMapperCall() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new InventoryStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
