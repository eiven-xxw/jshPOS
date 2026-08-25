package com.jingshanghui.pos.foundation.application.context;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedDeviceTenantScopeTest {

    private final VerifiedDeviceTenantScope scope = new VerifiedDeviceTenantScope();
    private final VerifiedDeviceTenantScope.DeviceIdentity identity =
        new VerifiedDeviceTenantScope.DeviceIdentity("TENANT_A", 1001L, 1101L, "DEVICE_A");

    @Test
    void authorizesOnlyMatchingDeviceBindingAndAlwaysCleansUp() {
        String result = scope.execute(identity, () -> {
            assertThat(scope.isActive()).isTrue();
            scope.requireMatches("TENANT_A", 1001L, 1101L);
            return "OK";
        });

        assertThat(result).isEqualTo("OK");
        assertThat(scope.isActive()).isFalse();
        assertThatThrownBy(() -> scope.requireMatches("TENANT_A", 1001L, 1101L))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_DEVICE_SCOPE_MISSING");
    }

    @Test
    void rejectsSubstitutionNestedUseAndCleansAfterFailure() {
        assertThatThrownBy(() -> scope.execute(identity, () -> {
            scope.requireMatches("TENANT_A", 1001L, 2202L);
            return null;
        })).isInstanceOf(ServiceException.class).hasMessageContaining("TRM_DEVICE_SCOPE_MISMATCH");
        assertThat(scope.isActive()).isFalse();

        assertThatThrownBy(() -> scope.execute(identity, () -> scope.execute(identity, () -> null)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRM_DEVICE_SCOPE_NESTED");
        assertThat(scope.isActive()).isFalse();
    }
}
