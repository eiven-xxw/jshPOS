package com.jingshanghui.pos.member.infrastructure.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/** Mapper 攻击面在进入 SQL 前强制取得可信租户。 */
class MemberStrictTenantMapperGuardTest {
    @Test void requiresTrustedTenantForEveryMapperCall() {
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        new MemberStrictTenantMapperGuard(context).requireTrustedTenantBeforeMapperAccess();
        verify(context).requirePrincipal();
    }
}
