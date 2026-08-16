package com.jingshanghui.pos.foundation.application.security;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedTenantWorkAuthorizerTest {

    @Test
    void taskCannotSwitchTenantWithUntrustedArgument() {
        TrustedTenantContext context = new TrustedTenantContext(
            () -> Optional.of(new TrustedPrincipal("TENANT_A", 99L, null, "scheduler"))
        );
        TrustedTenantWorkAuthorizer authorizer = new TrustedTenantWorkAuthorizer(context);

        assertThatCode(() -> authorizer.requireCurrentTenant(null)).doesNotThrowAnyException();
        assertThatCode(() -> authorizer.requireCurrentTenant("TENANT_A")).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorizer.requireCurrentTenant("TENANT_B"))
            .hasMessageContaining("FND-IAM-006");
    }
}
