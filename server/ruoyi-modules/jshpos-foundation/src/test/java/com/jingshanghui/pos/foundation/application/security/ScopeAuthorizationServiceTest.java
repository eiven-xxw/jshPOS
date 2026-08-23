package com.jingshanghui.pos.foundation.application.security;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.OrgUnitEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StaffScopeEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.OrgUnitMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StaffScopeMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import com.jingshanghui.pos.foundation.infrastructure.security.PlatformPrivilegeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScopeAuthorizationServiceTest {

    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final PlatformPrivilegeSource privileges = mock(PlatformPrivilegeSource.class);
    private final StaffScopeMapper scopes = mock(StaffScopeMapper.class);
    private final OrgUnitMapper orgs = mock(OrgUnitMapper.class);
    private final StoreMapper stores = mock(StoreMapper.class);
    private final ScopeAuthorizationService service = new ScopeAuthorizationService(context, privileges, scopes, orgs, stores);

    @BeforeEach
    void setUp() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 10L, 1L, "alice"));
        when(scopes.selectList(any())).thenReturn(List.of());
    }

    @Test
    void tenantAdministratorCanAccessExistingObjectsAndGrantScopes() {
        when(privileges.isTenantAdministrator()).thenReturn(true);
        when(orgs.selectById(100L)).thenReturn(org(100L, null));
        when(stores.selectById(101L)).thenReturn(store(101L, 100L));

        service.requireTenantAdministrator();
        assertThat(service.canAccessOrg(100L)).isTrue();
        assertThat(service.canAccessStore(101L)).isTrue();
        assertThat(service.canAccessOrg(999L)).isFalse();
    }

    @Test
    void ordinaryStaffUsesTenantStoreOrAncestorScope() {
        when(privileges.isTenantAdministrator()).thenReturn(false);
        when(orgs.selectById(100L)).thenReturn(org(100L, null));
        when(orgs.selectById(110L)).thenReturn(org(110L, 100L));
        when(stores.selectById(101L)).thenReturn(store(101L, 110L));

        when(scopes.selectList(any())).thenReturn(List.of(scope("STORE", null, 101L)));
        assertThat(service.canAccessStore(101L)).isTrue();

        when(scopes.selectList(any())).thenReturn(List.of(scope("ORG_SUBTREE", 100L, null)));
        assertThat(service.canAccessOrg(110L)).isTrue();
        assertThat(service.canAccessStore(101L)).isTrue();

        when(scopes.selectList(any())).thenReturn(List.of(scope("TENANT", null, null)));
        assertThat(service.canAccessOrg(100L)).isTrue();
    }

    @Test
    void rejectsMissingObjectsAndOutOfScopeUsers() {
        when(privileges.isTenantAdministrator()).thenReturn(false);
        when(stores.selectById(999L)).thenReturn(null);

        assertThat(service.canAccessStore(999L)).isFalse();
        assertThat(service.canAccessOrg(999L)).isFalse();
        assertThatThrownBy(() -> service.requireOrgAccess(999L)).hasMessageContaining("FND-RBAC-002");
        assertThatThrownBy(() -> service.requireStoreAccess(999L)).hasMessageContaining("FND-RBAC-003");
        assertThatThrownBy(service::requireTenantAdministrator).hasMessageContaining("FND-RBAC-001");
        assertThatThrownBy(service::requirePlatformAdministrator).hasMessageContaining("FND-RBAC-004");
    }

    @Test
    void platformAdministratorIsCheckedIndependentlyFromTenantAdministrator() {
        when(privileges.isPlatformAdministrator()).thenReturn(true);
        service.requirePlatformAdministrator();
    }

    @Test
    void evaluatesTenantAndNonMatchingHierarchyScopesWithoutLeaking() {
        when(privileges.isTenantAdministrator()).thenReturn(false);
        when(orgs.selectById(100L)).thenReturn(org(100L, null));
        when(orgs.selectById(110L)).thenReturn(org(110L, 100L));
        when(stores.selectById(101L)).thenReturn(store(101L, 110L));

        when(scopes.selectList(any())).thenReturn(List.of(scope("TENANT", null, null)));
        assertThat(service.canAccessStore(101L)).isTrue();

        when(scopes.selectList(any())).thenReturn(List.of(scope("ORG_SUBTREE", 999L, null)));
        assertThat(service.canAccessOrg(110L)).isFalse();
        assertThat(service.canAccessStore(101L)).isFalse();

        when(scopes.selectList(any())).thenReturn(List.of(scope("STORE", null, 999L)));
        assertThat(service.canAccessStore(101L)).isFalse();
    }

    private OrgUnitEntity org(long id, Long parent) {
        OrgUnitEntity entity = new OrgUnitEntity();
        entity.setOrgUnitId(id);
        entity.setParentId(parent);
        return entity;
    }

    private StoreEntity store(long id, long orgId) {
        StoreEntity entity = new StoreEntity();
        entity.setStoreId(id);
        entity.setOrgUnitId(orgId);
        return entity;
    }

    private StaffScopeEntity scope(String type, Long orgId, Long storeId) {
        StaffScopeEntity entity = new StaffScopeEntity();
        entity.setScopeType(type);
        entity.setOrgUnitId(orgId);
        entity.setStoreId(storeId);
        entity.setStatus("ACTIVE");
        return entity;
    }
}
