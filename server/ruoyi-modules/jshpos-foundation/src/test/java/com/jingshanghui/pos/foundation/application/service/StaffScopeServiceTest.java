package com.jingshanghui.pos.foundation.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.infrastructure.observability.FoundationMetrics;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.OrgUnitEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StaffScopeEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.OrgUnitMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StaffScopeMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffScopeServiceTest {

    private final StaffScopeMapper mapper = mock(StaffScopeMapper.class);
    private final OrgUnitMapper orgs = mock(OrgUnitMapper.class);
    private final StoreMapper stores = mock(StoreMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final DomainAuditService audit = mock(DomainAuditService.class);
    private final FoundationMetrics metrics = mock(FoundationMetrics.class);
    private final StaffScopeService service = new StaffScopeService(mapper, orgs, stores, context, authorization, audit, metrics);

    @BeforeEach
    void setUp() {
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(mapper.insert(any(StaffScopeEntity.class))).thenAnswer(invocation -> {
            StaffScopeEntity entity = invocation.getArgument(0);
            entity.setStaffScopeId(99L);
            return 1;
        });
        when(mapper.updateById(any(StaffScopeEntity.class))).thenReturn(1);
    }

    @Test
    void replacesScopesByRevokingAndReactivatingWithoutPhysicalDelete() {
        StaffScopeEntity existing = scope(1L, "STORE", null, 101L, "ACTIVE");
        StaffScopeEntity revoked = scope(2L, "ORG_SUBTREE", 100L, null, "REVOKED");
        when(mapper.selectList(any())).thenReturn(List.of(existing, revoked), List.of(existing, revoked));
        when(orgs.selectById(100L)).thenReturn(new OrgUnitEntity());

        List<?> result = service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("ORG_SUBTREE", 100L, null),
            new StaffScopeService.ScopeInput("TENANT", null, null)
        ));

        assertThat(result).hasSize(2);
        assertThat(existing.getStatus()).isEqualTo("REVOKED");
        assertThat(revoked.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void validatesScopeShapesReferencesAndDuplicates() {
        when(mapper.selectList(any())).thenReturn(List.of());
        StoreEntity store = new StoreEntity();
        when(stores.selectById(101L)).thenReturn(store);

        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("TENANT", 100L, null))))
            .hasMessageContaining("FND-RBAC-008");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("ORG_SUBTREE", null, null))))
            .hasMessageContaining("FND-RBAC-009");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("STORE", null, null))))
            .hasMessageContaining("FND-RBAC-010");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("STORE", null, 101L),
            new StaffScopeService.ScopeInput("STORE", null, 101L))))
            .hasMessageContaining("FND-RBAC-005");
        assertThatThrownBy(() -> service.replace(0L, List.of())).hasMessageContaining("FND-RBAC-004");
    }

    @Test
    void validatesNullOversizedAndCrossShapedScopeInputs() {
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.replace(null, List.of())).hasMessageContaining("FND-RBAC-004");
        assertThatThrownBy(() -> service.replace(10L, null)).hasMessageContaining("FND-RBAC-004");
        assertThatThrownBy(() -> service.replace(10L,
            Collections.nCopies(101, new StaffScopeService.ScopeInput("TENANT", null, null))))
            .hasMessageContaining("FND-RBAC-004");
        assertThatThrownBy(() -> service.replace(10L, Collections.singletonList(null)))
            .hasMessageContaining("FND-RBAC-006");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("UNKNOWN", null, null))))
            .hasMessageContaining("FND-RBAC-007");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("TENANT", null, 101L))))
            .hasMessageContaining("FND-RBAC-008");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("ORG_SUBTREE", 100L, 101L))))
            .hasMessageContaining("FND-RBAC-009");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("ORG_SUBTREE", 100L, null))))
            .hasMessageContaining("FND-RBAC-009");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("STORE", 100L, 101L))))
            .hasMessageContaining("FND-RBAC-010");
        assertThatThrownBy(() -> service.replace(10L, List.of(
            new StaffScopeService.ScopeInput("STORE", null, 101L))))
            .hasMessageContaining("FND-RBAC-010");
    }

    @Test
    void listsAllTenantScopedAssignmentsForAdministrator() {
        when(mapper.selectList(any())).thenReturn(List.of(scope(1L, "TENANT", null, null, "ACTIVE")));

        assertThat(service.list(10L)).singleElement().satisfies(view ->
            assertThat(view.scopeType()).isEqualTo("TENANT"));
    }

    private StaffScopeEntity scope(long id, String type, Long orgId, Long storeId, String status) {
        StaffScopeEntity entity = new StaffScopeEntity();
        entity.setStaffScopeId(id);
        entity.setUserId(10L);
        entity.setScopeType(type);
        entity.setOrgUnitId(orgId);
        entity.setStoreId(storeId);
        entity.setStatus(status);
        entity.setVersion(0);
        return entity;
    }
}
