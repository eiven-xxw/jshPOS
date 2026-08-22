package com.jingshanghui.pos.inventory.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 证明存在批次余额时能力关闭失败，且租户只能来自可信上下文。 */
class LotPolicyTransitionGuardServiceTest {
    @Test
    void rejectsDisableWhenHistoricalLotFactsExist() {
        LotInventoryMapper mapper = mock(LotInventoryMapper.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(mapper.countLotFacts("TENANT_A", 10L, 701L)).thenReturn(1L);
        LotPolicyTransitionGuardService service = new LotPolicyTransitionGuardService(mapper, context, authorization);

        assertThatThrownBy(() -> service.requireCanDisable(10L, 701L))
            .hasMessageContaining("已存在批次");
        verify(authorization).requireStoreAccess(10L);
    }
}
