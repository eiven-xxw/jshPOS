package com.jingshanghui.pos.foundation.application.security;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantResourceNamespaceTest {

    @Test
    void isolatesCacheExportAndObjectKeysForTwoTenants() {
        TenantResourceNamespace tenantA = namespace("TENANT_A");
        TenantResourceNamespace tenantB = namespace("TENANT_B");

        assertThat(tenantA.cacheKey("store-101")).isEqualTo("tenant/TENANT_A/cache/store-101");
        assertThat(tenantA.exportKey("org-list.csv")).isEqualTo("tenant/TENANT_A/export/org-list.csv");
        assertThat(tenantA.objectKey("logo.png")).isEqualTo("tenant/TENANT_A/object/logo.png");
        assertThat(tenantA.cacheKey("same-key")).isNotEqualTo(tenantB.cacheKey("same-key"));
    }

    @Test
    void rejectsTraversalSeparatorsAndCrossTenantPrefixes() {
        TenantResourceNamespace namespace = namespace("TENANT_A");

        for (String attack : new String[]{"../TENANT_B", "TENANT_B/object/x", "a\\b", "", " x"}) {
            assertThatThrownBy(() -> namespace.objectKey(attack)).hasMessageContaining("FND-IAM-005");
        }
    }

    private TenantResourceNamespace namespace(String tenantId) {
        TrustedTenantContext context = new TrustedTenantContext(
            () -> Optional.of(new TrustedPrincipal(tenantId, 1L, 1L, "synthetic"))
        );
        return new TenantResourceNamespace(context);
    }
}
