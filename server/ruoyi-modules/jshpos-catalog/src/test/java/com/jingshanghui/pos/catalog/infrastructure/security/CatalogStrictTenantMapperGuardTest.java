package com.jingshanghui.pos.catalog.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CatalogStrictTenantMapperGuardTest {

    @Test
    void requiresTrustedPrincipalBeforeEveryCatalogMapperCall() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new CatalogStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
