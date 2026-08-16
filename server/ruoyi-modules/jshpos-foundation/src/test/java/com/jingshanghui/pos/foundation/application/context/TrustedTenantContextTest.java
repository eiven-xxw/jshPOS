package com.jingshanghui.pos.foundation.application.context;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedTenantContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void requiresAuthenticatedTenantAndActor() {
        TrustedTenantContext context = context(new TrustedPrincipal("TENANT_A", 101L, 11L, "alice"));

        assertThat(context.requireTenantId()).isEqualTo("TENANT_A");
        assertThat(MDC.get("tenantId")).isEqualTo("TENANT_A");
    }

    @Test
    void failsClosedWhenPrincipalIsMissing() {
        TrustedTenantContext context = new TrustedTenantContext(Optional::<TrustedPrincipal>empty);

        assertThatThrownBy(context::requirePrincipal)
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("FND-IAM-001");
    }

    @Test
    void rejectsInvalidTenantAndActor() {
        assertThatThrownBy(() -> context(new TrustedPrincipal(null, 1L, null, "bad")).requirePrincipal())
            .hasMessageContaining("FND-IAM-002");
        assertThatThrownBy(() -> context(new TrustedPrincipal("../TENANT_B", 1L, null, "bad")).requirePrincipal())
            .hasMessageContaining("FND-IAM-002");
        assertThatThrownBy(() -> context(new TrustedPrincipal("TENANT_A", null, null, "bad")).requirePrincipal())
            .hasMessageContaining("FND-IAM-003");
        assertThatThrownBy(() -> context(new TrustedPrincipal("TENANT_A", 0L, null, "bad")).requirePrincipal())
            .hasMessageContaining("FND-IAM-003");
    }

    @Test
    void rejectsAnyClientTenantOverride() {
        TrustedTenantContext context = context(new TrustedPrincipal("TENANT_A", 1L, null, "alice"));

        context.rejectClientTenant(null);
        context.rejectClientTenant(" ");
        assertThatThrownBy(() -> context.rejectClientTenant("TENANT_B"))
            .hasMessageContaining("FND-IAM-004");
    }

    private TrustedTenantContext context(TrustedPrincipal principal) {
        return new TrustedTenantContext(() -> Optional.of(principal));
    }
}
