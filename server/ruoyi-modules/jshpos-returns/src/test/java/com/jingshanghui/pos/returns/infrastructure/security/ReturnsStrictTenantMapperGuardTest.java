package com.jingshanghui.pos.returns.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Return Owner 所有 XML Mapper 入口的可信主体失败关闭探针。 */
class ReturnsStrictTenantMapperGuardTest {
    @Test
    void requiresTrustedPrincipalBeforeEveryReturnMapperInvocation() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new ReturnsStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
