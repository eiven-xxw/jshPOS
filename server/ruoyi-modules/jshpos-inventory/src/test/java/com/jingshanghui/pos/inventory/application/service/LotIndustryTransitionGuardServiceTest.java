package com.jingshanghui.pos.inventory.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryTransitionGuardPort.IndustryTransition;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LotIndustryTransitionGuardServiceTest {
    private final LotInventoryMapper mapper = mock(LotInventoryMapper.class);
    private final TrustedTenantContext tenant = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final LotIndustryTransitionGuardService service =
        new LotIndustryTransitionGuardService(mapper, tenant, authorization);

    @Test
    void blocksRemovingCommunityCapabilityWhenLotFactsExist() {
        when(tenant.requireTenantId()).thenReturn("TENANT_A");
        when(mapper.countStoreLotFacts("TENANT_A", 1101L)).thenReturn(1L);

        assertThatThrownBy(() -> service.requireCanActivate(new IndustryTransition(
            1101L, 10L, "COMMUNITY_SUPERMARKET", 20L, "CONVENIENCE")))
            .hasMessageContaining("LOT-POLICY-014");
        verify(authorization).requireStoreAccess(1101L);
    }

    @Test
    void allowsCommunityVersionChangeAndNonCommunityTransitionsWithoutLotQuery() {
        service.requireCanActivate(new IndustryTransition(
            1101L, 10L, "COMMUNITY_SUPERMARKET", 11L, "COMMUNITY_SUPERMARKET"));
        service.requireCanActivate(new IndustryTransition(
            1101L, 20L, "CONVENIENCE", 10L, "COMMUNITY_SUPERMARKET"));
        verify(authorization, org.mockito.Mockito.times(2)).requireStoreAccess(1101L);
        verify(mapper, org.mockito.Mockito.never()).countStoreLotFacts(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyLong());
    }
}
