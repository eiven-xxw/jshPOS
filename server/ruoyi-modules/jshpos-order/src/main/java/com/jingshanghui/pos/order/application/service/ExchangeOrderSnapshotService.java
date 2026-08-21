package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.order.application.port.ExchangeOrderSnapshotPort;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.ExchangeOrderSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order Owner 对换货 Saga 提供的新销售权威状态只读适配器。 */
@Service
@RequiredArgsConstructor
public class ExchangeOrderSnapshotService implements ExchangeOrderSnapshotPort {
    private final ExchangeOrderSnapshotMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;

    @Override
    @Transactional(readOnly = true)
    public ExchangeOrderSnapshot find(String orderId) {
        String tenantId = tenantContext.requireTenantId();
        ExchangeOrderSnapshot result = mapper.find(tenantId, orderId);
        if (result != null) authorizationService.requireStoreAccess(result.storeId());
        return result;
    }
}
