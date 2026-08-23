package com.jingshanghui.pos.foundation.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T2 Gate 8B 平台提交/审批职责分离授权回归。 */
class RuoYiPlatformPrivilegeSourceTest {

    @Test
    void acceptsBuiltInSuperAdministratorOnlyInsidePlatformTenant() {
        assertThat(RuoYiPlatformPrivilegeSource.isPlatformAdministrator("000000", true, Set.of())).isTrue();
        assertThat(RuoYiPlatformPrivilegeSource.isPlatformAdministrator("200001", true, Set.of())).isFalse();
    }

    @Test
    void acceptsNamedPlatformReviewerRoleOnlyInsidePlatformTenant() {
        assertThat(RuoYiPlatformPrivilegeSource.isPlatformAdministrator(
            "000000", false, Set.of(RuoYiPlatformPrivilegeSource.PLATFORM_ADMIN_ROLE_KEY))).isTrue();
        assertThat(RuoYiPlatformPrivilegeSource.isPlatformAdministrator(
            "200001", false, Set.of(RuoYiPlatformPrivilegeSource.PLATFORM_ADMIN_ROLE_KEY))).isFalse();
    }

    @Test
    void rejectsOrdinaryAndMissingRoles() {
        assertThat(RuoYiPlatformPrivilegeSource.isPlatformAdministrator("000000", false, Set.of("tenant_admin"))).isFalse();
        assertThat(RuoYiPlatformPrivilegeSource.isPlatformAdministrator("000000", false, null)).isFalse();
    }
}
