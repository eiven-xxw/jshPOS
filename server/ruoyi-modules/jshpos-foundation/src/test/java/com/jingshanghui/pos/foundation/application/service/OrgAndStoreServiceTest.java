package com.jingshanghui.pos.foundation.application.service;

import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.infrastructure.observability.FoundationMetrics;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.OrgUnitEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.entity.StoreEntity;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.OrgUnitMapper;
import com.jingshanghui.pos.foundation.infrastructure.persistence.mapper.StoreMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrgAndStoreServiceTest {

    private final OrgUnitMapper orgMapper = mock(OrgUnitMapper.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final DomainAuditService audit = mock(DomainAuditService.class);
    private final FoundationMetrics metrics = mock(FoundationMetrics.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T21:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(orgMapper.insert(any(OrgUnitEntity.class))).thenAnswer(invocation -> {
            OrgUnitEntity entity = invocation.getArgument(0);
            entity.setOrgUnitId(100L);
            return 1;
        });
        when(storeMapper.insert(any(StoreEntity.class))).thenAnswer(invocation -> {
            StoreEntity entity = invocation.getArgument(0);
            entity.setStoreId(101L);
            return 1;
        });
    }

    @Test
    void createsRootAndChildOrganizationsWithAudit() {
        OrgUnitService service = orgService();

        var root = service.create(new OrgUnitService.CreateOrgUnit(null, "hq_a", "虚构总部A", "headquarters"));
        assertThat(root.orgUnitId()).isEqualTo(100L);
        assertThat(root.code()).isEqualTo("HQ_A");
        assertThat(root.treeDepth()).isEqualTo(1);
        verify(authorization).requireTenantAdministrator();

        OrgUnitEntity parent = activeOrg(200L, null, 3, 0);
        when(orgMapper.selectById(200L)).thenReturn(parent);
        var child = service.create(new OrgUnitService.CreateOrgUnit(200L, "region_a", "区域A", "region"));
        assertThat(child.treeDepth()).isEqualTo(4);
        verify(audit, org.mockito.Mockito.atLeastOnce()).append(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq("ORG_UNIT"),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    void updatesOrganizationWithOptimisticVersionAndRejectsConflictCycleAndDepth() {
        OrgUnitService service = orgService();
        OrgUnitEntity entity = activeOrg(100L, null, 1, 2);
        when(orgMapper.selectById(100L)).thenReturn(entity);
        when(orgMapper.updateById(any(OrgUnitEntity.class))).thenReturn(1);

        var updated = service.update(100L,
            new OrgUnitService.UpdateOrgUnit(null, "hq_new", "新总部", "company", "active", 2));
        assertThat(updated.code()).isEqualTo("HQ_NEW");

        entity.setVersion(4);
        assertThatThrownBy(() -> service.update(100L,
            new OrgUnitService.UpdateOrgUnit(null, "hq", "总部", "company", "active", 3)))
            .hasMessageContaining("FND-ORG-005");

        entity.setVersion(4);
        when(orgMapper.selectById(200L)).thenReturn(activeOrg(200L, 100L, 2, 0));
        assertThatThrownBy(() -> service.update(100L,
            new OrgUnitService.UpdateOrgUnit(200L, "hq", "总部", "company", "active", 4)))
            .hasMessageContaining("FND-ORG-008");

        when(orgMapper.selectById(300L)).thenReturn(activeOrg(300L, null, 8, 0));
        assertThatThrownBy(() -> service.update(100L,
            new OrgUnitService.UpdateOrgUnit(300L, "hq", "总部", "company", "active", 4)))
            .hasMessageContaining("FND-ORG-003");
    }

    @Test
    void preventsDisablingOrganizationWithActiveChildren() {
        OrgUnitService service = orgService();
        OrgUnitEntity entity = activeOrg(100L, null, 1, 0);
        when(orgMapper.selectById(100L)).thenReturn(entity);
        when(orgMapper.selectCount(any())).thenReturn(1L);
        when(storeMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> service.update(100L,
            new OrgUnitService.UpdateOrgUnit(null, "hq", "总部", "company", "inactive", 0)))
            .hasMessageContaining("FND-ORG-009");
    }

    @Test
    void rejectsMovingAnOrganizationThatOwnsAChildSubtree() {
        OrgUnitService service = orgService();
        OrgUnitEntity entity = activeOrg(100L, null, 1, 0);
        when(orgMapper.selectById(100L)).thenReturn(entity);
        when(orgMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.update(100L,
            new OrgUnitService.UpdateOrgUnit(200L, "hq", "总部", "company", "active", 0)))
            .hasMessageContaining("FND-ORG-015");
    }

    @Test
    void createsUpdatesAndCalculatesStoreBusinessDate() {
        StoreService service = storeService();
        OrgUnitEntity org = activeOrg(100L, null, 1, 0);
        when(orgMapper.selectById(100L)).thenReturn(org);

        var created = service.create(new StoreService.CreateStore(
            100L, 10L, "a101", "虚构A101", "Asia/Shanghai", LocalTime.of(6, 0)));
        assertThat(created.code()).isEqualTo("A101");
        assertThat(created.status()).isEqualTo("PREPARING");

        StoreEntity entity = store(101L, 100L, 1);
        when(storeMapper.selectById(101L)).thenReturn(entity);
        when(storeMapper.updateById(any(StoreEntity.class))).thenReturn(1);
        var updated = service.update(101L, new StoreService.UpdateStore(
            100L, 10L, "a101", "虚构A101", "Asia/Shanghai", LocalTime.of(6, 0), "active", 1));
        assertThat(updated.status()).isEqualTo("ACTIVE");

        var date = service.businessDate(101L, null);
        assertThat(date.businessDate()).hasToString("2026-08-16");
        assertThatThrownBy(() -> service.update(101L, new StoreService.UpdateStore(
            100L, null, "a101", "门店", "Asia/Shanghai", LocalTime.MIDNIGHT, "active", 0)))
            .hasMessageContaining("FND-ORG-012");
    }

    @Test
    void listsOnlyAuthorizedOrganizationsAndStores() {
        OrgUnitEntity visibleOrg = activeOrg(100L, null, 1, 0);
        OrgUnitEntity hiddenOrg = activeOrg(200L, null, 1, 0);
        when(orgMapper.selectList(any())).thenReturn(List.of(visibleOrg, hiddenOrg));
        when(authorization.canAccessOrg(100L)).thenReturn(true);
        when(authorization.canAccessOrg(200L)).thenReturn(false);
        assertThat(orgService().list()).extracting("orgUnitId").containsExactly(100L);

        StoreEntity visibleStore = store(101L, 100L, 0);
        StoreEntity hiddenStore = store(201L, 200L, 0);
        when(storeMapper.selectList(any())).thenReturn(List.of(visibleStore, hiddenStore));
        when(authorization.canAccessStore(101L)).thenReturn(true);
        when(authorization.canAccessStore(201L)).thenReturn(false);
        assertThat(storeService().list()).extracting("storeId").containsExactly(101L);
    }

    @Test
    void rejectsInactiveOrTooDeepOrganizationParents() {
        OrgUnitEntity inactive = activeOrg(200L, null, 1, 0);
        inactive.setStatus("INACTIVE");
        when(orgMapper.selectById(200L)).thenReturn(inactive);
        assertThatThrownBy(() -> orgService().create(
            new OrgUnitService.CreateOrgUnit(200L, "child", "子组织", "region")))
            .hasMessageContaining("FND-ORG-002");

        when(orgMapper.selectById(300L)).thenReturn(activeOrg(300L, null, 8, 0));
        assertThatThrownBy(() -> orgService().create(
            new OrgUnitService.CreateOrgUnit(300L, "child", "子组织", "region")))
            .hasMessageContaining("FND-ORG-003");
    }

    @Test
    void rejectsStoreCreationAndUpdateInvariantFailures() {
        StoreService service = storeService();
        when(orgMapper.selectById(100L)).thenReturn(null);
        assertThatThrownBy(() -> service.create(new StoreService.CreateStore(
            100L, null, "a101", "门店", "Asia/Shanghai", LocalTime.MIDNIGHT)))
            .hasMessageContaining("FND-ORG-001");

        when(orgMapper.selectById(100L)).thenReturn(activeOrg(100L, null, 1, 0));
        assertThatThrownBy(() -> service.create(new StoreService.CreateStore(
            100L, null, "a101", "门店", "Asia/Shanghai", null)))
            .hasMessageContaining("FND-ORG-014");

        StoreEntity current = store(101L, 100L, 2);
        when(storeMapper.selectById(101L)).thenReturn(current);
        assertThatThrownBy(() -> service.update(101L, new StoreService.UpdateStore(
            100L, null, "a101", "门店", "Asia/Shanghai", LocalTime.MIDNIGHT, "active", 1)))
            .hasMessageContaining("FND-ORG-012");

        current.setVersion(2);
        when(storeMapper.updateById(any(StoreEntity.class))).thenReturn(0);
        assertThatThrownBy(() -> service.update(101L, new StoreService.UpdateStore(
            100L, null, "a101", "门店", "Asia/Shanghai", LocalTime.MIDNIGHT, "active", 2)))
            .hasMessageContaining("FND-ORG-012");

        when(storeMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.businessDate(999L, Instant.parse("2026-08-16T00:00:00Z")))
            .hasMessageContaining("FND-ORG-010");
    }

    private OrgUnitService orgService() {
        return new OrgUnitService(orgMapper, storeMapper, context, authorization, audit, metrics);
    }

    private StoreService storeService() {
        return new StoreService(storeMapper, orgMapper, context, authorization, audit, metrics, clock);
    }

    private OrgUnitEntity activeOrg(long id, Long parentId, int depth, int version) {
        OrgUnitEntity entity = new OrgUnitEntity();
        entity.setOrgUnitId(id);
        entity.setParentId(parentId);
        entity.setUnitCode("ORG" + id);
        entity.setUnitName("Synthetic " + id);
        entity.setUnitType("COMPANY");
        entity.setStatus("ACTIVE");
        entity.setTreeDepth(depth);
        entity.setVersion(version);
        return entity;
    }

    private StoreEntity store(long id, long orgId, int version) {
        StoreEntity entity = new StoreEntity();
        entity.setStoreId(id);
        entity.setOrgUnitId(orgId);
        entity.setStoreCode("A101");
        entity.setStoreName("Synthetic Store");
        entity.setZoneId("Asia/Shanghai");
        entity.setBusinessDayStart(LocalTime.of(6, 0));
        entity.setStatus("PREPARING");
        entity.setVersion(version);
        return entity;
    }
}
